package com.lidesheng.hyperlyric.root.statusbar

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.lidesheng.hyperlyric.root.utils.HookLogger
import java.lang.ref.WeakReference

/** A content-sized notification viewport next to native system icons, outside their measure tree. */
internal class StatusBarNotificationIconPosition(
    root: ViewGroup,
    private val api: StatusBarNotificationIconPositionHooker.Bridge,
) {
    private val rootReference = WeakReference(root)
    private var iconsReference: WeakReference<ViewGroup>? = null
    private var areaReference: WeakReference<ViewGroup>? = null
    private var originalParams: ViewGroup.LayoutParams? = null
    private var overlay: FrameLayout? = null
    private var viewport: FrameLayout? = null
    private var requested = false
    private var failed = false
    private var restoring = false
    var isMoved = false
        private set
    var unconstrainedContentWidth = 0
        private set
    private val location = IntArray(2)
    private val origin = IntArray(2)
    private val cutoutReference = WeakReference(find(root, "cutout_space_view"))
    private val systemIconsReference = WeakReference(find(root, "statusIcons"))
    private val batteryReference = WeakReference(find(root, "battery"))
    private val privacyReference = WeakReference(find(root, "home_privacy_container"))
    private val overlayParentReference = WeakReference(find(root, "status_bar_icons") as? FrameLayout)
    private val spacingId = root.resources.getIdentifier(
        "status_bar_island_padding", "dimen", "com.android.systemui")
    private var spacingDensity = -1
    private var spacingOrientation = -1
    private var spacingPx = 0
    private val rootAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = reconcile()
        override fun onViewDetachedFromWindow(v: View) = restore()
    }

    init { root.addOnAttachStateChangeListener(rootAttachListener) }

    fun setEnabled(enabled: Boolean) {
        if (requested == enabled) return
        requested = enabled
        reconcile()
    }

    fun onNativeLayoutUpdated() {
        if (!restoring) reconcile()
    }

    /** Native siblings are laid out before the overlay; force its local layout even if
     * its outer dimensions are unchanged. This does not request another root traversal. */
    fun onSystemIconsLaidOut() {
        if (isMoved) overlay?.forceLayout()
    }

    fun refreshIslandBounds() {
        if (isMoved) overlay?.requestLayout()
    }

    private fun reconcile() {
        if (!requested || failed) {
            restore()
            return
        }
        val root = rootReference.get() ?: return
        if (!root.isAttachedToWindow) return
        runCatching {
            val icons = api.inner.get(root) as? ViewGroup ?: return
            val systemIcons = systemIconsReference.get() ?: return
            val parent = overlayParentReference.get() ?: return
            val area = systemIcons.parent?.parent as? ViewGroup ?: return
            if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL ||
                cutoutReference.get()?.visibility != View.VISIBLE ||
                !api.iconClass.isInstance(icons) || !api.supportsSystemIcons(systemIcons) ||
                !api.supportsArea(area)
            ) {
                restore()
                return
            }
            if (isMoved && iconsReference?.get() !== icons) restore()
            if (!isMoved) {
                iconsReference = WeakReference(icons)
                areaReference = WeakReference(area)
                originalParams = icons.layoutParams
                val frame = FrameLayout(root.context).apply {
                    clipChildren = true
                    clipToPadding = true
                }
                val layer = object : FrameLayout(root.context) {
                    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                        // This sibling runs after status_bar_contents. Its only visible child
                        // is measured and placed by content; it never measures native views.
                        layoutRightArea()
                    }
                }.apply {
                    clipChildren = true
                    clipToPadding = true
                    addView(frame, FrameLayout.LayoutParams(0, 0))
                }
                viewport = frame
                overlay = layer
                isMoved = true
                StatusBarNotificationIconPositionHooker.track(this, area, icons)
                parent.addView(layer, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            val frame = viewport ?: return
            if (icons.parent !== frame) {
                originalParams = icons.layoutParams
                (icons.parent as? ViewGroup)?.removeView(icons)
                frame.addView(icons, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }.onFailure {
            failed = true
            HookLogger.w(TAG, "通知图标右侧模式已降级: reason=${it.message}")
            restore()
        }
    }

    /** Right edge follows the native glyph boundary, not the allocated system-area width. */
    fun layoutRightArea() {
        if (!isMoved) return
        val layer = overlay ?: return
        val frame = viewport ?: return
        val icons = iconsReference?.get() ?: return
        val systemIcons = systemIconsReference.get() ?: return
        val cutout = cutoutReference.get() ?: return
        locationWithoutTransform(layer, origin)
        locationWithoutTransform(cutout, location)
        var leftBound = location[0] + cutout.width - origin[0]
        // The native hover helper uses this leftover-space offset to find the first
        // system icon, including native inter-icon spacing. Keep those views untouched.
        locationWithoutTransform(systemIcons, location)
        val top = location[1] - origin[1]
        val height = systemIcons.height
        var rightBound = if (systemIcons.visibility == View.VISIBLE) {
            location[0] + api.systemIconsStart(systemIcons).coerceIn(0, systemIcons.width) - origin[0]
        } else layer.width
        rightBound = boundaryBefore(batteryReference.get(), rightBound)
        rightBound = boundaryBefore(privacyReference.get(), rightBound)
        rightBound = rightBound.coerceIn(0, layer.width)
        val bounds = StatusBarLyricIslandRegionDispatcher.committedIslandBounds()
        if (bounds != null && bounds.top < origin[1] + top + height && bounds.bottom > origin[1] + top) {
            val resources = layer.resources
            val density = resources.configuration.densityDpi
            val orientation = resources.configuration.orientation
            if (density != spacingDensity || orientation != spacingOrientation) {
                spacingPx = if (spacingId != 0) resources.getDimensionPixelSize(spacingId) else 0
                spacingDensity = density
                spacingOrientation = orientation
            }
            leftBound = maxOf(leftBound,
                bounds.right + spacingPx - origin[0] - api.islandTranslationX(icons))
        }
        val available = (rightBound - leftBound).coerceAtLeast(0)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        // Native measurement applies the user's icon-count limit and measures each slot.
        // Measure notifications only; never alter the original signal/battery subtree.
        icons.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), heightSpec)
        unconstrainedContentWidth = icons.measuredWidth
        var width = unconstrainedContentWidth
        if (width > available) {
            // Fit complete leading slots, dropping trailing ones; right anchoring moves
            // the surviving prefix toward the system icons without reversing its order.
            width = icons.paddingStart + icons.paddingEnd
            for (index in 0 until icons.childCount) {
                val childWidth = icons.getChildAt(index).measuredWidth
                if (width + childWidth > available) break
                width += childWidth
            }
            width = width.coerceAtMost(available)
        }
        frame.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), heightSpec)
        frame.layout(rightBound - width, top, rightBound, top + height)
    }

    private fun restore() {
        if (!isMoved) return
        isMoved = false
        val icons = iconsReference?.get()
        StatusBarNotificationIconPositionHooker.untrack(areaReference?.get(), icons)
        val root = rootReference.get()
        restoring = true
        try {
            runCatching {
                if (root != null && icons != null && api.inner.get(root) === icons) {
                    api.updateParent.invoke(root)
                    val params = originalParams
                    if (params != null) icons.layoutParams = params
                }
            }.onFailure { HookLogger.w(TAG, "通知图标原厂位置恢复失败: reason=${it.message}") }
        } finally {
            restoring = false
        }
        // The native right area was never shown or modified by this mode.
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        viewport = null
        overlay = null
        iconsReference = null
        areaReference = null
        originalParams = null
        unconstrainedContentWidth = 0
    }

    fun release() {
        requested = false
        restore()
        rootReference.get()?.removeOnAttachStateChangeListener(rootAttachListener)
    }

    private fun boundaryBefore(view: View?, currentBoundary: Int): Int {
        if (view == null || view.visibility != View.VISIBLE || view.width == 0) return currentBoundary
        locationWithoutTransform(view, location)
        return minOf(currentBoundary, location[0] - origin[0])
    }

    private fun locationWithoutTransform(view: View, target: IntArray) {
        val windowRoot = view.rootView
        windowRoot.getLocationInWindow(target)
        var current = view
        while (current !== windowRoot) {
            target[0] += current.left
            target[1] += current.top
            val parent = current.parent as? View ?: break
            target[0] -= parent.scrollX
            target[1] -= parent.scrollY
            current = parent
        }
    }

    private fun find(root: View, name: String): View? = root.findViewById(
        root.resources.getIdentifier(name, "id", "com.android.systemui"))

    private companion object { const val TAG = "StatusBarNotificationIcons" }
}
