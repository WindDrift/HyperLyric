package com.lidesheng.hyperlyric.root

import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.common.UIConstants
import com.lidesheng.hyperlyric.lyric.source.SourceManager
import com.lidesheng.hyperlyric.root.aitrans.AiTranslationGateway
import com.lidesheng.hyperlyric.root.aitrans.AiTranslationGatewayImpl
import com.lidesheng.hyperlyric.root.island.IslandAlbumCoverStyleHooker
import com.lidesheng.hyperlyric.root.island.IslandModuleRestoreHooker
import com.lidesheng.hyperlyric.root.island.IslandMusicWaveColorHooker
import com.lidesheng.hyperlyric.root.island.IslandProgressGlowController
import com.lidesheng.hyperlyric.root.island.RealIslandHooker
import com.lidesheng.hyperlyric.root.island.StatusBarTextColorHooker
import com.lidesheng.hyperlyric.root.island.SystemUIHookRegistry
import com.lidesheng.hyperlyric.root.island.renderer.BaseIslandRenderer
import com.lidesheng.hyperlyric.root.mediacard.MediaCardElementBehaviorHooker
import com.lidesheng.hyperlyric.root.mediacard.MediaCardRuntimeConfig
import com.lidesheng.hyperlyric.root.mediacard.island.IslandExpandedMediaAmbientFlowHooker
import com.lidesheng.hyperlyric.root.mediacard.island.layout.IslandExpandedMediaLayoutHooker
import com.lidesheng.hyperlyric.root.mediacard.notification.NotificationMediaAmbientFlowHooker
import com.lidesheng.hyperlyric.root.mediacard.notification.NotificationMediaCoverStyleHooker
import com.lidesheng.hyperlyric.root.mediacard.progress.MediaProgressStyleHooker
import com.lidesheng.hyperlyric.root.source.LyricInfoSource
import com.lidesheng.hyperlyric.root.source.LyriconSource
import com.lidesheng.hyperlyric.root.source.RootLyricSink
import com.lidesheng.hyperlyric.root.source.SuperLyricSource
import com.lidesheng.hyperlyric.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

class HookEntry : XposedModule() {

    companion object {
        private const val STATE_RUNTIME_READY = "runtimeReady"
        private const val STATE_STATUS_BAR_TEXT_COLOR = "statusBarTextColor"

        @Volatile
        var activeMode = 0
        val lyriconSource = LyriconSource()
        val superLyricSource = SuperLyricSource()
        var lyricInfoSource: LyricInfoSource? = null
        var sourceManager: SourceManager? = null
            private set

        @JvmStatic
        var instance: HookEntry? = null
            private set

        private val SUPER_ISLAND_RUNTIME_REFRESH_KEYS = setOf(
            RootConstants.KEY_HOOK_ISLAND_CONTENT_LEFT,
            RootConstants.KEY_HOOK_ISLAND_CONTENT_RIGHT,
            RootConstants.KEY_HOOK_ISLAND_LEFT_PADDING_LEFT,
            RootConstants.KEY_HOOK_ISLAND_LEFT_PADDING_RIGHT,
            RootConstants.KEY_HOOK_ISLAND_RIGHT_PADDING_LEFT,
            RootConstants.KEY_HOOK_ISLAND_RIGHT_PADDING_RIGHT,
            RootConstants.KEY_HOOK_ISLAND_RIGHT_CONTENT_MAX_WIDTH,
            RootConstants.KEY_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE,
            RootConstants.KEY_HOOK_ISLAND_GLOW_EXTRACT_COLOR,
            RootConstants.KEY_HOOK_ISLAND_PROGRESS_GLOW,
            RootConstants.KEY_HOOK_ISLAND_PROGRESS_GRADIENT,
            RootConstants.KEY_HOOK_TEXT_SIZE,
            RootConstants.KEY_HOOK_TEXT_SIZE_RATIO,
            RootConstants.KEY_HOOK_FONT_WEIGHT,
            RootConstants.KEY_HOOK_FONT_ITALIC,
            RootConstants.KEY_HOOK_FADING_EDGE_LENGTH,
            RootConstants.KEY_HOOK_GRADIENT_PROGRESS,
            RootConstants.KEY_HOOK_CENTER_LYRIC,
            RootConstants.KEY_HOOK_ANIM_ENABLE,
            RootConstants.KEY_HOOK_ANIM_ID,
            RootConstants.KEY_HOOK_MARQUEE_MODE,
            RootConstants.KEY_HOOK_MARQUEE_SPEED,
            RootConstants.KEY_HOOK_MARQUEE_DELAY,
            RootConstants.KEY_HOOK_MARQUEE_LOOP_DELAY,
            RootConstants.KEY_HOOK_MARQUEE_INFINITE,
            RootConstants.KEY_HOOK_MARQUEE_STOP_END,
            RootConstants.KEY_HOOK_MARQUEE_METADATA_MODE,
            RootConstants.KEY_HOOK_MARQUEE_METADATA_SPEED,
            RootConstants.KEY_HOOK_MARQUEE_METADATA_DELAY,
            RootConstants.KEY_HOOK_MARQUEE_METADATA_LOOP_DELAY,
            RootConstants.KEY_HOOK_MARQUEE_METADATA_INFINITE,
            RootConstants.KEY_HOOK_SYLLABLE_RELATIVE,
            RootConstants.KEY_HOOK_SYLLABLE_HIGHLIGHT,
            RootConstants.KEY_HOOK_DISABLE_TRANSLATION,
            RootConstants.KEY_HOOK_TRANSLATION_ONLY,
            RootConstants.KEY_HOOK_SWAP_TRANSLATION,
            RootConstants.KEY_HOOK_NEXT_LYRIC_LINE,
            RootConstants.KEY_HOOK_CUSTOM_FONT_PATH,
            RootConstants.KEY_HOOK_WORD_MOTION_ENABLED,
            RootConstants.KEY_HOOK_WORD_MOTION_CJK_LIFT,
            RootConstants.KEY_HOOK_WORD_MOTION_CJK_WAVE,
            RootConstants.KEY_HOOK_WORD_MOTION_LATIN_BY_CHARACTER,
            RootConstants.KEY_HOOK_WORD_MOTION_LATIN_LIFT,
            RootConstants.KEY_HOOK_WORD_MOTION_LATIN_WAVE,
            RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND
        )
    }

    private var _prefs: android.content.SharedPreferences? = null
    private var prefListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? =
        null
    private var runtimeApp: Application? = null

    val prefs: android.content.SharedPreferences
        get() {
            if (_prefs == null) {
                _prefs = getRemotePreferences(UIConstants.PREF_NAME)
            }
            return _prefs!!
        }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        instance = this
        HookLogger.module = this
        HookLogger.i(
            "HookEntry",
            "模块加载完成，当前应用版本${com.lidesheng.hyperlyric.BuildConfig.VERSION_CODE}-${com.lidesheng.hyperlyric.BuildConfig.VERSION_NAME}"
        )
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        param.setSavedInstanceState(
            Bundle().apply {
                putBoolean(STATE_RUNTIME_READY, runtimeApp != null)
                putInt(
                    STATE_STATUS_BAR_TEXT_COLOR,
                    StatusBarTextColorHooker.currentTextColor()
                )
            }
        )
        // The media-card hookers intentionally stay alive in the old generation. Their
        // configuration is restart-only, so replacing them here is both unnecessary and
        // unsafe for active SystemUI card/fake-view animations.
        cleanupRuntime(preserveMediaHooks = true)
        HookLogger.i("HookEntry", "超级岛歌词热重载准备完成")
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        instance = this
        HookLogger.module = this

        var replacedCount = 0
        var retainedNoReloadCount = 0
        param.oldHookHandles.forEach { handle ->
            val executable = handle.executable
            val replacement = createLyricReplacementHooker(executable)
            if (replacement != null) {
                runCatching {
                    handle.replaceHook(replacement)
                    replacedCount++
                }.onFailure {
                    // A failed replacement leaves the old hook in place. This is safer than
                    // losing an active island feature while SystemUI continues to run.
                    retainedNoReloadCount++
                }
            } else {
                // Media cards deliberately fall into this branch: no replacement, no runtime
                // config read, no mutation of an in-flight card/fake-view animation.
                retainedNoReloadCount++
            }
        }

        val state = param.savedInstanceState as? Bundle
        if (state?.containsKey(STATE_STATUS_BAR_TEXT_COLOR) == true) {
            StatusBarTextColorHooker.restoreTextColor(
                state.getInt(STATE_STATUS_BAR_TEXT_COLOR)
            )
        }
        if (state?.getBoolean(STATE_RUNTIME_READY) == true) {
            findCurrentApplication()?.let { app ->
                Handler(Looper.getMainLooper()).post { initializeSystemEnvironment(app) }
            } ?: HookLogger.w(
                "HookEntry",
                "热重载后未取得当前 Application，等待 Application.onCreate"
            )
        }
        HookLogger.i(
            "HookEntry",
            "超级岛歌词热重载完成: replaced=$replacedCount, " +
                    "retainedNoReload=$retainedNoReloadCount"
        )
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        val processName = runCatching { Application.getProcessName() }.getOrNull() ?: ""

        // 仅在主进程注入
        if (processName.contains(":")) return

        val packageName = param.packageName

        if (packageName == "com.android.systemui") {
            StatusBarTextColorHooker.hook(this, param.defaultClassLoader)
            MediaCardRuntimeConfig.load(prefs)
            MediaProgressStyleHooker.hook(this, param.defaultClassLoader)
            MediaCardElementBehaviorHooker.hook(this, param.defaultClassLoader)
            IslandExpandedMediaAmbientFlowHooker.hook(this, param.defaultClassLoader)
            IslandExpandedMediaLayoutHooker.hook(this, param.defaultClassLoader)
            NotificationMediaAmbientFlowHooker.hook(this, param.defaultClassLoader)
            NotificationMediaCoverStyleHooker.hook(this, param.defaultClassLoader)
            try {
                UnlockIslandWhitelist.hook(this, param.defaultClassLoader)
            } catch (e: Exception) {
                if (e is ClassNotFoundException || e is NoSuchMethodException) {
                    HookLogger.w("HookEntry", "此系统版本不支持超级岛下拉小窗白名单")
                } else {
                    HookLogger.e("HookEntry", "超级岛下拉小窗白名单注入失败", e)
                }
            }
            try {
                UnlockFocusWhitelist.hook(this, param.defaultClassLoader)
            } catch (e: Exception) {
                if (e is ClassNotFoundException || e is NoSuchMethodException) {
                    HookLogger.w("HookEntry", "此系统版本不支持解锁焦点通知白名单")
                } else {
                    HookLogger.e("HookEntry", "焦点通知白名单注入失败", e)
                }
            }

            val isSuperIslandEnabled = SystemUiEnhancementGate.isEnabled()

            if (!isSuperIslandEnabled) {
                HookLogger.i("HookEntry", "小米系统界面增强已禁用")
            }

            activeMode = prefs.getInt(
                RootConstants.KEY_HOOK_LYRIC_MODE,
                RootConstants.DEFAULT_HOOK_LYRIC_MODE
            )
            HookLogger.i("HookEntry", "超级岛歌词模式: mode=$activeMode")

            // 劫持 Application.onCreate 以初始化 Lyricon Receiver 所需的环境
            try {
                val appClass = param.defaultClassLoader.loadClass("android.app.Application")
                val onCreateMethod = appClass.getDeclaredMethod("onCreate")
                deoptimize(onCreateMethod)
                hook(onCreateMethod).intercept(AppCreateHooker())
                HookLogger.d("HookEntry", "安装生命周期 Hook: target=Application.onCreate")
            } catch (e: Exception) {
                if (e is ClassNotFoundException || e is NoSuchMethodException) {
                    HookLogger.w("HookEntry", "跳过生命周期 Hook: target=Application.onCreate")
                } else {
                    HookLogger.e(
                        "HookEntry",
                        "安装生命周期 Hook 失败: target=Application.onCreate",
                        e
                    )
                }
            }

            // 核心：拦截 ClassLoader 构造，以捕捉 miui.systemui.plugin 等动态加载的插件
            try {
                val clClass = Class.forName("dalvik.system.BaseDexClassLoader")
                for (constructor in clClass.declaredConstructors) {
                    deoptimize(constructor)
                    hook(constructor).intercept(ClassLoaderHooker())
                }
                HookLogger.d("HookEntry", "安装插件加载 Hook: target=BaseDexClassLoader")
            } catch (e: Exception) {
                if (e is ClassNotFoundException || e is NoSuchMethodException) {
                    HookLogger.w("HookEntry", "跳过插件加载 Hook: target=BaseDexClassLoader")
                } else {
                    HookLogger.e(
                        "HookEntry",
                        "安装插件加载 Hook 失败: target=BaseDexClassLoader",
                        e
                    )
                }
            }

        } else if (packageName == "miui.systemui.plugin") {
            SystemUIHookRegistry.hook(this, param.defaultClassLoader)
        }
    }

    private fun initializeSystemEnvironment(app: Application) {
        try {
            cleanupRuntime()
            runtimeApp = app

            val renderer = BaseIslandRenderer
            val sink = RootLyricSink(renderer, prefs)

            lyriconSource.initialize(app, prefs)
            superLyricSource.initialize(app)
            lyricInfoSource = LyricInfoSource(app)

            AiTranslationGatewayImpl()
            AiTranslationGateway.init(app)

            sourceManager = SourceManager(
                sources = listOf(lyriconSource, superLyricSource, lyricInfoSource!!),
                prefs = prefs,
                sink = sink,
                prefKey = RootConstants.KEY_HOOK_LYRIC_SOURCE,
                defaultSourceId = RootConstants.DEFAULT_HOOK_LYRIC_SOURCE,
                stateResetter = LyriconDataBridge,
                logger = HookLogger
            )
            activeMode = prefs.getInt(
                RootConstants.KEY_HOOK_LYRIC_MODE,
                RootConstants.DEFAULT_HOOK_LYRIC_MODE
            )
            if (SystemUiEnhancementGate.isEnabled()) {
                sourceManager?.start()
            }

            prefListener =
                android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key?.startsWith(RootConstants.KEY_HOOK_LYRICON_PROVIDER_DELAY_PREFIX) == true) {
                        lyriconSource.onPreferenceChanged(key)
                    }
                    when (key) {
                        RootConstants.KEY_HOOK_LYRIC_SOURCE -> {
                            val newSourceId =
                                prefs.getString(key, RootConstants.DEFAULT_HOOK_LYRIC_SOURCE)
                                    ?: RootConstants.DEFAULT_HOOK_LYRIC_SOURCE
                            if (!SystemUiEnhancementGate.isEnabled()) {
                                return@OnSharedPreferenceChangeListener
                            }
                            HookLogger.i("HookEntry", "切换歌词源: source=$newSourceId")
                            Handler(Looper.getMainLooper()).post {
                                sourceManager?.switchSource(newSourceId)
                            }
                        }

                        RootConstants.KEY_HOOK_LYRIC_MODE -> {
                            val newMode = prefs.getInt(key, RootConstants.DEFAULT_HOOK_LYRIC_MODE)
                            if (newMode == activeMode) return@OnSharedPreferenceChangeListener
                            HookLogger.i("HookEntry", "切换歌词模式: mode=$newMode")
                            Handler(Looper.getMainLooper()).post {
                                activeMode = newMode
                                BaseIslandRenderer.refreshActiveIsland()
                            }
                        }

                        RootConstants.KEY_HOOK_PLACEHOLDER_FORMAT -> {
                            val format = prefs.getInt(
                                key,
                                RootConstants.DEFAULT_HOOK_PLACEHOLDER_FORMAT
                            )
                            Handler(Looper.getMainLooper()).post {
                                if (LyriconDataBridge.updatePlaceholderFormat(format)) {
                                    BaseIslandRenderer.updateLyricLine()
                                    BaseIslandRenderer.updatePosition(
                                        LyriconDataBridge.currentPosition
                                    )
                                }
                            }
                        }

                        RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND -> {
                            Handler(Looper.getMainLooper()).post {
                                updateSystemUiEnhancements(SystemUiEnhancementGate.isEnabled())
                            }
                        }

                        RootConstants.KEY_HOOK_ISLAND_ALBUM_COVER_STYLE -> {
                            Handler(Looper.getMainLooper()).post {
                                IslandAlbumCoverStyleHooker.refresh()
                                BaseIslandRenderer.refreshActiveIsland()
                            }
                        }

                        RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_STYLE -> {
                            Handler(Looper.getMainLooper()).post {
                                IslandMusicWaveColorHooker.refresh()
                                BaseIslandRenderer.refreshActiveIsland()
                            }
                        }

                        RootConstants.KEY_HOOK_TEXT_COLOR_STYLE -> {
                            Handler(Looper.getMainLooper()).post {
                                BaseIslandRenderer.updateTextColors()
                            }
                        }

                        in SUPER_ISLAND_RUNTIME_REFRESH_KEYS -> {
                            Handler(Looper.getMainLooper()).post {
                                BaseIslandRenderer.refreshActiveIsland()
                            }
                        }
                    }
                }
            prefListener?.let {
                prefs.registerOnSharedPreferenceChangeListener(it)
            }

            HookLogger.i(
                "HookEntry",
                "系统环境初始化完成: enabled=${SystemUiEnhancementGate.isEnabled()}, " +
                        "source=${sourceManager?.getActiveSource()?.displayName ?: "inactive"}, " +
                        "mode=$activeMode"
            )
        } catch (e: Exception) {
            HookLogger.e("HookEntry", "系统环境初始化失败", e)
        }
    }

    private fun updateSystemUiEnhancements(enabled: Boolean) {
        if (enabled) {
            sourceManager?.start()
        } else {
            sourceManager?.stop()
            AiTranslationGateway.cancelActiveRequests()
            LyriconDataBridge.clearState()
            BaseIslandRenderer.clearAllViews()
            IslandProgressGlowController.clearAll()
        }

        IslandAlbumCoverStyleHooker.refresh()
        IslandMusicWaveColorHooker.refresh()

        if (enabled) {
            BaseIslandRenderer.refreshActiveIsland()
        }
        HookLogger.i("HookEntry", "更新系统界面增强状态: enabled=$enabled")
    }

    private fun cleanupRuntime(preserveMediaHooks: Boolean = false) {
        if (!preserveMediaHooks) {
            IslandAlbumCoverStyleHooker.cleanup()
            IslandMusicWaveColorHooker.cleanup()
        }
        prefListener?.let {
            runCatching { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        }
        prefListener = null
        runCatching { sourceManager?.stop() }
        AiTranslationGateway.cancelActiveRequests()
        sourceManager = null
        lyricInfoSource = null
        runtimeApp = null
    }

    private fun findCurrentApplication(): Application? {
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThreadClass.getDeclaredMethod("currentApplication")
            currentApplication.invoke(null) as? Application
        }.getOrNull()
    }

    private fun createLyricReplacementHooker(executable: Executable): Hooker? {
        val owner = executable.declaringClass.name
        if (executable is Constructor<*> && owner == "dalvik.system.BaseDexClassLoader") {
            return ClassLoaderHooker(lyricsOnly = true)
        }
        if (executable !is Method) return null

        return when (executable.name) {
            "onCreate" -> AppCreateHooker().takeIf { owner == "android.app.Application" }
            "updateBigIslandView" -> RealIslandHooker.UpdateBigIslandViewHook()
            "hideIslandLayout", "showIslandLayout" -> RealIslandHooker.LayoutVisibilityHook(
                executable.name
            )

            "updateModuleView" -> IslandModuleRestoreHooker.UpdateModuleViewHook()
                .takeIf { owner.endsWith("IslandTemplateBuilder") }

            "bindData" -> IslandModuleRestoreHooker.AdapterBindDataHook()
                .takeIf { owner.endsWith("IslandModuleViewHolderAdapter") }

            "updateView" -> IslandModuleRestoreHooker.AdapterUpdateViewHook()
                .takeIf { owner.endsWith("IslandModuleViewHolderAdapter") }

            "updateTemplate" -> HookIslandGlow.UpdateTemplateHook()
                .takeIf { owner.endsWith("DynamicIslandBaseContentView") }

            else -> StatusBarTextColorHooker.createReplacement(executable)
        }
    }

    /**
     * 动态类加载器劫持
     */
    inner class ClassLoaderHooker(
        private val lyricsOnly: Boolean = false
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val cl = chain.thisObject as? ClassLoader ?: return result
            try {
                SystemUIHookRegistry.hook(this@HookEntry, cl, lyricsOnly = lyricsOnly)
            } catch (e: Exception) {
                if (e is ClassNotFoundException || e is NoSuchMethodException) {
                    // HookLogger.w("HookEntry","插件中未找到超级岛相关类")
                } else {
                    HookLogger.e("HookEntry", "注入超级岛插件失败", e)
                }
            }
            return result
        }
    }

    /**
     * Application 生命周期劫持
     */
    class AppCreateHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val app = chain.thisObject as? Application
            app?.let { instance?.initializeSystemEnvironment(it) }
            return chain.proceed()
        }
    }
}
