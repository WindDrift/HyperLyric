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
import com.lidesheng.hyperlyric.root.utils.HookLogger

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
            null
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
        val viewKey = view.tag?.toString() ?: view.javaClass.simpleName
        val debugState = listOf(
            mode,
            customLayout,
            newLine != null,
            firstLineFields.joinToString(","),
            secondLineFields.joinToString(","),
            separator,
            config.metadataMarqueeEnabled,
            force
        ).joinToString("|")
        HookLogger.dState(
            stateId = "IslandMetadataContentAssembler:$viewKey",
            tag = "IslandMetadataContentAssembler",
            state = debugState
        ) {
            "媒体信息内容已提交: tag=$viewKey, mode=$mode, customLayout=$customLayout, " +
                    "line=${newLine != null}, " +
                    "firstFields=${firstLineFields.joinToString(",")}, " +
                    "secondFields=${secondLineFields.joinToString(",")}, separator=$separator, " +
                    "marquee=${config.metadataMarqueeEnabled}, force=$force"
        }
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
