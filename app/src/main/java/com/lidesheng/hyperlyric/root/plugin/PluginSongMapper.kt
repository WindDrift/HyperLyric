package com.lidesheng.hyperlyric.root.plugin

import com.lidesheng.hyperlyric.lyric.model.LyricMetadata
import com.lidesheng.hyperlyric.lyric.model.LyricWord
import com.lidesheng.hyperlyric.lyric.model.RichLyricLine
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
     * Only lyric enhancements are accepted in V1. Song identity and line timing remain
     * Core-owned, while word timing may be supplied by a processor.
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
        if (baseLyrics != null && enhancedLyrics == null) return null
        if (!baseLyrics.isNullOrEmpty() && enhancedLyrics.isNullOrEmpty()) return null
        if (baseLyrics != null && enhancedLyrics != null &&
            (baseLyrics.size != enhancedLyrics.size || baseLyrics.zip(enhancedLyrics).any {
                (baseLine, enhancedLine) ->
                baseLine.begin != enhancedLine.begin ||
                    baseLine.end != enhancedLine.end ||
                    baseLine.duration != enhancedLine.duration ||
                    baseLine.isAlignedRight != enhancedLine.isAlignedRight
            })
        ) {
            return null
        }

        val candidate = Song(
            id = enhanced.id,
            name = enhanced.name,
            artist = enhanced.artist,
            duration = enhanced.duration,
            metadata = enhanced.metadata?.let(::toInternalMetadata),
            lyrics = enhancedLyrics?.map(::toInternalLine)
        )
        return candidate.normalize()
            .takeIf { baseLyrics.isNullOrEmpty() || !it.lyrics.isNullOrEmpty() }
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

    private fun toInternalLine(line: PluginLyricLine): RichLyricLine = RichLyricLine(
        begin = line.begin,
        end = line.end,
        duration = line.duration,
        isAlignedRight = line.isAlignedRight,
        metadata = line.metadata?.let(::toInternalMetadata),
        text = line.text,
        words = line.words?.map(::toInternalWord),
        secondary = line.secondary,
        secondaryWords = line.secondaryWords?.map(::toInternalWord),
        translation = line.translation,
        translationWords = line.translationWords?.map(::toInternalWord),
        roma = line.roma
    )

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
