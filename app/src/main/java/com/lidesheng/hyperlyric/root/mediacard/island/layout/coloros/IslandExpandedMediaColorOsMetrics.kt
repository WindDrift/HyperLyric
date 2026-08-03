package com.lidesheng.hyperlyric.root.mediacard.island.layout.coloros

import android.content.Context
import com.lidesheng.hyperlyric.root.mediacard.island.layout.islandExpandedMediaDimenPx

/** Geometry for ColorOS-style content inside the Super Island player. */
internal object IslandExpandedMediaColorOsMetrics {
    const val ROOT_HEIGHT_DP = 168f
    const val COVER_SIZE_DP = 72f
    const val COVER_TOP_DP = 15f
    const val COVER_START_DP = 15f
    const val CONTENT_GAP_DP = 10f
    const val CONTENT_END_DP = 15f

    const val TITLE_TOP_DP = 17f
    const val ARTIST_GAP_DP = 5f
    const val APP_ICON_SIZE_DP = 22f
    const val APP_ICON_TOP_DP = 17f
    const val APP_ICON_END_DP = 15f
    const val APP_ICON_TEXT_GAP_DP = 8f

    const val PROGRESS_HEIGHT_DP = 38f
    const val PROGRESS_OVERLAP_DP = 8f
    const val TIME_WIDTH_DP = 48f
    const val ACTION_HEIGHT_DP = 50f
    const val ACTION_BOTTOM_DP = 5f

    fun rootHeightPx(context: Context): Int {
        return context.islandExpandedMediaDimenPx("expanded_max_height", ROOT_HEIGHT_DP)
    }
}
