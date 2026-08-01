package com.lidesheng.hyperlyric.root.mediacard

import android.content.SharedPreferences
import com.lidesheng.hyperlyric.common.RootConstants

internal object MediaCardRuntimeConfig {
    @Volatile
    var current: Snapshot = Snapshot.defaults()
        private set

    fun load(prefs: SharedPreferences) {
        current = Snapshot.from(prefs)
    }

    data class Snapshot(
        val enabled: Boolean,
        val notification: Notification,
        val islandExpanded: IslandExpanded
    ) {
        companion object {
            fun defaults() = Snapshot(
                enabled = RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND,
                notification = Notification.defaults(),
                islandExpanded = IslandExpanded.defaults()
            )

            fun from(prefs: SharedPreferences) = Snapshot(
                enabled = prefs.getBoolean(
                    RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND,
                    RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND
                ),
                notification = Notification.from(prefs),
                islandExpanded = IslandExpanded.from(prefs)
            )
        }
    }

    data class Notification(
        val layoutStyle: Int,
        val ambientFlowMode: Int,
        val cardTheme: Int,
        val coverStyle: Int,
        val progressStyle: Int,
        val progressHeadGlow: Boolean,
        val thumbStyle: Int,
        val hideCoverSource: Boolean,
        val hideCoverShadow: Boolean,
        val disableCoverFlip: Boolean,
        val hideDeviceSwitch: Boolean,
        val hideCustomActions: Boolean,
        val hideTime: Boolean,
        val actionAlignLeft: Boolean,
        val actionOrder: Int,
        val backgroundStyle: Int,
        val backgroundBlur: Int,
        val backgroundColorAnimation: Boolean,
        val backgroundAutoInvert: Boolean,
        val softCoverTone: Int
    ) {
        companion object {
            fun defaults() = Notification(
                layoutStyle = RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_LAYOUT_STYLE,
                ambientFlowMode = RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE,
                cardTheme = RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_CARD_THEME,
                coverStyle = RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_COVER_STYLE,
                progressStyle = RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_PROGRESS_STYLE,
                progressHeadGlow =
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_PROGRESS_HEAD_GLOW,
                thumbStyle = RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_THUMB_STYLE,
                hideCoverSource = RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_HIDE_COVER_SOURCE,
                hideCoverShadow =
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_HIDE_COVER_SHADOW,
                disableCoverFlip =
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_DISABLE_COVER_FLIP,
                hideDeviceSwitch = RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_HIDE_DEVICE_SWITCH,
                hideCustomActions =
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_HIDE_CUSTOM_ACTIONS,
                hideTime = RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_HIDE_TIME,
                actionAlignLeft =
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_ACTION_ALIGN_LEFT,
                actionOrder = RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_ACTION_ORDER,
                backgroundStyle = RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_BACKGROUND_STYLE,
                backgroundBlur = RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_BACKGROUND_BLUR,
                backgroundColorAnimation =
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_BACKGROUND_COLOR_ANIMATION,
                backgroundAutoInvert =
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_BACKGROUND_AUTO_INVERT,
                softCoverTone = RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_SOFT_COVER_TONE
            )

            fun from(prefs: SharedPreferences) = Notification(
                layoutStyle = prefs.getInt(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_LAYOUT_STYLE,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_LAYOUT_STYLE
                ).let { style ->
                    if (style == RootConstants.NOTIFICATION_MEDIA_LAYOUT_STYLE_IOS) {
                        style
                    } else {
                        RootConstants.NOTIFICATION_MEDIA_LAYOUT_STYLE_SYSTEM
                    }
                },
                ambientFlowMode = prefs.getInt(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE
                ).coerceIn(
                    RootConstants.NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE_DISABLED,
                    RootConstants.NOTIFICATION_MEDIA_AMBIENT_FLOW_MODE_CUSTOM_FULL
                ),
                cardTheme = prefs.getInt(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_CARD_THEME,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_CARD_THEME
                ).coerceIn(
                    RootConstants.MEDIA_CARD_THEME_FOLLOW_SYSTEM,
                    RootConstants.MEDIA_CARD_THEME_ALWAYS_DARK
                ),
                coverStyle = prefs.getInt(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_COVER_STYLE,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_COVER_STYLE
                ).coerceIn(
                    RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_DEFAULT,
                    RootConstants.NOTIFICATION_MEDIA_COVER_STYLE_HIDDEN
                ),
                progressStyle = prefs.getInt(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_PROGRESS_STYLE,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_PROGRESS_STYLE
                ).let { style ->
                    if (style == RootConstants.NOTIFICATION_MEDIA_PROGRESS_STYLE_WAVE) {
                        RootConstants.NOTIFICATION_MEDIA_PROGRESS_STYLE_WAVE
                    } else {
                        RootConstants.NOTIFICATION_MEDIA_PROGRESS_STYLE_DEFAULT
                    }
                },
                progressHeadGlow = prefs.getBoolean(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_PROGRESS_HEAD_GLOW,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_PROGRESS_HEAD_GLOW
                ) || prefs.getInt(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_PROGRESS_STYLE,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_PROGRESS_STYLE
                ) == LEGACY_NOTIFICATION_MEDIA_PROGRESS_STYLE_GLOW,
                thumbStyle = prefs.getInt(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_THUMB_STYLE,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_THUMB_STYLE
                ).coerceIn(
                    RootConstants.NOTIFICATION_MEDIA_THUMB_STYLE_DEFAULT,
                    RootConstants.NOTIFICATION_MEDIA_THUMB_STYLE_HIDDEN
                ),
                hideCoverSource = prefs.getBoolean(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_HIDE_COVER_SOURCE,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_HIDE_COVER_SOURCE
                ),
                hideCoverShadow = prefs.getBoolean(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_HIDE_COVER_SHADOW,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_HIDE_COVER_SHADOW
                ),
                disableCoverFlip = prefs.getBoolean(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_DISABLE_COVER_FLIP,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_DISABLE_COVER_FLIP
                ),
                hideDeviceSwitch = prefs.getBoolean(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_HIDE_DEVICE_SWITCH,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_HIDE_DEVICE_SWITCH
                ),
                hideCustomActions = prefs.getBoolean(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_HIDE_CUSTOM_ACTIONS,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_HIDE_CUSTOM_ACTIONS
                ),
                hideTime = prefs.getBoolean(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_HIDE_TIME,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_HIDE_TIME
                ),
                actionAlignLeft = prefs.getBoolean(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_ACTION_ALIGN_LEFT,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_ACTION_ALIGN_LEFT
                ),
                actionOrder = prefs.getInt(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_ACTION_ORDER,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_ACTION_ORDER
                ).coerceIn(
                    RootConstants.NOTIFICATION_MEDIA_ACTION_ORDER_DEFAULT,
                    RootConstants.NOTIFICATION_MEDIA_ACTION_ORDER_PLAY_LEFT
                ),
                backgroundStyle = prefs.getInt(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_BACKGROUND_STYLE,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_BACKGROUND_STYLE
                ).coerceIn(
                    RootConstants.NOTIFICATION_MEDIA_BACKGROUND_STYLE_DEFAULT,
                    RootConstants.NOTIFICATION_MEDIA_BACKGROUND_STYLE_SOFT_COVER
                ),
                backgroundBlur = prefs.getInt(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_BACKGROUND_BLUR,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_BACKGROUND_BLUR
                ).coerceIn(1, 20),
                backgroundColorAnimation = prefs.getBoolean(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_BACKGROUND_COLOR_ANIMATION,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_BACKGROUND_COLOR_ANIMATION
                ),
                backgroundAutoInvert = prefs.getBoolean(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_BACKGROUND_AUTO_INVERT,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_BACKGROUND_AUTO_INVERT
                ),
                softCoverTone = prefs.getInt(
                    RootConstants.KEY_HOOK_NOTIFICATION_MEDIA_SOFT_COVER_TONE,
                    RootConstants.DEFAULT_HOOK_NOTIFICATION_MEDIA_SOFT_COVER_TONE
                ).coerceIn(
                    RootConstants.MEDIA_SOFT_COVER_TONE_LIGHT,
                    RootConstants.MEDIA_SOFT_COVER_TONE_DARK
                )
            )
        }
    }

    data class IslandExpanded(
        val ambientFlowMode: Int,
        val cardTheme: Int,
        val coverStyle: Int,
        val progressStyle: Int,
        val progressHeadGlow: Boolean,
        val thumbStyle: Int,
        val hideCoverSource: Boolean,
        val disableCoverFlip: Boolean,
        val hideDeviceSwitch: Boolean,
        val hideCustomActions: Boolean,
        val hideTime: Boolean,
        val actionAlignLeft: Boolean,
        val actionOrder: Int,
        val backgroundStyle: Int,
        val backgroundBlur: Int,
        val backgroundColorAnimation: Boolean,
        val backgroundAutoInvert: Boolean,
        val softCoverTone: Int
    ) {
        companion object {
            fun defaults() = IslandExpanded(
                ambientFlowMode =
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE,
                cardTheme = RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_CARD_THEME,
                coverStyle = RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_COVER_STYLE,
                progressStyle = RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_PROGRESS_STYLE,
                progressHeadGlow =
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_PROGRESS_HEAD_GLOW,
                thumbStyle = RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_THUMB_STYLE,
                hideCoverSource =
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_COVER_SOURCE,
                disableCoverFlip =
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_DISABLE_COVER_FLIP,
                hideDeviceSwitch =
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_DEVICE_SWITCH,
                hideCustomActions =
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_CUSTOM_ACTIONS,
                hideTime = RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_TIME,
                actionAlignLeft =
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_ACTION_ALIGN_LEFT,
                actionOrder = RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_ACTION_ORDER,
                backgroundStyle =
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE,
                backgroundBlur = RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_BLUR,
                backgroundColorAnimation =
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_COLOR_ANIMATION,
                backgroundAutoInvert =
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_AUTO_INVERT,
                softCoverTone = RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_SOFT_COVER_TONE
            )

            fun from(prefs: SharedPreferences) = IslandExpanded(
                ambientFlowMode = prefs.getInt(
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE,
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE
                ).coerceIn(
                    RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_DEFAULT,
                    RootConstants.ISLAND_EXPANDED_MEDIA_AMBIENT_FLOW_MODE_CUSTOM_FULL
                ),
                cardTheme = prefs.getInt(
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_CARD_THEME,
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_CARD_THEME
                ).coerceIn(
                    RootConstants.MEDIA_CARD_THEME_FOLLOW_SYSTEM,
                    RootConstants.MEDIA_CARD_THEME_ALWAYS_DARK
                ),
                coverStyle = prefs.getInt(
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_COVER_STYLE,
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_COVER_STYLE
                ).coerceIn(
                    RootConstants.ISLAND_EXPANDED_MEDIA_COVER_STYLE_DEFAULT,
                    RootConstants.ISLAND_EXPANDED_MEDIA_COVER_STYLE_HIDDEN
                ),
                progressStyle = prefs.getInt(
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_PROGRESS_STYLE,
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_PROGRESS_STYLE
                ).coerceIn(
                    RootConstants.ISLAND_EXPANDED_MEDIA_PROGRESS_STYLE_DEFAULT,
                    RootConstants.ISLAND_EXPANDED_MEDIA_PROGRESS_STYLE_WAVE
                ),
                progressHeadGlow = prefs.getBoolean(
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_PROGRESS_HEAD_GLOW,
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_PROGRESS_HEAD_GLOW
                ),
                thumbStyle = prefs.getInt(
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_THUMB_STYLE,
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_THUMB_STYLE
                ).coerceIn(
                    RootConstants.ISLAND_EXPANDED_MEDIA_THUMB_STYLE_DEFAULT,
                    RootConstants.ISLAND_EXPANDED_MEDIA_THUMB_STYLE_HIDDEN
                ),
                hideCoverSource = prefs.getBoolean(
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_COVER_SOURCE,
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_COVER_SOURCE
                ),
                disableCoverFlip = prefs.getBoolean(
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_DISABLE_COVER_FLIP,
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_DISABLE_COVER_FLIP
                ),
                hideDeviceSwitch = prefs.getBoolean(
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_DEVICE_SWITCH,
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_DEVICE_SWITCH
                ),
                hideCustomActions = prefs.getBoolean(
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_CUSTOM_ACTIONS,
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_CUSTOM_ACTIONS
                ),
                hideTime = prefs.getBoolean(
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_TIME,
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_HIDE_TIME
                ),
                actionAlignLeft = prefs.getBoolean(
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_ACTION_ALIGN_LEFT,
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_ACTION_ALIGN_LEFT
                ),
                actionOrder = prefs.getInt(
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_ACTION_ORDER,
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_ACTION_ORDER
                ).coerceIn(
                    RootConstants.ISLAND_EXPANDED_MEDIA_ACTION_ORDER_DEFAULT,
                    RootConstants.ISLAND_EXPANDED_MEDIA_ACTION_ORDER_PLAY_LEFT
                ),
                backgroundStyle = prefs.getInt(
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE,
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE
                ).coerceIn(
                    RootConstants.ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE_DEFAULT,
                    RootConstants.ISLAND_EXPANDED_MEDIA_BACKGROUND_STYLE_SOFT_COVER
                ),
                backgroundBlur = prefs.getInt(
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_BLUR,
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_BLUR
                ).coerceIn(1, 20),
                backgroundColorAnimation = prefs.getBoolean(
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_COLOR_ANIMATION,
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_COLOR_ANIMATION
                ),
                backgroundAutoInvert = prefs.getBoolean(
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_AUTO_INVERT,
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_BACKGROUND_AUTO_INVERT
                ),
                softCoverTone = prefs.getInt(
                    RootConstants.KEY_HOOK_ISLAND_EXPANDED_MEDIA_SOFT_COVER_TONE,
                    RootConstants.DEFAULT_HOOK_ISLAND_EXPANDED_MEDIA_SOFT_COVER_TONE
                ).coerceIn(
                    RootConstants.MEDIA_SOFT_COVER_TONE_LIGHT,
                    RootConstants.MEDIA_SOFT_COVER_TONE_DARK
                )
            )
        }
    }

    private const val LEGACY_NOTIFICATION_MEDIA_PROGRESS_STYLE_GLOW = 2

    private fun SharedPreferences.float(
        key: String,
        default: Float,
        min: Float,
        max: Float
    ): Float = runCatching { getFloat(key, default) }
        .recoverCatching { getInt(key, default.toInt()).toFloat() }
        .getOrDefault(default)
        .coerceIn(min, max)
}
