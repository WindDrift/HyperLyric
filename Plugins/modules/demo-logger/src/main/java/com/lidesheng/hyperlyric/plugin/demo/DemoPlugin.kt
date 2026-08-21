package com.lidesheng.hyperlyric.plugin.demo

import com.lidesheng.hyperlyric.plugin.api.HyperLyricPlugin
import com.lidesheng.hyperlyric.plugin.api.LyricProcessorExtension
import com.lidesheng.hyperlyric.plugin.api.PluginConfig
import com.lidesheng.hyperlyric.plugin.api.PluginContext
import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginLyricsUpdateMode
import com.lidesheng.hyperlyric.plugin.api.PluginMetadata
import com.lidesheng.hyperlyric.plugin.api.PluginProcessingContext
import com.lidesheng.hyperlyric.plugin.api.PluginProcessorStage
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import com.lidesheng.hyperlyric.plugin.api.PluginSongResult
import com.lidesheng.hyperlyric.plugin.api.PluginWord

class DemoPlugin : HyperLyricPlugin {
    private companion object {
        const val EXTENSION_ID = "demo.logger"
        const val LYRIC_REPLACEMENT_EXTENSION_ID = "demo.lyric-replacement"
        const val TRANSLATION_EXTENSION_ID = "demo.translation"
        const val ROMA_METADATA_EXTENSION_ID = "demo.roma-metadata"
        const val DEMO_PREFIX = "[Demo] "
        const val DEMO_METADATA_KEY = "hyperlyric.demo"
        const val MAX_LOG_LYRIC_LINES = 5
        const val MAX_LOG_WORDS_PER_LINE = 8
        const val MAX_LOG_TEXT_LENGTH = 120
    }

    private lateinit var context: PluginContext

    override fun onLoad(context: PluginContext) {
        this.context = context
        context.registerExtension(LyricReplacementProcessor(context))
        context.registerExtension(TranslationProcessor(context))
        context.registerExtension(RomaMetadataProcessor(context))
        context.registerExtension(LoggerProcessor(context))
        context.logger.info("lifecycle=onLoad, extensions=4")
    }

    override fun onEnable() {
        context.logger.info("lifecycle=onEnable")
    }

    override fun onConfigChanged(config: PluginConfig) {
        context.logger.debug(
                    "lifecycle=onConfigChanged, log_song=${config.getBoolean("log_song", true)}, " +
                    "replace_lyrics=${config.getBoolean("replace_lyrics", false)}, " +
                    "add_translation=${config.getBoolean("add_translation", false)}, " +
                    "add_roma_metadata=${config.getBoolean("add_roma_metadata", false)}"
        )
    }

    override fun onUnload() {
        context.logger.info("lifecycle=onUnload")
    }

    private class LoggerProcessor(private val context: PluginContext) : LyricProcessorExtension {
        override val id: String = EXTENSION_ID

        override fun processResult(
            song: PluginSong,
            processingContext: PluginProcessingContext
        ): PluginSongResult? {
            if (context.config.getBoolean("log_song", true)) {
                val media = processingContext.mediaInfo
                val lyrics = song.lyrics.orEmpty()
                context.logger.info(
                    "event=processSong, song={" +
                            "id=${song.id.logValue()}, " +
                            "name=${song.name.logValue()}, " +
                            "artist=${song.artist.logValue()}, " +
                            "album=${song.album.logValue()}, " +
                            "duration=${song.duration}, " +
                            "metadata=${song.metadata?.values.logValue()}, " +
                            "lyricsCount=${lyrics.size}}, " +
                            "mediaInfo={" +
                            "title=${media?.title.logValue()}, " +
                            "artist=${media?.artist.logValue()}, " +
                            "album=${media?.album.logValue()}, " +
                            "duration=${media?.duration ?: "<null>"}}"
                )
                lyrics.take(MAX_LOG_LYRIC_LINES).forEachIndexed { index, line ->
                    context.logger.info(
                        "event=processSongLyric, index=$index, " +
                                "timeline=${line.begin}-${line.end}/${line.duration}, " +
                                "text=${line.text.logValue()}, " +
                                "words=${formatWords(line.words)}, " +
                                "secondary=${line.secondary.logValue()}, " +
                                "translation=${line.translation.logValue()}, " +
                                "roma=${line.roma.logValue()}, " +
                                "secondaryWordCount=${line.secondaryWords?.size ?: 0}, " +
                                "translationWordCount=${line.translationWords?.size ?: 0}"
                    )
                }
                if (lyrics.size > MAX_LOG_LYRIC_LINES) {
                    context.logger.info(
                        "event=processSongLyric, omitted=${lyrics.size - MAX_LOG_LYRIC_LINES}"
                    )
                }
            }
            return null
        }

        private fun formatWords(words: List<PluginWord>?): String {
            if (words == null) return "<null>"
            val preview = words.take(MAX_LOG_WORDS_PER_LINE).joinToString("|") { word ->
                "${word.begin}-${word.end}:${word.text.logValue()}"
            }
            val omitted = words.size - MAX_LOG_WORDS_PER_LINE
            return if (omitted > 0) {
                "[$preview|...+$omitted]"
            } else {
                "[$preview]"
            }
        }

        private fun Any?.logValue(): String = when (this) {
            null -> "<null>"
            is String -> replace("\\r", "\\\\r")
                .replace("\\n", "\\\\n")
                .take(MAX_LOG_TEXT_LENGTH)
            else -> toString().take(MAX_LOG_TEXT_LENGTH)
        }
    }

    private class LyricReplacementProcessor(
        private val context: PluginContext
    ) : LyricProcessorExtension {
        override val id: String = LYRIC_REPLACEMENT_EXTENSION_ID
        override val stage: PluginProcessorStage = PluginProcessorStage.LYRIC_REPLACEMENT

        override fun processResult(song: PluginSong): PluginSongResult? {
            if (!context.config.getBoolean("replace_lyrics", false)) return null
            val lyrics = song.lyrics ?: return null
            val replaced = lyrics.map(::replaceLine)
            return PluginSongResult(
                song = song.copy(lyrics = replaced),
                changedFields = setOf(PluginSongField.LYRICS),
                lyricsUpdateMode = PluginLyricsUpdateMode.PATCH,
                changedLyricFields = setOf(PluginLyricField.TEXT, PluginLyricField.WORDS)
            )
        }

        private fun replaceLine(line: PluginLyricLine): PluginLyricLine {
            val originalText = line.text
                ?: line.words?.joinToString("") { it.text.orEmpty() }
                ?: return line
            if (originalText.startsWith(DEMO_PREFIX)) return line

            val originalWords = line.words
            if (originalWords.isNullOrEmpty()) {
                return line.copy(text = DEMO_PREFIX + originalText)
            }

            val lineDuration = line.end - line.begin
            val slotCount = originalWords.size + 1
            if (lineDuration < slotCount) {
                // There is not enough millisecond precision for a new independent word.
                // Keep the original timeline valid and attach the prefix to its first word.
                val first = originalWords.first()
                return line.copy(
                    text = DEMO_PREFIX + originalText,
                    words = originalWords.toMutableList().apply {
                        set(0, first.copy(text = DEMO_PREFIX + first.text.orEmpty()))
                    }
                )
            }

            val segment = lineDuration / slotCount
            val remainder = lineDuration % slotCount
            var cursor = line.begin

            fun nextEnd(index: Int): Long {
                val end = cursor + segment + if (index < remainder) 1L else 0L
                cursor = end
                return end
            }

            val replacedWords = buildList {
                val prefixEnd = nextEnd(0)
                add(
                    PluginWord(
                        begin = line.begin,
                        end = prefixEnd,
                        duration = prefixEnd - line.begin,
                        text = DEMO_PREFIX
                    )
                )
                originalWords.forEachIndexed { index, word ->
                    val begin = cursor
                    val end = nextEnd(index + 1)
                    add(word.copy(begin = begin, end = end, duration = end - begin))
                }
            }

            return line.copy(
                text = DEMO_PREFIX + originalText,
                words = replacedWords
            )
        }
    }

    private class TranslationProcessor(
        private val context: PluginContext
    ) : LyricProcessorExtension {
        override val id: String = TRANSLATION_EXTENSION_ID
        override val stage: PluginProcessorStage = PluginProcessorStage.TRANSLATION_ENHANCEMENT

        override fun processResult(song: PluginSong): PluginSongResult? {
            if (!context.config.getBoolean("add_translation", false)) return null
            val lyrics = song.lyrics ?: return null
            val translated = lyrics.map { line ->
                line.copy(
                    translation = line.text?.let { "Demo: $it" },
                    translationWords = null
                )
            }
            return PluginSongResult(
                song = song.copy(lyrics = translated),
                changedFields = setOf(PluginSongField.LYRICS),
                lyricsUpdateMode = PluginLyricsUpdateMode.PATCH,
                changedLyricFields = setOf(
                    PluginLyricField.TRANSLATION,
                    PluginLyricField.TRANSLATION_WORDS
                )
            )
        }
    }

    private class RomaMetadataProcessor(
        private val context: PluginContext
    ) : LyricProcessorExtension {
        override val id: String = ROMA_METADATA_EXTENSION_ID
        override val stage: PluginProcessorStage = PluginProcessorStage.TRANSLATION_ENHANCEMENT

        override fun processResult(song: PluginSong): PluginSongResult? {
            if (!context.config.getBoolean("add_roma_metadata", false)) return null
            val lyrics = song.lyrics ?: return null
            var changed = false
            val enriched = lyrics.map { line ->
                val roma = line.roma ?: line.text?.takeIf { it.isNotBlank() }?.let { "Demo: $it" }
                val metadata = (line.metadata ?: PluginMetadata()).let { current ->
                    if (current.values[DEMO_METADATA_KEY] == "true") {
                        current
                    } else {
                        current.copy(values = current.values + (DEMO_METADATA_KEY to "true"))
                    }
                }
                if (line.roma != roma || line.metadata != metadata) changed = true
                line.copy(roma = roma, metadata = metadata)
            }
            if (!changed) return null
            return PluginSongResult(
                song = song.copy(lyrics = enriched),
                changedFields = setOf(PluginSongField.LYRICS),
                lyricsUpdateMode = PluginLyricsUpdateMode.PATCH,
                changedLyricFields = setOf(PluginLyricField.ROMA, PluginLyricField.METADATA)
            )
        }
    }
}
