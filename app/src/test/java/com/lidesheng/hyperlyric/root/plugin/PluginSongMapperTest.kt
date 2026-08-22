package com.lidesheng.hyperlyric.root.plugin

import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginLyricsUpdateMode
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import com.lidesheng.hyperlyric.plugin.api.PluginSongResult
import com.lidesheng.hyperlyric.plugin.api.PluginWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class PluginSongMapperTest {
    @Test
    fun topLevelNullableFieldIsClearedOnlyWhenDeclared() {
        val base = PluginSong(
            name = "title",
            artist = "artist",
            album = "album"
        )
        val result = PluginSongResult(
            song = base.copy(album = null, artist = null),
            changedFields = setOf(PluginSongField.ALBUM)
        )

        val merged = PluginSongMapper.mergePluginSong(base, result)

        assertNotNull(merged)
        assertEquals("artist", merged?.artist)
        assertNull(merged?.album)
    }

    @Test
    fun translationPatchPreservesOriginalAndWords() {
        val originalWords = listOf(word("原"))
        val base = PluginSong(lyrics = listOf(line(text = "原文", words = originalWords)))
        val candidate = base.copy(
            lyrics = listOf(
                line(
                    text = "插件不应覆盖的原文",
                    words = listOf(word("插件词")),
                    translation = "译文"
                )
            )
        )

        val merged = PluginSongMapper.mergePluginSong(
            base,
            PluginSongResult(
                song = candidate,
                changedFields = setOf(PluginSongField.LYRICS),
                lyricsUpdateMode = PluginLyricsUpdateMode.PATCH,
                changedLyricFields = setOf(PluginLyricField.TRANSLATION)
            )
        )

        assertEquals("原文", merged?.lyrics?.single()?.text)
        assertEquals(originalWords, merged?.lyrics?.single()?.words)
        assertEquals("译文", merged?.lyrics?.single()?.translation)
    }

    @Test
    fun wordsTranslationAndRomaPatchesAccumulate() {
        val base = PluginSong(
            lyrics = listOf(line(text = "原文", words = listOf(word("原词"))))
        )
        val words = base.copy(
            lyrics = listOf(line(text = "原文", words = listOf(word("新词"))))
        )
        val translated = words.copy(
            lyrics = listOf(
                line(text = "原文", words = words.lyrics!!.single().words, translation = "译文")
            )
        )
        val romanized = translated.copy(
            lyrics = listOf(
                line(
                    text = "原文",
                    words = translated.lyrics!!.single().words,
                    translation = "译文",
                    roma = "yuan wen"
                )
            )
        )

        val afterWords = PluginSongMapper.mergePluginSong(
            base,
            patch(words, PluginLyricField.WORDS)
        )!!
        val afterTranslation = PluginSongMapper.mergePluginSong(
            afterWords,
            patch(translated, PluginLyricField.TRANSLATION)
        )!!
        val afterRoma = PluginSongMapper.mergePluginSong(
            afterTranslation,
            patch(romanized, PluginLyricField.ROMA)
        )!!

        val finalLine = afterRoma.lyrics!!.single()
        assertEquals("新词", finalLine.words!!.single().text)
        assertEquals("译文", finalLine.translation)
        assertEquals("yuan wen", finalLine.roma)
    }

    @Test
    fun translationPatchAcceptsIncompleteUnchangedSourceTiming() {
        val base = PluginSong(
            lyrics = listOf(
                PluginLyricLine(
                    begin = 0L,
                    end = 0L,
                    duration = 0L,
                    text = "未带精确时间的原文",
                    translationWords = listOf(word("旧译文"))
                )
            )
        )
        val candidate = base.copy(
            lyrics = listOf(base.lyrics!!.single().copy(translation = "AI 译文", translationWords = null))
        )

        val merged = PluginSongMapper.mergePluginSong(
            base,
            PluginSongResult(
                song = candidate,
                changedFields = setOf(PluginSongField.LYRICS),
                lyricsUpdateMode = PluginLyricsUpdateMode.PATCH,
                changedLyricFields = setOf(
                    PluginLyricField.TRANSLATION,
                    PluginLyricField.TRANSLATION_WORDS
                )
            )
        )

        assertEquals("AI 译文", merged?.lyrics?.single()?.translation)
        assertNull(merged?.lyrics?.single()?.translationWords)
    }

    @Test
    fun invalidReplaceOrderIsRejectedAndBaseRemainsAvailable() {
        val base = PluginSong(
            lyrics = listOf(
                line(begin = 0L, text = "first"),
                line(begin = 200L, text = "second")
            )
        )
        val invalid = base.copy(
            lyrics = listOf(
                line(begin = 200L, text = "second"),
                line(begin = 0L, text = "first")
            )
        )

        val merged = PluginSongMapper.mergePluginSong(
            base,
            PluginSongResult(
                song = invalid,
                changedFields = setOf(PluginSongField.LYRICS),
                lyricsUpdateMode = PluginLyricsUpdateMode.REPLACE
            )
        )

        assertNull(merged)
        assertEquals("first", base.lyrics!!.first().text)
    }

    private fun patch(
        song: PluginSong,
        field: PluginLyricField
    ): PluginSongResult = PluginSongResult(
        song = song,
        changedFields = setOf(PluginSongField.LYRICS),
        lyricsUpdateMode = PluginLyricsUpdateMode.PATCH,
        changedLyricFields = setOf(field)
    )

    private fun line(
        begin: Long = 0L,
        text: String? = "text",
        words: List<PluginWord>? = null,
        translation: String? = null,
        roma: String? = null
    ): PluginLyricLine = PluginLyricLine(
        begin = begin,
        end = begin + 100L,
        duration = 100L,
        text = text,
        words = words,
        translation = translation,
        roma = roma
    )

    private fun word(text: String): PluginWord = PluginWord(
        begin = 0L,
        end = 100L,
        duration = 100L,
        text = text
    )
}
