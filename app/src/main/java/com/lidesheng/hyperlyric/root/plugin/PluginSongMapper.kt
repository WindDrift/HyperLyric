package com.lidesheng.hyperlyric.root.plugin

import com.lidesheng.hyperlyric.lyric.model.LyricMetadata
import com.lidesheng.hyperlyric.lyric.model.LyricWord
import com.lidesheng.hyperlyric.lyric.model.Song
import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginMetadata
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginWord

/** Converts the private lyric model to and from the small public plugin snapshot. */
object PluginSongMapper {
    fun toPluginSong(song: Song): PluginSong = PluginSong(
        id = song.id,
        name = song.name,
        artist = song.artist,
        duration = song.duration,
        metadata = song.metadata?.let(::toPluginMetadata),
        lyrics = song.lyrics?.map(::toPluginLine)
    )

    /**
     * V1 accepts translation text only. Song identity, metadata, source text, source words,
     * secondary text and line timing remain Core-owned. Translation word timing is accepted
     * only when it is fully contained by a reliable source line timeline.
     */
    fun toInternalSong(base: Song, enhanced: PluginSong): Song? {
        if (base.id != enhanced.id ||
            base.name != enhanced.name ||
            base.artist != enhanced.artist ||
            base.duration != enhanced.duration
        ) {
            return null
        }
        val baseLyrics = base.lyrics
        val enhancedLyrics = enhanced.lyrics
        if (baseLyrics == null) return if (enhancedLyrics == null) base else null
        if (enhancedLyrics == null || enhancedLyrics.size != baseLyrics.size) return null
        if (baseLyrics.zip(enhancedLyrics).any { (baseLine, enhancedLine) ->
                baseLine.begin != enhancedLine.begin ||
                    baseLine.end != enhancedLine.end ||
                    baseLine.duration != enhancedLine.duration ||
                    baseLine.isAlignedRight != enhancedLine.isAlignedRight
            }
        ) return null

        val candidateLyrics = baseLyrics.zip(enhancedLyrics).map { (baseLine, enhancedLine) ->
            val translationChanged = baseLine.translation != enhancedLine.translation
            baseLine.copy(
                translation = enhancedLine.translation,
                translationWords = when {
                    !translationChanged -> baseLine.translationWords
                    else -> enhancedLine.translationWords
                        ?.takeIf { hasReliableTimeline(enhancedLine, it) }
                        ?.map(::toInternalWord)
                }
            )
        }
        return base.copy(lyrics = candidateLyrics)
    }

    private fun toPluginMetadata(metadata: LyricMetadata): PluginMetadata =
        PluginMetadata(metadata.entries.associate { it.key to it.value })

    private fun toInternalMetadata(metadata: PluginMetadata): LyricMetadata =
        LyricMetadata(metadata.values)

    private fun toPluginLine(line: com.lidesheng.hyperlyric.lyric.model.RichLyricLine): PluginLyricLine =
        PluginLyricLine(
            begin = line.begin,
            end = line.end,
            duration = line.duration,
            isAlignedRight = line.isAlignedRight,
            metadata = line.metadata?.let(::toPluginMetadata),
            text = line.text,
            words = line.words?.map(::toPluginWord),
            secondary = line.secondary,
            secondaryWords = line.secondaryWords?.map(::toPluginWord),
            translation = line.translation,
            translationWords = line.translationWords?.map(::toPluginWord),
            roma = line.roma
        )

    private fun hasReliableTimeline(
        line: PluginLyricLine,
        words: List<PluginWord>,
    ): Boolean {
        if (line.end <= line.begin || line.duration <= 0L || words.isEmpty()) return false
        var previousEnd = line.begin
        return words.all { word ->
            val valid = word.begin >= line.begin &&
                word.end > word.begin &&
                word.end <= line.end &&
                word.begin >= previousEnd
            if (valid) previousEnd = word.end
            valid
        }
    }

    private fun toPluginWord(word: LyricWord): PluginWord = PluginWord(
        begin = word.begin,
        end = word.end,
        duration = word.duration,
        text = word.text,
        metadata = word.metadata?.let(::toPluginMetadata)
    )

    private fun toInternalWord(word: PluginWord): LyricWord = LyricWord(
        begin = word.begin,
        end = word.end,
        duration = word.duration,
        text = word.text,
        metadata = word.metadata?.let(::toInternalMetadata)
    )
}
