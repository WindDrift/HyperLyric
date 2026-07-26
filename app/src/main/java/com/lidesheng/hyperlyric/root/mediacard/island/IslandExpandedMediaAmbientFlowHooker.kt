package com.lidesheng.hyperlyric.root.mediacard.island

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.root.island.IslandAlbumCoverStyleHooker
import com.lidesheng.hyperlyric.root.island.IslandProbeUtils
import com.lidesheng.hyperlyric.root.mediacard.MediaAmbientFlowPalette
import com.lidesheng.hyperlyric.root.mediacard.MediaAmbientFlowPaletteExtractor
import com.lidesheng.hyperlyric.root.mediacard.MediaArtworkSampler
import com.lidesheng.hyperlyric.root.mediacard.MediaCardRuntimeConfig
import com.lidesheng.hyperlyric.root.mediacard.background.MediaFlowArtwork
import com.lidesheng.hyperlyric.root.mediacard.background.MediaFlowBackgroundView
import com.lidesheng.hyperlyric.root.mediacard.background.MediaFlowOverlayLayout
import com.lidesheng.hyperlyric.root.mediacard.background.MediaFlowTimeline
import com.lidesheng.hyperlyric.root.mediacard.background.MediaFlowTone
import com.lidesheng.hyperlyric.root.mediacard.island.background.IslandExpandedBackgroundTarget
import com.lidesheng.hyperlyric.root.mediacard.island.background.IslandExpandedMediaBackgroundApi
import com.lidesheng.hyperlyric.root.mediacard.island.background.IslandExpandedMediaBackgroundController
import com.lidesheng.hyperlyric.root.mediacard.island.background.IslandExpandedMediaBackgroundHost
import com.lidesheng.hyperlyric.root.mediacard.island.background.IslandMediaBackgroundHostAdapter
import com.lidesheng.hyperlyric.root.mediacard.notification.background.NotificationMediaColorConfig
import com.lidesheng.hyperlyric.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object IslandExpandedMediaAmbientFlowHooker {
    private const val TAG = "IslandExpandedMediaAmbientFlowHooker"
    private const val BINDER_CLASS =
        "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewBinderImpl"
    private const val MINI_BAR_EVENT_CLASS = "${BINDER_CLASS}\$attach\$4\$1"
    private const val MUSIC_BG_VIEW_CLASS = "com.mi.widget.view.MusicBgView"
    private const val SEEK_BAR_HEAD_ALPHA_LISTENER_CLASS =
        "miuix.miuixbasewidget.widget.HyperProgressSeekBar\$1"
    private const val BASE_CONTENT_VIEW_CLASS =
        "miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView"
    private const val FAKE_CONTENT_VIEW_CLASS =
        "miui.systemui.dynamicisland.window.content.DynamicIslandContentFakeView"
    private const val EXPANDED_VIEW_CLASS =
        "miui.systemui.dynamicisland.view.DynamicIslandExpandedView"
    private const val MI_BLUR_COMPAT_CLASS = "miui.systemui.util.MiBlurCompat"
    private const val ORIGINAL_ALPHA_TAG_KEY = 0x7e48594c
    private const val CUSTOM_FLOW_VIEW_TAG = "hyperlyric.island_expanded_media_custom_flow"

    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val binderStates = Collections.synchronizedMap(WeakHashMap<Any, BinderState>())
    private val activeBinders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    )
    private val themeStates = Collections.synchronizedMap(WeakHashMap<View, ViewThemeState>())
    private val seekBarThemeStates = Collections.synchronizedMap(
        WeakHashMap<View, SeekBarThemeState>()
    )
    private val seekBarTrackStates = Collections.synchronizedMap(
        WeakHashMap<View, SeekBarTrackState>()
    )
    private val restoringNativeForeground = ThreadLocal<Boolean>()
    private val bindingBinder = ThreadLocal<Any?>()
    private val colorExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HyperLyric-IslandMediaColor").apply { isDaemon = true }
    }

    @Volatile
    private var nativeApi: NativeApi? = null

    fun hook(xposedModule: XposedModule, classLoader: ClassLoader) {
        if (!hookedClassLoaders.add(classLoader)) return

        val api = resolveApi(classLoader) ?: run {
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "跳过展开态媒体流光 Hook: reason=native_api_unavailable")
            return
        }

        val installedHandles = mutableListOf<HookHandle>()
        api.hookMethods.forEach { method ->
            runCatching {
                xposedModule.deoptimize(method)
                val hooker = hookerFor(method)
                    ?: error("No hooker for ${method.declaringClass.name}.${method.name}")
                installedHandles += xposedModule.hook(method).intercept(hooker)
            }.onFailure { error ->
                HookLogger.e(
                    TAG,
                    "安装展开态媒体 Hook 失败: method=${method.declaringClass.simpleName}.${method.name}",
                    error
                )
            }
        }

        if (installedHandles.size != api.hookMethods.size) {
            installedHandles.forEach(HookHandle::unhook)
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "展开态媒体流光 Hook 不完整，已移除全部 Hook")
        } else {
            installMiniBarTrackingHook(xposedModule, classLoader)
            HookLogger.i(
                TAG,
                "展开态媒体流光 Hook 已初始化: methods=${api.hookMethods.size}"
            )
        }
    }

    fun isTargetMethod(method: Method): Boolean {
        return when (method.declaringClass.name) {
            BINDER_CLASS -> when (method.name) {
                "attach" -> method.parameterCount == 2
                "bindMediaData" -> method.parameterCount == 1
                "detach" -> method.parameterCount == 0
                "setAlbumImage" -> method.parameterCount == 1
                "setSeamless" -> method.parameterCount == 2
                "updateForegroundColors" -> method.parameterCount == 1
                else -> false
            }

            MUSIC_BG_VIEW_CLASS ->
                (method.name == "start" || method.name == "resume") &&
                        method.parameterCount == 0

            SEEK_BAR_HEAD_ALPHA_LISTENER_CLASS ->
                method.name == "onUpdate" && method.parameterCount == 2

            else -> false
        }
    }

    fun hookerFor(executable: Executable): Hooker? {
        val method = executable as? Method ?: return null
        if (!isTargetMethod(method)) return null
        resolveApi(method.declaringClass.classLoader) ?: return null
        return when (method.declaringClass.name) {
            BINDER_CLASS -> when (method.name) {
                "attach" -> BinderHook(Action.ATTACH)
                "bindMediaData" -> BinderHook(Action.BIND)
                "detach" -> BinderHook(Action.DETACH)
                "setAlbumImage" -> BinderHook(Action.ALBUM)
                "setSeamless" -> BinderHook(Action.SEAMLESS)
                "updateForegroundColors" -> ForegroundColorsHook()
                else -> null
            }

            MUSIC_BG_VIEW_CLASS -> PlaybackStartHook()
            SEEK_BAR_HEAD_ALPHA_LISTENER_CLASS -> HeadGlowUpdateHook()
            else -> null
        }
    }

    fun resetMiniWindowBackgroundTransform() {
        if (!IslandExpandedMediaBackgroundController.isActive()) return
        val api = nativeApi ?: return
        synchronized(activeBinders) { activeBinders.toList() }.forEach { binder ->
            runCatching {
                api.resetDummyBackgroundTransform(binder)
            }.onFailure { error ->
                HookLogger.e(TAG, "复位小窗返回时的媒体背景失败", error)
            }
        }
    }

    private enum class Action { ATTACH, BIND, DETACH, ALBUM, SEAMLESS }

    private class BinderHook(private val action: Action) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val binder = chain.thisObject ?: return chain.proceed()
            if (!MediaCardRuntimeConfig.current.enabled) {
                if (action == Action.DETACH) cleanupBinder(binder)
                val result = chain.proceed()
                if (action == Action.ATTACH || action == Action.BIND) {
                    activeBinders.add(binder)
                }
                return result
            }
            // Keep XiaomiHelper's timing exactly: intercept the native setter
            // before it can restore this view on a fresh real/dummy holder.
            if (action == Action.SEAMLESS && hideDeviceSwitch()) {
                return null
            }
            if (action == Action.DETACH) cleanupBinder(binder)
            val nestedInBind = action != Action.BIND && bindingBinder.get() === binder
            val previousBinding = if (action == Action.BIND) bindingBinder.get() else null
            if (action == Action.BIND) bindingBinder.set(binder)
            val result = try {
                chain.proceed()
            } finally {
                if (action == Action.BIND) {
                    if (previousBinding == null) bindingBinder.remove()
                    else bindingBinder.set(previousBinding)
                }
            }
            if (nestedInBind && (action == Action.ALBUM || action == Action.SEAMLESS)) {
                return result
            }
            runCatching {
                when (action) {
                    Action.ATTACH -> {
                        activeBinders.add(binder)
                        applyAppearance(binder, allowCoverColor = false)
                        applyMediaElements(binder)
                    }

                    Action.BIND -> {
                        activeBinders.add(binder)
                        applyAppearance(
                            binder,
                            allowCoverColor = true,
                            mediaData = chain.args.firstOrNull()
                        )
                        applyMediaElements(binder)
                    }

                    Action.ALBUM -> {
                        applyMode(binder, allowCoverColor = true)
                        if (!IslandExpandedMediaBackgroundController.isActive()) {
                            applyCardTheme(binder)
                        }
                        applyMediaElements(binder)
                    }

                    Action.SEAMLESS -> applyMediaElements(binder)
                    Action.DETACH -> Unit
                }
                if (action != Action.DETACH) {
                    IslandAlbumCoverStyleHooker.onPlaybackStateChanged(
                        requireNotNull(nativeApi).isPlaying(binder)
                    )
                }
            }.onFailure { error ->
                HookLogger.e(TAG, "应用展开态媒体流光模式失败", error)
            }
            return result
        }
    }

    private class PlaybackStartHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (!MediaCardRuntimeConfig.current.enabled) return chain.proceed()
            val view = chain.thisObject as? View ?: return chain.proceed()
            val suppressNativeFlow = if (IslandExpandedMediaBackgroundController.isActive()) {
                IslandExpandedMediaBackgroundController.hasAppliedBackgroundForNativeFlow(view)
            } else {
                currentMode() == RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_DISABLED ||
                        isCustomMode(currentMode())
            }
            if (suppressNativeFlow && isExpandedIslandView(view)) {
                return null
            }
            return chain.proceed()
        }
    }

    private class ForegroundColorsHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (!MediaCardRuntimeConfig.current.enabled) return chain.proceed()
            if (restoringNativeForeground.get() == true) return chain.proceed()
            val binder = chain.thisObject ?: return chain.proceed()
            val holder = chain.args.firstOrNull() ?: return chain.proceed()
            if (IslandExpandedMediaBackgroundController.isActive()) {
                val api = nativeApi ?: return chain.proceed()
                val foregroundApplied = runCatching {
                    val foregroundApplied =
                        IslandExpandedMediaBackgroundController.applyForeground(
                            binder,
                            holder,
                            api
                        )
                    syncMusicWave(binder, holder, api)
                    foregroundApplied
                }.getOrElse { error ->
                    HookLogger.e(TAG, "保持展开态媒体前景色失败", error)
                    false
                }
                return if (foregroundApplied) null else chain.proceed()
            }
            val api = nativeApi ?: return chain.proceed()
            if (!shouldUseLightTheme(binder)) {
                val result = chain.proceed()
                syncMusicWave(binder, holder, api)
                return result
            }
            return try {
                val lightContext = api.getContext(binder)
                    .withNightMode(Configuration.UI_MODE_NIGHT_NO)
                applyLightForeground(api, holder, CardColors.from(lightContext))
                syncMusicWave(binder, holder, api)
                null
            } catch (error: Throwable) {
                HookLogger.e(TAG, "应用原生浅色前景失败", error)
                chain.proceed()
            }
        }
    }

    private class HeadGlowUpdateHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            if (!MediaCardRuntimeConfig.current.enabled) return result
            val api = nativeApi ?: return result
            val listener = chain.thisObject ?: return result
            val seekBar = api.getHeadAlphaListenerSeekBar(listener)
            if (seekBarThemeStates[seekBar]?.suppressHeadGlow == true) {
                api.setSeekBarHeadGlowAlpha(seekBar, 0f)
            }
            return result
        }
    }

    /**
     * XiaomiHelper applies MiniBar expansion from the binder's collector, not
     * from DynamicIslandContentFakeView.  Keeping this hook on the collector
     * means SystemUI continues to own fake-card alpha and content animations.
     */
    private class MiniBarTrackingHook(
        private val dummyHolderField: Field
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            if (!MediaCardRuntimeConfig.current.enabled) return result
            runCatching {
                val dummyHolder = dummyHolderField.get(chain.thisObject) ?: return@runCatching
                val event = chain.args.firstOrNull() ?: return@runCatching
                val action = eventComponent(event, "getFirst", "first") as? String
                    ?: return@runCatching
                val extras = eventComponent(event, "getSecond", "second") as? Bundle
                nativeApi?.applyDummyMiniBarTracking(
                    dummyHolder = dummyHolder,
                    action = action,
                    pullDownOffset = extras?.getFloat("pull_down_action_offset_y", 0f) ?: 0f
                )
            }.onFailure { error ->
                HookLogger.e(TAG, "同步展开态 MiniBar 背景失败", error)
            }
            return result
        }
    }

    private fun installMiniBarTrackingHook(module: XposedModule, classLoader: ClassLoader) {
        runCatching {
            val eventClass = classLoader.loadClass(MINI_BAR_EVENT_CLASS)
            // FlowCollector.emit is a suspend method on current HyperOS builds,
            // therefore its JVM signature has the value plus Continuation.
            // Keep the one-argument form for older/rewritten variants.
            val emit = eventClass.declaredMethods
                .filter { method -> method.name == "emit" && method.parameterCount in 1..2 }
                .maxByOrNull { method -> method.parameterCount }
                ?: return@runCatching
            val dummyHolder = eventClass.declaredFields.singleOrNull { field ->
                field.name == "\$dummyHolder"
            } ?: return@runCatching
            emit.isAccessible = true
            dummyHolder.isAccessible = true
            module.deoptimize(emit)
            module.hook(emit).intercept(MiniBarTrackingHook(dummyHolder))
            HookLogger.d(TAG, "已 Hook 展开态媒体 MiniBar collector: $emit")
        }.onFailure { error ->
            HookLogger.w(TAG, "跳过展开态媒体 MiniBar collector Hook: reason=${error.message}")
        }
    }

    private fun eventComponent(event: Any, getterName: String, fieldName: String): Any? {
        return runCatching {
            event.javaClass.methods.firstOrNull { method ->
                method.name == getterName && method.parameterCount == 0
            }?.invoke(event)
                ?: event.javaClass.declaredFields.firstOrNull { field ->
                    field.name == fieldName
                }?.apply { isAccessible = true }?.get(event)
        }.getOrNull()
    }

    fun findFakeTransitionContent(fakeContentView: ViewGroup): View? {
        return runCatching {
            fakeContentView.javaClass.methods.firstOrNull {
                it.name == "getFakeExpandedView" && it.parameterTypes.isEmpty()
            }?.invoke(fakeContentView) as? View
        }.getOrNull()
    }

    fun applyFakeTransitionElements(
        fakeContentView: ViewGroup,
        fakeExpandedView: View
    ): Boolean {
        val dataOwner = fakeContentView.javaClass.getMethod("getRealView").invoke(fakeContentView)
        if (!IslandProbeUtils.isMediaIsland(IslandProbeUtils.getCurrentIslandData(dataOwner))) {
            return false
        }
        val api = nativeApi ?: return false
        val binder = findBinderForContentOwner(dataOwner as? View, api)
        return applyFakeMediaElements(fakeExpandedView, binder, api)
    }

    private fun findBinderForContentOwner(owner: View?, api: NativeApi): Any? {
        owner ?: return null
        return synchronized(activeBinders) { activeBinders.toList() }.firstOrNull { binder ->
            api.getHolders(binder).any { holder ->
                api.findExpandedBackgroundTarget(api.getPlayer(holder))?.owner === owner
            }
        }
    }

    private fun applyContentViewTheme(contentView: View) {
        val isFakeView = contentView.javaClass.name == FAKE_CONTENT_VIEW_CLASS
        val dataOwner = if (isFakeView) {
            contentView.javaClass.methods.firstOrNull {
                it.name == "getRealView" && it.parameterTypes.isEmpty()
            }?.invoke(contentView)
        } else {
            contentView
        }
        if (!IslandProbeUtils.isMediaIsland(IslandProbeUtils.getCurrentIslandData(dataOwner))) return

        val api = nativeApi ?: return
        val target = api.findContentBackgroundTarget(contentView) ?: return
        if (IslandExpandedMediaBackgroundController.isActive()) {
            return
        }
        if (!shouldUseLightTheme(contentView.context)) {
            restoreTrackedTheme(contentView, api)
            return
        }

        applyLightExpandedBackground(api, target)
    }

    private fun applyCardTheme(binder: Any) {
        val api = nativeApi ?: return
        if (!shouldUseLightTheme(binder)) {
            restoreCardTheme(binder)
            return
        }

        val lightContext = api.getContext(binder).withNightMode(Configuration.UI_MODE_NIGHT_NO)
        val colors = CardColors.from(lightContext)
        api.getHolders(binder).forEach { holder ->
            val player = api.getPlayer(holder)
            if (!applyLightExpandedBackground(api, player)) {
                player.post {
                    if (activeBinders.contains(binder) && shouldUseLightTheme(binder)) {
                        runCatching {
                            applyLightExpandedBackground(api, player)
                        }.onFailure {
                            HookLogger.e(TAG, "应用延后的实时通知背景失败", it)
                        }
                    }
                }
            }
            applyLightForeground(api, holder, colors)
        }
    }

    private fun applyAppearance(
        binder: Any,
        allowCoverColor: Boolean,
        mediaData: Any? = null
    ) {
        val api = nativeApi ?: return
        if (IslandExpandedMediaBackgroundController.isActive()) {
            if (mediaData != null) {
                IslandExpandedMediaBackgroundController.bind(binder, mediaData, api)
            } else {
                IslandExpandedMediaBackgroundController.attach(binder, api)
            }
            applyMode(binder, allowCoverColor)
        } else {
            IslandExpandedMediaBackgroundController.restore(binder)
            applyMode(binder, allowCoverColor)
            applyCardTheme(binder)
        }
    }

    private fun applyLightForeground(api: NativeApi, holder: Any, colors: CardColors) {
        val seekBar = api.getSeekBar(holder)
        val state = seekBarThemeStates.getOrPut(seekBar) {
            SeekBarThemeState(
                originalColorFilter = api.getSeekBarShaderColorFilter(seekBar),
                originalHeadGlowAlpha = api.getSeekBarHeadGlowAlpha(seekBar)
            )
        }
        state.suppressHeadGlow = true
        api.applyLightForeground(holder, colors)
    }

    private fun applyLightExpandedBackground(
        api: NativeApi,
        player: View
    ): Boolean {
        val target = api.findExpandedBackgroundTarget(player) ?: return false
        applyLightExpandedBackground(api, target)
        return true
    }

    private fun applyLightExpandedBackground(
        api: NativeApi,
        target: IslandExpandedBackgroundTarget
    ) {
        val state = themeStates.getOrPut(target.owner) {
            val miniBar = api.getMiniBar(target)
            ViewThemeState(
                target = target,
                miniBar = miniBar,
                originalMiniBarTint = miniBar?.backgroundTintList
            )
        }
        val lightContext = target.expandedView.context.withNightMode(Configuration.UI_MODE_NIGHT_NO)
        api.applyLiveUpdateBackground(target, lightContext)
        state.miniBar?.backgroundTintList = ColorStateList.valueOf(
            Color.argb(0x99, 0, 0, 0)
        )
    }

    private fun shouldUseLightTheme(binder: Any): Boolean {
        val api = nativeApi ?: return false
        return shouldUseLightTheme(api.getContext(binder))
    }

    private fun shouldUseLightTheme(context: Context): Boolean {
        return when (currentCardTheme()) {
            RootConstants.MEDIA_CARD_THEME_ALWAYS_LIGHT -> true
            RootConstants.MEDIA_CARD_THEME_ALWAYS_DARK -> false
            else -> !context.resources.configuration.isNightMode
        }
    }

    private fun restoreTrackedTheme(view: View, api: NativeApi) {
        themeStates.remove(view)?.let { state ->
            state.miniBar?.backgroundTintList = state.originalMiniBarTint
            api.restoreNativeExpandedBackground(state.target)
        }
    }

    private fun restoreCardTheme(binder: Any) {
        val api = nativeApi ?: return
        api.getHolders(binder).forEach { holder ->
            val player = api.getPlayer(holder)
            val seekBar = api.getSeekBar(holder)
            api.findExpandedBackgroundTarget(player)?.let { target ->
                restoreTrackedTheme(target.owner, api)
            }
            seekBarThemeStates.remove(seekBar)?.let { state ->
                api.setSeekBarShaderColorFilter(seekBar, state.originalColorFilter)
                api.setSeekBarHeadGlowAlpha(seekBar, state.originalHeadGlowAlpha)
            }
            restoringNativeForeground.set(true)
            try {
                api.applyNativeForeground(binder, holder)
            } finally {
                restoringNativeForeground.remove()
            }
        }
    }

    private fun applyMediaElements(binder: Any) {
        val api = nativeApi ?: return
        val coverStyle = currentCoverStyle()
        // Hidden cover is now expressed exclusively by the shared ConstraintSet
        // (the same lifecycle XiaomiHelper uses).  Applying the legacy View
        // mutation afterwards derives margins from the real holder's transient
        // height and makes RealView diverge from the dummy/FakeView.
        val directCoverStyle = if (
            coverStyle == RootConstants.ISLAND_EXPANDED_MEDIA_COVER_STYLE_HIDDEN
        ) {
            RootConstants.ISLAND_EXPANDED_MEDIA_COVER_STYLE_DEFAULT
        } else {
            coverStyle
        }
        val hideCoverSource = hideCoverSource()
        val playbackActive = api.isPlaying(binder)
        api.getHolders(binder).forEach { holder ->
            IslandExpandedMediaElementController.apply(
                elements = api.getMediaElements(holder),
                coverStyle = directCoverStyle,
                hideCoverSource = hideCoverSource,
                // These visibility states live in ConstraintSet now, just like
                // XiaomiHelper.  Do not race the initial dummy-player bind by
                // setting the concrete View again here.
                hideDeviceSwitch = false,
                hideCustomActions = false,
                hideTime = false,
                keepAction4Slot = false,
                playbackActive = playbackActive
            )
            applySeekBarTrackOffset(api, holder)
            syncMusicWave(binder, holder, api)
        }
    }

    private fun syncMusicWave(binder: Any, holder: Any, api: NativeApi) {
        val player = api.getPlayer(holder) as? ViewGroup ?: return
        IslandExpandedMediaMusicWaveController.remove(player)
    }

    private fun restoreMediaElements(binder: Any) {
        val api = nativeApi ?: return
        api.getHolders(binder).forEach { holder ->
            IslandExpandedMediaElementController.restore(api.getMediaElements(holder))
            restoreSeekBarTrackOffset(api, holder)
            (api.getPlayer(holder) as? ViewGroup)?.let {
                IslandExpandedMediaMusicWaveController.remove(it)
            }
        }
    }

    private fun applySeekBarTrackOffset(api: NativeApi, holder: Any) {
        restoreSeekBarTrackOffset(api, holder)
    }

    private fun restoreSeekBarTrackOffset(api: NativeApi, holder: Any) {
        val seekBar = api.getSeekBar(holder)
        val state = seekBarTrackStates.remove(seekBar) ?: return
        api.setSeekBarTrackOffset(seekBar, state.paddingOffset, state.trackPositionX)
    }

    private fun applyFakeMediaElements(
        fakeExpandedView: View,
        binder: Any?,
        api: NativeApi
    ): Boolean {
        val coverStyle = currentCoverStyle()
        val hideCoverSource = hideCoverSource()
        val hideDeviceSwitch = hideDeviceSwitch()
        val hideCustomActions = hideCustomActions()
        val hideTime = hideTime()
        val keepAction4Slot = false
        if (
            coverStyle == RootConstants.ISLAND_EXPANDED_MEDIA_COVER_STYLE_DEFAULT &&
            !hideCoverSource &&
            !hideDeviceSwitch &&
            !hideCustomActions &&
            !hideTime
        ) {
            return true
        }
        val activeBinder = binder
            ?: synchronized(activeBinders) { activeBinders.firstOrNull() }
            ?: return false
        val referenceElements = api.getHolders(activeBinder).firstNotNullOfOrNull { holder ->
            runCatching { api.getMediaElements(holder) }.getOrNull()
        } ?: return false
        IslandExpandedMediaElementController.applyToFakeView(
            fakeExpandedView = fakeExpandedView,
            referenceElements = referenceElements,
            coverStyle = coverStyle,
            hideCoverSource = hideCoverSource,
            hideDeviceSwitch = hideDeviceSwitch,
            hideCustomActions = hideCustomActions,
            hideTime = hideTime,
            keepAction4Slot = keepAction4Slot
        )
        return true
    }

    private fun applyMode(binder: Any, allowCoverColor: Boolean) {
        val api = nativeApi ?: return
        val views = api.getMusicBgViews(binder)
        if (views.isEmpty()) return

        if (IslandExpandedMediaBackgroundController.isActive()) {
            removeCustomFlow(binder)
            binderStates[binder]?.request?.incrementAndGet()
            views.forEach { view ->
                if (
                    IslandExpandedMediaBackgroundController
                        .hasAppliedBackgroundForNativeFlow(view)
                ) {
                    hideAmbientFlow(view, api)
                } else {
                    restoreViewAlpha(view)
                }
            }
            return
        }

        when (currentMode()) {
            RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_DISABLED -> {
                removeCustomFlow(binder)
                binderStates[binder]?.request?.incrementAndGet()
                views.forEach { view -> hideAmbientFlow(view, api) }
            }

            RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_COVER_COLOR -> {
                removeCustomFlow(binder)
                views.forEach(::restoreViewAlpha)
                if (allowCoverColor) scheduleCoverColors(binder, views.first(), api)
            }

            RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_CUSTOM_FULL -> {
                views.forEach { view -> hideAmbientFlow(view, api) }
                val state = binderStates.getOrPut(binder) { BinderState() }
                val customViews = syncCustomFlowViews(state, views)
                if (customViews.isEmpty()) return
                customViews.forEach { configureCustomFlowView(binder, state, it, api) }
                if (allowCoverColor) scheduleCustomFlowColors(binder, state, api)
            }

            else -> {
                removeCustomFlow(binder)
                binderStates[binder]?.request?.incrementAndGet()
                views.forEach(::restoreViewAlpha)
            }
        }
    }

    private fun syncCustomFlowViews(
        state: BinderState,
        anchors: List<View>
    ): List<MediaFlowBackgroundView> {
        val currentAnchors = anchors.toSet()
        state.customViews.keys.filter { it !in currentAnchors }.forEach { staleAnchor ->
            state.customViews.remove(staleAnchor)?.let { staleView ->
                (staleView.parent as? ViewGroup)?.removeView(staleView)
            }
        }
        return anchors.mapNotNull { anchor -> ensureCustomFlowView(state, anchor) }
    }

    private fun ensureCustomFlowView(
        state: BinderState,
        anchor: View
    ): MediaFlowBackgroundView? {
        state.customViews[anchor]?.takeIf { it.parent === anchor.parent }?.let { return it }
        state.customViews.remove(anchor)?.let { staleView ->
            (staleView.parent as? ViewGroup)?.removeView(staleView)
        }
        val parent = anchor.parent as? ViewGroup ?: return null
        val ownedViews = state.customViews.values.toSet()
        for (index in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(index)
            if (child.tag == CUSTOM_FLOW_VIEW_TAG && child !in ownedViews) {
                parent.removeViewAt(index)
            }
        }
        val layoutParams = MediaFlowOverlayLayout.copyForOverlay(anchor.layoutParams) ?: return null
        val view = MediaFlowBackgroundView(anchor.context, state.customTimeline).apply {
            tag = CUSTOM_FLOW_VIEW_TAG
            outlineProvider = anchor.outlineProvider
            clipToOutline = anchor.clipToOutline
        }
        val index = (parent.indexOfChild(anchor) + 1).coerceAtMost(parent.childCount)
        parent.addView(view, index, layoutParams)
        state.customViews[anchor] = view
        return view
    }

    private fun configureCustomFlowView(
        binder: Any,
        state: BinderState,
        view: MediaFlowBackgroundView,
        api: NativeApi
    ) {
        view.visibility = if (state.customArtwork != null) View.VISIBLE else View.INVISIBLE
        view.update(
            artwork = state.customArtwork,
            tone = if (shouldUseLightTheme(binder)) MediaFlowTone.LIGHT else MediaFlowTone.DARK,
            playing = api.isPlaying(binder) && state.customArtwork != null
        )
    }

    private fun scheduleCustomFlowColors(
        binder: Any,
        state: BinderState,
        api: NativeApi
    ) {
        val drawable = api.getArtwork(binder) ?: return
        val token =
            "${System.identityHashCode(drawable)}:${drawable.constantState?.hashCode() ?: 0}"
        if (state.customColorToken == token && state.customArtwork != null) {
            state.customViews.values.toList().forEach {
                configureCustomFlowView(binder, state, it, api)
            }
            return
        }
        val bitmap = MediaArtworkSampler.sample(drawable) ?: return
        state.customColorToken = token
        state.customArtwork = null
        val request = state.request.incrementAndGet()
        runCatching {
            colorExecutor.execute {
                if (binderStates[binder] !== state || state.request.get() != request) {
                    bitmap.recycle()
                    return@execute
                }
                val artwork = runCatching { MediaFlowArtwork.prepare(bitmap) }
                    .onFailure { HookLogger.e(TAG, "提取展开态媒体柔光颜色失败", it) }
                    .getOrNull()
                bitmap.recycle()
                Handler(Looper.getMainLooper()).post {
                    if (binderStates[binder] !== state || state.request.get() != request) return@post
                    if (!isCustomMode(currentMode()) || artwork == null) {
                        if (artwork == null) state.customColorToken = null
                        return@post
                    }
                    state.customArtwork = artwork
                    state.customViews.values.toList().forEach {
                        configureCustomFlowView(binder, state, it, api)
                    }
                }
            }
        }.onFailure { error ->
            bitmap.recycle()
            HookLogger.e(TAG, "调度展开态媒体柔光取色失败", error)
        }
    }

    private fun removeCustomFlow(binder: Any) {
        val state = binderStates[binder] ?: return
        state.customViews.values.toList().forEach { view ->
            view.update(
                tone = MediaFlowTone.DARK,
                playing = false
            )
            (view.parent as? ViewGroup)?.removeView(view)
        }
        state.customViews.clear()
        state.customColorToken = null
        state.customArtwork = null
    }

    private fun scheduleCoverColors(binder: Any, primaryView: View, api: NativeApi) {
        val drawable = api.getArtwork(binder) ?: return
        val token =
            "${System.identityHashCode(drawable)}:${drawable.constantState?.hashCode() ?: 0}"
        val state = binderStates.getOrPut(binder) { BinderState() }
        if (state.colorToken == token) {
            state.palette?.let { palette ->
                api.setGradientColor(primaryView, palette.mainColor, palette.colors)
            }
            return
        }
        val bitmap = MediaArtworkSampler.sample(drawable) ?: return
        state.colorToken = token
        state.palette = null
        val request = state.request.incrementAndGet()

        runCatching {
            colorExecutor.execute {
                if (binderStates[binder] !== state || state.request.get() != request) {
                    bitmap.recycle()
                    return@execute
                }
                val palette = runCatching {
                    MediaAmbientFlowPaletteExtractor.extractCoverMainColor(bitmap)
                        ?.let(api::createPalette)
                }
                    .onFailure { HookLogger.e(TAG, "提取展开态媒体颜色失败", it) }
                    .getOrNull()
                bitmap.recycle()
                primaryView.post {
                    val current = binderStates[binder]
                    if (current !== state || current.request.get() != request) return@post
                    if (palette == null) {
                        current.colorToken = null
                        return@post
                    }
                    if (currentMode() !=
                        RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_COVER_COLOR
                    ) return@post
                    val currentPrimary = api.getMusicBgViews(binder).firstOrNull() ?: return@post
                    current.palette = palette
                    api.setGradientColor(currentPrimary, palette.mainColor, palette.colors)
                }
            }
        }.onFailure { error ->
            bitmap.recycle()
            HookLogger.e(TAG, "调度展开态媒体取色任务失败", error)
        }
    }

    private fun cleanupBinder(binder: Any) {
        activeBinders.remove(binder)
        IslandExpandedMediaBackgroundController.restore(binder)
        restoreCardTheme(binder)
        restoreMediaElements(binder)
        removeCustomFlow(binder)
        binderStates.remove(binder)?.request?.incrementAndGet()
        nativeApi?.getMusicBgViews(binder)?.forEach(::restoreViewAlpha)
        nativeApi?.removeHolderBackgrounds(binder)
    }

    private fun restoreViewAlpha(view: View) {
        val original = view.getTag(ORIGINAL_ALPHA_TAG_KEY) as? Float ?: return
        view.setTag(ORIGINAL_ALPHA_TAG_KEY, null)
        if (view.alpha == 0f) view.alpha = original
    }

    private fun hideAmbientFlow(view: View, api: NativeApi) {
        val alreadyHidden = view.getTag(ORIGINAL_ALPHA_TAG_KEY) != null && view.alpha == 0f
        if (alreadyHidden) return
        if (view.getTag(ORIGINAL_ALPHA_TAG_KEY) == null) {
            view.setTag(ORIGINAL_ALPHA_TAG_KEY, view.alpha)
        }
        view.alpha = 0f
        api.pause(view)
    }

    private fun isExpandedIslandView(view: View): Boolean {
        var current: View? = view
        repeat(8) {
            val parent = current?.parent ?: return false
            if (parent.javaClass.name.contains(".notification.mediaisland.")) return true
            current = parent as? View ?: return false
        }
        return false
    }

    private fun currentMode(): Int {
        if (!MediaCardRuntimeConfig.current.enabled) {
            return RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_DEFAULT
        }
        return MediaCardRuntimeConfig.current.islandExpanded.ambientFlowMode
    }

    private fun isCustomMode(mode: Int): Boolean =
        mode == RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_CUSTOM_FULL

    private fun currentCardTheme(): Int {
        if (!MediaCardRuntimeConfig.current.enabled) {
            return RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_CARD_THEME
        }
        return MediaCardRuntimeConfig.current.islandExpanded.cardTheme
    }

    private fun currentCoverStyle(): Int {
        if (!MediaCardRuntimeConfig.current.enabled) {
            return RootConstants.ISLAND_EXPANDED_MEDIA_COVER_STYLE_DEFAULT
        }
        return MediaCardRuntimeConfig.current.islandExpanded.coverStyle
    }

    private fun currentLayoutStyle(): Int {
        return RootConstants.NOTIFICATION_MEDIA_LAYOUT_STYLE_SYSTEM
    }

    private fun hideCoverSource(): Boolean {
        if (!MediaCardRuntimeConfig.current.enabled) return false
        return MediaCardRuntimeConfig.current.islandExpanded.hideCoverSource
    }

    private fun hideDeviceSwitch(): Boolean {
        if (!MediaCardRuntimeConfig.current.enabled) return false
        return MediaCardRuntimeConfig.current.islandExpanded.hideDeviceSwitch
    }

    private fun hideCustomActions(): Boolean {
        if (!MediaCardRuntimeConfig.current.enabled) return false
        return MediaCardRuntimeConfig.current.islandExpanded.hideCustomActions
    }

    private fun hideTime(): Boolean {
        if (!MediaCardRuntimeConfig.current.enabled) return false
        return MediaCardRuntimeConfig.current.islandExpanded.hideTime
    }

    private val Configuration.isNightMode: Boolean
        get() = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun Context.withNightMode(nightMode: Int): Context {
        val configuration = Configuration(resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
        }
        return createConfigurationContext(configuration)
    }

    private fun resolveApi(classLoader: ClassLoader?): NativeApi? {
        nativeApi?.let { return it }
        classLoader ?: return null
        return runCatching { NativeApi.create(classLoader) }
            .onSuccess { nativeApi = it }
            .onFailure { HookLogger.w(TAG, "展开态媒体原生接口不可用: reason=${it.message}") }
            .getOrNull()
    }

    private data class BinderState(
        var colorToken: String? = null,
        var palette: MediaAmbientFlowPalette? = null,
        var customColorToken: String? = null,
        var customArtwork: MediaFlowArtwork? = null,
        val customTimeline: MediaFlowTimeline = MediaFlowTimeline(),
        val customViews: MutableMap<View, MediaFlowBackgroundView> = mutableMapOf(),
        val request: AtomicInteger = AtomicInteger()
    )

    private data class ViewThemeState(
        val target: IslandExpandedBackgroundTarget,
        val miniBar: View?,
        val originalMiniBarTint: ColorStateList?
    )

    private data class SeekBarThemeState(
        val originalColorFilter: ColorFilter?,
        val originalHeadGlowAlpha: Float,
        var suppressHeadGlow: Boolean = false
    )

    private data class SeekBarTrackState(
        val paddingOffset: Int,
        val trackPositionX: Float,
        var applied: Boolean = false
    )

    private data class CardColors(
        val primaryText: Int,
        val secondaryText: Int,
        val durationText: Int,
        val action: Int,
        val seekBarForeground: Int,
        val seekBarBackground: Int
    ) {
        companion object {
            fun from(context: Context): CardColors {
                fun color(name: String): Int {
                    val id = context.resources.getIdentifier(name, "color", context.packageName)
                    require(id != 0) { "Missing color resource: $name" }
                    return context.getColor(id)
                }
                return CardColors(
                    primaryText = color("media_primary_text"),
                    secondaryText = color("media_secondary_text"),
                    durationText = color("media_duration_time_font_color"),
                    action = color("notification_media_action_button_light_color"),
                    seekBarForeground = Color.BLACK,
                    seekBarBackground = color("media_seekbar_background_color")
                )
            }
        }
    }

    private class NativeApi private constructor(
        val hookMethods: List<Method>,
        private val holderField: Field,
        private val dummyHolderField: Field,
        private val artworkField: Field?,
        private val mediaBgViewField: Field,
        private val playerField: Field,
        private val contextField: Field,
        private val titleTextField: Field,
        private val artistTextField: Field,
        private val elapsedTimeViewField: Field,
        private val totalTimeViewField: Field,
        private val seamlessIconField: Field,
        private val albumViewField: Field,
        private val albumImageField: Field,
        private val appIconField: Field,
        private val seamlessField: Field,
        private val mediaDataField: Field,
        private val mediaDataIsPlayingField: Field,
        private val mediaDataArtworkField: Field?,
        private val mediaDataPackageNameField: Field,
        private val isArtworkUpdateField: Field?,
        private val seekBarField: Field,
        private val seekBarPaintField: Field,
        private val seekBarRuntimeShaderField: Field,
        private val seekBarPaddingOffsetField: Field?,
        private val seekBarTrackPositionField: Field?,
        private val seekBarHeadGlowAlphaField: Field,
        private val headAlphaListenerSeekBarField: Field,
        private val getActionListMethod: Method,
        private val updateForegroundColorsMethod: Method,
        private val setSeekBarForegroundMethod: Method,
        private val setSeekBarBackgroundMethod: Method,
        private val pauseMethod: Method,
        private val setGradientColorMethod: Method,
        private val getPaletteColorMethod: Method
    ) : IslandExpandedMediaBackgroundApi {
        private val expandedBackgroundMethods = Collections.synchronizedMap(
            WeakHashMap<ClassLoader, ExpandedBackgroundMethods>()
        )
        private val holderBackgrounds = IslandMediaBackgroundHostAdapter(
            holderField = holderField,
            dummyHolderField = dummyHolderField,
            playerField = playerField,
            titleTextField = titleTextField,
            mediaBgViewField = mediaBgViewField
        )

        fun getHolders(binder: Any): List<Any> {
            return listOfNotNull(holderField.get(binder), dummyHolderField.get(binder)).distinct()
        }

        fun getMusicBgViews(binder: Any): List<View> {
            return getHolders(binder).mapNotNull { holder -> getMusicBgView(holder) }
                .distinct()
        }

        fun getMusicBgView(holder: Any): View = mediaBgViewField.get(holder) as View

        fun getPlayer(holder: Any): View = playerField.get(holder) as View

        fun getMusicWaveColor(holder: Any): Int {
            return (titleTextField.get(holder) as TextView).currentTextColor
        }

        fun getMediaElements(holder: Any): IslandExpandedMediaElements {
            val player = getPlayer(holder)

            @Suppress("UNCHECKED_CAST")
            val actions = getActionListMethod.invoke(holder) as List<View>
            val actionsId = player.resources.getIdentifier(
                "actions",
                "id",
                player.context.packageName
            )
            require(actionsId != 0) { "Missing SystemUI id resource: actions" }
            return IslandExpandedMediaElements(
                albumView = albumViewField.get(holder) as View,
                albumImage = albumImageField.get(holder) as ImageView,
                coverSource = appIconField.get(holder) as ImageView,
                deviceSwitch = seamlessField.get(holder) as View,
                title = titleTextField.get(holder) as View,
                artist = artistTextField.get(holder) as View,
                actionsAnchor = requireNotNull(player.findViewById(actionsId)),
                firstAction = actions.first(),
                actionButtons = actions,
                elapsedTime = elapsedTimeViewField.get(holder) as View,
                totalTime = totalTimeViewField.get(holder) as View,
                player = player
            )
        }

        fun isPlaying(binder: Any): Boolean {
            val mediaData = mediaDataField.get(binder) ?: return false
            return mediaDataIsPlayingField.get(mediaData) == true
        }

        fun getSeekBar(holder: Any): View = seekBarField.get(holder) as View

        fun captureSeekBarTrackState(seekBar: View): SeekBarTrackState? {
            val paddingField = seekBarPaddingOffsetField ?: return null
            val trackPositionField = seekBarTrackPositionField ?: return null
            val trackPosition = trackPositionField.get(seekBar) as? FloatArray ?: return null
            return SeekBarTrackState(
                paddingOffset = paddingField.getInt(seekBar),
                trackPositionX = trackPosition.firstOrNull() ?: return null
            )
        }

        fun setSeekBarTrackOffset(seekBar: View, paddingOffset: Int, trackPositionX: Float) {
            val paddingField = seekBarPaddingOffsetField ?: return
            val trackPositionField = seekBarTrackPositionField ?: return
            paddingField.setInt(seekBar, paddingOffset)
            val trackPosition = trackPositionField.get(seekBar) as? FloatArray ?: return
            if (trackPosition.isEmpty()) return
            trackPosition[0] = trackPositionX
            runCatching {
                val shader = seekBarRuntimeShaderField.get(seekBar) ?: return@runCatching
                val setFloatUniform = shader.javaClass.methods.find { method ->
                    method.name == "setFloatUniform" &&
                            method.parameterCount == 2 &&
                            method.parameterTypes[0] == String::class.java &&
                            method.parameterTypes[1] == FloatArray::class.java
                } ?: return@runCatching
                setFloatUniform.invoke(shader, "uTrackPosition", trackPosition)
            }
            seekBar.requestLayout()
            seekBar.invalidate()
        }

        fun getHeadAlphaListenerSeekBar(listener: Any): View {
            return headAlphaListenerSeekBarField.get(listener) as View
        }

        override fun getContext(binder: Any): Context = contextField.get(binder) as Context

        override fun getMediaPackageName(mediaData: Any): String? {
            return mediaDataPackageNameField.get(mediaData) as? String
        }

        override fun getMediaArtwork(mediaData: Any): Icon? {
            return runCatching {
                mediaDataArtworkField?.get(mediaData) as? Icon
            }.getOrNull()
        }

        override fun isArtworkUpdated(binder: Any): Boolean? {
            val field = isArtworkUpdateField ?: return null
            return runCatching { field.get(binder) as? Boolean }.getOrNull()
        }

        override fun supportsCustomBackground(): Boolean {
            // Match XiaomiHelper: pull-down tracking is an optional visual companion,
            // not a prerequisite for binding the real/dummy custom backgrounds.
            return true
        }

        override fun getBackgroundRetryView(binder: Any): View? {
            val holder = holderField.get(binder) ?: return null
            return playerField.get(holder) as? View
        }

        override fun getBackgroundHosts(binder: Any): List<IslandExpandedMediaBackgroundHost> {
            val holders = getHolders(binder)
            if (holders.size != 2) return emptyList()
            val dedicatedHosts = holderBackgrounds.getOrCreateHosts(binder)
                .toMap()
            if (dedicatedHosts.size != holders.size ||
                holders.any { holder -> holder !in dedicatedHosts }
            ) {
                return emptyList()
            }
            val hosts = holders.mapNotNull { holder ->
                val dedicatedHost = dedicatedHosts[holder] ?: return@mapNotNull null
                val player = dedicatedHost.player
                // The real and dummy holders are the source of truth here.  Do
                // not borrow the outer expanded/fake viewport: their bounds
                // change during transitions and make the two card backgrounds
                // crop differently.  XiaomiHelper likewise renders against each
                // holder's media background.
                val target = IslandExpandedBackgroundTarget(
                    owner = player,
                    expandedView = player,
                    measurementView = player,
                    customBackgroundView = dedicatedHost.customBackground,
                    nativeBackgroundViews = emptyList(),
                    nativeFlowViews = emptyList()
                )
                IslandExpandedMediaBackgroundHost(target, holder)
            }
            return hosts.takeIf { it.size == holders.size } ?: emptyList()
        }

        fun removeHolderBackgrounds(binder: Any) {
            holderBackgrounds.detach(binder)
        }

        fun applyDummyMiniBarTracking(
            dummyHolder: Any,
            action: String,
            pullDownOffset: Float
        ) {
            if (!IslandExpandedMediaBackgroundController.isActive()) return
            val mediaBackground = holderBackgrounds.findHost(dummyHolder)?.customBackground
                ?: return
            when (action) {
                "pull_down_type_start" -> mediaBackground.pivotY = 0f

                "pull_down_type_update" -> {
                    val height = mediaBackground.height
                    if (height > 0) {
                        mediaBackground.scaleY = (height + pullDownOffset) / height.toFloat()
                    }
                }
            }
        }

        fun resetDummyBackgroundTransform(binder: Any) {
            val dummyHolder = dummyHolderField.get(binder) ?: return
            val mediaBackground = holderBackgrounds.findHost(dummyHolder)?.customBackground
                ?: return
            mediaBackground.scaleY = 1f
        }

        fun applyNativeForeground(binder: Any, holder: Any) {
            updateForegroundColorsMethod.invoke(binder, holder)
        }

        fun applyLightForeground(holder: Any, colors: CardColors) {
            (titleTextField.get(holder) as TextView).setTextColor(colors.primaryText)
            (artistTextField.get(holder) as TextView).setTextColor(colors.secondaryText)
            (elapsedTimeViewField.get(holder) as TextView).setTextColor(colors.durationText)
            (totalTimeViewField.get(holder) as TextView).setTextColor(colors.durationText)
            val tint = ColorStateList.valueOf(colors.action)
            (seamlessIconField.get(holder) as ImageView).imageTintList =
                ColorStateList.valueOf(colors.seekBarForeground)
            @Suppress("UNCHECKED_CAST")
            (getActionListMethod.invoke(holder) as List<Any>).forEach { action ->
                (action as ImageView).apply {
                    imageTintBlendMode = BlendMode.SRC_IN
                    imageTintList = tint
                }
            }
            val seekBar = getSeekBar(holder)
            setSeekBarForegroundMethod.invoke(seekBar, colors.seekBarForeground)
            setSeekBarBackgroundMethod.invoke(seekBar, colors.seekBarBackground)
            setSeekBarShaderColorFilter(
                seekBar,
                BlendModeColorFilter(colors.seekBarForeground, BlendMode.SRC_IN)
            )
            setSeekBarHeadGlowAlpha(seekBar, 0f)
        }

        override fun applyCustomForeground(
            holder: Any,
            colors: NotificationMediaColorConfig
        ) {
            val seekBar = getSeekBar(holder)
            val state = seekBarThemeStates.getOrPut(seekBar) {
                SeekBarThemeState(
                    originalColorFilter = getSeekBarShaderColorFilter(seekBar),
                    originalHeadGlowAlpha = getSeekBarHeadGlowAlpha(seekBar)
                )
            }
            state.suppressHeadGlow = false
            (titleTextField.get(holder) as TextView).setTextColor(colors.textPrimary)
            (artistTextField.get(holder) as TextView).setTextColor(colors.textSecondary)
            (elapsedTimeViewField.get(holder) as TextView).setTextColor(colors.textSecondary)
            (totalTimeViewField.get(holder) as TextView).setTextColor(colors.textSecondary)
            val tint = ColorStateList.valueOf(colors.textPrimary)
            (seamlessIconField.get(holder) as ImageView).imageTintList = tint
            @Suppress("UNCHECKED_CAST")
            (getActionListMethod.invoke(holder) as List<Any>).forEach { action ->
                (action as ImageView).apply {
                    imageTintBlendMode = BlendMode.SRC_IN
                    imageTintList = tint
                }
            }
            setSeekBarForegroundMethod.invoke(seekBar, colors.textPrimary)
            setSeekBarBackgroundMethod.invoke(
                seekBar,
                colors.textPrimary and 0x00ffffff or (0x33 shl 24)
            )
            setSeekBarShaderColorFilter(
                seekBar,
                BlendModeColorFilter(colors.textPrimary, BlendMode.SRC_IN)
            )
            setSeekBarHeadGlowAlpha(seekBar, state.originalHeadGlowAlpha)
        }

        fun getSeekBarShaderColorFilter(seekBar: View): ColorFilter? {
            return (seekBarPaintField.get(seekBar) as Paint).colorFilter
        }

        fun setSeekBarShaderColorFilter(seekBar: View, colorFilter: ColorFilter?) {
            (seekBarPaintField.get(seekBar) as Paint).colorFilter = colorFilter
            seekBar.invalidate()
        }

        fun getSeekBarHeadGlowAlpha(seekBar: View): Float {
            return seekBarHeadGlowAlphaField.getFloat(seekBar)
        }

        fun setSeekBarHeadGlowAlpha(seekBar: View, alpha: Float) {
            seekBarHeadGlowAlphaField.setFloat(seekBar, alpha)
            (seekBarRuntimeShaderField.get(seekBar) as? RuntimeShader)?.setFloatUniform(
                "uHeadGlowAlpha",
                alpha
            )
            seekBar.invalidate()
        }

        fun findExpandedBackgroundTarget(player: View): IslandExpandedBackgroundTarget? {
            var current: View? = player
            var expandedView: View? = null
            repeat(16) {
                current = current?.parent as? View ?: return null
                if (current.javaClass.name == EXPANDED_VIEW_CLASS) expandedView = current
                if (expandedView != null && current.javaClass.isOrExtends(BASE_CONTENT_VIEW_CLASS)) {
                    val owner = current
                    val expanded = expandedView
                    fun dimension(name: String): Int {
                        return (owner.javaClass.methods.single {
                            it.name == name && it.parameterTypes.isEmpty()
                        }.invoke(owner) as Number).toInt()
                    }
                    return IslandExpandedBackgroundTarget(
                        owner = owner,
                        expandedView = expanded,
                        viewportWidth = dimension("getExpandedViewWidth"),
                        viewportHeight = dimension("getExpandedViewHeight")
                    )
                }
            }
            return null
        }

        fun findContentBackgroundTarget(contentView: View): IslandExpandedBackgroundTarget? {
            val isFakeView = contentView.javaClass.name == FAKE_CONTENT_VIEW_CLASS
            val getterName = if (isFakeView) "getFakeExpandedView" else "getExpandedView"
            val expandedView = contentView.javaClass.methods.firstOrNull {
                it.name == getterName && it.parameterTypes.isEmpty()
            }?.invoke(contentView) as? View ?: return null
            if (isFakeView) {
                val realView = contentView.javaClass.methods.single {
                    it.name == "getRealView" && it.parameterTypes.isEmpty()
                }.invoke(contentView) as View

                fun realDimension(name: String): Int {
                    return (realView.javaClass.methods.single {
                        it.name == name && it.parameterTypes.isEmpty()
                    }.invoke(realView) as Number).toInt()
                }

                val width = realDimension("getExpandedViewWidth")
                val height = realDimension("getExpandedViewHeight")
                return IslandExpandedBackgroundTarget(
                    owner = contentView,
                    expandedView = expandedView,
                    customBackgroundView = expandedView,
                    viewportWidth = width,
                    viewportHeight = height,
                    nativeBackgroundViews = listOf(expandedView)
                )
            }
            return IslandExpandedBackgroundTarget(contentView, expandedView)
        }

        fun getMiniBar(target: IslandExpandedBackgroundTarget): View? {
            return expandedBackgroundMethods(target).getMiniBar.invoke(target.owner) as? View
        }

        fun applyLiveUpdateBackground(
            target: IslandExpandedBackgroundTarget,
            lightContext: Context
        ) {
            val view = target.expandedView
            val methods = expandedBackgroundMethods(target)
            val blurOpened = methods.getBackgroundBlurOpened.invoke(null, view.context) as Boolean
            if (!blurOpened || view.parent == null) {
                methods.setMiViewBlurMode.invoke(null, view, 0)
                methods.clearMiBackgroundBlendColor.invoke(null, view)
                view.background = requireNotNull(
                    lightContext.getDrawable(methods.liveUpdateBackgroundDrawableId)
                )
                return
            }

            val blendColors = intArrayOf(
                lightContext.getColor(methods.liveUpdateBlendColor1Id),
                lightContext.resources.getInteger(methods.blurModeLinearLightId),
                lightContext.getColor(methods.liveUpdateBlendColor2Id),
                lightContext.resources.getInteger(methods.blurModeLabId),
                lightContext.getColor(methods.liveUpdateBlendColor3Id),
                lightContext.resources.getInteger(methods.blurModePureId)
            )
            methods.setMiViewBlurMode.invoke(null, view, 1)
            methods.clearMiBackgroundBlendColor.invoke(null, view)
            methods.setMiBackgroundBlendColors.invoke(null, view, blendColors, 0.0f, 2, null)
            view.background = null
        }

        fun restoreNativeExpandedBackground(target: IslandExpandedBackgroundTarget) {
            expandedBackgroundMethods(target).updateBackgroundBg.invoke(
                target.owner,
                target.expandedView,
                false
            )
        }

        override fun prepareCustomBackground(target: IslandExpandedBackgroundTarget) {
            if (target.customBackgroundView !== target.expandedView) {
                target.customBackgroundView.visibility = View.VISIBLE
                target.nativeFlowViews.forEach { view -> hideAmbientFlow(view, this) }
                return
            }
            val methods = expandedBackgroundMethods(target)
            target.nativeBackgroundViews.forEach { view ->
                methods.setMiViewBlurMode.invoke(null, view, 0)
                methods.clearMiBackgroundBlendColor.invoke(null, view)
                if (view !== target.customBackgroundView) view.background = null
            }
            target.nativeFlowViews.forEach { view -> hideAmbientFlow(view, this) }
        }

        override fun restoreNativeBackground(target: IslandExpandedBackgroundTarget) {
            target.nativeFlowViews.forEach(::restoreViewAlpha)
            if (target.customBackgroundView !== target.expandedView) {
                (target.customBackgroundView as? ImageView)?.setImageDrawable(null)
                    ?: run { target.customBackgroundView.background = null }
                target.customBackgroundView.visibility = View.INVISIBLE
                target.customBackgroundView.scaleY = 1f
                return
            }
            target.nativeBackgroundViews.forEach { view ->
                expandedBackgroundMethods(target).updateBackgroundBg.invoke(
                    target.owner,
                    view,
                    false
                )
            }
        }

        private fun expandedBackgroundMethods(
            target: IslandExpandedBackgroundTarget
        ): ExpandedBackgroundMethods {
            val ownerClass = target.owner.javaClass
            val classLoader = requireNotNull(ownerClass.classLoader) {
                "Expanded island view has no ClassLoader"
            }
            return synchronized(expandedBackgroundMethods) {
                expandedBackgroundMethods.getOrPut(classLoader) {
                    ExpandedBackgroundMethods.create(ownerClass, classLoader)
                }
            }
        }

        private fun Class<*>.isOrExtends(className: String): Boolean {
            var current: Class<*>? = this
            while (current != null) {
                if (current.name == className) return true
                current = current.superclass
            }
            return false
        }

        fun getArtwork(binder: Any): Drawable? {
            val field = artworkField ?: return null
            return runCatching { field.get(binder) as? Drawable }.getOrNull()
        }

        fun pause(view: View) {
            pauseMethod.invoke(view)
        }

        fun setGradientColor(view: View, mainColor: Int, colors: IntArray) {
            setGradientColorMethod.invoke(view, mainColor, colors)
        }

        fun createPalette(mainColor: Int): MediaAmbientFlowPalette {
            val colors = intArrayOf(
                getPaletteColor(mainColor, "primary", 12),
                getPaletteColor(mainColor, "primary", 10),
                getPaletteColor(mainColor, "tertiary", 12)
            )
            return MediaAmbientFlowPalette(mainColor, colors)
        }

        private fun getPaletteColor(mainColor: Int, role: String, tone: Int): Int {
            return getPaletteColorMethod.invoke(null, mainColor, role, tone) as Int
        }

        companion object {
            fun create(classLoader: ClassLoader): NativeApi {
                val binderClass = classLoader.loadClass(BINDER_CLASS)
                val holderClass = classLoader.loadClass(
                    "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewHolder"
                )
                val musicBgViewClass = classLoader.loadClass(MUSIC_BG_VIEW_CLASS)
                val mediaDataClass = classLoader.loadClass(
                    "com.android.systemui.media.controls.shared.model.MediaData"
                )
                val miPaletteClass = classLoader.loadClass("miuix.mipalette.MiPalette")
                miPaletteClass.declaredMethods.firstOrNull { method ->
                    method.name == "init" && method.parameterCount == 0
                }?.apply { isAccessible = true }?.invoke(null)
                val getPaletteColor = miPaletteClass.getDeclaredMethod(
                    "getPaletteColor",
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType
                ).apply { isAccessible = true }
                val attach = binderClass.declaredMethods.single {
                    it.name == "attach" && it.parameterCount == 2
                }.apply { isAccessible = true }
                val bind = binderClass.declaredMethods.single {
                    it.name == "bindMediaData" && it.parameterCount == 1
                }.apply { isAccessible = true }
                val detach = binderClass.declaredMethods.single {
                    it.name == "detach" && it.parameterCount == 0
                }.apply { isAccessible = true }
                val setAlbumImage = binderClass.declaredMethods.single {
                    it.name == "setAlbumImage" && it.parameterCount == 1
                }.apply { isAccessible = true }
                val setSeamless = binderClass.declaredMethods.single {
                    it.name == "setSeamless" && it.parameterCount == 2
                }.apply { isAccessible = true }
                val start = musicBgViewClass.getDeclaredMethod("start").apply {
                    isAccessible = true
                }
                val resume = musicBgViewClass.getDeclaredMethod("resume").apply {
                    isAccessible = true
                }
                val seekBarClass = holderClass.getDeclaredField("seekBar").type
                val headAlphaListenerClass = classLoader.loadClass(
                    SEEK_BAR_HEAD_ALPHA_LISTENER_CLASS
                )
                val headAlphaUpdate = headAlphaListenerClass.declaredMethods.single {
                    it.name == "onUpdate" && it.parameterCount == 2
                }.apply { isAccessible = true }
                return NativeApi(
                    hookMethods = listOf(
                        attach,
                        bind,
                        detach,
                        setAlbumImage,
                        setSeamless,
                        binderClass.declaredMethods.single {
                            it.name == "updateForegroundColors" && it.parameterCount == 1
                        }.apply { isAccessible = true },
                        start,
                        resume,
                        headAlphaUpdate
                    ),
                    holderField = binderClass.getDeclaredField("holder").apply {
                        isAccessible = true
                    },
                    dummyHolderField = binderClass.getDeclaredField("dummyHolder").apply {
                        isAccessible = true
                    },
                    artworkField = findOptionalField(
                        owner = binderClass,
                        candidateNames = listOf("artWorkDrawable", "artworkDrawable"),
                        expectedType = Drawable::class.java
                    ),
                    mediaBgViewField = holderClass.getDeclaredField("mediaBgView").apply {
                        isAccessible = true
                    },
                    playerField = holderClass.getDeclaredField("player").apply {
                        isAccessible = true
                    },
                    contextField = binderClass.getDeclaredField("context").apply {
                        isAccessible = true
                    },
                    titleTextField = holderClass.getDeclaredField("titleText").apply {
                        isAccessible = true
                    },
                    artistTextField = holderClass.getDeclaredField("artistText").apply {
                        isAccessible = true
                    },
                    elapsedTimeViewField = holderClass.getDeclaredField("elapsedTimeView").apply {
                        isAccessible = true
                    },
                    totalTimeViewField = holderClass.getDeclaredField("totalTimeView").apply {
                        isAccessible = true
                    },
                    seamlessIconField = holderClass.getDeclaredField("seamlessIcon").apply {
                        isAccessible = true
                    },
                    albumViewField = holderClass.getDeclaredField("albumView").apply {
                        isAccessible = true
                    },
                    albumImageField = holderClass.getDeclaredField("albumImageView").apply {
                        isAccessible = true
                    },
                    appIconField = holderClass.getDeclaredField("appIcon").apply {
                        isAccessible = true
                    },
                    seamlessField = holderClass.getDeclaredField("seamless").apply {
                        isAccessible = true
                    },
                    mediaDataField = binderClass.getDeclaredField("mediaData").apply {
                        isAccessible = true
                    },
                    mediaDataIsPlayingField = mediaDataClass.getDeclaredField("isPlaying").apply {
                        isAccessible = true
                    },
                    mediaDataArtworkField = findOptionalField(
                        owner = mediaDataClass,
                        candidateNames = listOf("artwork", "artWork"),
                        expectedType = Icon::class.java
                    ),
                    mediaDataPackageNameField = mediaDataClass.getDeclaredField("packageName")
                        .apply {
                            isAccessible = true
                        },
                    isArtworkUpdateField = runCatching {
                        binderClass.getDeclaredField("isArtWorkUpdate").apply {
                            isAccessible = true
                        }
                    }.getOrNull(),
                    seekBarField = holderClass.getDeclaredField("seekBar").apply {
                        isAccessible = true
                    },
                    seekBarPaintField = seekBarClass.getDeclaredField("mPaint").apply {
                        isAccessible = true
                    },
                    seekBarRuntimeShaderField = seekBarClass.getDeclaredField("runtimeShader")
                        .apply {
                            isAccessible = true
                        },
                    seekBarPaddingOffsetField = runCatching {
                        seekBarClass.getDeclaredField("mProgressPaddingOffset").apply {
                            isAccessible = true
                        }
                    }.getOrNull(),
                    seekBarTrackPositionField = runCatching {
                        seekBarClass.getDeclaredField("uTrackPosition").apply {
                            isAccessible = true
                        }
                    }.getOrNull(),
                    seekBarHeadGlowAlphaField = seekBarClass.getDeclaredField("uHeadGlowAlpha")
                        .apply {
                            isAccessible = true
                        },
                    headAlphaListenerSeekBarField = headAlphaListenerClass.getDeclaredField(
                        "this\$0"
                    ).apply { isAccessible = true },
                    getActionListMethod = holderClass.getDeclaredMethod("getActionList").apply {
                        isAccessible = true
                    },
                    updateForegroundColorsMethod = binderClass.declaredMethods.single {
                        it.name == "updateForegroundColors" && it.parameterCount == 1
                    }.apply { isAccessible = true },
                    setSeekBarForegroundMethod = seekBarClass.getDeclaredMethod(
                        "setForegroundPrimaryColor",
                        Int::class.javaPrimitiveType
                    ).apply { isAccessible = true },
                    setSeekBarBackgroundMethod = seekBarClass.getDeclaredMethod(
                        "setBackgroundPrimaryColor",
                        Int::class.javaPrimitiveType
                    ).apply { isAccessible = true },
                    pauseMethod = musicBgViewClass.getDeclaredMethod("pause").apply {
                        isAccessible = true
                    },
                    setGradientColorMethod = musicBgViewClass.getDeclaredMethod(
                        "setGradientColor",
                        Int::class.javaPrimitiveType,
                        IntArray::class.java
                    ).apply { isAccessible = true },
                    getPaletteColorMethod = getPaletteColor
                )
            }

            private fun findOptionalField(
                owner: Class<*>,
                candidateNames: List<String>,
                expectedType: Class<*>
            ): Field? {
                val hierarchy = generateSequence(owner as Class<*>?) { it.superclass }.toList()
                candidateNames.forEach { name ->
                    hierarchy.forEach { type ->
                        runCatching { type.getDeclaredField(name) }.getOrNull()?.let { field ->
                            if (expectedType.isAssignableFrom(field.type)) {
                                field.isAccessible = true
                                return field
                            }
                        }
                    }
                }
                return hierarchy
                    .flatMap { it.declaredFields.asList() }
                    .filter { expectedType.isAssignableFrom(it.type) }
                    .singleOrNull()
                    ?.apply { isAccessible = true }
            }
        }
    }

    private data class ExpandedBackgroundMethods(
        val updateBackgroundBg: Method,
        val getMiniBar: Method,
        val getBackgroundBlurOpened: Method,
        val setMiViewBlurMode: Method,
        val clearMiBackgroundBlendColor: Method,
        val setMiBackgroundBlendColors: Method,
        val liveUpdateBlendColor1Id: Int,
        val liveUpdateBlendColor2Id: Int,
        val liveUpdateBlendColor3Id: Int,
        val blurModeLinearLightId: Int,
        val blurModeLabId: Int,
        val blurModePureId: Int,
        val liveUpdateBackgroundDrawableId: Int
    ) {
        companion object {
            fun create(ownerClass: Class<*>, classLoader: ClassLoader): ExpandedBackgroundMethods {
                val baseContentViewClass = generateSequence(ownerClass as Class<*>?) {
                    it.superclass
                }.firstOrNull { it.name == BASE_CONTENT_VIEW_CLASS }
                    ?: error("Missing superclass: $BASE_CONTENT_VIEW_CLASS")
                val miBlurCompatClass = classLoader.loadClass(MI_BLUR_COMPAT_CLASS)
                val colorClass = classLoader.loadClass("miui.systemui.dynamicisland.R\$color")
                val integerClass = classLoader.loadClass("miui.systemui.dynamicisland.R\$integer")
                val drawableClass = classLoader.loadClass("miui.systemui.dynamicisland.R\$drawable")
                return ExpandedBackgroundMethods(
                    updateBackgroundBg = baseContentViewClass.getDeclaredMethod(
                        "updateBackgroundBg",
                        View::class.java,
                        Boolean::class.javaPrimitiveType
                    ).apply { isAccessible = true },
                    getMiniBar = baseContentViewClass.getDeclaredMethod("getMiniBar").apply {
                        isAccessible = true
                    },
                    getBackgroundBlurOpened = miBlurCompatClass.getDeclaredMethod(
                        "getBackgroundBlurOpened",
                        Context::class.java
                    ).apply { isAccessible = true },
                    setMiViewBlurMode = miBlurCompatClass.getDeclaredMethod(
                        "setMiViewBlurModeCompat",
                        View::class.java,
                        Int::class.javaPrimitiveType
                    ).apply { isAccessible = true },
                    clearMiBackgroundBlendColor = miBlurCompatClass.getDeclaredMethod(
                        "clearMiBackgroundBlendColorCompat",
                        View::class.java
                    ).apply { isAccessible = true },
                    setMiBackgroundBlendColors = miBlurCompatClass.getDeclaredMethod(
                        "setMiBackgroundBlendColors\$default",
                        View::class.java,
                        IntArray::class.java,
                        Float::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Any::class.java
                    ).apply { isAccessible = true },
                    liveUpdateBlendColor1Id = colorClass.resourceId(
                        "liveupdate_island_element_blend_shade_color_1"
                    ),
                    liveUpdateBlendColor2Id = colorClass.resourceId(
                        "liveupdate_island_element_blend_shade_color_2"
                    ),
                    liveUpdateBlendColor3Id = colorClass.resourceId(
                        "liveupdate_island_element_blend_shade_color_3"
                    ),
                    blurModeLinearLightId = integerClass.resourceId("blur_mode_linear_light"),
                    blurModeLabId = integerClass.resourceId("blur_mode_lab"),
                    blurModePureId = integerClass.resourceId("blur_mode_pure"),
                    liveUpdateBackgroundDrawableId = drawableClass.resourceId(
                        "dynamic_island_liveupdate_background"
                    )
                )
            }

            private fun Class<*>.resourceId(name: String): Int {
                return getDeclaredField(name).apply { isAccessible = true }.getInt(null)
            }
        }
    }
}
