package com.lidesheng.hyperlyric.root.mediacard.island.layout.ios

import android.content.Context
import android.view.View
import com.lidesheng.hyperlyric.root.mediacard.island.layout.IslandExpandedMediaLayoutResourceIds
import com.lidesheng.hyperlyric.root.mediacard.island.layout.islandExpandedMediaDp

/**
 * The island album container owns a nested image view.  The root ConstraintSet
 * can resize only the container, so the image must follow it after each real or
 * dummy player layout application or the parent outline clips the artwork.
 */
internal object IslandExpandedMediaIosAlbumArtSync {
    fun apply(player: View, context: Context, ids: IslandExpandedMediaLayoutResourceIds) {
        val size = context.islandExpandedMediaDp(IslandExpandedMediaIosMetrics.COVER_SIZE_DP)
        updateSize(player.findViewById(ids.albumArt), size)
        if (ids.albumArtImage != 0) {
            updateSize(player.findViewById(ids.albumArtImage), size)
        }
    }

    private fun updateSize(view: View?, size: Int) {
        val params = view?.layoutParams ?: return
        if (params.width == size && params.height == size) return
        params.width = size
        params.height = size
        view.layoutParams = params
        view.requestLayout()
    }
}
