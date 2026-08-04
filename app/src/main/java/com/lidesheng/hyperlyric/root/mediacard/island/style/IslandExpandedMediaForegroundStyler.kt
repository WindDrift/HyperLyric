package com.lidesheng.hyperlyric.root.mediacard.island.style

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Color
import android.graphics.ColorFilter
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.lidesheng.hyperlyric.root.mediacard.notification.background.NotificationMediaColorConfig
import java.util.Collections
import java.util.WeakHashMap

internal data class IslandExpandedMediaForegroundColors(
    val primaryText: Int,
    val secondaryText: Int,
    val durationText: Int,
    val action: Int,
    val seekBarForeground: Int,
    val seekBarBackground: Int
) {
    companion object {
        fun from(context: Context): IslandExpandedMediaForegroundColors {
            fun color(name: String): Int {
                val id = context.resources.getIdentifier(name, "color", context.packageName)
                require(id != 0) { "Missing color resource: $name" }
                return context.getColor(id)
            }
            return IslandExpandedMediaForegroundColors(
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

internal interface IslandExpandedMediaForegroundAccess {
    fun getTitleText(holder: Any): TextView

    fun getArtistText(holder: Any): TextView

    fun getElapsedTime(holder: Any): TextView

    fun getTotalTime(holder: Any): TextView

    fun getSeamlessIcon(holder: Any): ImageView

    fun getActionViews(holder: Any): List<ImageView>

    fun getSeekBar(holder: Any): View

    fun setSeekBarForeground(seekBar: View, color: Int)

    fun setSeekBarBackground(seekBar: View, color: Int)

    fun getSeekBarShaderColorFilter(seekBar: View): ColorFilter?

    fun setSeekBarShaderColorFilter(seekBar: View, colorFilter: ColorFilter?)

    fun getSeekBarHeadGlowAlpha(seekBar: View): Float

    fun setSeekBarHeadGlowAlpha(seekBar: View, alpha: Float)
}

internal object IslandExpandedMediaForegroundStyler {
    private val seekBarThemeStates = Collections.synchronizedMap(
        WeakHashMap<View, SeekBarThemeState>()
    )
    private val islandSeekBars = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    )

    fun applyLightForeground(
        access: IslandExpandedMediaForegroundAccess,
        holder: Any,
        colors: IslandExpandedMediaForegroundColors
    ) {
        val seekBar = access.getSeekBar(holder)
        val state = seekBarThemeStates.getOrPut(seekBar) {
            SeekBarThemeState(
                originalColorFilter = access.getSeekBarShaderColorFilter(seekBar),
                originalHeadGlowAlpha = access.getSeekBarHeadGlowAlpha(seekBar)
            )
        }
        state.suppressHeadGlow = true

        access.getTitleText(holder).setTextColor(colors.primaryText)
        access.getArtistText(holder).setTextColor(colors.secondaryText)
        access.getElapsedTime(holder).setTextColor(colors.durationText)
        access.getTotalTime(holder).setTextColor(colors.durationText)

        access.getSeamlessIcon(holder).imageTintList =
            ColorStateList.valueOf(colors.seekBarForeground)
        val actionTint = ColorStateList.valueOf(colors.action)
        access.getActionViews(holder).forEach { action ->
            action.imageTintBlendMode = BlendMode.SRC_IN
            action.imageTintList = actionTint
        }

        access.setSeekBarForeground(seekBar, colors.seekBarForeground)
        access.setSeekBarBackground(seekBar, colors.seekBarBackground)
        access.setSeekBarShaderColorFilter(
            seekBar,
            BlendModeColorFilter(colors.seekBarForeground, BlendMode.SRC_IN)
        )
        access.setSeekBarHeadGlowAlpha(seekBar, 0f)
    }

    fun applyCustomForeground(
        access: IslandExpandedMediaForegroundAccess,
        holder: Any,
        colors: NotificationMediaColorConfig
    ) {
        val seekBar = access.getSeekBar(holder)
        val state = seekBarThemeStates.getOrPut(seekBar) {
            SeekBarThemeState(
                originalColorFilter = access.getSeekBarShaderColorFilter(seekBar),
                originalHeadGlowAlpha = access.getSeekBarHeadGlowAlpha(seekBar)
            )
        }
        state.suppressHeadGlow = false

        access.getTitleText(holder).setTextColor(colors.textPrimary)
        access.getArtistText(holder).setTextColor(colors.textSecondary)
        access.getElapsedTime(holder).setTextColor(colors.textSecondary)
        access.getTotalTime(holder).setTextColor(colors.textSecondary)

        val tint = ColorStateList.valueOf(colors.textPrimary)
        access.getSeamlessIcon(holder).imageTintList = tint
        access.getActionViews(holder).forEach { action ->
            action.imageTintBlendMode = BlendMode.SRC_IN
            action.imageTintList = tint
        }

        access.setSeekBarForeground(seekBar, colors.textPrimary)
        access.setSeekBarBackground(
            seekBar,
            colors.textPrimary and 0x00ffffff or (0x33 shl 24)
        )
        access.setSeekBarShaderColorFilter(
            seekBar,
            BlendModeColorFilter(colors.textPrimary, BlendMode.SRC_IN)
        )
        access.setSeekBarHeadGlowAlpha(seekBar, state.originalHeadGlowAlpha)
    }

    fun restore(access: IslandExpandedMediaForegroundAccess, holder: Any) {
        val seekBar = access.getSeekBar(holder)
        seekBarThemeStates.remove(seekBar)?.let { state ->
            access.setSeekBarShaderColorFilter(seekBar, state.originalColorFilter)
            access.setSeekBarHeadGlowAlpha(seekBar, state.originalHeadGlowAlpha)
        }
    }

    fun trackSeekBar(seekBar: View) {
        islandSeekBars.add(seekBar)
    }

    fun untrackSeekBar(seekBar: View) {
        islandSeekBars.remove(seekBar)
    }

    fun isTracked(seekBar: View): Boolean = islandSeekBars.contains(seekBar)

    fun shouldSuppressHeadGlow(seekBar: View): Boolean =
        seekBarThemeStates[seekBar]?.suppressHeadGlow == true

    private data class SeekBarThemeState(
        val originalColorFilter: ColorFilter?,
        val originalHeadGlowAlpha: Float,
        var suppressHeadGlow: Boolean = false
    )
}
