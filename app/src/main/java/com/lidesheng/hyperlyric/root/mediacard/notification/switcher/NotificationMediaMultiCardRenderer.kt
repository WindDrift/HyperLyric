package com.lidesheng.hyperlyric.root.mediacard.notification.switcher

import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.VelocityTracker
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.lidesheng.hyperlyric.root.mediacard.notification.NotificationMediaHostClasses
import com.lidesheng.hyperlyric.root.utils.HookLogger
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.LinkedHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Renders one native HyperOS notification-media card per MediaData entry.
 *
 * The stock notification path owns one [MiuiMediaViewControllerImpl] and one
 * [MiuiMediaViewHolder]. Once the carousel is active, that original pair is
 * kept hidden as SystemUI's synchronization anchor. Every visible page gets
 * its own native controller/holder pair and its own SeekBarViewModel. This
 * prevents a native top-media rebind from replacing the currently visible
 * page or leaking its artwork into another session.
 */
internal class NotificationMediaMultiCardRenderer(
    private val layoutController: Any,
    private val templateController: Any,
    private val nativeTopKey: () -> String?,
    private val onPlayerAttached: (View, Any) -> Unit,
    private val onPlayerDetached: (View) -> Unit,
    private val onPageSelected: (Int) -> Unit,
    private val onPageScrolled: (Float, Int) -> Unit,
    private val onGestureStarted: () -> Unit,
    private val shouldIgnoreScrollTouch: (MotionEvent) -> Boolean
) {
    private companion object {
        const val TAG = "NotificationMediaMultiCardRenderer"
        const val MIN_FLING_VELOCITY = 400f
        // The card View is miui_media_session.xml. The similarly named
        // miui_media_session_normal.xml is a ConstraintSet resource loaded by
        // MiuiMediaNotificationControllerImpl.normalLayout, not an inflatable
        // layout resource.
        const val PLAYER_LAYOUT = "miui_media_session"
        const val SIDE_PADDING_DIMEN = "notification_side_paddings"
        const val FALLBACK_SIDE_PADDING_DP = 12f
        const val SYSTEMUI_PACKAGE = "com.android.systemui"
        const val SYSTEMUI_LAYOUT_CLASS = "com.android.systemui.R\$layout"
        val CONTROLLER_DEPENDENCIES = listOf(
            "context",
            "activityStarter",
            "seekBarViewModel",
            "mediaTransferManager",
            "fullAodController",
            "miPlayPluginManager",
            "mediaOutputDialogManager",
            "notificationStat",
            "miuiMediaAlbumAnimationUtils",
            "dynamicIslandController",
            "mainHandler",
            "miuiMediaActionButtonUtils",
            "miuiMediaWakeLockManager"
        )
    }

    private class Card(
        var key: String,
        var data: Any?,
        val player: View,
        val holder: Any,
        val controller: Any,
        val original: Boolean
    )

    /**
     * The MIUI14 notification carousel is a real HorizontalScrollView. Keep
     * the child card's dispatch hook for the seek bar and let this view own
     * horizontal motion, then snap to a child on release.
     */
    private class PageScrollView(
        context: Context,
        private val shouldIgnoreTouch: (MotionEvent) -> Boolean,
        private val onScrollPositionChanged: (Int) -> Unit,
        private val onGestureReleased: (Float) -> Unit,
        private val onGestureStarted: () -> Unit
    ) : HorizontalScrollView(context) {
        private var velocityTracker: VelocityTracker? = null
        private var ignoredGesture = false
        private var handlingGesture = false

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    finishGesture(snap = false)
                    ignoredGesture = shouldIgnoreTouch(event)
                    if (!ignoredGesture) {
                        onGestureStarted()
                        velocityTracker = VelocityTracker.obtain()
                        velocityTracker?.addMovement(event)
                    }
                }

                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (!ignoredGesture) velocityTracker?.addMovement(event)
                }
            }

            if (ignoredGesture) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    finishGesture(snap = false)
                }
                return false
            }

            val intercepted = super.onInterceptTouchEvent(event)
            if (intercepted) {
                handlingGesture = true
                // Start at the header, not at this view. Calling
                // requestDisallowInterceptTouchEvent on this view itself
                // would set HorizontalScrollView's own disallow flag and
                // prevent it from intercepting the child on the next MOVE.
                parent?.requestDisallowInterceptTouchEvent(true)
            } else if ((event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL) &&
                !handlingGesture
            ) {
                finishGesture(snap = false)
            }
            return intercepted
        }

        /**
         * HorizontalScrollView starts its own OverScroller fling from
         * super.onTouchEvent(ACTION_UP). The renderer computes the target page
         * itself, so allowing both fling implementations produces a visible
         * indicator 2 -> 1 -> 2 rebound.
         */
        override fun fling(velocityX: Int) = Unit

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (ignoredGesture) return false
            if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                velocityTracker == null
            ) {
                velocityTracker = VelocityTracker.obtain()
            }
            velocityTracker?.addMovement(event)
            val handled = super.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                finishGesture(snap = true)
            }
            return handled
        }

        override fun onScrollChanged(
            scrollX: Int,
            scrollY: Int,
            oldScrollX: Int,
            oldScrollY: Int
        ) {
            super.onScrollChanged(scrollX, scrollY, oldScrollX, oldScrollY)
            onScrollPositionChanged(scrollX)
        }

        private fun finishGesture(snap: Boolean) {
            if (snap && handlingGesture) {
                val tracker = velocityTracker
                tracker?.computeCurrentVelocity(1000)
                onGestureReleased(tracker?.xVelocity ?: 0f)
            }
            parent?.requestDisallowInterceptTouchEvent(false)
            velocityTracker?.recycle()
            velocityTracker = null
            ignoredGesture = false
            handlingGesture = false
        }
    }

    private val controllerClass = templateController.javaClass
    private val holderClassLoader = controllerClass.classLoader
    private val bindMethod = findMethod(controllerClass, "bindMediaData") {
        it.parameterCount == 1
    }
    private val attachMethod = findMethod(controllerClass, "attach") {
        it.parameterCount == 1
    }
    private val detachMethod = findMethod(controllerClass, "detach") {
        it.parameterCount == 0
    }

    private var holderConstructor: Constructor<*>? = null
    private var controllerConstructor: Constructor<*>? = null
    private var seekBarConstructor: Constructor<*>? = null
    private var layoutId: Int? = null

    private var header: ViewGroup? = null
    private var scrollView: PageScrollView? = null
    private var pageContainer: LinearLayout? = null
    private var originalLayoutParams: FrameLayout.LayoutParams? = null
    private var originalCard: Card? = null
    private var pageWidthPx = 0
    private var sidePaddingPx = 0
    private var pageGapPx = 0
    private var pageOrderGeneration = 0
    private var originalVisibility = View.VISIBLE
    private var originalAlpha = 1f
    private var originalHostClipChildren: Boolean? = null
    private var originalHostClipToPadding: Boolean? = null
    private var clipHost: ViewGroup? = null
    private var originalParentClipChildren: Boolean? = null
    private var originalParentClipToPadding: Boolean? = null
    private var clipParent: ViewGroup? = null
    private val cards = LinkedHashMap<String, Card>()

    val isActive: Boolean
        get() = scrollView != null && pageContainer != null && originalCard != null

    /**
     * Identifies the current child order. Delayed scroll callbacks from an old
     * order must not overwrite the indicator after MediaSortUtils promoted a
     * different session to page zero.
     */
    val currentPageOrderGeneration: Int
        get() = pageOrderGeneration

    fun attachOriginal(player: View, holder: Any): Boolean {
        if (originalCard != null) return true
        val parent = player.parent as? ViewGroup ?: return false
        header = parent
        originalCard = Card(
            key = nativeTopKey().orEmpty(),
            data = null,
            player = player,
            holder = holder,
            controller = templateController,
            original = true
        )
        return true
    }

    /**
     * Synchronizes the page list with the coordinator snapshot. A false result
     * means that the selected multi-card mode is unavailable; callers must
     * disable the switcher instead of binding the original card as another mode.
     */
    fun sync(entries: List<Pair<String, Any>>, selectedIndex: Int): Boolean {
        originalCard ?: return false
        if (entries.size < 2) {
            if (isActive) disableMultiView()
            return false
        }
        if (!isActive && !ensureContainer()) return false

        val oldCards = cards.values.toList()
        val oldOrder = oldCards.map { it.key }
        val visualAnchor = captureVisualAnchor(oldCards)
        val oldByKey = oldCards.associateBy { it.key }
        val nextCards = LinkedHashMap<String, Card>()
        val createdCards = mutableListOf<Card>()

        try {
            entries.forEach { (key, data) ->
                val existing = oldByKey[key]
                if (existing != null) {
                    if (existing.data !== data) {
                        existing.data = data
                        bind(existing, data)
                    }
                    nextCards[key] = existing
                } else {
                    val created = createCard(key, data)
                        ?: error("无法创建媒体卡片: key=$key")
                    createdCards += created
                    nextCards[key] = created
                }
            }
        } catch (error: Throwable) {
            createdCards.forEach(::destroyExtraCard)
            disableMultiView()
            warn("创建多媒体卡片失败，多卡片视图不可用", error)
            return false
        }

        oldCards.filter { old -> old !in nextCards.values }
            .forEach(::destroyExtraCard)

        cards.clear()
        cards.putAll(nextCards)
        val nextOrder = nextCards.keys.toList()
        val viewOrderChanged = oldCards.map { it.player } !=
            nextCards.values.map { it.player }
        val orderChanged = oldOrder != nextOrder || viewOrderChanged
        if (orderChanged) {
            rebuildPageOrder()
            val anchor = visualAnchor?.takeIf { it.key in nextCards }
            if (anchor != null) {
                restoreVisualAnchor(anchor, selectedIndex)
            } else {
                // Invalidate a previously posted anchor restore when the
                // anchor itself was removed by this update.
                pageOrderGeneration++
                scrollToPage(selectedIndex, animate = false)
            }
        } else {
            // Metadata/action updates must not tear down the child Views or
            // reset an in-progress native scroll animation. The individual
            // controller was already rebound above; only refresh the
            // fractional indicator from the existing scrollX.
            updatePageWidths()
            onScrollPositionChanged(scrollView?.scrollX ?: 0)
        }
        return true
    }

    private data class VisualAnchor(
        val key: String,
        val screenLeft: Int
    )

    /**
     * The native top MediaData may change the logical order while the user is
     * looking at a secondary page. Preserve that page's screen coordinate;
     * only its logical index and the indicator should change.
     */
    private fun captureVisualAnchor(oldCards: List<Card>): VisualAnchor? {
        val scroller = scrollView ?: return null
        if (oldCards.isEmpty()) return null
        val oldIndex = pageLocation(scroller.scrollX)
            .roundToInt()
            .coerceIn(0, oldCards.lastIndex)
        val card = oldCards.getOrNull(oldIndex) ?: return null
        return VisualAnchor(
            key = card.key,
            screenLeft = card.player.left - scroller.scrollX
        )
    }

    private fun restoreVisualAnchor(anchor: VisualAnchor, fallbackIndex: Int) {
        val scroller = scrollView ?: return
        val pages = pageContainer ?: return
        val generation = ++pageOrderGeneration
        val anchorIndex = cards.keys.indexOf(anchor.key)
        if (anchorIndex < 0 || pageWidthPx <= 0) {
            scrollToPage(fallbackIndex, animate = false)
            return
        }

        // Reordering the children schedules a layout pass. Do not wait for
        // that pass before correcting scrollX: the old scrollX would expose
        // the former page for one frame (A flashes over the selected B).
        val stride = pageWidthPx + pageGapPx
        val anchorLeft = sidePaddingPx + anchorIndex * stride
        val contentWidth = sidePaddingPx * 2 + cards.size * pageWidthPx +
            (cards.size - 1).coerceAtLeast(0) * pageGapPx
        val viewportWidth = scroller.width.takeIf { it > 0 }
            ?: pageWidthPx + sidePaddingPx * 2
        val maxScroll = (contentWidth - viewportWidth).coerceAtLeast(0)
        val target = (anchorLeft - anchor.screenLeft).coerceIn(0, maxScroll)
        scroller.scrollTo(target, 0)
        pages.requestLayout()

        // Child.left is reliable after layout; only defer the fractional dot
        // refresh, never the visual page position itself.
        pages.post {
            if (generation == pageOrderGeneration && scrollView === scroller) {
                onScrollPositionChanged(scroller.scrollX)
            }
        }
    }

    fun setHeaderTranslation(translation: Float) {
        scrollView?.translationX = translation
    }

    fun headerTranslation(): Float? = scrollView?.translationX

    fun detach() {
        val original = originalCard
        cards.values.filter { !it.original }.forEach(::destroyExtraCard)
        cards.clear()

        val container = scrollView
        val restoreParams = originalLayoutParams
        if (original != null) {
            if (restoreParams != null) {
                // Keep the native anchor's original LayoutParams after the
                // carousel is removed.
                original.player.layoutParams = restoreParams
            }
            restoreOriginalVisualState()
        }
        if (container != null) {
            (container.parent as? ViewGroup)?.removeView(container)
        }
        restoreCarouselClipState()

        originalCard = null
        scrollView = null
        pageContainer = null
        originalLayoutParams = null
        pageWidthPx = 0
        sidePaddingPx = 0
        pageGapPx = 0
        originalVisibility = View.VISIBLE
        originalAlpha = 1f
        header = null
        pageOrderGeneration++
    }

    private fun ensureContainer(): Boolean {
        if (isActive) return true
        val host = header ?: return false
        val original = originalCard ?: return false
        if (original.player.parent !== host) {
            warn("原生媒体卡片父容器已被其他逻辑接管")
            return false
        }

        val oldLayoutParams = original.player.layoutParams
        pageWidthPx = resolveOriginalPageWidth(host, original.player, oldLayoutParams)
        sidePaddingPx = resolveSidePadding(host.context)
        pageGapPx = sidePaddingPx
        allowCarouselBleed(host)
        val restoreParams = FrameLayout.LayoutParams(
            oldLayoutParams?.width ?: ViewGroup.LayoutParams.MATCH_PARENT,
            oldLayoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
        )
        if (oldLayoutParams is ViewGroup.MarginLayoutParams) {
            restoreParams.setMargins(
                oldLayoutParams.leftMargin,
                oldLayoutParams.topMargin,
                oldLayoutParams.rightMargin,
                oldLayoutParams.bottomMargin
            )
        }
        if (oldLayoutParams is FrameLayout.LayoutParams) {
            restoreParams.gravity = oldLayoutParams.gravity
        }

        val context = host.context
        val scroller = PageScrollView(
            context = context,
            shouldIgnoreTouch = shouldIgnoreScrollTouch,
            onScrollPositionChanged = ::onScrollPositionChanged,
            onGestureReleased = ::onGestureReleased,
            onGestureStarted = onGestureStarted
        ).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            clipChildren = false
            clipToPadding = false
            clipToOutline = false
        }
        val pages = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        scroller.addView(
            pages,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val initialCarouselWidth = pageWidthPx.takeIf { it > 0 }
            ?.let { it + sidePaddingPx * 2 }
            ?: ViewGroup.LayoutParams.MATCH_PARENT
        val containerParams = FrameLayout.LayoutParams(
            initialCarouselWidth,
            oldLayoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
        )
        if (oldLayoutParams is ViewGroup.MarginLayoutParams) {
            containerParams.setMargins(
                oldLayoutParams.leftMargin - sidePaddingPx,
                oldLayoutParams.topMargin,
                oldLayoutParams.rightMargin - sidePaddingPx,
                oldLayoutParams.bottomMargin
            )
        }
        if (oldLayoutParams is FrameLayout.LayoutParams) {
            containerParams.gravity = oldLayoutParams.gravity
        }

        return runCatching {
            originalVisibility = original.player.visibility
            originalAlpha = original.player.alpha
            original.player.visibility = View.INVISIBLE
            original.player.alpha = 0f
            host.addView(scroller, 0, containerParams)
            originalLayoutParams = restoreParams
            scrollView = scroller
            pageContainer = pages
            updatePageWidths()
            scroller.post(::updatePageWidths)
            true
        }.onFailure { error ->
            restoreOriginalVisualState()
            restoreCarouselClipState()
            pageWidthPx = 0
            sidePaddingPx = 0
            pageGapPx = 0
            warn("创建媒体横向容器失败", error)
        }.getOrDefault(false)
    }

    private fun createCard(key: String, data: Any): Card? {
        val pages = pageContainer ?: return null
        val context = resourceContext() ?: return null
        val player = inflatePlayer(context) ?: return null
        val holder = createHolder(player) ?: return null
        applyLoadedLayouts(player, holder)
        val controller = createController() ?: return null
        val card = Card(key, data, player, holder, controller, original = false)

        return runCatching {
            pages.addView(player, pageLayoutParams(player))
            attachMethod?.invoke(controller, holder)
            onPlayerAttached(player, holder)
            bind(card, data)
            card
        }.onFailure { error ->
            onPlayerDetached(player)
            runCatching { detachMethod?.invoke(controller) }
            (player.parent as? ViewGroup)?.removeView(player)
            warn("绑定副媒体卡片失败: key=$key", error)
        }.getOrNull()
    }

    private fun bind(card: Card, data: Any) {
        bindMethod?.invoke(card.controller, data)
        copyNativeChrome(card)
        if (!card.original) card.player.post { copyNativeChrome(card) }
    }

    private fun destroyExtraCard(card: Card) {
        if (card.original) return
        onPlayerDetached(card.player)
        runCatching { detachMethod?.invoke(card.controller) }
            .onFailure { warn("销毁副媒体控制器失败: key=${card.key}", it) }
        (card.player.parent as? ViewGroup)?.removeView(card.player)
    }

    private fun disableMultiView() {
        val original = originalCard ?: return
        cards.values.filter { !it.original }.forEach(::destroyExtraCard)
        cards.clear()

        val host = header
        val container = scrollView
        if (container != null) {
            (container.parent as? ViewGroup)?.removeView(container)
        }
        restoreCarouselClipState()
        if (host != null && original.player.parent == null) {
            runCatching {
                host.addView(
                    original.player,
                    originalLayoutParams ?: FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }.onFailure { warn("恢复系统原生媒体卡片失败", it) }
        }
        restoreOriginalVisualState()
        scrollView = null
        pageContainer = null
        originalLayoutParams = null
        pageWidthPx = 0
        sidePaddingPx = 0
        pageGapPx = 0
        originalVisibility = View.VISIBLE
        originalAlpha = 1f
    }

    private fun restoreOriginalVisualState() {
        originalCard?.player?.let { player ->
            player.visibility = originalVisibility
            player.alpha = originalAlpha
        }
    }

    private fun rebuildPageOrder() {
        val pages = pageContainer ?: return
        val orderedCards = cards.values.toList()
        pages.removeAllViews()
        orderedCards.forEach { card ->
            pages.addView(card.player, pageLayoutParams(card.player))
        }
        updatePageWidths()
    }

    private fun pageWidth(): Int = pageWidthPx.takeIf { it > 0 }
        ?: header?.width?.takeIf { it > 0 }
        ?: ViewGroup.LayoutParams.MATCH_PARENT

    private fun pageLayoutParams(player: View): LinearLayout.LayoutParams {
        val old = player.layoutParams
        val height = old?.height?.takeIf { it != 0 }
            ?: ViewGroup.LayoutParams.WRAP_CONTENT
        val params = LinearLayout.LayoutParams(
            pageWidth(),
            height
        )
        if (old is ViewGroup.MarginLayoutParams) {
            params.setMargins(
                0,
                old.topMargin,
                pageGapPx,
                old.bottomMargin
            )
        }
        return params
    }

    private fun updatePageWidths() {
        val pages = pageContainer ?: return
        val width = pageWidthPx.takeIf { it > 0 }
            ?: originalCard?.player?.width?.takeIf { it > 0 }
            ?: header?.width?.takeIf { it > 0 }
            ?: 0
        if (width <= 0) return
        if (pageWidthPx <= 0) pageWidthPx = width
        val scroller = scrollView
        scroller?.layoutParams?.let { rawParams ->
            val params = rawParams as? FrameLayout.LayoutParams ?: return@let
            val desiredWidth = width + sidePaddingPx * 2
            if (params.width != desiredWidth) {
                params.width = desiredWidth
                scroller.layoutParams = params
            }
        }
        pages.setPaddingRelative(
            sidePaddingPx,
            0,
            sidePaddingPx,
            0
        )
        pages.children().forEachIndexed { index, child ->
            val params = child.layoutParams as? LinearLayout.LayoutParams ?: return@forEachIndexed
            if (params.width != width) {
                params.width = width
            }
            params.setMarginStart(0)
            params.setMarginEnd(
                if (index == pages.childCount - 1) 0 else pageGapPx
            )
            child.layoutParams = params
        }
        pages.requestLayout()
    }

    private fun onScrollPositionChanged(scrollX: Int) {
        val location = pageLocation(scrollX)
        onPageScrolled(location, pageOrderGeneration)
    }

    private fun onGestureReleased(velocityX: Float) {
        val scroller = scrollView ?: return
        val location = pageLocation(scroller.scrollX)
        val lower = floor(location).toInt()
        val fraction = location - lower
        val target = when {
            velocityX < -MIN_FLING_VELOCITY -> {
                if (fraction > 0.01f) ceil(location).toInt() else lower + 1
            }

            velocityX > MIN_FLING_VELOCITY -> {
                if (fraction > 0.01f) floor(location).toInt() else lower - 1
            }

            else -> location.roundToInt()
        }.coerceIn(0, (cards.size - 1).coerceAtLeast(0))

        onPageSelected(target)
        // Queue the single custom snap after ACTION_UP dispatch so the
        // animation starts from the actual current scrollX.
        val generation = pageOrderGeneration
        scroller.post {
            if (generation == pageOrderGeneration && scrollView === scroller) {
                scrollToPage(target, animate = true, generation = generation)
            }
        }
    }

    private fun pageLocation(scrollX: Int): Float {
        val pages = pageContainer ?: return 0f
        val count = cards.size
        if (count <= 1) return 0f

        val positions = (0 until count).map { index ->
            pages.getChildAt(index)?.left ?: 0
        }
        if (positions.size < 2 || positions.last() <= positions.first()) return 0f

        // The first page is intentionally inset by sidePadding, while the
        // scroll position starts at zero. Convert scrollX back into the
        // content coordinate used by the child positions.
        val x = (scrollX + positions.first()).coerceIn(
            positions.first(),
            positions.last()
        )
        for (index in 0 until positions.lastIndex) {
            val start = positions[index]
            val end = positions[index + 1]
            if (end <= start) continue
            if (x <= end) {
                return index + ((x - start).toFloat() / (end - start))
            }
        }
        return positions.lastIndex.toFloat()
    }

    private fun scrollToPage(
        index: Int,
        animate: Boolean,
        generation: Int = pageOrderGeneration
    ) {
        val scroller = scrollView ?: return
        val pages = pageContainer ?: return
        if (generation != pageOrderGeneration) return
        val count = cards.size
        if (count == 0) return
        val target = index.coerceIn(0, count - 1)
        val targetView = pages.getChildAt(target)
        val firstView = pages.getChildAt(0)
        val x = if (targetView != null && firstView != null) {
            targetView.left - firstView.left
        } else {
            0
        }
        if (targetView == null || firstView == null ||
            (target > 0 && targetView.left == 0 && pages.width == 0)
        ) {
            scroller.post {
                if (generation == pageOrderGeneration && scrollView === scroller) {
                    scrollToPage(target, animate, generation)
                }
            }
            return
        }
        if (animate) {
            scroller.smoothScrollTo(x, 0)
        } else {
            scroller.scrollTo(x, 0)
        }
        pages.requestLayout()
    }

    private fun inflatePlayer(context: Context): View? {
        val resourceId = layoutId ?: run {
            val resolved = context.resources.getIdentifier(
                PLAYER_LAYOUT,
                "layout",
                context.packageName
            ).takeIf { it != 0 }
                ?: context.resources.getIdentifier(
                    PLAYER_LAYOUT,
                    "layout",
                    SYSTEMUI_PACKAGE
                ).takeIf { it != 0 }
                ?: resolveSystemUiLayoutId()
            layoutId = resolved
            resolved
        }
        if (resourceId == 0) {
            warn("找不到原生媒体布局: $PLAYER_LAYOUT")
            return null
        }
        return runCatching {
            LayoutInflater.from(context).inflate(resourceId, header, false)
        }.onFailure { warn("膨胀原生媒体布局失败", it) }.getOrNull()
    }

    private fun resourceContext(): Context? {
        return (readField(layoutController, "context") as? Context)
            ?: (readField(layoutController, "layoutContext") as? Context)
            ?: header?.context
    }

    private fun resolveSystemUiLayoutId(): Int {
        return runCatching {
            val clazz = holderClassLoader?.loadClass(SYSTEMUI_LAYOUT_CLASS)
                ?: error("SystemUI R.layout class unavailable")
            clazz.getDeclaredField(PLAYER_LAYOUT).apply { isAccessible = true }.getInt(null)
        }.onFailure { warn("反射 SystemUI 媒体布局资源失败", it) }.getOrDefault(0)
    }

    private fun createHolder(player: View): Any? {
        return runCatching {
            val constructor = holderConstructor ?: run {
                val clazz = holderClassLoader?.loadClass(NotificationMediaHostClasses.HOLDER)
                    ?: error("MediaViewHolder class unavailable")
                clazz.getDeclaredConstructor(View::class.java).apply {
                    isAccessible = true
                }.also { holderConstructor = it }
            }
            constructor.newInstance(player)
        }.onFailure { warn("创建原生媒体 Holder 失败", it) }.getOrNull()
    }

    private fun createController(): Any? {
        return runCatching {
            val values = CONTROLLER_DEPENDENCIES.map { name ->
                readField(templateController, name)
            }.toMutableList()
            values[2] = createSeekBarViewModel()
                ?: error("SeekBarViewModel clone unavailable")
            if (values.any { it == null }) {
                error("MiuiMediaViewControllerImpl 依赖字段不完整")
            }

            val constructor = controllerConstructor ?: controllerClass.declaredConstructors
                .firstOrNull { candidate ->
                    candidate.parameterCount == values.size && candidate.parameterTypes
                        .zip(values)
                        .all { (type, value) ->
                            value != null && type.isAssignableFrom(value.javaClass)
                        }
                }?.apply { isAccessible = true }
                ?.also { controllerConstructor = it }
                ?: error("MiuiMediaViewControllerImpl 构造函数不匹配")

            constructor.newInstance(*values.toTypedArray()).also { controller ->
                readField(templateController, "statusBarState")?.let { state ->
                    writeField(controller, "statusBarState", state)
                }
            }
        }.onFailure { warn("创建原生媒体控制器失败", it) }.getOrNull()
    }

    private fun createSeekBarViewModel(): Any? {
        val template = readField(templateController, "seekBarViewModel") ?: return null
        val executor = readField(template, "bgExecutor") ?: return null
        val falsingManager = readField(template, "falsingManager") ?: return null
        return runCatching {
            val constructor = seekBarConstructor ?: template.javaClass.declaredConstructors
                .firstOrNull { candidate ->
                    candidate.parameterCount == 2 &&
                        candidate.parameterTypes[0].isAssignableFrom(executor.javaClass) &&
                        candidate.parameterTypes[1].isAssignableFrom(falsingManager.javaClass)
                }?.apply { isAccessible = true }
                ?.also { seekBarConstructor = it }
                ?: error("SeekBarViewModel 构造函数不匹配")
            constructor.newInstance(executor, falsingManager)
        }.onFailure { warn("创建独立 SeekBarViewModel 失败", it) }.getOrNull()
    }

    private fun applyLoadedLayouts(player: View, holder: Any) {
        applyConstraintSet(readField(layoutController, "normalLayout"), player)
        val album = readField(holder, "albumView") as? View
        applyConstraintSet(readField(layoutController, "normalAlbumLayout"), album)
    }

    /**
     * ConstraintSet resources do not carry runtime shape state. The native
     * controller (and our cover/background hooks) put the album/background
     * outline on the original View after inflation, so copy only the chrome
     * properties to an independently bound page. Artwork Drawables remain
     * owned by each page's controller.
     */
    private fun copyNativeChrome(card: Card) {
        if (card.original) return
        val original = originalCard ?: return
        copyViewChrome(original.player, card.player)
        listOf("mediaBg", "albumView", "albumImageView").forEach { fieldName ->
            copyViewChrome(
                readField(original.holder, fieldName) as? View,
                readField(card.holder, fieldName) as? View
            )
        }
    }

    private fun copyViewChrome(source: View?, target: View?) {
        if (source == null || target == null) return
        runCatching {
            target.clipToOutline = source.clipToOutline
            target.outlineProvider = source.outlineProvider
            target.clipBounds = source.clipBounds
            target.setPadding(
                source.paddingLeft,
                source.paddingTop,
                source.paddingRight,
                source.paddingBottom
            )
            target.elevation = source.elevation
            target.translationZ = source.translationZ
            if (target.background == null && source.background != null) {
                target.background = source.background.constantState
                    ?.newDrawable(target.resources, target.context.theme)
                    ?.mutate()
            }
            target.invalidateOutline()

            val sourceGroup = source as? ViewGroup
            val targetGroup = target as? ViewGroup
            if (sourceGroup != null && targetGroup != null) {
                targetGroup.clipChildren = sourceGroup.clipChildren
                targetGroup.clipToPadding = sourceGroup.clipToPadding
                for (index in 0 until targetGroup.childCount) {
                    val targetChild = targetGroup.getChildAt(index)
                    val sourceChild = if (targetChild.id != View.NO_ID) {
                        sourceGroup.findViewById(targetChild.id)
                    } else {
                        sourceGroup.getChildAt(index)
                    }
                    copyViewChrome(sourceChild, targetChild)
                }
            }
        }.onFailure { error ->
            warn("复制副媒体卡片圆角属性失败", error)
        }
    }

    private fun resolveOriginalPageWidth(
        host: ViewGroup,
        player: View,
        layoutParams: ViewGroup.LayoutParams?
    ): Int {
        return player.width.takeIf { it > 0 }
            ?: layoutParams?.width?.takeIf { it > 0 }
            ?: (host.width - host.paddingLeft - host.paddingRight).takeIf { it > 0 }
            ?: 0
    }

    private fun resolveSidePadding(hostContext: Context): Int {
        val context = resourceContext() ?: hostContext
        val resourceId = listOf(context.packageName, SYSTEMUI_PACKAGE)
            .asSequence()
            .map { packageName ->
                context.resources.getIdentifier(SIDE_PADDING_DIMEN, "dimen", packageName)
            }
            .firstOrNull { it != 0 }
        return runCatching {
            resourceId?.takeIf { it != 0 }?.let(context.resources::getDimensionPixelSize)
                ?: (FALLBACK_SIDE_PADDING_DP * context.resources.displayMetrics.density)
                    .roundToInt()
        }.getOrDefault(
            (FALLBACK_SIDE_PADDING_DP * hostContext.resources.displayMetrics.density)
                .roundToInt()
        ).coerceAtLeast(1)
    }

    private fun allowCarouselBleed(host: ViewGroup) {
        if (clipHost == null) {
            clipHost = host
            originalHostClipChildren = host.clipChildren
            originalHostClipToPadding = host.clipToPadding
            host.clipChildren = false
            host.clipToPadding = false
        }

        val parent = host.parent as? ViewGroup ?: return
        if (clipParent == null || clipParent !== parent) {
            clipParent = parent
            originalParentClipChildren = parent.clipChildren
            originalParentClipToPadding = parent.clipToPadding
            parent.clipChildren = false
            parent.clipToPadding = false
        }
    }

    private fun restoreCarouselClipState() {
        clipHost?.let { host ->
            originalHostClipChildren?.let { host.clipChildren = it }
            originalHostClipToPadding?.let { host.clipToPadding = it }
        }
        clipParent?.let { parent ->
            originalParentClipChildren?.let { parent.clipChildren = it }
            originalParentClipToPadding?.let { parent.clipToPadding = it }
        }
        clipHost = null
        clipParent = null
        originalHostClipChildren = null
        originalHostClipToPadding = null
        originalParentClipChildren = null
        originalParentClipToPadding = null
    }

    private fun applyConstraintSet(constraintSet: Any?, target: View?) {
        if (constraintSet == null || target == null) return
        runCatching {
            val method = findMethod(constraintSet.javaClass, "applyTo") {
                it.parameterCount == 1 &&
                    it.parameterTypes[0].isAssignableFrom(target.javaClass)
            } ?: return@runCatching
            method.invoke(constraintSet, target)
        }.onFailure { warn("应用原生媒体布局约束失败", it) }
    }

    private fun readField(target: Any?, name: String): Any? {
        target ?: return null
        var current: Class<*>? = target.javaClass
        while (current != null) {
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(target)
                }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    private fun writeField(target: Any, name: String, value: Any) {
        var current: Class<*>? = target.javaClass
        while (current != null) {
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                runCatching {
                    field.isAccessible = true
                    field.set(target, value)
                }
                return
            }
            current = current.superclass
        }
    }

    private fun findMethod(
        clazz: Class<*>,
        name: String,
        predicate: (Method) -> Boolean
    ): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredMethods.firstOrNull { it.name == name && predicate(it) }
                ?.apply { isAccessible = true }
                ?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun warn(message: String, error: Throwable? = null) {
        HookLogger.w(TAG, message, error)
    }
}

private fun ViewGroup.children(): Sequence<View> = sequence {
    for (index in 0 until childCount) yield(getChildAt(index))
}
