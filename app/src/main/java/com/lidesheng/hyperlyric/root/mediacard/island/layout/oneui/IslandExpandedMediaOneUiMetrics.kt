package com.lidesheng.hyperlyric.root.mediacard.island.layout.oneui

import android.content.Context
import com.lidesheng.hyperlyric.root.mediacard.island.layout.islandExpandedMediaDimenPx

/** Geometry for the compact, information-first One UI island player. */
internal object IslandExpandedMediaOneUiMetrics {
    const val ROOT_HEIGHT_DP = 168f
    const val HORIZONTAL_MARGIN_DP = 26f
    const val IDENTITY_TOP_DP = 15f
    const val IDENTITY_HEIGHT_DP = 18f
    const val TITLE_GAP_DP = 3f
    const val ARTIST_GAP_DP = 1f

    const val PROGRESS_HEIGHT_DP = 38f
    const val PROGRESS_OVERLAP_DP = 8f

    const val ACTION_BUTTON_SCALE = 0.8f
    const val ACTION_GAP_DP = 4f
    const val ACTION_BOTTOM_DP = 9f

    const val APP_ICON_SIZE_DP = 16f
    const val APP_NAME_GAP_DP = 6f
    const val APP_NAME_TEXT_SIZE_SP = 12f
    const val APP_NAME_ALPHA = 0.65f

    fun rootHeightPx(context: Context): Int {
        return context.islandExpandedMediaDimenPx("expanded_max_height", ROOT_HEIGHT_DP)
    }
}
