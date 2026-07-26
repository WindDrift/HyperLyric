package com.lidesheng.hyperlyric.root.mediacard.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.root.mediacard.MediaCardRuntimeConfig
import com.lidesheng.hyperlyric.root.mediacard.MediaCoverRotationController
import com.lidesheng.hyperlyric.root.mediacard.notification.layout.NotificationMediaConstraintBridge
import com.lidesheng.hyperlyric.root.mediacard.notification.layout.NotificationMediaLayoutController
import com.lidesheng.hyperlyric.root.mediacard.notification.layout.NotificationMediaLayoutResourceIds
import com.lidesheng.hyperlyric.root.mediacard.notification.layout.NotificationMediaTopSlotContent
import com.lidesheng.hyperlyric.root.mediacard.notification.layout.notificationMediaLayoutSpec
import com.lidesheng.hyperlyric.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

object NotificationMediaCoverStyleHooker {
    private const val TAG = "NotificationMediaCoverStyleHooker"
    private val ICON_LOAD_DRAWABLE_AS_USER_METHOD = runCatching {
        Icon::class.java.getDeclaredMethod(
            "loadDrawableAsUser",
            Context::class.java,
            Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
    }.getOrNull()
    private const val VIEW_CONTROLLER_CLASS =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewControllerImpl"
    private const val LAYOUT_CONTROLLER_CLASS =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaNotificationControllerImpl"
    private const val HOLDER_CLASS =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewHolder"
    private const val ACTION_BUTTON_UTILS_CLASS =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaActionButtonUtils"
    private const val MEDIA_TRANSFER_MANAGER_CLASS =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaTransferManagerImpl"
    private const val MEDIA_DATA_CLASS =
        "com.android.systemui.media.controls.shared.model.MediaData"
    private const val MEDIA_ACTION_CLASS =
        "com.android.systemui.media.controls.shared.model.MediaAction"
    private const val LOTTIE_VIEW_CLASS = "com.airbnb.lottie.LottieAnimationView"
    private const val SYSTEM_UI_PLUGIN_PACKAGE = "miui.systemui.plugin"
    private const val MUSIC_WAVE_CACHE_KEY = "hyperlyric_notification_music_wave"
    private const val MOVED_DEVICE_SWITCH_ICON_SCALE = 0.9f

    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val viewStates = Collections.synchronizedMap(WeakHashMap<View, CoverViewState>())
    private val seekBarTrackStates =
        Collections.synchronizedMap(WeakHashMap<View, SeekBarTrackState>())
    private val timeTextStates =
        Collections.synchronizedMap(WeakHashMap<TextView, TimeTextState>())
    private val timeVisibilityStates =
        Collections.synchronizedMap(WeakHashMap<TextView, Int>())
    private val elementVisibilityStates =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val actionButtonPaddingStates =
        Collections.synchronizedMap(WeakHashMap<ImageButton, ActionButtonPaddingState>())
    private val action4BindingStates =
        Collections.synchronizedMap(WeakHashMap<ImageButton, Action4BindingState>())
    private val movedDeviceSwitchStates =
        Collections.synchronizedMap(WeakHashMap<ImageButton, MovedDeviceSwitchState>())
    private val nativeApis = Collections.synchronizedMap(WeakHashMap<ClassLoader, NativeApi>())
    private val circleOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    fun hook(xposedModule: XposedModule, classLoader: ClassLoader) {
        if (!hookedClassLoaders.add(classLoader)) return

        val api = resolveApi(classLoader) ?: run {
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "跳过通知中心媒体封面 Hook: reason=native_api_unavailable")
            return
        }
        val handles = mutableListOf<HookHandle>()
        api.hookMethods.forEach { method ->
            runCatching {
                xposedModule.deoptimize(method)
                val hooker = hookerFor(method)
                    ?: error("No hooker for ${method.declaringClass.name}.${method.name}")
                handles += xposedModule.hook(method).intercept(hooker)
            }.onFailure { error ->
                HookLogger.e(
                    TAG,
                    "安装通知中心媒体封面 Hook 失败: " +
                            "method=${method.declaringClass.simpleName}.${method.name}",
                    error
                )
            }
        }

        if (handles.size != api.hookMethods.size) {
            handles.forEach(HookHandle::unhook)
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "通知中心媒体封面 Hook 安装不完整")
        } else {
            HookLogger.i(TAG, "通知中心媒体封面 Hook 已初始化: methods=${handles.size}")
        }
    }

    fun isTargetMethod(method: Method): Boolean {
        return when (method.declaringClass.name) {
            VIEW_CONTROLLER_CLASS -> when (method.name) {
                "attach", "bindMediaData", "setSeamless" -> method.parameterCount == 1
                "detach", "updateForegroundColors" -> method.parameterCount == 0
                "onFullAodStateChanged" -> true
                else -> false
            }

            ACTION_BUTTON_UTILS_CLASS -> when (method.name) {
                "setSemanticButton" -> method.parameterCount == 2
                "bindButtonsCommon" -> method.parameterCount == 3
                else -> false
            }

            MEDIA_TRANSFER_MANAGER_CLASS ->
                method.name == "updateChip" &&
                        method.parameterCount == 1 &&
                        method.parameterTypes[0] == ImageView::class.java

            LAYOUT_CONTROLLER_CLASS ->
                method.name == "loadLayout\$1" && method.parameterCount == 0

            else -> false
        }
    }

    fun hookerFor(executable: Executable): Hooker? {
        val method = executable as? Method ?: return null
        if (!isTargetMethod(method)) return null
        return when (method.declaringClass.name) {
            VIEW_CONTROLLER_CLASS -> ControllerHook(method.name)
            ACTION_BUTTON_UTILS_CLASS -> when (method.name) {
                "setSemanticButton" -> SemanticButtonHook(method)
                "bindButtonsCommon" -> CommonButtonHook(method)
                else -> null
            }

            MEDIA_TRANSFER_MANAGER_CLASS -> TransferChipHook()
            LAYOUT_CONTROLLER_CLASS -> LayoutLoadHook()
            else -> null
        }
    }

    private class ControllerHook(private val methodName: String) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val controller = chain.thisObject ?: return chain.proceed()
            if (!MediaCardRuntimeConfig.current.enabled) {
                if (methodName == "detach") {
                    restoreStyle(controller)
                    forgetAction4Binding(controller)
                }
                val result = chain.proceed()
                return result
            }
            if (
                methodName == "setSeamless" &&
                hideDeviceSwitch() &&
                notificationMediaLayoutSpec(currentLayoutStyle()).topSlotContent ==
                NotificationMediaTopSlotContent.NONE
            ) return null
            if (methodName == "detach") {
                restoreStyle(controller)
                forgetAction4Binding(controller)
            }
            val result = chain.proceed()
            if (methodName == "setSeamless") {
                runCatching { refreshMovedDeviceSwitchTopSlot(controller) }
            }
            if (methodName == "updateForegroundColors") {
                runCatching { refreshMovedDeviceSwitchTopSlot(controller) }
            }
            if (
                methodName == "attach" ||
                methodName == "bindMediaData" ||
                methodName == "onFullAodStateChanged"
            ) {
                runCatching {
                    val mediaData = if (methodName == "bindMediaData") {
                        chain.args.firstOrNull()
                    } else {
                        null
                    }
                    applyStyle(controller, mediaData)
                }.onFailure { HookLogger.e(TAG, "应用通知中心媒体封面样式失败", it) }
            }
            return result
        }
    }

    private class SemanticButtonHook(private val method: Method) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val buttonUtils = chain.thisObject ?: return chain.proceed()
            val button = chain.args.getOrNull(0) as? ImageButton ?: return chain.proceed()
            if (button.isAction4()) {
                captureAction4Binding(buttonUtils, method, chain.args.toList(), button)
                val result = chain.proceed()
                applyMovedDeviceSwitchForButton(button)
                applyCustomActionVisibility(button)
                return result
            }
            if (button.isAction0()) {
                val result = chain.proceed()
                applyCustomActionVisibility(button)
                return result
            }
            val actionScale = notificationMediaLayoutSpec(
                currentLayoutStyle()
            ).semanticActionIconScale
            if (!MediaCardRuntimeConfig.current.enabled || actionScale == null) {
                return chain.proceed()
            }
            val mediaAction = chain.args.getOrNull(1) ?: return chain.proceed()
            val scaledAction = runCatching {
                resolveApi(buttonUtils.javaClass.classLoader)?.createScaledSemanticAction(
                    buttonUtils,
                    button,
                    mediaAction,
                    actionScale
                )
            }.getOrNull() ?: return chain.proceed()
            if (scaledAction === mediaAction) return chain.proceed()
            return chain.proceed(arrayOf<Any?>(button, scaledAction))
        }
    }

    private class CommonButtonHook(private val method: Method) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val buttonUtils = chain.thisObject ?: return chain.proceed()
            val button = chain.args.getOrNull(0) as? ImageButton ?: return chain.proceed()
            if (!button.isAction4() && !button.isAction0()) return chain.proceed()
            if (button.isAction4()) {
                captureAction4Binding(buttonUtils, method, chain.args.toList(), button)
            }
            val result = chain.proceed()
            if (button.isAction4()) applyMovedDeviceSwitchForButton(button)
            applyCustomActionVisibility(button)
            return result
        }
    }

    private class TransferChipHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val sourceIcon = chain.args.firstOrNull() as? ImageView ?: return result
            syncMovedDeviceSwitchIcon(sourceIcon)
            return result
        }
    }

    private class LayoutLoadHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val controller = chain.thisObject ?: return result
            if (MediaCardRuntimeConfig.current.enabled) {
                runCatching {
                    resolveApi(controller.javaClass.classLoader)?.applyLoadedLayout(
                        controller,
                        currentStyle(),
                        currentLayoutStyle()
                    )
                }.onFailure { HookLogger.e(TAG, "应用通知中心媒体封面约束失败", it) }
            }
            return result
        }
    }

    private fun applyStyle(controller: Any, mediaData: Any?) {
        val api = resolveApi(controller.javaClass.classLoader) ?: return
        val holder = api.getHolder(controller) ?: return
        val albumView = api.getAlbumView(holder)
        val albumImage = api.getAlbumImage(holder)
        val layoutStyle = currentLayoutStyle()
        val style = currentStyle()
        val currentMediaData = mediaData ?: api.getMediaData(controller)
        val isPlaying = api.isPlaying(currentMediaData)
        applySeekBarTrackOffset(api, holder, layoutStyle)
        applyTimeTextAlignment(api, holder, layoutStyle)
        applyActionButtonPadding(api, holder, layoutStyle)
        applyMovedDeviceSwitch(api, controller, holder, layoutStyle, currentMediaData, isPlaying)
        applyElementVisibility(api, holder, layoutStyle)
        if (style == RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_DEFAULT) {
            restoreCoverStyle(api, holder)
            return
        }
        val state = viewStates.getOrPut(albumView) {
            CoverViewState(
                albumView = albumView,
                albumImage = albumImage,
                albumOutlineProvider = albumView.outlineProvider,
                albumClipToOutline = albumView.clipToOutline,
                imageOutlineProvider = albumImage.outlineProvider,
                imageClipToOutline = albumImage.clipToOutline
            )
        }
        when (style) {
            RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_CIRCLE -> {
                MediaCoverRotationController.detach(albumImage)
                state.applyCircle()
            }

            RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_ROTATING_CIRCLE -> {
                state.applyCircle()
                MediaCoverRotationController.attach(albumImage, isPlaying)
            }

            RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_HIDDEN -> {
                MediaCoverRotationController.detach(albumImage)
                state.restoreOutlines()
                if (albumView.visibility != View.GONE) albumView.visibility = View.GONE
            }

            else -> restoreStyle(controller)
        }
    }

    private fun restoreStyle(controller: Any) {
        val api = resolveApi(controller.javaClass.classLoader) ?: return
        val holder = api.getHolder(controller) ?: return
        restoreSeekBarTrackOffset(api, holder)
        restoreTimeTextAlignment(api, holder)
        restoreActionButtonPadding(api, holder)
        restoreMovedDeviceSwitch(api, holder)
        restoreElementVisibility(api, holder)
        restoreCoverStyle(api, holder)
    }

    private fun applyActionButtonPadding(api: NativeApi, holder: Any, layoutStyle: Int) {
        val spec = notificationMediaLayoutSpec(layoutStyle)
        api.getActionButtons(holder).forEachIndexed { index, button ->
            val scale = if (index == 2) spec.action2ButtonScale else spec.actionButtonScale
            if (scale == null) {
                restoreActionButtonPadding(button)
                return@forEachIndexed
            }
            val state = actionButtonPaddingStates.getOrPut(button) {
                ActionButtonPaddingState.capture(button)
            }
            button.setPadding(
                (state.left * scale).roundToInt(),
                (state.top * scale).roundToInt(),
                (state.right * scale).roundToInt(),
                (state.bottom * scale).roundToInt()
            )
        }
    }

    private fun restoreActionButtonPadding(api: NativeApi, holder: Any) {
        api.getActionButtons(holder).forEach(::restoreActionButtonPadding)
    }

    private fun restoreActionButtonPadding(button: ImageButton) {
        val state = actionButtonPaddingStates.remove(button) ?: return
        button.setPadding(state.left, state.top, state.right, state.bottom)
    }

    private fun applySeekBarTrackOffset(api: NativeApi, holder: Any, layoutStyle: Int) {
        val spec = notificationMediaLayoutSpec(layoutStyle)
        val modifiesVerticalPadding =
            spec.seekBarPaddingTopDp != null || spec.seekBarPaddingBottomDp != null
        if (!spec.removeSeekBarTrackInset && !modifiesVerticalPadding) {
            restoreSeekBarTrackOffset(api, holder)
            return
        }
        val seekBar = api.getSeekBar(holder) ?: return
        val state = seekBarTrackStates[seekBar] ?: api.captureSeekBarTrackState(seekBar)?.also {
            seekBarTrackStates[seekBar] = it
        } ?: return
        val targetOffset = if (spec.removeSeekBarTrackInset) 0 else state.paddingOffset
        val targetTrackPositionX = if (spec.removeSeekBarTrackInset) 0f else state.trackPositionX
        if (state.appliedOffset != targetOffset) {
            api.setSeekBarTrackOffset(seekBar, targetOffset, targetTrackPositionX)
            state.appliedOffset = targetOffset
        }
        val density = seekBar.resources.displayMetrics.density
        val targetPaddingTop = spec.seekBarPaddingTopDp
            ?.let { (it * density).roundToInt() }
            ?: state.viewPaddingTop
        val targetPaddingBottom = spec.seekBarPaddingBottomDp
            ?.let { (it * density).roundToInt() }
            ?: state.viewPaddingBottom
        if (
            seekBar.paddingLeft != state.viewPaddingLeft ||
            seekBar.paddingTop != targetPaddingTop ||
            seekBar.paddingRight != state.viewPaddingRight ||
            seekBar.paddingBottom != targetPaddingBottom
        ) {
            seekBar.setPadding(
                state.viewPaddingLeft,
                targetPaddingTop,
                state.viewPaddingRight,
                targetPaddingBottom
            )
        }
    }

    private fun restoreSeekBarTrackOffset(api: NativeApi, holder: Any) {
        val seekBar = api.getSeekBar(holder) ?: return
        val state = seekBarTrackStates.remove(seekBar) ?: return
        api.setSeekBarTrackOffset(seekBar, state.paddingOffset, state.trackPositionX)
        seekBar.setPadding(
            state.viewPaddingLeft,
            state.viewPaddingTop,
            state.viewPaddingRight,
            state.viewPaddingBottom
        )
    }

    private fun applyTimeTextAlignment(api: NativeApi, holder: Any, layoutStyle: Int) {
        val elapsed = api.getElapsedTimeView(holder)
        val total = api.getTotalTimeView(holder)
        if (
            layoutStyle != RootConstants.NOTIFICATION_MEDIA_LAYOUT_STYLE_COLOROS &&
            layoutStyle != RootConstants.NOTIFICATION_MEDIA_LAYOUT_STYLE_ONEUI &&
            layoutStyle != RootConstants.NOTIFICATION_MEDIA_LAYOUT_STYLE_MIUI
        ) {
            elapsed?.let(::restoreTimeTextAlignment)
            total?.let(::restoreTimeTextAlignment)
            return
        }
        val textSizeSp: Float? = null
        elapsed?.let { view ->
            restoreTimeTextVisibility(view)
            timeTextStates.getOrPut(view) { TimeTextState.capture(view) }
                .apply(Gravity.START or Gravity.CENTER_VERTICAL, textSizeSp)
        }
        total?.let { view ->
            restoreTimeTextVisibility(view)
            timeTextStates.getOrPut(view) { TimeTextState.capture(view) }
                .apply(Gravity.END or Gravity.CENTER_VERTICAL, textSizeSp)
        }
    }

    private fun restoreTimeTextAlignment(api: NativeApi, holder: Any) {
        api.getElapsedTimeView(holder)?.let(::restoreTimeTextAlignment)
        api.getTotalTimeView(holder)?.let(::restoreTimeTextAlignment)
    }

    private fun restoreTimeTextAlignment(view: TextView) {
        timeTextStates.remove(view)?.restore()
        restoreTimeTextVisibility(view)
    }

    private fun hideTimeText(view: TextView) {
        timeTextStates.remove(view)?.restore()
        timeVisibilityStates.putIfAbsent(view, view.visibility)
        view.visibility = View.GONE
    }

    private fun restoreTimeTextVisibility(view: TextView) {
        timeVisibilityStates.remove(view)?.let { visibility ->
            view.visibility = visibility
        }
    }


    private fun captureAction4Binding(
        owner: Any,
        method: Method,
        args: List<Any?>,
        button: ImageButton
    ) {
        synchronized(action4BindingStates) {
            val existing = action4BindingStates[button]
            val mediaAction = args.getOrNull(1)
            if (
                method.name == "bindButtonsCommon" ||
                mediaAction == null ||
                existing == null ||
                existing.method.name != "bindButtonsCommon"
            ) {
                action4BindingStates[button] = Action4BindingState(
                    owner = owner,
                    method = method,
                    argsAfterButton = args.drop(1)
                )
            }
        }
    }

    private fun applyMovedDeviceSwitch(
        api: NativeApi,
        controller: Any,
        holder: Any,
        layoutStyle: Int,
        mediaData: Any?,
        isPlaying: Boolean
    ) {
        val spec = notificationMediaLayoutSpec(layoutStyle)
        val button = api.getAction4(holder) ?: return
        if (
            !spec.moveDeviceToAction4 &&
            spec.topSlotContent == NotificationMediaTopSlotContent.NONE
        ) {
            restoreMovedDeviceSwitch(button)
            return
        }
        val sourceIcon = api.getSeamlessIcon(holder) ?: return
        val sourceContainer = api.getSeamlessContainer(holder) ?: return
        val previous = movedDeviceSwitchStates[button]
        if (
            previous != null &&
            (previous.sourceIcon !== sourceIcon || previous.sourceContainer !== sourceContainer)
        ) {
            restoreMovedDeviceSwitch(button)
        }
        val state = movedDeviceSwitchStates.getOrPut(button) {
            MovedDeviceSwitchState(sourceIcon, sourceContainer)
        }
        if (
            spec.moveDeviceToAction4 &&
            !hideDeviceSwitch() &&
            api.supportsDeviceSwitchMove() &&
            action4BindingStates.containsKey(button) &&
            state.canApply()
        ) {
            state.applyTo(button)
        } else {
            restoreMovedDeviceSwitchButton(button, state)
        }
    }

    private fun refreshMovedDeviceSwitchTopSlot(controller: Any) {
        val layoutStyle = currentLayoutStyle()
        val spec = notificationMediaLayoutSpec(layoutStyle)
        if (
            !spec.moveDeviceToAction4 &&
            spec.topSlotContent == NotificationMediaTopSlotContent.NONE
        ) return
        val api = resolveApi(controller.javaClass.classLoader) ?: return
        val holder = api.getHolder(controller) ?: return
        val mediaData = api.getMediaData(controller)
        applyMovedDeviceSwitch(
            api,
            controller,
            holder,
            layoutStyle,
            mediaData,
            api.isPlaying(mediaData)
        )
    }

    private fun applyMovedDeviceSwitchForButton(button: ImageButton) {
        if (
            !notificationMediaLayoutSpec(currentLayoutStyle()).moveDeviceToAction4 ||
            hideDeviceSwitch()
        ) return
        movedDeviceSwitchStates[button]?.takeIf { it.canApply() }?.applyTo(button)
    }

    private fun syncMovedDeviceSwitchIcon(sourceIcon: ImageView) {
        val targets = synchronized(movedDeviceSwitchStates) {
            movedDeviceSwitchStates.entries
                .filter { it.value.sourceIcon === sourceIcon && it.value.applied }
                .map { it.key to it.value }
        }
        targets.forEach { (button, state) ->
            state.copyIconTo(button)
        }
    }

    private fun restoreMovedDeviceSwitch(api: NativeApi, holder: Any) {
        api.getAction4(holder)?.let(::restoreMovedDeviceSwitch)
    }

    private fun restoreMovedDeviceSwitch(button: ImageButton) {
        val movedState = movedDeviceSwitchStates.remove(button) ?: return
        movedState.restoreSourceSlot()
        restoreMovedDeviceSwitchButton(button, movedState)
    }

    private fun restoreMovedDeviceSwitchButton(
        button: ImageButton,
        movedState: MovedDeviceSwitchState
    ) {
        if (!movedState.applied) return
        movedState.applied = false
        val bindingState = action4BindingStates[button] ?: return
        runCatching { bindingState.restore(button) }
            .onFailure { HookLogger.e(TAG, "恢复通知中心第五个媒体动作失败", it) }
    }

    private fun forgetAction4Binding(controller: Any) {
        val api = resolveApi(controller.javaClass.classLoader) ?: return
        val holder = api.getHolder(controller) ?: return
        val button = api.getAction4(holder) ?: return
        movedDeviceSwitchStates.remove(button)
        action4BindingStates.remove(button)
    }

    private fun applyElementVisibility(api: NativeApi, holder: Any, layoutStyle: Int) {
        api.getActionButtons(holder).forEach(::applyCustomActionVisibility)
        listOfNotNull(
            api.getElapsedTimeView(holder),
            api.getTotalTimeView(holder)
        ).forEach { timeView ->
            if (hideTime()) {
                elementVisibilityStates.putIfAbsent(timeView, timeView.visibility)
                timeView.visibility = View.GONE
            } else {
                restoreElementVisibility(timeView)
            }
        }
    }

    private fun applyCustomActionVisibility(button: ImageButton) {
        if (!button.isAction0() && !button.isAction4()) return
        val hide = hideCustomActions() && (
                button.isAction0() || !keepsAction4ForDeviceSwitch(button.javaClass.classLoader)
                )
        if (hide) {
            elementVisibilityStates.putIfAbsent(button, button.visibility)
            button.visibility = View.INVISIBLE
        } else {
            restoreElementVisibility(button)
        }
    }

    private fun restoreElementVisibility(api: NativeApi, holder: Any) {
        api.getActionButtons(holder).forEach(::restoreElementVisibility)
        api.getElapsedTimeView(holder)?.let(::restoreElementVisibility)
        api.getTotalTimeView(holder)?.let(::restoreElementVisibility)
    }

    private fun restoreElementVisibility(view: View) {
        elementVisibilityStates.remove(view)?.let { view.visibility = it }
    }

    private fun keepsAction4ForDeviceSwitch(classLoader: ClassLoader?): Boolean {
        if (hideDeviceSwitch()) return false
        val spec = notificationMediaLayoutSpec(currentLayoutStyle())
        return spec.moveDeviceToAction4 &&
                resolveApi(classLoader)?.supportsDeviceSwitchMove() == true
    }

    private fun ImageButton.isAction0(): Boolean {
        return runCatching { resources.getResourceEntryName(id) == "action0" }.getOrDefault(false)
    }

    private fun ImageButton.isAction4(): Boolean {
        return runCatching { resources.getResourceEntryName(id) == "action4" }.getOrDefault(false)
    }

    private fun restoreCoverStyle(api: NativeApi, holder: Any) {
        val albumView = api.getAlbumView(holder)
        val state = viewStates.remove(albumView) ?: return
        MediaCoverRotationController.detach(state.albumImage)
        state.restoreOutlines()
        state.albumView.visibility = View.VISIBLE
    }

    private fun currentStyle(): Int {
        if (!MediaCardRuntimeConfig.current.enabled) {
            return RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_DEFAULT
        }
        return MediaCardRuntimeConfig.current.notification.coverStyle
    }

    private fun currentLayoutStyle(): Int {
        return RootConstants.NOTIFICATION_MEDIA_LAYOUT_STYLE_SYSTEM
    }

    private fun hideCoverSource(): Boolean {
        if (!MediaCardRuntimeConfig.current.enabled) return false
        return MediaCardRuntimeConfig.current.notification.hideCoverSource
    }

    private fun hideDeviceSwitch(): Boolean {
        if (!MediaCardRuntimeConfig.current.enabled) return false
        return MediaCardRuntimeConfig.current.notification.hideDeviceSwitch
    }

    private fun hideCustomActions(): Boolean {
        if (!MediaCardRuntimeConfig.current.enabled) return false
        return MediaCardRuntimeConfig.current.notification.hideCustomActions
    }

    private fun hideTime(): Boolean {
        if (!MediaCardRuntimeConfig.current.enabled) return false
        return MediaCardRuntimeConfig.current.notification.hideTime
    }

    private fun resolveApi(classLoader: ClassLoader?): NativeApi? {
        classLoader ?: return null
        nativeApis[classLoader]?.let { return it }
        return runCatching { NativeApi.create(classLoader) }
            .onSuccess { nativeApis[classLoader] = it }
            .onFailure { HookLogger.w(TAG, "通知中心媒体封面接口不可用: reason=${it.message}") }
            .getOrNull()
    }

    private data class CoverViewState(
        val albumView: View,
        val albumImage: ImageView,
        val albumOutlineProvider: ViewOutlineProvider?,
        val albumClipToOutline: Boolean,
        val imageOutlineProvider: ViewOutlineProvider?,
        val imageClipToOutline: Boolean,
        var coverOutlined: Boolean = false
    ) {
        fun applyCircle() {
            if (albumView.visibility != View.VISIBLE) albumView.visibility = View.VISIBLE
            if (
                coverOutlined &&
                albumView.outlineProvider === circleOutlineProvider &&
                !albumView.clipToOutline &&
                albumImage.outlineProvider === circleOutlineProvider &&
                albumImage.clipToOutline
            ) {
                return
            }
            albumView.outlineProvider = circleOutlineProvider
            albumView.clipToOutline = false
            albumImage.outlineProvider = circleOutlineProvider
            albumImage.clipToOutline = true
            albumView.invalidateOutline()
            albumImage.invalidateOutline()
            coverOutlined = true
        }

        fun restoreOutlines() {
            if (!coverOutlined) return
            albumView.outlineProvider = albumOutlineProvider
            albumView.clipToOutline = albumClipToOutline
            albumImage.outlineProvider = imageOutlineProvider
            albumImage.clipToOutline = imageClipToOutline
            albumView.invalidateOutline()
            albumImage.invalidateOutline()
            coverOutlined = false
        }
    }

    private data class SeekBarTrackState(
        val paddingOffset: Int,
        val trackPositionX: Float,
        val viewPaddingLeft: Int,
        val viewPaddingTop: Int,
        val viewPaddingRight: Int,
        val viewPaddingBottom: Int,
        var appliedOffset: Int? = null
    )

    private data class TimeTextState(
        val view: TextView,
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int,
        val minEms: Int,
        val gravity: Int,
        val textSizePx: Float
    ) {
        fun apply(horizontalGravity: Int, textSizeSp: Float?) {
            view.setPadding(0, paddingTop, 0, paddingBottom)
            view.minEms = 0
            view.gravity = horizontalGravity
            view.setTextSize(
                if (textSizeSp == null) TypedValue.COMPLEX_UNIT_PX else TypedValue.COMPLEX_UNIT_SP,
                textSizeSp ?: textSizePx
            )
        }

        fun restore() {
            view.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
            view.minEms = minEms
            view.gravity = gravity
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
        }

        companion object {
            fun capture(view: TextView): TimeTextState {
                return TimeTextState(
                    view = view,
                    paddingLeft = view.paddingLeft,
                    paddingTop = view.paddingTop,
                    paddingRight = view.paddingRight,
                    paddingBottom = view.paddingBottom,
                    minEms = view.minEms,
                    gravity = view.gravity,
                    textSizePx = view.textSize
                )
            }
        }
    }

    private data class ActionButtonPaddingState(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        companion object {
            fun capture(button: ImageButton): ActionButtonPaddingState {
                return ActionButtonPaddingState(
                    left = button.paddingLeft,
                    top = button.paddingTop,
                    right = button.paddingRight,
                    bottom = button.paddingBottom
                )
            }
        }
    }

    private data class Action4BindingState(
        val owner: Any,
        val method: Method,
        val argsAfterButton: List<Any?>
    ) {
        fun restore(button: ImageButton) {
            method.invoke(owner, button, *argsAfterButton.toTypedArray())
        }
    }

    private data class MovedDeviceSwitchState(
        val sourceIcon: ImageView,
        val sourceContainer: ViewGroup,
        val originalSourceVisibility: Int = sourceIcon.visibility,
        val originalContainerGravity: Int? = (sourceContainer as? LinearLayout)?.gravity,
        val originalContainerBackground: Drawable? = sourceContainer.background,
        val sourceControl: View? = (sourceIcon.parent as? View)?.takeIf {
            it !== sourceContainer
        },
        val originalSourceControlVisibility: Int? = sourceControl?.visibility,
        var applied: Boolean = false,
        var appIconView: ImageView? = null,
    ) {
        fun canApply(): Boolean {
            return sourceContainer.isEnabled &&
                    sourceIcon.isEnabled &&
                    sourceIcon.drawable != null &&
                    sourceIcon.hasOnClickListeners()
        }

        fun applyTo(button: ImageButton) {
            copyIconTo(button)
            button.contentDescription = sourceIcon.contentDescription
            button.isEnabled = true
            button.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            button.visibility = View.VISIBLE
            button.setOnClickListener { sourceIcon.performClick() }
            applied = true
        }

        fun copyIconTo(button: ImageButton) {
            val drawable = sourceIcon.drawable ?: return
            val copy = runCatching {
                drawable.constantState
                    ?.newDrawable(button.resources, button.context.theme)
                    ?.mutate()
            }.getOrNull() ?: drawable
            val insetFraction = (1f - MOVED_DEVICE_SWITCH_ICON_SCALE) / 2f
            button.setImageDrawable(InsetDrawable(copy, insetFraction))
        }


        fun showAppIcon(drawable: Drawable): Boolean {
            releaseAppIdentity()
            val iconView = appIconView ?: ImageView(sourceContainer.context).also { view ->
                view.layoutParams = if (sourceContainer is LinearLayout) {
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ).apply { gravity = Gravity.CENTER }
                } else {
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                view.scaleType = ImageView.ScaleType.FIT_CENTER
                view.isClickable = false
                view.isFocusable = false
                view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                sourceContainer.addView(view)
                appIconView = view
            }
            val copy = runCatching {
                drawable.constantState
                    ?.newDrawable(iconView.resources, iconView.context.theme)
                    ?.mutate()
            }.getOrNull() ?: drawable
            iconView.setImageDrawable(copy)
            iconView.imageTintList = null
            iconView.alpha = 1f
            iconView.visibility = View.VISIBLE
            sourceContainer.background = null
            (sourceContainer as? LinearLayout)?.gravity = Gravity.CENTER
            sourceIcon.visibility = View.GONE
            sourceContainer.visibility = View.VISIBLE
            return true
        }


        fun hideSourceSlot() {
            releaseAppIcon()
            releaseAppIdentity()
            sourceContainer.background = originalContainerBackground
            sourceIcon.visibility = View.GONE
            sourceContainer.visibility = View.GONE
        }

        fun restoreSourceSlot() {
            releaseAppIcon()
            releaseAppIdentity()
            sourceContainer.background = originalContainerBackground
            originalContainerGravity?.let { gravity ->
                (sourceContainer as? LinearLayout)?.gravity = gravity
            }
            sourceIcon.visibility = originalSourceVisibility
        }


        private fun releaseAppIcon() {
            appIconView?.let { iconView ->
                (iconView.parent as? ViewGroup)?.removeView(iconView)
            }
            appIconView = null
        }

        private fun releaseAppIdentity() {
            originalSourceControlVisibility?.let { visibility ->
                sourceControl?.visibility = visibility
            }
        }
    }


    private class SemanticActionApi private constructor(
        val hookMethod: Method,
        val commonHookMethod: Method?,
        private val playField: Field,
        private val prevField: Field,
        private val nextField: Field,
        private val islandField: Field,
        private val mediaActionConstructor: Constructor<*>,
        private val iconField: Field,
        private val actionField: Field,
        private val descriptionField: Field,
        private val backgroundField: Field,
        private val rebindIdField: Field,
        private val drawableToBitmapMethod: Method
    ) {
        private val scaledIcons = Collections.synchronizedSet(
            Collections.newSetFromMap(WeakHashMap<Drawable, Boolean>())
        )

        fun createScaledAction(
            buttonUtils: Any,
            button: ImageButton,
            mediaAction: Any,
            scale: Float
        ): Any? {
            if (islandField.getBoolean(buttonUtils)) return null
            val isBuiltInAction =
                playField.get(buttonUtils) === button ||
                        prevField.get(buttonUtils) === button ||
                        nextField.get(buttonUtils) === button
            if (!isBuiltInAction) return null

            val icon = iconField.get(mediaAction) as? Drawable ?: return null
            if (scaledIcons.contains(icon)) return mediaAction
            val bitmap = drawableToBitmapMethod.invoke(null, icon) as? Bitmap ?: return null
            if (bitmap.width <= 0 || bitmap.height <= 0) return null
            val targetWidth = (bitmap.width * scale)
                .roundToInt()
                .coerceAtLeast(1)
            val targetHeight = (bitmap.height * scale)
                .roundToInt()
                .coerceAtLeast(1)
            if (bitmap.width == targetWidth && bitmap.height == targetHeight) return mediaAction
            val scaledBitmap = Bitmap.createScaledBitmap(
                bitmap,
                targetWidth,
                targetHeight,
                true
            ).apply {
                density = bitmap.density
            }
            val scaledIcon = BitmapDrawable(button.resources, scaledBitmap)
            scaledIcons.add(scaledIcon)
            return mediaActionConstructor.newInstance(
                scaledIcon,
                actionField.get(mediaAction),
                descriptionField.get(mediaAction),
                backgroundField.get(mediaAction),
                rebindIdField.get(mediaAction)
            )
        }

        companion object {
            fun createOrNull(classLoader: ClassLoader): SemanticActionApi? = runCatching {
                val actionButtonUtilsClass = classLoader.loadClass(ACTION_BUTTON_UTILS_CLASS)
                val mediaActionClass = classLoader.loadClass(MEDIA_ACTION_CLASS)
                val drawableUtilsClass = classLoader.loadClass("com.miui.utils.DrawableUtils")
                val hookMethod = actionButtonUtilsClass.declaredMethods.find { method ->
                    method.name == "setSemanticButton" &&
                            method.parameterCount == 2 &&
                            method.parameterTypes[0] == ImageButton::class.java &&
                            method.parameterTypes[1] == mediaActionClass
                }?.apply { isAccessible = true }
                    ?: error("Missing MiuiMediaActionButtonUtils.setSemanticButton")
                val commonHookMethod = actionButtonUtilsClass.declaredMethods.find { method ->
                    method.name == "bindButtonsCommon" &&
                            method.parameterCount == 3 &&
                            method.parameterTypes[0] == ImageButton::class.java &&
                            method.parameterTypes[1] == mediaActionClass
                }?.apply { isAccessible = true }
                val mediaActionConstructor = mediaActionClass.declaredConstructors.find {
                    it.parameterCount == 5
                }?.apply { isAccessible = true }
                    ?: error("Missing MediaAction constructor")
                val drawableToBitmap = drawableUtilsClass.declaredMethods.find { method ->
                    method.name == "drawable2Bitmap" &&
                            method.parameterCount == 1 &&
                            method.parameterTypes[0] == Drawable::class.java
                }?.apply { isAccessible = true }
                    ?: error("Missing DrawableUtils.drawable2Bitmap")

                SemanticActionApi(
                    hookMethod = hookMethod,
                    commonHookMethod = commonHookMethod,
                    playField = actionButtonUtilsClass.getDeclaredField("play").apply {
                        isAccessible = true
                    },
                    prevField = actionButtonUtilsClass.getDeclaredField("prev").apply {
                        isAccessible = true
                    },
                    nextField = actionButtonUtilsClass.getDeclaredField("next").apply {
                        isAccessible = true
                    },
                    islandField = actionButtonUtilsClass.getDeclaredField("island").apply {
                        isAccessible = true
                    },
                    mediaActionConstructor = mediaActionConstructor,
                    iconField = mediaActionClass.getDeclaredField("icon").apply {
                        isAccessible = true
                    },
                    actionField = mediaActionClass.getDeclaredField("action").apply {
                        isAccessible = true
                    },
                    descriptionField = mediaActionClass.getDeclaredField(
                        "contentDescription"
                    ).apply { isAccessible = true },
                    backgroundField = mediaActionClass.getDeclaredField("background").apply {
                        isAccessible = true
                    },
                    rebindIdField = mediaActionClass.getDeclaredField("rebindId").apply {
                        isAccessible = true
                    },
                    drawableToBitmapMethod = drawableToBitmap
                )
            }.getOrNull()
        }
    }

    private class NativeApi private constructor(
        val hookMethods: List<Method>,
        private val holderField: Field,
        private val controllerMediaDataField: Field,
        private val controllerAppIconDrawableField: Field?,
        private val playerField: Field?,
        private val albumViewField: Field,
        private val albumImageField: Field,
        private val action4Field: Field?,
        private val actionButtonFields: List<Field>,
        private val actionColorSourceField: Field?,
        private val titleTextField: Field?,
        private val artistTextField: Field?,
        private val seamlessContainerField: Field?,
        private val seamlessIconField: Field?,
        private val seekBarField: Field?,
        private val elapsedTimeViewField: Field?,
        private val totalTimeViewField: Field?,
        private val seekBarPaddingOffsetField: Field?,
        private val seekBarTrackPositionField: Field?,
        private val seekBarRuntimeShaderField: Field?,
        private val mediaDataIsPlayingField: Field,
        private val mediaDataPackageNameField: Field?,
        private val mediaDataAppNameField: Field?,
        private val mediaDataAppIconField: Field?,
        private val mediaDataUserIdField: Field?,
        private val semanticActionApi: SemanticActionApi?,
        private val layoutContextField: Field,
        private val normalLayoutField: Field,
        private val normalAlbumLayoutField: Field,
        private val setVisibilityMethod: Method,
        private val setGoneMarginMethod: Method,
        private val connectMethod: Method,
        private val setMarginMethod: Method,
        private val clearMethod: Method?,
        private val constrainWidthMethod: Method?,
        private val constrainHeightMethod: Method?
    ) : NotificationMediaConstraintBridge {
        override val supportsFullLayout: Boolean
            get() = clearMethod != null &&
                    constrainWidthMethod != null &&
                    constrainHeightMethod != null

        fun getHolder(controller: Any): Any? = holderField.get(controller)

        fun getMediaData(controller: Any): Any? = controllerMediaDataField.get(controller)

        fun getPlayer(holder: Any): ViewGroup? =
            playerField?.get(holder) as? ViewGroup

        fun getArtistText(holder: Any): TextView? =
            artistTextField?.get(holder) as? TextView

        fun getTitleText(holder: Any): TextView? =
            titleTextField?.get(holder) as? TextView

        private fun getNativeAppIconDrawable(controller: Any): Drawable? {
            return runCatching {
                controllerAppIconDrawableField?.get(controller) as? Drawable
            }.getOrNull()
        }

        fun getAlbumView(holder: Any): View = albumViewField.get(holder) as View

        fun getAlbumImage(holder: Any): ImageView = albumImageField.get(holder) as ImageView

        fun getAction4(holder: Any): ImageButton? = action4Field?.get(holder) as? ImageButton

        fun getActionButtons(holder: Any): List<ImageButton> {
            return actionButtonFields.mapNotNull { field ->
                runCatching { field.get(holder) as? ImageButton }.getOrNull()
            }
        }

        fun getSeamlessContainer(holder: Any): ViewGroup? =
            seamlessContainerField?.get(holder) as? ViewGroup

        fun getSeamlessIcon(holder: Any): ImageView? = seamlessIconField?.get(holder) as? ImageView

        fun getSeekBar(holder: Any): View? = seekBarField?.get(holder) as? View

        fun getElapsedTimeView(holder: Any): TextView? =
            elapsedTimeViewField?.get(holder) as? TextView

        fun getTotalTimeView(holder: Any): TextView? =
            totalTimeViewField?.get(holder) as? TextView

        fun captureSeekBarTrackState(seekBar: View): SeekBarTrackState? {
            val paddingField = seekBarPaddingOffsetField ?: return null
            val trackPositionField = seekBarTrackPositionField ?: return null
            val trackPosition = trackPositionField.get(seekBar) as? FloatArray ?: return null
            val trackPositionX = trackPosition.firstOrNull() ?: return null
            return SeekBarTrackState(
                paddingOffset = paddingField.getInt(seekBar),
                trackPositionX = trackPositionX,
                viewPaddingLeft = seekBar.paddingLeft,
                viewPaddingTop = seekBar.paddingTop,
                viewPaddingRight = seekBar.paddingRight,
                viewPaddingBottom = seekBar.paddingBottom
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
                val shader = seekBarRuntimeShaderField?.get(seekBar) ?: return@runCatching
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

        fun isPlaying(mediaData: Any?): Boolean {
            return mediaData?.let { mediaDataIsPlayingField.get(it) == true } ?: false
        }

        private fun getApplicationIcon(mediaData: Any?, context: Context): Drawable? {
            if (mediaData == null) return null
            val packageName = mediaDataPackageNameField?.get(mediaData) as? String ?: return null
            return runCatching {
                context.packageManager.getApplicationIcon(packageName)
            }.getOrNull()
        }

        private fun getMediaSourceIcon(mediaData: Any?, context: Context): Drawable? {
            if (mediaData == null) return null
            val icon = runCatching {
                mediaDataAppIconField?.get(mediaData) as? Icon
            }.getOrNull() ?: return null
            val userId = mediaDataUserIdField?.let { field ->
                runCatching { field.getInt(mediaData) }.getOrNull()
            }
            return runCatching {
                val drawableForUser = userId?.let { id ->
                    ICON_LOAD_DRAWABLE_AS_USER_METHOD?.invoke(icon, context, id) as? Drawable
                }
                drawableForUser ?: icon.loadDrawable(context)
            }.getOrNull()
        }

        @Suppress("DEPRECATION")
        fun getApplicationName(mediaData: Any?, context: Context): CharSequence? {
            if (mediaData == null) return null
            val mediaName = mediaDataAppNameField?.get(mediaData) as? CharSequence
            if (!mediaName.isNullOrBlank()) return mediaName
            val packageName = mediaDataPackageNameField?.get(mediaData) as? String ?: return null
            return runCatching {
                val packageManager = context.packageManager
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0)
                )
            }.getOrElse { packageName.substringAfterLast('.') }
        }

        fun getAppIdentityIcon(
            controller: Any,
            mediaData: Any?,
            context: Context
        ): Drawable? {
            return getNativeAppIconDrawable(controller)
                ?: getMediaSourceIcon(mediaData, context)
                ?: getApplicationIcon(mediaData, context)
        }

        fun getPixelSmallIcon(
            controller: Any,
            mediaData: Any?,
            context: Context
        ): Drawable? = getAppIdentityIcon(controller, mediaData, context)


        private fun getActionForegroundColor(holder: Any): Int? {
            val source = actionColorSourceField?.get(holder) as? ImageButton
            return source?.imageTintList?.defaultColor
                ?: (artistTextField?.get(holder) as? TextView)?.currentTextColor
        }

        fun createScaledSemanticAction(
            buttonUtils: Any,
            button: ImageButton,
            mediaAction: Any,
            scale: Float
        ): Any? = semanticActionApi?.createScaledAction(buttonUtils, button, mediaAction, scale)

        fun supportsDeviceSwitchMove(): Boolean {
            return action4Field != null &&
                    seamlessContainerField != null &&
                    seamlessIconField != null &&
                    semanticActionApi?.commonHookMethod != null
        }

        fun applyLoadedLayout(controller: Any, coverStyle: Int, layoutStyle: Int) {
            val hideSource = hideCoverSource()
            val hideDevice = hideDeviceSwitch()
            val config = MediaCardRuntimeConfig.current.notification
            val spec = notificationMediaLayoutSpec(layoutStyle)
            val moveDevice = !hideDevice && (
                    (spec.moveDeviceToAction4 && supportsDeviceSwitchMove()) ||
                            spec.moveDeviceToActionRow
                    )
            val keepAction4 = !hideDevice &&
                    spec.moveDeviceToAction4 &&
                    supportsDeviceSwitchMove()
            if (
                coverStyle != RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_HIDDEN &&
                !hideSource &&
                !hideDevice &&
                !config.hideCustomActions &&
                !config.hideTime &&
                !config.actionAlignLeft &&
                config.actionOrder == RootConstants.NOTIFICATION_MEDIA_ACTION_ORDER_DEFAULT
            ) return
            val normalLayout = normalLayoutField.get(controller) ?: return
            val context = layoutContextField.get(controller) as Context
            val ids = NotificationMediaLayoutResourceIds.from(context)
            val normalAlbumLayout = normalAlbumLayoutField.get(controller)
            NotificationMediaLayoutController.apply(
                bridge = this,
                normalLayout = normalLayout,
                normalAlbumLayout = normalAlbumLayout,
                ids = ids,
                context = context,
                coverStyle = coverStyle,
                hideSource = hideSource,
                hideDevice = hideDevice,
                moveDevice = moveDevice,
                keepAction4 = keepAction4,
                hideCustomActions = config.hideCustomActions,
                hideTime = config.hideTime,
                actionsLeftAligned = config.actionAlignLeft,
                actionsOrder = config.actionOrder
            )
        }

        override fun setVisibility(layout: Any, viewId: Int, visibility: Int) {
            setVisibilityMethod.invoke(layout, viewId, visibility)
        }

        override fun setGoneMargin(layout: Any, viewId: Int, side: Int, margin: Int) {
            setGoneMarginMethod.invoke(layout, viewId, side, margin)
        }

        override fun connect(
            layout: Any,
            startId: Int,
            startSide: Int,
            endId: Int,
            endSide: Int
        ) {
            connectMethod.invoke(layout, startId, startSide, endId, endSide)
        }

        override fun setMargin(layout: Any, viewId: Int, side: Int, margin: Int) {
            setMarginMethod.invoke(layout, viewId, side, margin)
        }

        override fun clear(layout: Any, viewId: Int, side: Int) {
            requireNotNull(clearMethod).invoke(layout, viewId, side)
        }

        override fun constrainWidth(layout: Any, viewId: Int, width: Int) {
            requireNotNull(constrainWidthMethod).invoke(layout, viewId, width)
        }

        override fun constrainHeight(layout: Any, viewId: Int, height: Int) {
            requireNotNull(constrainHeightMethod).invoke(layout, viewId, height)
        }

        companion object {
            fun create(classLoader: ClassLoader): NativeApi {
                val viewControllerClass = classLoader.loadClass(VIEW_CONTROLLER_CLASS)
                val layoutControllerClass = classLoader.loadClass(LAYOUT_CONTROLLER_CLASS)
                val holderClass = classLoader.loadClass(HOLDER_CLASS)
                val mediaDataClass = classLoader.loadClass(MEDIA_DATA_CLASS)
                val semanticActionApi = SemanticActionApi.createOrNull(classLoader)
                val transferUpdateMethod = runCatching {
                    val transferClass = classLoader.loadClass(MEDIA_TRANSFER_MANAGER_CLASS)
                    transferClass.declaredMethods.find { method ->
                        method.name == "updateChip" &&
                                method.parameterCount == 1 &&
                                method.parameterTypes[0] == ImageView::class.java
                    }?.apply { isAccessible = true }
                }.getOrNull()
                val constraintSetClass = classLoader.loadClass(
                    "androidx.constraintlayout.widget.ConstraintSet"
                )

                val attach = viewControllerClass.getDeclaredMethod(
                    "attach",
                    holderClass
                ).apply { isAccessible = true }
                val bind = viewControllerClass.getDeclaredMethod(
                    "bindMediaData",
                    mediaDataClass
                ).apply { isAccessible = true }
                val detach = viewControllerClass.getDeclaredMethod("detach").apply {
                    isAccessible = true
                }
                val updateForegroundColors = viewControllerClass.declaredMethods.find { method ->
                    method.name == "updateForegroundColors" && method.parameterCount == 0
                }?.apply { isAccessible = true }
                val onFullAodStateChanged = viewControllerClass.declaredMethods.find { method ->
                    method.name == "onFullAodStateChanged"
                }?.apply { isAccessible = true }
                val setSeamless = viewControllerClass.getDeclaredMethod(
                    "setSeamless",
                    mediaDataClass
                ).apply { isAccessible = true }
                val loadLayout = layoutControllerClass.getDeclaredMethod("loadLayout\$1").apply {
                    isAccessible = true
                }
                val seekBarField = runCatching {
                    holderClass.getDeclaredField("seekBar").apply { isAccessible = true }
                }.getOrNull()
                val seekBarClass = seekBarField?.type
                val seekBarPaddingOffsetField = runCatching {
                    seekBarClass?.getDeclaredField("mProgressPaddingOffset")?.apply {
                        isAccessible = true
                    }
                }.getOrNull()
                val seekBarTrackPositionField = runCatching {
                    seekBarClass?.getDeclaredField("uTrackPosition")?.apply {
                        isAccessible = true
                    }
                }.getOrNull()
                val seekBarRuntimeShaderField = runCatching {
                    seekBarClass?.getDeclaredField("runtimeShader")?.apply {
                        isAccessible = true
                    }
                }.getOrNull()

                return NativeApi(
                    hookMethods = listOf(
                        attach,
                        bind,
                        detach,
                        setSeamless
                    ) + listOfNotNull(
                        updateForegroundColors,
                        onFullAodStateChanged,
                        semanticActionApi?.hookMethod,
                        semanticActionApi?.commonHookMethod,
                        transferUpdateMethod
                    ) + loadLayout,
                    holderField = viewControllerClass.getDeclaredField("holder").apply {
                        isAccessible = true
                    },
                    controllerMediaDataField = viewControllerClass.getDeclaredField("mediaData")
                        .apply {
                            isAccessible = true
                        },
                    controllerAppIconDrawableField = runCatching {
                        viewControllerClass.getDeclaredField("appIconDrawable").apply {
                            isAccessible = true
                        }
                    }.getOrNull(),
                    playerField = runCatching {
                        holderClass.getDeclaredField("player").apply { isAccessible = true }
                    }.getOrNull(),
                    albumViewField = holderClass.getDeclaredField("albumView").apply {
                        isAccessible = true
                    },
                    albumImageField = holderClass.getDeclaredField("albumImageView").apply {
                        isAccessible = true
                    },
                    action4Field = runCatching {
                        holderClass.getDeclaredField("action4").apply { isAccessible = true }
                    }.getOrNull(),
                    actionButtonFields = (0..4).mapNotNull { index ->
                        runCatching {
                            holderClass.getDeclaredField("action$index").apply {
                                isAccessible = true
                            }
                        }.getOrNull()
                    },
                    actionColorSourceField = runCatching {
                        holderClass.getDeclaredField("action2").apply { isAccessible = true }
                    }.getOrNull(),
                    titleTextField = runCatching {
                        holderClass.getDeclaredField("titleText").apply { isAccessible = true }
                    }.getOrNull(),
                    artistTextField = runCatching {
                        holderClass.getDeclaredField("artistText").apply { isAccessible = true }
                    }.getOrNull(),
                    seamlessContainerField = runCatching {
                        holderClass.getDeclaredField("seamless").apply { isAccessible = true }
                    }.getOrNull(),
                    seamlessIconField = runCatching {
                        holderClass.getDeclaredField("seamlessIcon").apply { isAccessible = true }
                    }.getOrNull(),
                    seekBarField = seekBarField,
                    elapsedTimeViewField = runCatching {
                        holderClass.getDeclaredField("elapsedTimeView").apply {
                            isAccessible = true
                        }
                    }.getOrNull(),
                    totalTimeViewField = runCatching {
                        holderClass.getDeclaredField("totalTimeView").apply {
                            isAccessible = true
                        }
                    }.getOrNull(),
                    seekBarPaddingOffsetField = seekBarPaddingOffsetField,
                    seekBarTrackPositionField = seekBarTrackPositionField,
                    seekBarRuntimeShaderField = seekBarRuntimeShaderField,
                    mediaDataIsPlayingField = mediaDataClass.getDeclaredField("isPlaying").apply {
                        isAccessible = true
                    },
                    mediaDataPackageNameField = runCatching {
                        mediaDataClass.getDeclaredField("packageName").apply {
                            isAccessible = true
                        }
                    }.getOrNull(),
                    mediaDataAppNameField = runCatching {
                        mediaDataClass.getDeclaredField("app").apply {
                            isAccessible = true
                        }
                    }.getOrNull(),
                    mediaDataAppIconField = runCatching {
                        mediaDataClass.getDeclaredField("appIcon").apply {
                            isAccessible = true
                        }
                    }.getOrNull(),
                    mediaDataUserIdField = runCatching {
                        mediaDataClass.getDeclaredField("userId").apply {
                            isAccessible = true
                        }
                    }.getOrNull(),
                    semanticActionApi = semanticActionApi,
                    layoutContextField = layoutControllerClass.getDeclaredField("context").apply {
                        isAccessible = true
                    },
                    normalLayoutField = layoutControllerClass.getDeclaredField("normalLayout")
                        .apply {
                            isAccessible = true
                        },
                    normalAlbumLayoutField = layoutControllerClass.getDeclaredField(
                        "normalAlbumLayout"
                    ).apply { isAccessible = true },
                    setVisibilityMethod = constraintSetClass.getDeclaredMethod(
                        "setVisibility",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    ).apply { isAccessible = true },
                    setGoneMarginMethod = constraintSetClass.getDeclaredMethod(
                        "setGoneMargin",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    ).apply { isAccessible = true },
                    connectMethod = constraintSetClass.getDeclaredMethod(
                        "connect",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    ).apply { isAccessible = true },
                    setMarginMethod = constraintSetClass.getDeclaredMethod(
                        "setMargin",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    ).apply { isAccessible = true },
                    clearMethod = runCatching {
                        constraintSetClass.getDeclaredMethod(
                            "clear",
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType
                        ).apply { isAccessible = true }
                    }.getOrNull(),
                    constrainWidthMethod = runCatching {
                        constraintSetClass.getDeclaredMethod(
                            "constrainWidth",
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType
                        ).apply { isAccessible = true }
                    }.getOrNull(),
                    constrainHeightMethod = runCatching {
                        constraintSetClass.getDeclaredMethod(
                            "constrainHeight",
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType
                        ).apply { isAccessible = true }
                    }.getOrNull()
                )
            }
        }
    }
}
