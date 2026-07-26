package com.lidesheng.hyperlyric.root.mediacard.notification.layout

internal const val NOTIFICATION_MEDIA_MIUI_HORIZONTAL_MARGIN_DP = 21f

internal enum class NotificationMediaTopSlotContent {
    NONE,
    MUSIC_WAVE,
    APP_ICON,
    APP_IDENTITY
}

internal data class NotificationMediaLayoutSpec(
    val moveDeviceToAction4: Boolean,
    val moveDeviceToActionRow: Boolean,
    val removeSeekBarTrackInset: Boolean,
    val seekBarPaddingTopDp: Float?,
    val seekBarPaddingBottomDp: Float?,
    val semanticActionIconScale: Float?,
    val actionButtonScale: Float?,
    val topSlotContent: NotificationMediaTopSlotContent,
    val action2ButtonScale: Float? = actionButtonScale
)

internal fun notificationMediaLayoutSpec(style: Int): NotificationMediaLayoutSpec {
    return NotificationMediaLayoutSpec(
        moveDeviceToAction4 = false,
        moveDeviceToActionRow = false,
        removeSeekBarTrackInset = false,
        seekBarPaddingTopDp = null,
        seekBarPaddingBottomDp = null,
        semanticActionIconScale = null,
        actionButtonScale = null,
        topSlotContent = NotificationMediaTopSlotContent.NONE
    )
}
