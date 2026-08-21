package com.lidesheng.hyperlyric.root.plugin

import android.app.Application
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import android.util.Base64
import dalvik.system.InMemoryDexClassLoader
import com.lidesheng.hyperlyric.plugin.api.HYPERLYRIC_PLUGIN_API_VERSION
import com.lidesheng.hyperlyric.plugin.api.HyperLyricExtension
import com.lidesheng.hyperlyric.plugin.api.HyperLyricPlugin
import com.lidesheng.hyperlyric.plugin.api.LyricProcessorExtension
import com.lidesheng.hyperlyric.plugin.api.PluginCache
import com.lidesheng.hyperlyric.plugin.api.PluginConfig
import com.lidesheng.hyperlyric.plugin.api.PluginContext
import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginLyricsUpdateMode
import com.lidesheng.hyperlyric.plugin.api.PluginLogger
import com.lidesheng.hyperlyric.plugin.api.PluginProcessingContext
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import com.lidesheng.hyperlyric.plugin.api.PluginStorage
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongResult
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
        private const val LAST_CACHE_CLEAR_TOKEN_KEY = "__hyperlyric_core_last_clear_token"

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
    private var registryPreferences: SharedPreferences? = null

    private val registryListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PluginConstants.REMOTE_CACHE_CLEAR_TOKENS_KEY) {
            consumePendingCacheClears(registryPreferences)
        }
    }

    @Volatile
    private var processors: List<RegisteredProcessor> = emptyList()
    @Volatile
    private var processorSetFingerprint: String = ""

    fun loadEnabledPlugins() {
        if (closed.get()) return

        val registry = runCatching {
            module.getRemotePreferences(PluginConstants.REMOTE_REGISTRY_PREFS)
        }.getOrElse { error ->
            HookLogger.w(TAG, "读取插件启用状态失败，跳过插件加载", error)
            return
        }
        registryPreferences = registry
        consumePendingCacheClears(registry)
        registry.registerOnSharedPreferenceChangeListener(registryListener)
        val enabledIds = runCatching {
            registry.getStringSet(PluginConstants.REMOTE_ENABLED_IDS_KEY, emptySet()).orEmpty()
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

        val registeredProcessors = mutableListOf<RegisteredProcessor>()
        loadedPlugins.forEachIndexed { pluginIndex, loaded ->
            loaded.extensions.filterIsInstance<LyricProcessorExtension>()
                .forEachIndexed { extensionIndex, extension ->
                    registeredProcessors += RegisteredProcessor(
                        pluginId = loaded.manifest.id,
                        pluginIndex = pluginIndex,
                        extensionIndex = extensionIndex,
                        extension = extension
                    )
                }
        }
        processors = registeredProcessors.sortedWith(
            compareBy<RegisteredProcessor> { it.extension.stage.ordinal }
                .thenBy { it.pluginId }
                .thenBy { it.extension.id }
                .thenBy { it.pluginIndex }
                .thenBy { it.extensionIndex }
        )
        processorSetFingerprint = processors.joinToString("\u001F") { registered ->
            "${registered.pluginId}:${registered.extension.id}:${registered.extension.stage.name}"
        }
        HookLogger.i(
            TAG,
            "插件 Runtime 初始化完成: enabled=${enabledIds.size}, " +
                    "loaded=${loadedPlugins.size}, processors=${processors.size}"
        )
    }

    internal fun processingSetFingerprint(): String = processorSetFingerprint

    internal fun processSong(
        song: PluginSong,
        processingContext: PluginProcessingContext,
        onResult: (PluginProcessingResult?) -> Unit
    ) {
        if (closed.get()) return
        val currentGeneration = generation.incrementAndGet()
        activeJob?.cancel()
        val currentProcessors = processors
        if (currentProcessors.isEmpty()) {
            runCatching { onResult(null) }.onFailure { error ->
                HookLogger.w(TAG, "插件结果回调失败", error)
            }
            return
        }

        activeJob = scope.launch {
            var current = song
            val changedFields = linkedSetOf<PluginSongField>()
            val changedLyricFields = linkedSetOf<PluginLyricField>()
            var lyricsUpdateMode: PluginLyricsUpdateMode? = null
            for (registered in currentProcessors) {
                if (!isActive || currentGeneration != generation.get()) return@launch
                val result = runProcessor(
                    processor = registered.extension,
                    song = current,
                    processingContext = processingContext
                ) ?: continue
                val merged = PluginSongMapper.mergePluginSong(
                    base = current,
                    result = result
                )
                if (merged == null) {
                    HookLogger.w(
                        TAG,
                        "插件结果非法，保留当前快照: extension=${registered.extension.id}"
                    )
                    continue
                }
                if (merged != current) {
                    current = merged
                    changedFields += result.changedFields
                    if (PluginSongField.LYRICS in result.changedFields) {
                        if (result.lyricsUpdateMode == PluginLyricsUpdateMode.REPLACE) {
                            // A full replacement already contains every lyric field. Any patch
                            // that follows is applied to this complete snapshot and the final
                            // callback can safely remain a REPLACE result.
                            lyricsUpdateMode = PluginLyricsUpdateMode.REPLACE
                            changedLyricFields.clear()
                        } else if (lyricsUpdateMode != PluginLyricsUpdateMode.REPLACE) {
                            lyricsUpdateMode = PluginLyricsUpdateMode.PATCH
                            changedLyricFields += result.changedLyricFields
                        }
                    }
                }
            }
            if (!isActive || currentGeneration != generation.get()) {
                return@launch
            }
            if (changedFields.isEmpty()) {
                runCatching { onResult(null) }.onFailure { error ->
                    HookLogger.w(TAG, "插件结果回调失败", error)
                }
                return@launch
            }
            runCatching {
                onResult(
                    PluginProcessingResult(
                        result = PluginSongResult(
                            song = current,
                            changedFields = changedFields.toSet(),
                            lyricsUpdateMode = if (
                                PluginSongField.LYRICS in changedFields &&
                                lyricsUpdateMode != PluginLyricsUpdateMode.REPLACE
                            ) {
                                PluginLyricsUpdateMode.PATCH
                            } else {
                                PluginLyricsUpdateMode.REPLACE
                            },
                            changedLyricFields = changedLyricFields.toSet()
                        )
                    )
                )
            }.onFailure { error ->
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
        processorSetFingerprint = ""
        registryPreferences?.let { preferences ->
            runCatching { preferences.unregisterOnSharedPreferenceChangeListener(registryListener) }
        }
        registryPreferences = null
        processorExecutor.shutdownNow()
        scope.cancel()
    }

    /**
     * App-side uninstall cannot directly open SystemUI's private preferences. It publishes a
     * one-shot token through the existing remote registry; the host runtime clears its own cache
     * and records the token locally so a later SystemUI restart does not repeat the operation.
     */
    private fun consumePendingCacheClears(registry: SharedPreferences?) {
        val tokens = runCatching {
            registry?.getStringSet(
                PluginConstants.REMOTE_CACHE_CLEAR_TOKENS_KEY,
                emptySet()
            ).orEmpty()
        }.onFailure { error ->
            HookLogger.w(TAG, "读取插件缓存清理请求失败", error)
        }.getOrDefault(emptySet())
        tokens.forEach { encoded ->
            val separator = encoded.indexOf('\u001F')
            if (separator <= 0 || separator == encoded.lastIndex) return@forEach
            val pluginId = encoded.substring(0, separator)
            val token = encoded.substring(separator + 1)
            runCatching {
                val marker = application.getSharedPreferences(
                    PluginConstants.cacheMetadataPreferences(pluginId),
                    android.content.Context.MODE_PRIVATE
                )
                if (marker.getString(LAST_CACHE_CLEAR_TOKEN_KEY, null) == token) {
                    return@runCatching
                }
                val cleared = application.getSharedPreferences(
                    PluginConstants.cachePreferences(pluginId),
                    android.content.Context.MODE_PRIVATE
                ).edit().clear().commit()
                check(cleared) { "cache clear commit returned false" }
                val marked = marker.edit().putString(LAST_CACHE_CLEAR_TOKEN_KEY, token).commit()
                check(marked) { "cache clear marker commit returned false" }
                HookLogger.i(TAG, "插件缓存已清理: id=$pluginId")
            }.onFailure { error ->
                HookLogger.w(TAG, "插件缓存清理失败: id=$pluginId", error)
            }
        }
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
        song: PluginSong,
        processingContext: PluginProcessingContext
    ): PluginSongResult? {
        val future: Future<PluginSongResult?> = try {
            processorExecutor.submit<PluginSongResult?> {
                invokePluginProcessorSafely(
                    processor = processor,
                    song = song,
                    processingContext = processingContext
                ) { error ->
                    HookLogger.w(TAG, "插件处理失败: extension=${processor.id}", error)
                }
            }
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

    private data class RegisteredProcessor(
        val pluginId: String,
        val pluginIndex: Int,
        val extensionIndex: Int,
        val extension: LyricProcessorExtension,
    )
}

internal fun invokePluginProcessorSafely(
    processor: LyricProcessorExtension,
    song: PluginSong,
    processingContext: PluginProcessingContext,
    onFailure: (Throwable) -> Unit,
): PluginSongResult? = try {
    processor.processResult(song, processingContext)
} catch (error: Throwable) {
    onFailure(error)
    null
}

internal data class PluginProcessingResult(
    val result: PluginSongResult,
)

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
    override val cache: PluginCache = SharedPreferencesPluginCache(
        preferences = application.getSharedPreferences(
            PluginConstants.cachePreferences(pluginId),
            android.content.Context.MODE_PRIVATE
        ),
        logger = logger.withTag("PluginCache")
    )
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

/** SharedPreferences is the current host backend, not a plugin-visible file directory. */
private class SharedPreferencesPluginCache(
    private val preferences: SharedPreferences,
    private val logger: PluginLogger,
) : PluginCache {
    private companion object {
        const val MAX_KEY_LENGTH = 256
        const val MAX_VALUE_BYTES = 2 * 1024 * 1024
    }

    override fun getString(key: String): String? {
        if (!isValidKey(key)) return null
        return runCatching { preferences.getString(key, null) }
            .onFailure { logger.warn("读取缓存失败: key=$key", it) }
            .getOrNull()
    }

    override fun putString(key: String, value: String) {
        if (!isValidKey(key) || !isWithinLimit(value.toByteArray(Charsets.UTF_8))) {
            logger.warn("忽略超限或非法缓存写入: key=$key")
            return
        }
        runCatching { preferences.edit().putString(key, value).apply() }
            .onFailure { logger.warn("写入缓存失败: key=$key", it) }
    }

    override fun getBytes(key: String): ByteArray? {
        val encoded = getString(key) ?: return null
        return runCatching { Base64.decode(encoded, Base64.DEFAULT) }
            .map { decoded ->
                if (!isWithinLimit(decoded)) {
                    remove(key)
                    null
                } else {
                    decoded
                }
            }
            .onFailure {
                logger.warn("解析缓存字节失败，删除记录: key=$key", it)
                remove(key)
            }
            .getOrNull()
    }

    override fun putBytes(key: String, value: ByteArray) {
        if (!isValidKey(key) || !isWithinLimit(value)) {
            logger.warn("忽略超限或非法缓存字节写入: key=$key")
            return
        }
        putString(key, Base64.encodeToString(value, Base64.NO_WRAP))
    }

    override fun contains(key: String): Boolean {
        if (!isValidKey(key)) return false
        return runCatching { preferences.contains(key) }
            .onFailure { logger.warn("检查缓存失败: key=$key", it) }
            .getOrDefault(false)
    }

    override fun remove(key: String) {
        if (!isValidKey(key)) return
        runCatching { preferences.edit().remove(key).apply() }
            .onFailure { logger.warn("删除缓存失败: key=$key", it) }
    }

    override fun clear() {
        runCatching { preferences.edit().clear().apply() }
            .onFailure { logger.warn("清空缓存失败", it) }
    }

    private fun isValidKey(key: String): Boolean =
        key.isNotBlank() && key.length <= MAX_KEY_LENGTH

    private fun isWithinLimit(value: ByteArray): Boolean = value.size <= MAX_VALUE_BYTES
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

    override fun withTag(tag: String): PluginLogger =
        TaggedRuntimePluginLogger(pluginId, tag)
}

private class TaggedRuntimePluginLogger(
    private val pluginId: String,
    private val componentTag: String,
) : PluginLogger {
    private val logTag = "$pluginId/$componentTag"

    override fun debug(message: String) = HookLogger.d(logTag, message)

    override fun info(message: String) = HookLogger.i(logTag, message)

    override fun warn(message: String, throwable: Throwable?) {
        if (throwable == null) HookLogger.w(logTag, message)
        else HookLogger.w(logTag, message, throwable)
    }

    override fun error(message: String, throwable: Throwable?) {
        if (throwable == null) HookLogger.e(logTag, message)
        else HookLogger.e(logTag, message, throwable)
    }

    override fun withTag(tag: String): PluginLogger =
        TaggedRuntimePluginLogger(pluginId, "$componentTag/$tag")
}

private inline fun <T> ParcelFileDescriptor.useReadOnly(block: (java.io.InputStream) -> T): T {
    return ParcelFileDescriptor.AutoCloseInputStream(this).use(block)
}
