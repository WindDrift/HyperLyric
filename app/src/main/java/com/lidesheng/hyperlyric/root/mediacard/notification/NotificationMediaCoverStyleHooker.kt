package com.lidesheng.hyperlyric.root.mediacard.notification

import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.root.mediacard.MediaCardRuntimeConfig
import com.lidesheng.hyperlyric.root.mediacard.MediaCoverRotationController
import com.lidesheng.hyperlyric.root.mediacard.notification.aod.NotificationMediaFullAodAnimatedHeightHook
import com.lidesheng.hyperlyric.root.mediacard.notification.aod.NotificationMediaFullAodHook
import com.lidesheng.hyperlyric.root.mediacard.notification.host.NotificationMediaHostApi
import com.lidesheng.hyperlyric.root.mediacard.notification.host.NotificationMediaHostClasses
import com.lidesheng.hyperlyric.root.mediacard.notification.layout.NotificationMediaLayoutController
import com.lidesheng.hyperlyric.root.mediacard.notification.layout.NotificationMediaLayoutResourceIds
import com.lidesheng.hyperlyric.root.mediacard.notification.style.NotificationMediaCoverStyler
import com.lidesheng.hyperlyric.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

object NotificationMediaCoverStyleHooker {
    private const val TAG = "NotificationMediaCoverStyleHooker"

    private lateinit var runtimeConfig: MediaCardRuntimeConfig.Snapshot

    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val nativeApis =
        Collections.synchronizedMap(WeakHashMap<ClassLoader, NotificationMediaHostApi>())

    fun hook(xposedModule: XposedModule, classLoader: ClassLoader) {
        if (!hookedClassLoaders.add(classLoader)) return

        if (!::runtimeConfig.isInitialized) {
            runtimeConfig = MediaCardRuntimeConfig.current
        }

        val api = resolveApi(classLoader) ?: run {
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "跳过通知中心媒体卡片 Hook: reason=native_api_unavailable")
            return
        }
        val methods = api.hookMethods.filter(::isHookRequired)
        val handles = mutableListOf<HookHandle>()
        methods.forEach { method ->
            runCatching {
                xposedModule.deoptimize(method)
                val hooker = hookerFor(method)
                    ?: error("No hooker for ${method.declaringClass.name}.${method.name}")
                handles += xposedModule.hook(method).intercept(hooker)
            }.onFailure { error ->
                HookLogger.e(
                    TAG,
                    "安装通知中心媒体卡片 Hook 失败: " +
                            "method=${method.declaringClass.simpleName}.${method.name}",
                    error
                )
            }
        }

        if (handles.size != methods.size) {
            handles.forEach(HookHandle::unhook)
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "通知中心媒体卡片 Hook 安装不完整")
        } else {
            HookLogger.i(TAG, "通知中心媒体卡片 Hook 已初始化: methods=${handles.size}")
        }
    }

    fun isTargetMethod(method: Method): Boolean {
        return when (method.declaringClass.name) {
            NotificationMediaHostClasses.VIEW_CONTROLLER -> when (method.name) {
                "attach", "bindMediaData", "setSeamless" -> method.parameterCount == 1
                "detach" -> method.parameterCount == 0
                "onFullAodStateChanged" ->
                    method.parameterCount == 1 &&
                            method.parameterTypes[0] == Boolean::class.javaPrimitiveType
                else -> false
            }

            NotificationMediaHostClasses.ACTION_BUTTON_UTILS -> when (method.name) {
                "setSemanticButton" -> method.parameterCount == 2
                "bindButtonsCommon" -> method.parameterCount == 3
                else -> false
            }

            NotificationMediaHostClasses.MEDIA_HEADER_VIEW ->
                method.name == "setAnimateHeight" &&
                        method.parameterCount == 1 &&
                        method.parameterTypes[0] == Int::class.javaPrimitiveType

            NotificationMediaHostClasses.LAYOUT_CONTROLLER ->
                method.name == "loadLayout\$1" && method.parameterCount == 0

            else -> false
        }
    }

    fun hookerFor(executable: Executable): Hooker? {
        val method = executable as? Method ?: return null
        if (!isTargetMethod(method)) return null
        return when (method.declaringClass.name) {
            NotificationMediaHostClasses.VIEW_CONTROLLER -> when (method.name) {
                "onFullAodStateChanged" -> NotificationMediaFullAodHook(
                    keepExpanded = ::shouldKeepExpandedInFullAod,
                    onApplied = { controller -> applyStyle(controller, null) },
                    onFailure = { error ->
                        HookLogger.e(TAG, "应用通知中心 Full AOD 媒体样式失败", error)
                    }
                )

                else -> ControllerHook(method.name)
            }

            NotificationMediaHostClasses.ACTION_BUTTON_UTILS -> ActionButtonHook()
            NotificationMediaHostClasses.MEDIA_HEADER_VIEW ->
                NotificationMediaFullAodAnimatedHeightHook(
                    keepExpanded = ::shouldKeepExpandedInFullAod
                )
            NotificationMediaHostClasses.LAYOUT_CONTROLLER -> LayoutLoadHook()
            else -> null
        }
    }

    private fun isHookRequired(method: Method): Boolean {
        if (!runtimeConfig.enabled) return false
        val config = runtimeConfig.notification
        return when (method.declaringClass.name) {
            NotificationMediaHostClasses.VIEW_CONTROLLER -> when (method.name) {
                "attach", "bindMediaData" ->
                    config.coverStyle != RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_DEFAULT ||
                            config.layoutStyle ==
                            RootConstants.NOTIFICATION_MEDIA_LAYOUT_STYLE_IOS ||
                            config.hideTime ||
                            config.hideCustomActions
                "detach" ->
                    config.coverStyle ==
                            RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_ROTATING_CIRCLE
                "setSeamless" -> config.hideDeviceSwitch
                "onFullAodStateChanged" -> shouldKeepExpandedInFullAod()
                else -> false
            }

            NotificationMediaHostClasses.ACTION_BUTTON_UTILS -> config.hideCustomActions
            NotificationMediaHostClasses.MEDIA_HEADER_VIEW -> shouldKeepExpandedInFullAod()
            NotificationMediaHostClasses.LAYOUT_CONTROLLER -> needsConstraintLayout(config)
            else -> false
        }
    }

    private fun needsConstraintLayout(config: MediaCardRuntimeConfig.Notification): Boolean {
        return config.layoutStyle != RootConstants.NOTIFICATION_MEDIA_LAYOUT_STYLE_SYSTEM ||
                config.coverStyle == RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_HIDDEN ||
                config.hideCoverSource ||
                config.hideDeviceSwitch ||
                config.hideCustomActions ||
                config.hideTime ||
                config.actionAlignLeft ||
                config.actionOrder != RootConstants.NOTIFICATION_MEDIA_ACTION_ORDER_DEFAULT
    }

    private class ControllerHook(private val methodName: String) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val controller = chain.thisObject ?: return chain.proceed()
            if (
                methodName == "setSeamless" &&
                runtimeConfig.notification.hideDeviceSwitch
            ) {
                return null
            }
            if (methodName == "detach") {
                cleanupStyle(controller)
                return chain.proceed()
            }

            val result = chain.proceed()
            if (methodName == "attach" || methodName == "bindMediaData") {
                runCatching {
                    val mediaData = if (methodName == "bindMediaData") {
                        chain.args.firstOrNull()
                    } else {
                        null
                    }
                    applyStyle(controller, mediaData)
                }.onFailure { HookLogger.e(TAG, "应用通知中心媒体卡片样式失败", it) }
            }
            return result
        }
    }

    private class ActionButtonHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            (chain.args.firstOrNull() as? ImageButton)?.let(::applyCustomActionVisibility)
            return result
        }
    }

    private class LayoutLoadHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val controller = chain.thisObject ?: return result
            runCatching {
                resolveApi(controller.javaClass.classLoader)?.let { api ->
                    applyLoadedLayout(api, controller)
                }
            }.onFailure { HookLogger.e(TAG, "应用通知中心媒体卡片约束失败", it) }
            return result
        }
    }

    private fun applyStyle(controller: Any, mediaData: Any?) {
        val api = resolveApi(controller.javaClass.classLoader) ?: return
        val holder = api.getHolder(controller) ?: return
        val albumView = api.getAlbumView(holder)
        val albumImage = api.getAlbumImage(holder)
        val config = runtimeConfig.notification

        if (config.layoutStyle == RootConstants.NOTIFICATION_MEDIA_LAYOUT_STYLE_IOS) {
            api.getSeekBar(holder)?.let(api::removeSeekBarTrackInset)
        }
        if (config.hideTime) {
            api.getElapsedTimeView(holder)?.visibility = View.GONE
            api.getTotalTimeView(holder)?.visibility = View.GONE
        }
        if (config.hideCustomActions) {
            api.getActionButtons(holder).forEach(::applyCustomActionVisibility)
        }

        when (config.coverStyle) {
            RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_CIRCLE -> {
                MediaCoverRotationController.detach(albumImage)
                NotificationMediaCoverStyler.applyCircle(albumView, albumImage)
            }

            RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_ROTATING_CIRCLE -> {
                NotificationMediaCoverStyler.applyCircle(albumView, albumImage)
                val currentMediaData = mediaData ?: api.getMediaData(controller)
                MediaCoverRotationController.attach(
                    albumImage,
                    api.isPlaying(currentMediaData)
                )
            }

            RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_HIDDEN -> {
                MediaCoverRotationController.detach(albumImage)
                albumView.visibility = View.GONE
            }
        }
    }

    private fun cleanupStyle(controller: Any) {
        val api = resolveApi(controller.javaClass.classLoader) ?: return
        val holder = api.getHolder(controller) ?: return
        MediaCoverRotationController.detach(api.getAlbumImage(holder))
    }

    private fun applyCustomActionVisibility(button: ImageButton) {
        if (button.isCustomActionSlot()) button.visibility = View.INVISIBLE
    }

    private fun ImageButton.isCustomActionSlot(): Boolean {
        return runCatching {
            val name = resources.getResourceEntryName(id)
            name == "action0" || name == "action4"
        }.getOrDefault(false)
    }

    private fun applyLoadedLayout(api: NotificationMediaHostApi, controller: Any) {
        val config = runtimeConfig.notification
        val normalLayout = api.getNormalLayout(controller) ?: return
        val context = api.getLayoutContext(controller)
        NotificationMediaLayoutController.apply(
            bridge = api,
            normalLayout = normalLayout,
            normalAlbumLayout = api.getNormalAlbumLayout(controller),
            ids = NotificationMediaLayoutResourceIds.from(context),
            context = context,
            layoutStyle = config.layoutStyle,
            coverStyle = config.coverStyle,
            hideSource = config.hideCoverSource,
            hideDevice = config.hideDeviceSwitch,
            hideCustomActions = config.hideCustomActions,
            hideTime = config.hideTime,
            actionsLeftAligned = config.actionAlignLeft,
            actionsOrder = config.actionOrder
        )
    }

    private fun shouldKeepExpandedInFullAod(): Boolean {
        return runtimeConfig.enabled &&
                (
                    runtimeConfig.notification.layoutStyle ==
                        RootConstants.NOTIFICATION_MEDIA_LAYOUT_STYLE_IOS ||
                        runtimeConfig.alwaysOnDisplay.disableMediaCardCollapsing
                )
    }

    private fun resolveApi(classLoader: ClassLoader?): NotificationMediaHostApi? {
        classLoader ?: return null
        nativeApis[classLoader]?.let { return it }
        return runCatching { NotificationMediaHostApi.create(classLoader) }
            .onSuccess { nativeApis[classLoader] = it }
            .onFailure { HookLogger.w(TAG, "通知中心媒体卡片接口不可用: reason=${it.message}") }
            .getOrNull()
    }
}
