package com.lidesheng.hyperlyric.root.plugin

import com.lidesheng.hyperlyric.lyric.model.LyricMetadata
import com.lidesheng.hyperlyric.lyric.model.LyricWord
import com.lidesheng.hyperlyric.lyric.model.RichLyricLine
import com.lidesheng.hyperlyric.lyric.model.Song
import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginMetadata
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import com.lidesheng.hyperlyric.plugin.api.PluginSongResult
import com.lidesheng.hyperlyric.plugin.api.PluginWord

/** Converts the private lyric model to and from the stable plugin snapshots. */
object PluginSongMapper {
    private const val MAX_LYRICS = 20_000
    private const val MAX_WORDS_PER_LINE = 2_000
    private const val MAX_TOTAL_WORDS = 100_000

    fun toPluginSong(song: Song): PluginSong = PluginSong(
        id = song.id,
        name = song.name,
        artist = song.artist,
        album = song.album,
        duration = song.duration,
        metadata = song.metadata?.let(::toPluginMetadata),
        lyrics = song.lyrics?.map(::toPluginLine)
    )

    /**
     * Applies a plugin result without allowing it to rewrite Core-owned media fields. A result
     * is accepted only when it explicitly updates the complete lyrics candidate.
     */
    fun mergePluginSong(
        base: PluginSong,
        result: PluginSongResult
    ): PluginSong? {
        if (result.changedFields.isEmpty()) return base
        if (result.changedFields.any { it != PluginSongField.LYRICS }) return null

        val lyrics = result.song.lyrics
        if (!hasValidLyrics(lyrics)) return null
        return base.copy(lyrics = lyrics)
    }

    /**
     * Converts a validated lyric candidate back to the private model. Every media field comes
     * from [base]; a plugin can only replace the lyrics list.
     */
    fun toInternalSong(
        base: Song,
        result: PluginSongResult
    ): Song? {
        val candidate = mergePluginSong(
            base = toPluginSong(base),
            result = result
        ) ?: return null

        return base.copy(lyrics = candidate.lyrics?.map(::toInternalLine))
    }

    private fun hasValidLyrics(lyrics: List<PluginLyricLine>?): Boolean {
        if (lyrics.isNullOrEmpty() || lyrics.size > MAX_LYRICS) return false
        var totalWords = 0
        return lyrics.all { line ->
            val lineValid = line.begin >= 0L &&
                    line.end > line.begin &&
                    line.duration == line.end - line.begin &&
                    (!line.text.isNullOrBlank() || !line.words.isNullOrEmpty())
            if (!lineValid) return@all false

            val wordLists = listOf(line.words, line.secondaryWords, line.translationWords)
            if (wordLists.any { it != null && it.size > MAX_WORDS_PER_LINE }) return@all false
            val wordsInLine = wordLists.sumOf { it?.size ?: 0 }
            totalWords += wordsInLine
            if (totalWords > MAX_TOTAL_WORDS) return@all false
            wordLists.all { words -> hasValidWords(line, words) }
        }
    }

    private fun hasValidWords(
        line: PluginLyricLine,
        words: List<PluginWord>?
    ): Boolean {
        if (words == null) return true
        var previousEnd = line.begin
        return words.all { word ->
            val valid = word.begin >= line.begin &&
                    word.end > word.begin &&
                    word.end <= line.end &&
                    word.duration == word.end - word.begin &&
                    word.begin >= previousEnd
            if (valid) previousEnd = word.end
            valid
        }
    }

    private fun toPluginMetadata(metadata: LyricMetadata): PluginMetadata =
        PluginMetadata(metadata.entries.associate { it.key to it.value })

    private fun toInternalMetadata(metadata: PluginMetadata): LyricMetadata =
        LyricMetadata(metadata.values)

    private fun toPluginLine(line: RichLyricLine): PluginLyricLine =
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

    private fun toInternalLine(line: PluginLyricLine): RichLyricLine =
        RichLyricLine(
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
