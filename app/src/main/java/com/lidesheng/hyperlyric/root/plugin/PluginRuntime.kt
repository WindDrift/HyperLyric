package com.lidesheng.hyperlyric.root.plugin

import android.app.Application
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import dalvik.system.InMemoryDexClassLoader
import com.lidesheng.hyperlyric.plugin.api.HYPERLYRIC_PLUGIN_API_VERSION
import com.lidesheng.hyperlyric.plugin.api.HyperLyricExtension
import com.lidesheng.hyperlyric.plugin.api.HyperLyricPlugin
import com.lidesheng.hyperlyric.plugin.api.LyricProcessorExtension
import com.lidesheng.hyperlyric.plugin.api.PluginConfig
import com.lidesheng.hyperlyric.plugin.api.PluginContext
import com.lidesheng.hyperlyric.plugin.api.PluginLogger
import com.lidesheng.hyperlyric.plugin.api.PluginStorage
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.core.PluginArchiveReader
import com.lidesheng.hyperlyric.plugin.core.PluginConstants
import com.lidesheng.hyperlyric.plugin.core.PluginManifest
import com.lidesheng.hyperlyric.plugin.core.PluginRemoteFileNames
import com.lidesheng.hyperlyric.root.utils.HookLogger
import io.github.libxposed.api.XposedModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException as FutureCancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** SystemUI-side loader and executor for trusted HyperLyric ZIP plugins. */
class PluginRuntime(
    private val module: XposedModule,
    private val application: Application,
    private val parentClassLoader: ClassLoader =
        HyperLyricPlugin::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
) {
    companion object {
        private const val TAG = "PluginRuntime"

        private val creatingPluginLoaderDepth = ThreadLocal.withInitial<Int> { 0 }

        /** Prevent the existing SystemUI plugin hook from treating our API loader as a host one. */
        @JvmStatic
        fun isCreatingPluginClassLoader(): Boolean = currentPluginLoaderDepth() > 0

        private inline fun <T> withPluginClassLoaderCreation(block: () -> T): T {
            creatingPluginLoaderDepth.set(currentPluginLoaderDepth() + 1)
            return try {
                block()
            } finally {
                creatingPluginLoaderDepth.set(
                    (currentPluginLoaderDepth() - 1).coerceAtLeast(0)
                )
            }
        }

        private fun currentPluginLoaderDepth(): Int = creatingPluginLoaderDepth.get() ?: 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processorExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "HyperLyric-PluginProcessor").apply { isDaemon = true }
    }
    private val generation = AtomicInteger(0)
    private val closed = AtomicBoolean(false)
    private var activeJob: Job? = null
    private val loadedPlugins = mutableListOf<LoadedPlugin>()

    @Volatile
    private var processors: List<LyricProcessorExtension> = emptyList()

    fun loadEnabledPlugins() {
        if (closed.get()) return

        val enabledIds = runCatching {
            module.getRemotePreferences(PluginConstants.REMOTE_REGISTRY_PREFS)
                .getStringSet(PluginConstants.REMOTE_ENABLED_IDS_KEY, emptySet())
                .orEmpty()
        }.getOrElse { error ->
            HookLogger.w(TAG, "读取插件启用状态失败，跳过插件加载", error)
            emptySet()
        }
        if (enabledIds.isEmpty()) {
            HookLogger.d(TAG, "没有启用的 HyperLyric 插件")
            return
        }

        val remoteFiles = runCatching { module.listRemoteFiles().toSet() }.getOrElse { error ->
            HookLogger.w(TAG, "读取插件远程文件列表失败，跳过插件加载", error)
            return
        }

        enabledIds.sorted().forEach { pluginId ->
            val fileName = PluginRemoteFileNames.forId(pluginId)
            if (fileName !in remoteFiles) {
                HookLogger.w(TAG, "插件文件不存在: id=$pluginId, file=$fileName")
                return@forEach
            }
            runCatching { loadPlugin(pluginId, fileName) }.onFailure { error ->
                HookLogger.w(TAG, "插件加载失败: id=$pluginId", error)
            }
        }

        processors = loadedPlugins
            .flatMap { it.extensions }
            .filterIsInstance<LyricProcessorExtension>()
        HookLogger.i(
            TAG,
            "插件 Runtime 初始化完成: enabled=${enabledIds.size}, " +
                    "loaded=${loadedPlugins.size}, processors=${processors.size}"
        )
    }

    fun processSong(song: PluginSong, onResult: (PluginSong) -> Unit) {
        if (closed.get()) return
        val currentGeneration = generation.incrementAndGet()
        activeJob?.cancel()
        val currentProcessors = processors
        if (currentProcessors.isEmpty()) return

        activeJob = scope.launch {
            var current = song
            var changed = false
            for (processor in currentProcessors) {
                if (!isActive || currentGeneration != generation.get()) return@launch
                val result = runProcessor(processor, current) ?: continue
                if (result != current) {
                    current = result
                    changed = true
                }
            }
            if (!changed || !isActive || currentGeneration != generation.get()) return@launch
            runCatching { onResult(current) }.onFailure { error ->
                HookLogger.w(TAG, "插件结果回调失败", error)
            }
        }
    }

    fun cancelActiveProcessing() {
        generation.incrementAndGet()
        activeJob?.cancel()
        activeJob = null
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelActiveProcessing()
        loadedPlugins.asReversed().forEach { loaded ->
            runCatching { loaded.preferences.unregisterOnSharedPreferenceChangeListener(loaded.listener) }
            runCatching { loaded.plugin.onUnload() }.onFailure { error ->
                HookLogger.w(TAG, "插件卸载回调失败: id=${loaded.manifest.id}", error)
            }
        }
        loadedPlugins.clear()
        processors = emptyList()
        processorExecutor.shutdownNow()
        scope.cancel()
    }

    private fun loadPlugin(pluginId: String, fileName: String) {
        val archiveBytes = module.openRemoteFile(fileName).useReadOnly { input ->
            PluginArchiveReader.readBounded(input)
        }
        val archive = PluginArchiveReader.read(archiveBytes)
        require(archive.manifest.id == pluginId) {
            "Plugin id does not match enabled registry"
        }
        require(archive.manifest.apiVersion <= HYPERLYRIC_PLUGIN_API_VERSION) {
            "Plugin API is newer than host"
        }

        val classLoader = withPluginClassLoaderCreation {
            InMemoryDexClassLoader(
                archive.dexFiles
                    .map { java.nio.ByteBuffer.wrap(it) }
                    .toTypedArray(),
                parentClassLoader
            )
        }
        val entryClass = classLoader.loadClass(archive.manifest.entry)
        require(HyperLyricPlugin::class.java.isAssignableFrom(entryClass)) {
            "Plugin entry does not implement HyperLyricPlugin"
        }
        val plugin = entryClass.getDeclaredConstructor().apply { isAccessible = true }
            .newInstance() as HyperLyricPlugin
        val preferences = module.getRemotePreferences(PluginConstants.configGroup(pluginId))
        val context = RuntimePluginContext(pluginId, application, preferences)

        try {
            plugin.onLoad(context)
            plugin.onEnable()
        } catch (error: Throwable) {
            runCatching { plugin.onUnload() }
            throw error
        }

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            scope.launch {
                runCatching { plugin.onConfigChanged(context.config) }.onFailure { error ->
                    HookLogger.w(TAG, "插件配置回调失败: id=$pluginId", error)
                }
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        loadedPlugins += LoadedPlugin(
            manifest = archive.manifest,
            plugin = plugin,
            context = context,
            preferences = preferences,
            listener = listener,
            extensions = context.registeredExtensions()
        )
        HookLogger.i(
            TAG,
            "插件已启用: id=$pluginId, version=${archive.manifest.version}, " +
                    "extensions=${context.registeredExtensions().size}"
        )
    }

    private fun runProcessor(
        processor: LyricProcessorExtension,
        song: PluginSong
    ): PluginSong? {
        val future: Future<PluginSong?> = try {
            processorExecutor.submit<PluginSong?> { processor.process(song) }
        } catch (_: Exception) {
            return null
        }
        return try {
            future.get(PluginConstants.MAX_PROCESSOR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            HookLogger.w(
                TAG,
                "插件处理超时: extension=${processor.id}, " +
                        "timeoutMs=${PluginConstants.MAX_PROCESSOR_TIMEOUT_MS}"
            )
            null
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            null
        } catch (_: FutureCancellationException) {
            null
        } catch (error: ExecutionException) {
            HookLogger.w(TAG, "插件处理失败: extension=${processor.id}", error.cause)
            null
        } catch (error: Exception) {
            HookLogger.w(TAG, "插件处理失败: extension=${processor.id}", error)
            null
        }
    }

    private data class LoadedPlugin(
        val manifest: PluginManifest,
        val plugin: HyperLyricPlugin,
        val context: RuntimePluginContext,
        val preferences: SharedPreferences,
        val listener: SharedPreferences.OnSharedPreferenceChangeListener,
        val extensions: List<HyperLyricExtension>,
    )
}

private class RuntimePluginContext(
    override val pluginId: String,
    application: Application,
    preferences: SharedPreferences,
) : PluginContext {
    private val extensionLock = Any()
    private val extensions = mutableListOf<HyperLyricExtension>()

    override val hostApiVersion: Int = HYPERLYRIC_PLUGIN_API_VERSION
    override val config: PluginConfig = SharedPreferencesPluginConfig(preferences)
    override val logger: PluginLogger = RuntimePluginLogger(pluginId)
    override val storage: PluginStorage = SharedPreferencesPluginStorage(
        application.getSharedPreferences(
            com.lidesheng.hyperlyric.plugin.core.PluginConstants.storagePreferences(pluginId),
            android.content.Context.MODE_PRIVATE
        )
    )

    override fun registerExtension(extension: HyperLyricExtension) {
        require(extension.id.isNotBlank()) { "Plugin extension id is blank" }
        synchronized(extensionLock) {
            require(extensions.none { it.id == extension.id }) {
                "Duplicate plugin extension id: ${extension.id}"
            }
            extensions += extension
        }
    }

    fun registeredExtensions(): List<HyperLyricExtension> = synchronized(extensionLock) {
        extensions.toList()
    }
}

private class SharedPreferencesPluginConfig(
    private val preferences: SharedPreferences
) : PluginConfig {
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        preferences.getBoolean(key, defaultValue)

    override fun getString(key: String, defaultValue: String?): String? =
        preferences.getString(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Long =
        preferences.getLong(key, defaultValue)

    override fun getFloat(key: String, defaultValue: Float): Float =
        preferences.getFloat(key, defaultValue)

    override fun getStringSet(key: String, defaultValue: Set<String>): Set<String> =
        preferences.getStringSet(key, defaultValue)?.toSet() ?: defaultValue
}

private class SharedPreferencesPluginStorage(
    private val preferences: SharedPreferences
) : PluginStorage {
    override fun getString(key: String, defaultValue: String?): String? =
        preferences.getString(key, defaultValue)

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }
}

private class RuntimePluginLogger(private val pluginId: String) : PluginLogger {
    private fun tag() = pluginId

    override fun debug(message: String) = HookLogger.d(tag(), message)

    override fun info(message: String) = HookLogger.i(tag(), message)

    override fun warn(message: String, throwable: Throwable?) {
        if (throwable == null) HookLogger.w(tag(), message) else HookLogger.w(tag(), message, throwable)
    }

    override fun error(message: String, throwable: Throwable?) {
        if (throwable == null) HookLogger.e(tag(), message) else HookLogger.e(tag(), message, throwable)
    }

    override fun withTag(tag: String): PluginLogger = TaggedRuntimePluginLogger(tag)
}

private class TaggedRuntimePluginLogger(
    private val componentTag: String,
) : PluginLogger {
    override fun debug(message: String) = HookLogger.d(componentTag, message)

    override fun info(message: String) = HookLogger.i(componentTag, message)

    override fun warn(message: String, throwable: Throwable?) {
        if (throwable == null) HookLogger.w(componentTag, message)
        else HookLogger.w(componentTag, message, throwable)
    }

    override fun error(message: String, throwable: Throwable?) {
        if (throwable == null) HookLogger.e(componentTag, message)
        else HookLogger.e(componentTag, message, throwable)
    }

    override fun withTag(tag: String): PluginLogger = TaggedRuntimePluginLogger(tag)
}

private inline fun <T> ParcelFileDescriptor.useReadOnly(block: (java.io.InputStream) -> T): T {
    return ParcelFileDescriptor.AutoCloseInputStream(this).use(block)
}
