package com.lidesheng.hyperlyric.root.mediacard.notification.background

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.drawable.TransitionDrawable
import android.widget.ImageView
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.root.mediacard.MediaCardRuntimeConfig
import com.lidesheng.hyperlyric.root.mediacard.notification.NotificationMediaDataIdentity
import com.lidesheng.hyperlyric.root.mediacard.notification.style.NotificationMediaForegroundStyler
import com.lidesheng.hyperlyric.root.utils.HookLogger
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal object NotificationMediaBackgroundController {
    private const val TAG = "NotificationMediaBackgroundController"
    private val states = Collections.synchronizedMap(WeakHashMap<Any, ControllerState>())
    private val unavailableLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val supportedLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val executor: ExecutorService = newExecutor()

    fun isActive(controller: Any): Boolean {
        if (currentStyle() == RootConstants.NOTIFICATION_MEDIA_BACKGROUND_STYLE_DEFAULT) return false
        val classLoader = controller.javaClass.classLoader ?: return false
        return supportedLoaders.contains(classLoader) && resolveRenderer(classLoader) != null
    }

    fun setNativeHooksAvailable(classLoader: ClassLoader, available: Boolean) {
        if (available) supportedLoaders.add(classLoader) else supportedLoaders.remove(classLoader)
    }

    fun onBind(controller: Any, mediaData: Any?) {
        val state = states.getOrPut(controller) { ControllerState() }
        if (!isActive(controller)) {
            state.token = null
            state.customApplied = false
            state.renderPending = false
            NotificationMediaForegroundStyler.forget(controller)
            return
        }
        mediaData ?: return
        val context = readField(controller, "context") as? Context ?: return
        val holder = readField(controller, "holder") ?: return
        val mediaBg = readField(holder, "mediaBg") as? ImageView ?: return
        state.lastMediaData = mediaData
        if (state.mediaBg !== mediaBg || (!state.customApplied && !state.renderPending)) {
            captureNativeBackground(state, mediaBg)
        }
        val packageName = readField(mediaData, "packageName") as? String ?: return
        val artwork = readField(mediaData, "artwork") as? Icon
        val width = mediaBg.measuredWidth.takeIf { it > 0 }
            ?: mediaBg.layoutParams?.width?.takeIf { it > 0 }
            ?: state.lastWidth
        val height = mediaBg.measuredHeight.takeIf { it > 0 }
            ?: mediaBg.layoutParams?.height?.takeIf { it > 0 }
            ?: state.lastHeight
        if (width == null || height == null) {
            scheduleMeasuredBind(controller, state, mediaBg)
            return
        }
        state.lastWidth = width
        state.lastHeight = height
        val style = currentStyle()
        val blurAmount = currentBlurAmount()
        val autoInvert = currentAutoInvert()
        val softCoverTone = currentSoftCoverTone()
        val nightMode = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK
        val artworkUpdated = readField(controller, "isArtWorkUpdate") == true
        val mediaIdentity = NotificationMediaDataIdentity.of(mediaData)
        val token =
            "$style:$blurAmount:$autoInvert:$softCoverTone:$nightMode:" +
                "$packageName:$mediaIdentity:$width:$height"
        if (state.token == token && (state.customApplied || state.renderPending) && !artworkUpdated) {
            return
        }
        state.token = token
        state.renderPending = true
        val request = state.request.incrementAndGet()
        val renderer = resolveRenderer(controller.javaClass.classLoader) ?: run {
            state.renderPending = false
            return
        }

        executor.execute {
            val rendered = runCatching {
                renderer.render(
                    context, artwork, packageName, style, blurAmount,
                    autoInvert, softCoverTone, width, height
                )
            }.onFailure { error ->
                HookLogger.e(TAG, "渲染通知中心媒体背景失败", error)
            }.getOrNull()
            if (rendered == null) {
                mediaBg.post {
                    if (states[controller] === state && state.request.get() == request) {
                        state.renderPending = false
                    }
                }
                return@execute
            }
            mediaBg.post {
                val current = states[controller]
                if (
                    current !== state || current.request.get() != request ||
                    currentStyle() != style || !isActive(controller)
                ) {
                    rendered.bitmap.recycle()
                    return@post
                }
                if (
                    state.customApplied && state.appliedToken == token &&
                    state.artworkFingerprint == rendered.artworkFingerprint
                ) {
                    rendered.bitmap.recycle()
                    state.renderPending = false
                    return@post
                }
                applyBackground(mediaBg, rendered.bitmap)
                NotificationMediaForegroundStyler.apply(controller, holder, rendered.colors)
                state.customApplied = true
                state.appliedToken = token
                state.artworkFingerprint = rendered.artworkFingerprint
                state.renderPending = false
            }
        }
    }

    /**
     * Rebinds the last media payload after SystemUI reports a UI-mode change.
     * The night-mode value participates in the render token, so unchanged
     * cards stay cheap while theme-dependent backgrounds invalidate correctly.
     */
    fun onUiModeChanged(controller: Any) {
        if (!isActive(controller)) return
        states[controller]?.lastMediaData?.let { mediaData ->
            onBind(controller, mediaData)
        }
    }

    fun onDetach(controller: Any) {
        NotificationMediaForegroundStyler.clear(controller)
        states.remove(controller)?.let { state ->
            state.request.incrementAndGet()
            restoreMediaBackground(state)
        }
    }

    /**
     * SystemUI may bind before the holder has been measured.  Do not replace the
     * native image with an empty custom layer in that frame; retry the same bind
     * once the holder has a real viewport, mirroring XiaomiHelper's holder-size
     * fallback behavior.
     */
    fun shouldSuppressNativeBackground(controller: Any): Boolean {
        if (!isActive(controller)) return false
        val state = states[controller] ?: return false
        return state.customApplied || state.renderPending
    }

    private fun scheduleMeasuredBind(
        controller: Any,
        state: ControllerState,
        mediaBg: ImageView
    ) {
        if (state.layoutRetryScheduled) return
        state.layoutRetryScheduled = true
        mediaBg.addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: android.view.View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int
            ) {
                if (right <= left || bottom <= top) return
                view.removeOnLayoutChangeListener(this)
                state.layoutRetryScheduled = false
                val pending = state.lastMediaData ?: return
                if (states[controller] === state) onBind(controller, pending)
            }
        })
    }

    private fun applyBackground(mediaBg: ImageView, bitmap: Bitmap) {
        mediaBg.setPadding(0, 0, 0, 0)
        mediaBg.clipToOutline = true
        val next = BitmapDrawable(mediaBg.resources, bitmap)
        if (!currentColorAnimation() || !mediaBg.isShown || !mediaBg.isAttachedToWindow) {
            mediaBg.setImageDrawable(next)
            return
        }
        val previous = mediaBg.drawable
        if (previous == null) {
            mediaBg.setImageDrawable(next)
            return
        }
        val transition = TransitionDrawable(arrayOf(previous, next)).apply {
            isCrossFadeEnabled = true
        }
        mediaBg.setImageDrawable(transition)
        transition.startTransition(333)
        mediaBg.postDelayed({
            if (mediaBg.drawable === transition) {
                mediaBg.setImageDrawable(next)
            }
        }, 350L)
    }

    private fun captureNativeBackground(state: ControllerState, mediaBg: ImageView) {
        state.mediaBg = mediaBg
        state.originalDrawable = mediaBg.drawable
        state.originalScaleType = mediaBg.scaleType
        state.originalClipToOutline = mediaBg.clipToOutline
        state.originalPadding = intArrayOf(
            mediaBg.paddingLeft,
            mediaBg.paddingTop,
            mediaBg.paddingRight,
            mediaBg.paddingBottom
        )
    }

    private fun restoreMediaBackground(state: ControllerState) {
        val mediaBg = state.mediaBg ?: return
        mediaBg.setImageDrawable(state.originalDrawable)
        state.originalScaleType?.let { mediaBg.scaleType = it }
        val padding = state.originalPadding
        mediaBg.setPadding(padding[0], padding[1], padding[2], padding[3])
        mediaBg.clipToOutline = state.originalClipToOutline
        mediaBg.invalidate()
    }

    private fun resolveRenderer(classLoader: ClassLoader?): NotificationMediaBackgroundRenderer? {
        classLoader ?: return null
        if (unavailableLoaders.contains(classLoader)) return null
        return runCatching { MediaBackgroundRendererPool.get(classLoader) }
            .onFailure { error ->
                unavailableLoaders.add(classLoader)
                HookLogger.w(TAG, "通知中心 Monet 背景接口不可用: reason=${error.message}")
            }
            .getOrNull()
    }

    private fun currentStyle(): Int {
        if (!MediaCardRuntimeConfig.current.enabled) {
            return RootConstants.NOTIFICATION_MEDIA_BACKGROUND_STYLE_DEFAULT
        }
        return MediaCardRuntimeConfig.current.notification.backgroundStyle
    }

    private fun currentSoftCoverTone(): Int =
        MediaCardRuntimeConfig.current.notification.softCoverTone

    private fun currentBlurAmount(): Int =
        MediaCardRuntimeConfig.current.notification.backgroundBlur

    private fun currentAutoInvert(): Boolean =
        MediaCardRuntimeConfig.current.notification.backgroundAutoInvert

    private fun currentColorAnimation(): Boolean =
        MediaCardRuntimeConfig.current.notification.backgroundColorAnimation

    private fun readField(receiver: Any, name: String): Any? {
        return findField(receiver.javaClass, name)?.let { field ->
            runCatching { field.get(receiver) }.getOrNull()
        }
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let { field ->
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        return null
    }

    private fun newExecutor(): ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HyperLyric-MediaBackground").apply { isDaemon = true }
    }

    private data class ControllerState(
        var token: String? = null,
        var appliedToken: String? = null,
        var artworkFingerprint: Long? = null,
        var customApplied: Boolean = false,
        var renderPending: Boolean = false,
        var mediaBg: ImageView? = null,
        var originalDrawable: Drawable? = null,
        var originalScaleType: ImageView.ScaleType? = null,
        var originalPadding: IntArray = intArrayOf(0, 0, 0, 0),
        var originalClipToOutline: Boolean = false,
        var lastMediaData: Any? = null,
        var lastWidth: Int? = null,
        var lastHeight: Int? = null,
        var layoutRetryScheduled: Boolean = false,
        val request: AtomicInteger = AtomicInteger()
    )

}
