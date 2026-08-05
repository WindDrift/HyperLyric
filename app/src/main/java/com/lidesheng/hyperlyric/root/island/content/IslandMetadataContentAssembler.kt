package com.lidesheng.hyperlyric.root.island.content

import android.view.View
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import com.lidesheng.hyperlyric.lyric.model.RichLyricLine
import com.lidesheng.hyperlyric.lyric.view.RichLyricLineView
import com.lidesheng.hyperlyric.lyric.view.SpaceGateRichLyricLineView
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.island.config.IslandSlotRuntimeConfig

internal object IslandMetadataContentAssembler {

    fun apply(
        view: View,
        config: IslandSlotRuntimeConfig,
        mode: Int,
        force: Boolean,
        mediaInfo: MediaMetadataHelper.MediaInfo
    ): Boolean {
        val songName =
            LyriconDataBridge.currentSongName?.takeIf { it.isNotEmpty() } ?: mediaInfo.title
        val artistName = mediaInfo.artist
        val albumName = mediaInfo.album

        val signature = listOf(
            "metadata",
            mode,
            songName,
            artistName,
            albumName,
            config.metadataMarqueeEnabled,
            config.metadataMarqueeSpeed,
            config.metadataMarqueeDelay,
            config.metadataMarqueeLoopDelay,
            config.metadataMarqueeInfinite
        ).joinToString("|")
        if (!force && IslandSlotContentSignatureCache.get(view) == signature) return false

        val singleModeText = when (mode) {
            1 -> songName
            2 -> artistName
            3 -> albumName
            4 -> "$songName - $artistName"
            else -> ""
        }
        val newLine = when (mode) {
            1, 2, 3, 4 -> RichLyricLine(text = singleModeText, words = emptyList())
            5 -> RichLyricLine(
                text = songName,
                words = emptyList(),
                secondary = artistName,
                secondaryWords = emptyList()
            )

            6 -> {
                val secondary = if (albumName.isEmpty()) artistName else "$artistName - $albumName"
                RichLyricLine(
                    text = songName,
                    words = emptyList(),
                    secondary = secondary,
                    secondaryWords = emptyList()
                )
            }

            else -> null
        }

        when (view) {
            is RichLyricLineView -> {
                view.line = newLine
                applyMarquee(view, config)
            }

            is SpaceGateRichLyricLineView -> {
                view.line = newLine
                applyMarquee(view, config)
            }
        }
        IslandSlotContentSignatureCache.set(view, signature)
        return true
    }

    fun invalidate(view: View? = null) {
        IslandSlotContentSignatureCache.invalidate(view)
    }

    private fun applyMarquee(view: RichLyricLineView, config: IslandSlotRuntimeConfig) {
        if (!config.metadataMarqueeEnabled) return
        view.setMetadataMarqueeConfig(
            config.metadataMarqueeSpeed.toFloat(),
            config.metadataMarqueeDelay,
            config.metadataMarqueeLoopDelay,
            if (config.metadataMarqueeInfinite) -1 else 1,
            true
        )
        view.main.setPeerLineWidth(view.secondary.lineWidth)
        view.secondary.setPeerLineWidth(view.main.lineWidth)
        view.post { view.requestStartMarquee() }
    }

    private fun applyMarquee(
        view: SpaceGateRichLyricLineView,
        config: IslandSlotRuntimeConfig
    ) {
        if (!config.metadataMarqueeEnabled) return
        view.setMetadataMarqueeConfig(
            config.metadataMarqueeSpeed.toFloat(),
            config.metadataMarqueeDelay,
            config.metadataMarqueeLoopDelay,
            if (config.metadataMarqueeInfinite) -1 else 1,
            true
        )
        view.main.setPeerLineWidth(view.secondary.lineWidth)
        view.secondary.setPeerLineWidth(view.main.lineWidth)
        view.post { view.requestStartMarquee() }
    }
}
