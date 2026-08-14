package com.lidesheng.hyperlyric.root.mediacard.notification.switcher

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.lidesheng.hyperlyric.root.utils.HookLogger
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.roundToInt

/**
 * Hosts the SystemUI-native page indicator for the notification media card.
 *
 * HyperOS 3 still contains [com.android.systemui.qs.PageIndicator] for the
 * regular QS media carousel, but the notification media path no longer adds
 * one to [MiuiMediaHeaderView]. Reusing that class keeps the dot drawable,
 * tint and page transition animation consistent with SystemUI.
 */
internal class NotificationMediaPageIndicator {
    private companion object {
        const val TAG = "NotificationMediaPageIndicator"
        const val PAGE_INDICATOR = "com.android.systemui.qs.PageIndicator"
        const val BOTTOM_MARGIN_DP = 4f
    }

    private var indicator: View? = null
    private var setNumPages: Method? = null
    private var setLocation: Method? = null
    private var setIndex: Method? = null
    private var setTintListMethod: Method? = null
    private var animatingField: Field? = null
    private var positionField: Field? = null
    private var queuedPositionsField: Field? = null
    private var configuredPageCount = -1

    fun attach(player: View) {
        val parent = player.parent as? ViewGroup ?: run {
            // MiuiMediaNotificationControllerImpl adds player to the header
            // immediately before MiuiMediaViewControllerImpl.attach(). A
            // single posted retry also covers variant-specific attach order.
            player.post {
                if (player.parent is ViewGroup) attach(player)
            }
            return
        }
        attachTo(parent)
    }

    fun attachTo(parent: ViewGroup) {
        if (indicator?.parent === parent) return

        val frameParent = parent as? FrameLayout ?: run {
            warnOnce("媒体卡片父容器不是 FrameLayout: ${parent.javaClass.name}")
            return
        }
        detach()

        val nativeIndicator = createNativeIndicator(frameParent) ?: return
        val density = frameParent.resources.displayMetrics.density
        val layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (BOTTOM_MARGIN_DP * density).roundToInt()
        }

        runCatching {
            nativeIndicator.isClickable = false
            nativeIndicator.isFocusable = false
            nativeIndicator.isFocusableInTouchMode = false
            nativeIndicator.importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_NO
            frameParent.addView(nativeIndicator, layoutParams)
            indicator = nativeIndicator
            applyTint(nativeIndicator)
        }.onFailure { error ->
            warnOnce("挂载媒体圆点指示器失败", error)
        }
    }

    /**
     * Keeps the native PageIndicator animation and only changes its base
     * foreground color. The native implementation already lowers the alpha of
     * unselected dots, so selected/unselected colors stay visually consistent
     * with SystemUI's own page indicator.
     */
    fun updateTint(color: Int?) {
        if (tintColor == color) return
        tintColor = color
        indicator?.let(::applyTint)
    }

    fun update(pageCount: Int, selectedIndex: Int, enabled: Boolean) {
        updateLocation(
            pageCount = pageCount,
            location = selectedIndex.toFloat(),
            enabled = enabled
        )
    }

    /**
     * Applies a logical page reorder without feeding the old scroll animation
     * back through HyperOS' PageIndicator queue. The target PageIndicator keeps
     * mQueuedPositions and mAnimating as public fields, but older builds may
     * not expose the same implementation; in that case fall back to the
     * regular animated path.
     */
    fun forceUpdate(pageCount: Int, selectedIndex: Int, enabled: Boolean) {
        val view = indicator ?: return
        val count = pageCount.coerceAtLeast(0)
        if (!enabled || count <= 1) {
            updateLocation(pageCount, selectedIndex.toFloat(), enabled)
            return
        }

        if (!ensurePageCount(view, count)) return
        view.visibility = View.VISIBLE
        val target = selectedIndex.coerceIn(0, count - 1)
        if (!forceSetPosition(view, target)) {
            updateLocation(count, target.toFloat(), enabled = true)
        }
    }

    /**
     * MIUI14 updates PageIndicator with a fractional location while the
     * MediaScrollView is being dragged. Keeping this separate from the
     * coordinator's integer selectedIndex makes the indicator follow the
     * neighboring native card during a real carousel gesture.
     */
    fun updateLocation(pageCount: Int, location: Float, enabled: Boolean) {
        val view = indicator ?: return
        val count = pageCount.coerceAtLeast(0)
        if (!enabled || count <= 1) {
            hide(view)
            view.visibility = View.GONE
            return
        }

        if (!ensurePageCount(view, count)) return
        view.visibility = View.VISIBLE
        runCatching {
            setLocation?.invoke(view, location.coerceIn(0f, (count - 1).toFloat()))
        }.onFailure { error ->
            warnOnce("更新媒体圆点位置失败", error)
        }
    }

    fun setTranslationX(translation: Float) {
        indicator?.translationX = translation
    }

    fun detach() {
        val view = indicator ?: return
        runCatching {
            (view.parent as? ViewGroup)?.removeView(view)
        }.onFailure { error ->
            warnOnce("移除媒体圆点指示器失败", error)
        }
        indicator = null
        setNumPages = null
        setLocation = null
        setIndex = null
        setTintListMethod = null
        animatingField = null
        positionField = null
        queuedPositionsField = null
        configuredPageCount = -1
    }

    private fun createNativeIndicator(player: View): View? {
        val classLoader = player.javaClass.classLoader ?: run {
            warnOnce("无法取得 SystemUI classLoader")
            return null
        }
        return runCatching {
            val indicatorClass = classLoader.loadClass(PAGE_INDICATOR)
            if (!View::class.java.isAssignableFrom(indicatorClass)) {
                error("$PAGE_INDICATOR 不是 View")
            }
            val constructor = indicatorClass.getConstructor(
                Context::class.java,
                AttributeSet::class.java
            )
            val pageIndicator = constructor.newInstance(
                player.context,
                null as AttributeSet?
            ) as View
            setNumPages = indicatorClass.getMethod(
                "setNumPages",
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
            setLocation = indicatorClass.getMethod(
                "setLocation",
                Float::class.javaPrimitiveType
            ).apply { isAccessible = true }
            setIndex = findMethod(indicatorClass, "setIndex") {
                it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            setTintListMethod = findMethod(indicatorClass, "setTintList") {
                it.parameterCount == 1 &&
                    it.parameterTypes[0] == ColorStateList::class.java
            }
            animatingField = findField(indicatorClass, "mAnimating") {
                it.type == Boolean::class.javaPrimitiveType
            }
            positionField = findField(indicatorClass, "mPosition") {
                it.type == Int::class.javaPrimitiveType
            }
            queuedPositionsField = findField(indicatorClass, "mQueuedPositions") {
                List::class.java.isAssignableFrom(it.type)
            }
            pageIndicator
        }.onFailure { error ->
            warnOnce("SystemUI 原生 PageIndicator 不可用", error)
        }.getOrNull()
    }

    private var tintColor: Int? = null

    private fun applyTint(view: View) {
        val color = tintColor ?: return
        val method = setTintListMethod ?: return
        runCatching {
            method.invoke(view, ColorStateList.valueOf(color))
        }.onFailure { error ->
            warnOnce("更新媒体圆点颜色失败", error)
        }
    }

    private fun invokeNumPages(view: View, count: Int): Boolean {
        return runCatching {
            setNumPages?.invoke(view, count)
        }.onFailure { error ->
            warnOnce("更新媒体圆点数量失败", error)
        }.isSuccess
    }

    private fun ensurePageCount(view: View, count: Int): Boolean {
        if (configuredPageCount == count) return true
        if (!invokeNumPages(view, count)) return false
        configuredPageCount = count
        return true
    }

    private fun hide(view: View) {
        if (configuredPageCount != 0) {
            invokeNumPages(view, 0)
            configuredPageCount = 0
        }
    }

    private fun forceSetPosition(view: View, index: Int): Boolean {
        val setIndexMethod = setIndex ?: return false
        val animating = animatingField ?: return false
        val position = positionField ?: return false
        val queuedPositions = queuedPositionsField ?: return false
        return runCatching {
            val queue = queuedPositions.get(view) as? MutableList<*>
                ?: error("PageIndicator.mQueuedPositions 类型不匹配")
            queue.clear()
            animating.setBoolean(view, false)
            setIndexMethod.invoke(view, index)
            position.setInt(view, index shl 1)
            animating.setBoolean(view, false)
        }.onFailure { error ->
            warnOnce("强制同步媒体圆点位置失败", error)
        }.isSuccess
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

    private fun findField(
        clazz: Class<*>,
        name: String,
        predicate: (Field) -> Boolean
    ): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            runCatching { current.getDeclaredField(name) }
                .onSuccess { field -> field.isAccessible = true }
                .getOrNull()
                ?.takeIf(predicate)
                ?.let { return it }
            current = current.superclass
        }
        return null
    }

    private var warned = false

    private fun warnOnce(message: String, error: Throwable? = null) {
        if (warned) return
        warned = true
        HookLogger.w(TAG, message, error)
    }
}
