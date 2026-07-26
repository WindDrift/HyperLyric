package com.lidesheng.hyperlyric.root.mediacard.island.background

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.drawable.TransitionDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.root.mediacard.MediaCardRuntimeConfig
import com.lidesheng.hyperlyric.root.mediacard.notification.background.MediaBackgroundRendererPool
import com.lidesheng.hyperlyric.root.mediacard.notification.background.NotificationMediaColorConfig
import com.lidesheng.hyperlyric.root.utils.HookLogger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal data class IslandExpandedBackgroundTarget(
    val owner: View,
    val expandedView: View,
    val measurementView: View = expandedView,
    val customBackgroundView: View = expandedView,
    val viewportWidth: Int? = null,
    val viewportHeight: Int? = null,
    val nativeBackgroundViews: List<View> = listOf(expandedView),
    val nativeFlowViews: List<View> = emptyList()
)

internal data class IslandExpandedMediaBackgroundHost(
    val target: IslandExpandedBackgroundTarget,
    val holder: Any
)

internal interface IslandExpandedMediaBackgroundApi {
    fun getContext(binder: Any): Context
    fun getMediaPackageName(mediaData: Any): String?
    fun getMediaArtwork(mediaData: Any): Icon?
    fun isArtworkUpdated(binder: Any): Boolean?
    fun supportsCustomBackground(): Boolean
    fun getBackgroundRetryView(binder: Any): View?
    fun getBackgroundHosts(binder: Any): List<IslandExpandedMediaBackgroundHost>
    fun prepareCustomBackground(target: IslandExpandedBackgroundTarget)
    fun restoreNativeBackground(target: IslandExpandedBackgroundTarget)
    fun applyCustomForeground(holder: Any, colors: NotificationMediaColorConfig)
}

internal object IslandExpandedMediaBackgroundController {
    private const val TAG = "IslandExpandedMediaBackgroundController"
    private val states = Collections.synchronizedMap(WeakHashMap<Any, BinderState>())
    private val foregroundColors = Collections.synchronizedMap(
        WeakHashMap<Any, NotificationMediaColorConfig>()
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = newExecutor()

    fun isActive(): Boolean {
        return currentStyle() != RootConstants.ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE_DEFAULT
    }

    fun hasAppliedBackgroundForNativeFlow(view: View): Boolean {
        return synchronized(states) {
            states.values.any { binderState ->
                binderState.targets.values.any { targetState ->
                    targetState.customApplied &&
                            targetState.background != null &&
                            targetState.target.nativeFlowViews.any { it === view }
                }
            }
        }
    }

    fun attach(binder: Any, api: IslandExpandedMediaBackgroundApi) {
        if (!isActive() || !api.supportsCustomBackground()) {
            restore(binder)
            return
        }
        val hosts = api.getBackgroundHosts(binder)
        if (hosts.isEmpty()) return
        val binderState = states.getOrPut(binder) { BinderState(api) }
        binderState.api = api
        resolveMeasuredSize(hosts)?.let { size ->
            binderState.lastWidth = size.width
            binderState.lastHeight = size.height
        }
        val pendingMediaData = binderState.pendingMediaData
        val pendingApi = binderState.pendingApi
        if (pendingMediaData != null && pendingApi != null) {
            cancelBindRetry(binderState)
            bindInternal(
                binder = binder,
                mediaData = pendingMediaData,
                api = pendingApi,
                allowRetry = false
            )
        }
    }

    fun bind(
        binder: Any,
        mediaData: Any,
        api: IslandExpandedMediaBackgroundApi
    ) = bindInternal(
        binder = binder,
        mediaData = mediaData,
        api = api,
        allowRetry = true
    )

    private fun bindInternal(
        binder: Any,
        mediaData: Any,
        api: IslandExpandedMediaBackgroundApi,
        allowRetry: Boolean
    ) {
        if (!isActive() || !api.supportsCustomBackground()) {
            restore(binder)
            return
        }
        val binderState = states.getOrPut(binder) { BinderState(api) }
        binderState.api = api
        val context = api.getContext(binder)
        val packageName = api.getMediaPackageName(mediaData) ?: run {
            restore(binder)
            return
        }
        val artwork = api.getMediaArtwork(mediaData)
        val artworkUpdated = api.isArtworkUpdated(binder)
        val hosts = api.getBackgroundHosts(binder)
        if (hosts.isEmpty()) {
            restoreTargets(binderState, api)
            if (allowRetry) scheduleBindRetry(binder, binderState, mediaData, api)
            return
        }

        val activeViews = hosts.mapTo(HashSet()) { it.target.customBackgroundView }
        binderState.targets.entries.removeAll { (view, state) ->
            if (view in activeViews) return@removeAll false
            state.request.incrementAndGet()
            restoreTarget(state, api)
            true
        }

        val renderSize = resolveRenderSize(
            hosts = hosts,
            binderState = binderState
        ) ?: run {
            if (allowRetry) scheduleBindRetry(binder, binderState, mediaData, api)
            return
        }
        cancelBindRetry(binderState)
        binderState.lastWidth = renderSize.width
        binderState.lastHeight = renderSize.height

        val style = currentStyle()
        val blurAmount = currentBlurAmount()
        val autoInvert = currentAutoInvert()
        val softCoverTone = currentSoftCoverTone()
        val token =
            "$style:$blurAmount:$autoInvert:$softCoverTone:$packageName:" +
                    "${renderSize.width}:${renderSize.height}"
        val preparedTargets = hosts
            .groupBy { it.target.customBackgroundView }
            .values
            .mapNotNull { groupedHosts ->
                prepareTarget(
                    binderState = binderState,
                    hosts = groupedHosts,
                    artworkUpdated = artworkUpdated,
                    renderSize = renderSize,
                    token = token,
                    api = api
                )
            }
        if (preparedTargets.isEmpty()) return
        renderTargets(
            binder = binder,
            binderState = binderState,
            preparedTargets = preparedTargets,
            context = context,
            packageName = packageName,
            artwork = artwork,
            style = style,
            blurAmount = blurAmount,
            autoInvert = autoInvert,
            softCoverTone = softCoverTone,
            renderSize = renderSize,
            token = token,
            api = api
        )
    }

    fun restore(binder: Any) {
        val state = states.remove(binder) ?: return
        cancelBindRetry(state)
        restoreTargets(state, state.api)
    }

    private fun restoreTargets(
        binderState: BinderState,
        api: IslandExpandedMediaBackgroundApi
    ) {
        binderState.targets.values.forEach { target ->
            target.request.incrementAndGet()
            restoreTarget(target, api)
        }
        binderState.targets.clear()
    }

    fun applyForeground(
        binder: Any,
        api: IslandExpandedMediaBackgroundApi,
        force: Boolean = false
    ) {
        states[binder]?.targets?.values?.forEach { target ->
            val colors = target.colors ?: return@forEach
            target.holders.forEach { holder -> applyForeground(holder, colors, api, force) }
        }
    }

    fun applyForeground(
        binder: Any,
        holder: Any,
        api: IslandExpandedMediaBackgroundApi,
        force: Boolean = false
    ): Boolean {
        val colors = states[binder]
            ?.targets
            ?.values
            ?.firstOrNull { target ->
                target.holders.any { current -> current === holder }
            }
            ?.colors
            ?: return false
        applyForeground(holder, colors, api, force)
        return true
    }

    private fun prepareTarget(
        binderState: BinderState,
        hosts: List<IslandExpandedMediaBackgroundHost>,
        artworkUpdated: Boolean?,
        renderSize: RenderSize,
        token: String,
        api: IslandExpandedMediaBackgroundApi
    ): PreparedTarget? {
        val host = hosts.first()
        val targetState = binderState.targets.getOrPut(host.target.customBackgroundView) {
            TargetState(
                target = host.target,
                holders = hosts.map { it.holder }
            )
        }
        targetState.target = host.target
        val nextHolders = hosts.map { it.holder }
        targetState.holders
            .filter { previous -> nextHolders.none { current -> current === previous } }
            .forEach(foregroundColors::remove)
        targetState.holders = nextHolders
        val width = renderSize.width
        val height = renderSize.height
        targetState.lastWidth = width
        targetState.lastHeight = height

        if (
            targetState.token == token &&
            (targetState.customApplied || targetState.renderPending) &&
            artworkUpdated == false
        ) {
            if (targetState.customApplied) {
                ensureBackgroundAttached(targetState, api)
                targetState.colors?.let { colors ->
                    hosts.forEach { applyForeground(it.holder, colors, api) }
                }
            }
            return null
        }

        targetState.token = token
        targetState.renderPending = true
        val request = targetState.request.incrementAndGet()
        return PreparedTarget(targetState, hosts, request)
    }

    private fun renderTargets(
        binder: Any,
        binderState: BinderState,
        preparedTargets: List<PreparedTarget>,
        context: Context,
        packageName: String,
        artwork: Icon?,
        style: Int,
        blurAmount: Int,
        autoInvert: Boolean,
        softCoverTone: Int,
        renderSize: RenderSize,
        token: String,
        api: IslandExpandedMediaBackgroundApi
    ) {
        val classLoader = binder.javaClass.classLoader ?: run {
            preparedTargets.forEach { it.state.renderPending = false }
            return
        }

        executor.execute {
            if (
                states[binder] !== binderState ||
                preparedTargets.none { it.state.request.get() == it.request }
            ) {
                return@execute
            }
            val renderer = runCatching { MediaBackgroundRendererPool.get(classLoader) }
                .onFailure { error ->
                    HookLogger.e(TAG, "初始化展开态媒体背景渲染器失败", error)
                }
                .getOrNull()
            if (renderer == null) {
                clearPendingOnMain(binder, binderState, preparedTargets)
                return@execute
            }
            val rendered = runCatching {
                renderer.render(
                    context,
                    artwork,
                    packageName,
                    style,
                    blurAmount,
                    autoInvert,
                    softCoverTone,
                    renderSize.width,
                    renderSize.height
                )
            }.onFailure { error ->
                HookLogger.e(TAG, "渲染展开态媒体背景失败", error)
            }.getOrNull()
            if (rendered == null) {
                clearPendingOnMain(binder, binderState, preparedTargets)
                return@execute
            }
            mainHandler.post {
                if (
                    states[binder] !== binderState ||
                    currentStyle() != style ||
                    !isActive() ||
                    !api.supportsCustomBackground()
                ) {
                    rendered.bitmap.recycle()
                    if (states[binder] === binderState && !api.supportsCustomBackground()) {
                        restore(binder)
                    }
                    return@post
                }
                val validTargets = preparedTargets.filter { prepared ->
                    prepared.state.request.get() == prepared.request
                }
                if (validTargets.isEmpty()) {
                    rendered.bitmap.recycle()
                    return@post
                }

                var bitmapAttached = false
                validTargets.forEach { prepared ->
                    val targetState = prepared.state
                    val alreadyApplied =
                        targetState.customApplied &&
                                targetState.appliedToken == token &&
                                targetState.artworkFingerprint == rendered.artworkFingerprint
                    if (alreadyApplied) {
                        ensureBackgroundAttached(targetState, api)
                    } else {
                        setBackground(
                            targetState,
                            rendered.bitmap,
                            rendered.colors.backgroundEnd,
                            currentColorAnimation() && targetState.appliedStyle == style
                        )
                        api.prepareCustomBackground(targetState.target)
                        targetState.customApplied = true
                        targetState.appliedStyle = style
                        targetState.appliedToken = token
                        targetState.artworkFingerprint = rendered.artworkFingerprint
                        bitmapAttached = true
                    }
                    prepared.hosts.forEach { host ->
                        applyForeground(host.holder, rendered.colors, api)
                    }
                    targetState.colors = rendered.colors
                    targetState.renderPending = false
                }
                if (!bitmapAttached) rendered.bitmap.recycle()
            }
        }
    }

    private fun clearPendingOnMain(
        binder: Any,
        binderState: BinderState,
        preparedTargets: List<PreparedTarget>
    ) {
        mainHandler.post {
            if (states[binder] !== binderState) return@post
            preparedTargets.forEach { prepared ->
                if (prepared.state.request.get() == prepared.request) {
                    prepared.state.renderPending = false
                }
            }
        }
    }

    private fun scheduleBindRetry(
        binder: Any,
        binderState: BinderState,
        mediaData: Any,
        api: IslandExpandedMediaBackgroundApi
    ) {
        binderState.pendingMediaData = mediaData
        binderState.pendingApi = api
        if (binderState.retryScheduled) return
        val retryView = api.getBackgroundRetryView(binder) ?: return
        binderState.retryScheduled = true
        val request = binderState.retryRequest.incrementAndGet()
        fun retry() {
            if (
                states[binder] !== binderState ||
                binderState.retryRequest.get() != request
            ) {
                return
            }
            val latestMediaData = binderState.pendingMediaData ?: return
            val latestApi = binderState.pendingApi ?: return
            cancelBindRetry(binderState)
            bindInternal(
                binder = binder,
                mediaData = latestMediaData,
                api = latestApi,
                allowRetry = false
            )
        }

        val layoutListener = View.OnLayoutChangeListener { _,
                                                           left,
                                                           top,
                                                           right,
                                                           bottom,
                                                           _,
                                                           _,
                                                           _,
                                                           _ ->
            if (right - left > 1 && bottom - top > 1) retry()
        }
        binderState.retryView = retryView
        binderState.retryLayoutListener = layoutListener
        retryView.addOnLayoutChangeListener(layoutListener)
        if (retryView.width > 1 && retryView.height > 1) {
            mainHandler.post(::retry)
        }
    }

    private fun cancelBindRetry(binderState: BinderState) {
        binderState.retryScheduled = false
        binderState.retryRequest.incrementAndGet()
        val retryView = binderState.retryView
        val layoutListener = binderState.retryLayoutListener
        if (retryView != null && layoutListener != null) {
            retryView.removeOnLayoutChangeListener(layoutListener)
        }
        binderState.retryView = null
        binderState.retryLayoutListener = null
        binderState.pendingMediaData = null
        binderState.pendingApi = null
    }

    private fun resolveRenderSize(
        hosts: List<IslandExpandedMediaBackgroundHost>,
        binderState: BinderState
    ): RenderSize? {
        resolveMeasuredSize(hosts)?.let { return it }
        return positiveSize(binderState.lastWidth, binderState.lastHeight)
    }

    private fun resolveMeasuredSize(
        hosts: List<IslandExpandedMediaBackgroundHost>
    ): RenderSize? {
        hosts.firstNotNullOfOrNull { host ->
            positiveSize(host.target.viewportWidth, host.target.viewportHeight)
        }?.let { return it }
        return hosts.firstNotNullOfOrNull { host ->
            val view = host.target.measurementView
            val width = view.width.takeIf { it > 0 }
                ?: view.measuredWidth.takeIf { it > 0 }
                ?: view.layoutParams?.width?.takeIf { it > 0 }
            val height = view.height.takeIf { it > 0 }
                ?: view.measuredHeight.takeIf { it > 0 }
                ?: view.layoutParams?.height?.takeIf { it > 0 }
            positiveSize(width, height)
        }
    }

    private fun positiveSize(width: Int?, height: Int?): RenderSize? {
        return if (width != null && width > 1 && height != null && height > 1) {
            RenderSize(width, height)
        } else {
            null
        }
    }

    private fun restoreTarget(
        state: TargetState,
        api: IslandExpandedMediaBackgroundApi
    ) {
        state.holders.forEach(foregroundColors::remove)
        val hadCustomBackground = state.customApplied || state.background != null
        state.customApplied = false
        if (hadCustomBackground) api.restoreNativeBackground(state.target)
        state.appliedStyle = null
        state.renderPending = false
        state.appliedToken = null
        state.artworkFingerprint = null
        state.colors = null
        state.background = null
        state.transitionBackground = null
    }

    private fun ensureBackgroundAttached(
        state: TargetState,
        api: IslandExpandedMediaBackgroundApi
    ) {
        val background = state.background ?: return
        val view = state.target.customBackgroundView
        val current = view.customBackgroundDrawable()
        if (current === background || current === state.transitionBackground) return
        view.setCustomBackgroundDrawable(background)
        api.prepareCustomBackground(state.target)
        state.transitionBackground = null
    }

    private fun setBackground(
        state: TargetState,
        bitmap: Bitmap,
        fallbackColor: Int,
        animate: Boolean
    ) {
        val view = state.target.customBackgroundView
        val width = view.width.coerceAtLeast(1)
        val height = view.height.coerceAtLeast(1)
        val next = FixedViewportBitmapDrawable(
            bitmap,
            state.lastWidth.takeIf { it > 0 }
                ?: width,
            state.lastHeight.takeIf { it > 0 }
                ?: height,
            fallbackColor
        ).apply {
            setBounds(0, 0, width, height)
        }
        state.background = next
        if (!animate || !view.isShown || !view.isAttachedToWindow) {
            view.setCustomBackgroundDrawable(next)
            state.transitionBackground = null
            view.invalidate()
            return
        }
        val previous = view.customBackgroundDrawable()
        if (previous == null) {
            view.setCustomBackgroundDrawable(next)
            state.transitionBackground = null
            return
        }
        val transition = TransitionDrawable(arrayOf(previous, next)).apply {
            isCrossFadeEnabled = true
            setBounds(0, 0, width, height)
        }
        state.transitionBackground = transition
        view.setCustomBackgroundDrawable(transition)
        transition.startTransition(333)
        view.postDelayed({
            if (view.customBackgroundDrawable() === transition) {
                next.setBounds(0, 0, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
                view.setCustomBackgroundDrawable(next)
                view.invalidate()
            }
            if (state.transitionBackground === transition) state.transitionBackground = null
        }, 350L)
        view.postOnAnimation {
            view.customBackgroundDrawable()?.setBounds(
                0,
                0,
                view.width.coerceAtLeast(1),
                view.height.coerceAtLeast(1)
            )
            view.invalidate()
        }
    }

    private fun View.customBackgroundDrawable(): Drawable? {
        return if (this is ImageView) drawable else background
    }

    private fun View.setCustomBackgroundDrawable(drawable: Drawable?) {
        if (this is ImageView) {
            setImageDrawable(drawable)
        } else {
            background = drawable
        }
    }

    private fun applyForeground(
        holder: Any,
        colors: NotificationMediaColorConfig,
        api: IslandExpandedMediaBackgroundApi,
        force: Boolean = false
    ) {
        if (!force && foregroundColors[holder] == colors) return
        api.applyCustomForeground(holder, colors)
        foregroundColors[holder] = colors
    }

    private fun centerCropSourceRect(
        bitmapWidth: Int,
        bitmapHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Rect {
        return if (bitmapWidth.toLong() * targetHeight > bitmapHeight.toLong() * targetWidth) {
            val sourceWidth = (bitmapHeight.toLong() * targetWidth / targetHeight)
                .toInt()
                .coerceIn(1, bitmapWidth)
            val left = (bitmapWidth - sourceWidth) / 2
            Rect(left, 0, left + sourceWidth, bitmapHeight)
        } else {
            val sourceHeight = (bitmapWidth.toLong() * targetHeight / targetWidth)
                .toInt()
                .coerceIn(1, bitmapHeight)
            val top = (bitmapHeight - sourceHeight) / 2
            Rect(0, top, bitmapWidth, top + sourceHeight)
        }
    }

    private fun currentStyle(): Int {
        if (!MediaCardRuntimeConfig.current.enabled) {
            return RootConstants.ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE_DEFAULT
        }
        return MediaCardRuntimeConfig.current.islandExpanded.backgroundStyle
    }

    private fun currentSoftCoverTone(): Int =
        MediaCardRuntimeConfig.current.islandExpanded.softCoverTone

    private fun currentBlurAmount(): Int =
        MediaCardRuntimeConfig.current.islandExpanded.backgroundBlur

    private fun currentAutoInvert(): Boolean =
        MediaCardRuntimeConfig.current.islandExpanded.backgroundAutoInvert

    private fun currentColorAnimation(): Boolean =
        MediaCardRuntimeConfig.current.islandExpanded.backgroundColorAnimation

    private fun newExecutor(): ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HyperLyric-IslandMediaBackground").apply { isDaemon = true }
    }

    private data class BinderState(
        var api: IslandExpandedMediaBackgroundApi,
        var lastWidth: Int = 0,
        var lastHeight: Int = 0,
        var retryScheduled: Boolean = false,
        val retryRequest: AtomicInteger = AtomicInteger(),
        var retryView: View? = null,
        var retryLayoutListener: View.OnLayoutChangeListener? = null,
        var pendingMediaData: Any? = null,
        var pendingApi: IslandExpandedMediaBackgroundApi? = null,
        val targets: MutableMap<View, TargetState> = WeakHashMap()
    )

    private data class RenderSize(
        val width: Int,
        val height: Int
    )

    private data class PreparedTarget(
        val state: TargetState,
        val hosts: List<IslandExpandedMediaBackgroundHost>,
        val request: Int
    )

    private data class TargetState(
        var target: IslandExpandedBackgroundTarget,
        var holders: List<Any>,
        var token: String? = null,
        var appliedToken: String? = null,
        var artworkFingerprint: Long? = null,
        var lastWidth: Int = 0,
        var lastHeight: Int = 0,
        var colors: NotificationMediaColorConfig? = null,
        var background: Drawable? = null,
        var transitionBackground: Drawable? = null,
        var customApplied: Boolean = false,
        var appliedStyle: Int? = null,
        var renderPending: Boolean = false,
        val request: AtomicInteger = AtomicInteger()
    )

    private class FixedViewportBitmapDrawable(
        private val bitmap: Bitmap,
        viewportWidth: Int,
        viewportHeight: Int,
        fallbackColor: Int,
        private val cornerRadius: Float = 0f
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fallbackColor }
        private val viewportWidth = viewportWidth.coerceAtLeast(1)
        private val viewportHeight = viewportHeight.coerceAtLeast(1)
        private val viewportSource = centerCropSourceRect(
            bitmap.width,
            bitmap.height,
            this.viewportWidth,
            this.viewportHeight
        )
        private val source = Rect()
        private val destination = Rect()
        private val destinationF = RectF()
        private val clipPath = Path()

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            val checkpoint = canvas.save()
            destinationF.set(bounds)
            if (cornerRadius > 0f) {
                clipPath.rewind()
                clipPath.addRoundRect(
                    destinationF,
                    cornerRadius,
                    cornerRadius,
                    Path.Direction.CW
                )
                canvas.clipPath(clipPath)
            } else {
                canvas.clipRect(bounds)
            }
            canvas.drawRect(destinationF, fillPaint)

            if (bitmap.isRecycled) {
                canvas.restoreToCount(checkpoint)
                return
            }
            val idealDestinationHeight = (
                    bounds.width().toLong() * viewportHeight / viewportWidth
                    ).toInt().coerceAtLeast(1)
            val baseSourceHeight = viewportSource.height()
            val requestedSourceHeight = if (bounds.height() <= idealDestinationHeight) {
                baseSourceHeight
            } else {
                (
                        bounds.height().toLong() * viewportSource.width() + bounds.width() - 1L
                        ).div(bounds.width()).toInt().coerceAtLeast(baseSourceHeight)
            }
            val sourceHeight = requestedSourceHeight.coerceAtMost(
                bitmap.height - viewportSource.top
            )
            source.set(
                viewportSource.left,
                viewportSource.top,
                viewportSource.right,
                viewportSource.top + sourceHeight
            )
            val destinationHeight = (
                    sourceHeight.toLong() * bounds.width() + viewportSource.width() - 1L
                    ).div(viewportSource.width()).toInt().coerceAtLeast(1)
            destination.set(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.top + destinationHeight
            )
            canvas.drawBitmap(bitmap, source, destination, paint)
            canvas.restoreToCount(checkpoint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            fillPaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            fillPaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = -1

        override fun getIntrinsicHeight(): Int = -1

        override fun getMinimumWidth(): Int = 0

        override fun getMinimumHeight(): Int = 0
    }

}
