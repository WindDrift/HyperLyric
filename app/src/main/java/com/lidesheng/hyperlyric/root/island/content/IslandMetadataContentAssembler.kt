package com.lidesheng.hyperlyric.root.island.content

import android.content.SharedPreferences
import android.text.format.DateUtils
import android.view.View
import com.lidesheng.hyperlyric.common.MusicInfoLayoutPolicy
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import com.lidesheng.hyperlyric.lyric.model.RichLyricLine
import com.lidesheng.hyperlyric.lyric.view.RichLyricLineView
import com.lidesheng.hyperlyric.lyric.view.SpaceGateRichLyricLineView
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.island.config.IslandSlotRuntimeConfig

internal object IslandMetadataContentAssembler {

    fun apply(
        view: View,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        mode: Int,
        force: Boolean,
        mediaInfo: MediaMetadataHelper.MediaInfo
    ): Boolean {
        val songName = mediaInfo.title.takeIf { it.isNotBlank() }
            ?: LyriconDataBridge.currentSongName.orEmpty()
        val artistName = mediaInfo.artist
        val albumName = mediaInfo.album
        val durationText = formatDuration(mediaInfo.duration)
        val fieldValues = mapOf(
            MusicInfoLayoutPolicy.FIELD_TITLE to songName,
            MusicInfoLayoutPolicy.FIELD_ARTIST to artistName,
            MusicInfoLayoutPolicy.FIELD_ALBUM to albumName,
            MusicInfoLayoutPolicy.FIELD_DURATION to durationText
        )
        val customLayout = mode == RootConstants.ISLAND_CONTENT_MODE_CUSTOM_MUSIC_INFO
        val firstLineFields = if (customLayout) {
            MusicInfoLayoutPolicy.readFields(
                prefs,
                RootConstants.KEY_HOOK_ISLAND_MUSIC_INFO_FIRST_LINE,
                MusicInfoLayoutPolicy.defaultFirstLine
            )
        } else {
            emptyList()
        }
        val secondLineFields = if (customLayout) {
            MusicInfoLayoutPolicy.readFields(
                prefs,
                RootConstants.KEY_HOOK_ISLAND_MUSIC_INFO_SECOND_LINE,
                MusicInfoLayoutPolicy.defaultSecondLine
            )
        } else {
            emptyList()
        }
        val separator = if (customLayout) {
            MusicInfoLayoutPolicy.readSeparator(prefs)
        } else {
            RootConstants.DEFAULT_HOOK_ISLAND_MUSIC_INFO_SEPARATOR
        }

        val signature = listOf(
            "metadata",
            mode,
            songName,
            artistName,
            albumName,
            durationText,
            firstLineFields.joinToString(","),
            secondLineFields.joinToString(","),
            separator,
            config.metadataMarqueeEnabled,
            config.metadataMarqueeSpeed,
            config.metadataMarqueeDelay,
            config.metadataMarqueeLoopDelay,
            config.metadataMarqueeInfinite
        ).joinToString("|")
        if (!force && IslandSlotContentSignatureCache.get(view) == signature) return false

        val newLine = if (customLayout) {
            buildCustomLine(
                firstLineFields = firstLineFields,
                secondLineFields = secondLineFields,
                fieldValues = fieldValues,
                separator = separator
            )
        } else {
            buildLegacyLine(mode, songName, artistName, albumName)
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

    private fun buildCustomLine(
        firstLineFields: List<String>,
        secondLineFields: List<String>,
        fieldValues: Map<String, String>,
        separator: String
    ): RichLyricLine? {
        val separatorValue = MusicInfoLayoutPolicy.separatorValue(separator)
        val firstLine = joinFields(firstLineFields, fieldValues, separatorValue)
        val secondLine = joinFields(secondLineFields, fieldValues, separatorValue)

        return when {
            firstLine.isNotBlank() -> RichLyricLine(
                text = firstLine,
                words = emptyList(),
                secondary = secondLine.takeIf { it.isNotBlank() },
                secondaryWords = emptyList()
            )

            secondLine.isNotBlank() -> RichLyricLine(
                text = secondLine,
                words = emptyList()
            )

            else -> null
        }
    }

    private fun joinFields(
        fields: List<String>,
        fieldValues: Map<String, String>,
        separator: String
    ): String {
        return fields.mapNotNull { field ->
            fieldValues[field]?.takeIf { it.isNotBlank() }
        }.joinToString(separator)
    }

    private fun buildLegacyLine(
        mode: Int,
        songName: String,
        artistName: String,
        albumName: String
    ): RichLyricLine? {
        val singleModeText = when (mode) {
            1 -> songName
            2 -> artistName
            3 -> albumName
            4 -> "$songName - $artistName"
            else -> ""
        }
        return when (mode) {
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
    }

    private fun formatDuration(durationMs: Long): String {
        return durationMs.takeIf { it > 0L }?.let {
            DateUtils.formatElapsedTime(it / 1000L)
        }.orEmpty()
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
