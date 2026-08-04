package com.lidesheng.hyperlyric.root.mediacard.notification.style

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.root.mediacard.MediaCardRuntimeConfig
import com.lidesheng.hyperlyric.root.mediacard.notification.background.NotificationMediaColorConfig
import com.lidesheng.hyperlyric.root.utils.HookLogger
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the foreground appearance of a notification media card.
 *
 * Background rendering produces [NotificationMediaColorConfig], but applying
 * those colors to the native holder, preserving SeekBar state, and exposing
 * the currently visible foreground are separate responsibilities. Keeping
 * them here lets the background controller stay focused on bitmap lifecycle.
 */
internal object NotificationMediaForegroundStyler {
    private const val TAG = "NotificationMediaForegroundStyler"
    private const val LIGHT_CARD_FOREGROUND = 0xff202020.toInt()
    private const val DARK_CARD_FOREGROUND = 0xffe6ffffff.toInt()

    private val foregroundIconFields = arrayOf(
        "seamlessIcon", "action0", "action1", "action2", "action3", "action4"
    )
    private val appliedColors = Collections.synchronizedMap(WeakHashMap<Any, Int>())
    private val seekBarColors = Collections.synchronizedMap(WeakHashMap<SeekBar, Int>())
    private val seekBarStates = Collections.synchronizedMap(
        WeakHashMap<SeekBar, SeekBarState>()
    )
    @Volatile
    private var foregroundColorsAppliedListener: ((Any) -> Unit)? = null
    private val foregroundColorsAppliedListeners = CopyOnWriteArrayList<(Any) -> Unit>()

    fun setAppliedListener(listener: ((Any) -> Unit)?) {
        foregroundColorsAppliedListener = listener
    }

    fun addAppliedListener(listener: (Any) -> Unit) {
        foregroundColorsAppliedListeners.addIfAbsent(listener)
    }

    fun apply(controller: Any, holder: Any, colors: NotificationMediaColorConfig) {
        applyForeground(holder, colors)
        appliedColors[controller] = colors.textPrimary
        notifyApplied(controller)
    }

    fun forget(controller: Any) {
        appliedColors.remove(controller)
    }

    fun clear(controller: Any) {
        appliedColors.remove(controller)
        clearSeekBarColor(controller)
    }

    /**
     * Returns the foreground currently visible on the card. Custom renderer
     * state wins; native holder colors cover the default background style.
     */
    fun foregroundColor(controller: Any): Int {
        appliedColors[controller]?.let { return it }

        val holder = readField(controller, "holder")
        if (holder != null) {
            readField(holder, "seamlessIcon")
                .let { it as? ImageView }
                ?.imageTintList
                ?.defaultColor
                ?.let { return it }
            readField(holder, "artistText")
                .let { it as? TextView }
                ?.currentTextColor
                ?.let { return it }
            readField(holder, "titleText")
                .let { it as? TextView }
                ?.currentTextColor
                ?.let { return it }
        }

        return fallbackForegroundColor(controller)
    }

    fun applySeekBarColor(seekBar: Any) {
        val view = seekBar as? SeekBar ?: return
        val color = seekBarColors[view] ?: return
        applySeekBarColor(view, color)
    }

    fun applySeekBarColor(view: SeekBar, color: Int) {
        applySeekBarForegroundColor(view, color)
        (readField(view, "mBackgroundDrawable") as? Drawable)?.colorFilter =
            PorterDuffColorFilter(
                color and 0x00ffffff or (0x33 shl 24),
                PorterDuff.Mode.SRC_IN
            )
    }

    fun applySeekBarForegroundColor(view: SeekBar, color: Int) {
        val filter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        (readField(view, "mPaint") as? Paint)?.colorFilter = filter
        (readField(view, "mProgressDrawable") as? Drawable)?.colorFilter = filter
    }

    private fun applyForeground(holder: Any, colors: NotificationMediaColorConfig) {
        val primary = ColorStateList.valueOf(colors.textPrimary)
        (readField(holder, "titleText") as? TextView)?.setTextColor(colors.textPrimary)
        (readField(holder, "artistText") as? TextView)?.setTextColor(colors.textSecondary)
        foregroundIconFields.forEach { fieldName ->
            (readField(holder, fieldName) as? ImageView)?.imageTintList = primary
        }
        (readField(holder, "elapsedTimeView") as? TextView)?.setTextColor(colors.textPrimary)
        (readField(holder, "totalTimeView") as? TextView)?.setTextColor(colors.textPrimary)
        val seekBar = readField(holder, "seekBar") as? SeekBar ?: return
        seekBarStates.getOrPut(seekBar) {
            SeekBarState(
                thumbTintList = seekBar.thumbTintList,
                progressTintList = seekBar.progressTintList,
                progressBackgroundTintList = seekBar.progressBackgroundTintList,
                paintColorFilter = (readField(seekBar, "mPaint") as? Paint)?.colorFilter,
                progressDrawableColorFilter =
                    (readField(seekBar, "mProgressDrawable") as? Drawable)?.colorFilter,
                backgroundDrawableColorFilter =
                    (readField(seekBar, "mBackgroundDrawable") as? Drawable)?.colorFilter
            )
        }
        seekBar.thumbTintList = primary
        seekBar.progressTintList = primary
        seekBar.progressBackgroundTintList = ColorStateList.valueOf(
            colors.textPrimary and 0x00ffffff or (0x33 shl 24)
        )
        seekBarColors[seekBar] = colors.textPrimary
        seekBar.invalidate()
    }

    private fun clearSeekBarColor(controller: Any) {
        val holder = readField(controller, "holder") ?: return
        val seekBar = readField(holder, "seekBar") as? SeekBar ?: return
        seekBarColors.remove(seekBar)
        seekBarStates.remove(seekBar)?.let { state ->
            restoreSeekBarState(seekBar, state)
        }
    }

    private fun restoreSeekBarState(seekBar: SeekBar, state: SeekBarState) {
        seekBar.thumbTintList = state.thumbTintList
        seekBar.progressTintList = state.progressTintList
        seekBar.progressBackgroundTintList = state.progressBackgroundTintList
        (readField(seekBar, "mPaint") as? Paint)?.colorFilter = state.paintColorFilter
        (readField(seekBar, "mProgressDrawable") as? Drawable)?.colorFilter =
            state.progressDrawableColorFilter
        (readField(seekBar, "mBackgroundDrawable") as? Drawable)?.colorFilter =
            state.backgroundDrawableColorFilter
        seekBar.invalidate()
    }

    private fun fallbackForegroundColor(controller: Any): Int {
        val context = readField(controller, "context") as? Context
        val light = when (MediaCardRuntimeConfig.current.notification.cardTheme) {
            RootConstants.MEDIA_CARD_THEME_ALWAYS_LIGHT -> true
            RootConstants.MEDIA_CARD_THEME_ALWAYS_DARK -> false
            else -> context?.resources?.configuration?.let { configuration ->
                configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK !=
                    Configuration.UI_MODE_NIGHT_YES
            } ?: false
        }
        return if (light) LIGHT_CARD_FOREGROUND else DARK_CARD_FOREGROUND
    }

    private fun notifyApplied(controller: Any) {
        runCatching { foregroundColorsAppliedListener?.invoke(controller) }
            .onFailure { error ->
                HookLogger.e(TAG, "通知中心媒体前景色同步回调失败", error)
            }
        foregroundColorsAppliedListeners.forEach { listener ->
            runCatching { listener(controller) }
                .onFailure { error ->
                    HookLogger.e(TAG, "通知中心媒体前景色观察者回调失败", error)
                }
        }
    }

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

    private data class SeekBarState(
        val thumbTintList: ColorStateList?,
        val progressTintList: ColorStateList?,
        val progressBackgroundTintList: ColorStateList?,
        val paintColorFilter: ColorFilter?,
        val progressDrawableColorFilter: ColorFilter?,
        val backgroundDrawableColorFilter: ColorFilter?
    )
}
