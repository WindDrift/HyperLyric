/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.lidesheng.hyperlyric.lyric.view

import com.lidesheng.hyperlyric.lyric.model.LyricLine
import com.lidesheng.hyperlyric.lyric.model.LyricWord
import com.lidesheng.hyperlyric.lyric.model.interfaces.IRichLyricLine
import com.lidesheng.hyperlyric.lyric.model.lyricMetadataOf

internal const val METADATA_NEXT_LINE_PREVIEW = "nextLinePreview"

internal class LyricLineAssembler(
    private var displayTranslation: Boolean = true,
    private var displayRoma: Boolean = true,
    private var enableRelativeProgress: Boolean = false,
    private var enableRelativeHighlight: Boolean = false,
    private var displayLineByLine: Boolean = false,
) {
    private val wordBuilder = RelativeWordBuilder()

    fun updateFlags(displayTranslation: Boolean, displayRoma: Boolean,
                    enableRelativeProgress: Boolean, enableRelativeHighlight: Boolean,
                    displayLineByLine: Boolean) {
        this.displayTranslation = displayTranslation
        this.displayRoma = displayRoma
        this.enableRelativeProgress = enableRelativeProgress
        this.enableRelativeHighlight = enableRelativeHighlight
        this.displayLineByLine = displayLineByLine
    }

    data class MainResult(
        val line: LyricLine,
        val isScrollOnly: Boolean,
        val sustainAwareProgress: Boolean,
        val isLineTimeline: Boolean
    )

    fun buildMain(source: IRichLyricLine?): MainResult {
        if (source == null) return MainResult(LyricLine(), false, false, false)

        val hasOriginalWords = !source.words.isNullOrEmpty()
        val shouldGen = enableRelativeProgress && source.isTitleLine().not()
        val lineText = textForLine(source.text, source.words)
        val useLineTimeline = shouldUseLineTimeline(source, source.words, lineText)
        val words = when {
            useLineTimeline -> emptyList()
            shouldGen -> wordBuilder.build(source, source.text, source.words)
            else -> source.words
        }

        val generated = !useLineTimeline && !hasOriginalWords && words !== source.words
        val line = LyricLine(
            begin = source.begin, end = source.end, duration = source.duration,
            isAlignedRight = source.isAlignedRight, metadata = source.metadata,
            text = if (useLineTimeline) lineText else source.text,
            words = words
        )
        return MainResult(
            line = line,
            isScrollOnly = generated && !enableRelativeHighlight,
            sustainAwareProgress = hasOriginalWords && !useLineTimeline,
            isLineTimeline = useLineTimeline
        )
    }

    data class SecondaryResult(
        val line: LyricLine,
        val alwaysShow: Boolean,
        val isScrollOnly: Boolean,
        val isNextLinePreview: Boolean,
        val sustainAwareProgress: Boolean,
        val isLineTimeline: Boolean
    )

    fun buildSecondary(source: IRichLyricLine?): SecondaryResult {
        if (source == null) return SecondaryResult(LyricLine(), false, false, false, false, false)

        var generated = false
        var hasOriginalWords = false
        var lineTimelineGenerated = false
        val isNextLinePreview = source.metadata?.getBoolean(METADATA_NEXT_LINE_PREVIEW) == true
        val line = LyricLine().apply {
            begin = source.begin; end = source.end; duration = source.duration
            isAlignedRight = source.isAlignedRight

            when {
                !source.secondary.isNullOrBlank() || !source.secondaryWords.isNullOrEmpty() -> {
                    val secondaryText = textForLine(source.secondary, source.secondaryWords)
                    text = source.secondary
                    if (isNextLinePreview) {
                        // 下一句只是预览文本，不能继承当前行时间轴或生成相对时间轴。
                        words = emptyList()
                        metadata = lyricMetadataOf(METADATA_NEXT_LINE_PREVIEW to "true")
                    } else {
                        val useLineTimeline = shouldUseLineTimeline(
                            source, source.secondaryWords, secondaryText
                        )
                        val builtWords = if (useLineTimeline) {
                            emptyList()
                        } else {
                            wordBuilder.build(source, source.secondary, source.secondaryWords)
                        }
                        words = builtWords
                        lineTimelineGenerated = useLineTimeline
                        generated = !useLineTimeline && words !== source.secondaryWords
                        hasOriginalWords = !useLineTimeline && !source.secondaryWords.isNullOrEmpty()
                        if (useLineTimeline) text = secondaryText
                    }
                }
                displayTranslation && (!source.translation.isNullOrBlank()
                        || !source.translationWords.isNullOrEmpty()) -> {
                    val translationText = textForLine(source.translation, source.translationWords)
                    text = source.translation
                    val useLineTimeline = shouldUseLineTimeline(
                        source, source.translationWords, translationText
                    )
                    val builtWords = if (useLineTimeline) {
                        emptyList()
                    } else {
                        wordBuilder.build(source, source.translation, source.translationWords)
                    }
                    words = builtWords
                    metadata = lyricMetadataOf("translation" to "true")
                    lineTimelineGenerated = useLineTimeline
                    generated = !useLineTimeline && words !== source.translationWords
                    hasOriginalWords = !useLineTimeline && !source.translationWords.isNullOrEmpty()
                    if (useLineTimeline) text = translationText
                }
                displayRoma -> {
                    val romaText = source.roma
                    text = romaText
                    val useLineTimeline = shouldUseLineTimeline(source, null, romaText)
                    val builtWords = if (useLineTimeline) {
                        emptyList()
                    } else {
                        wordBuilder.build(source, romaText, null)
                    }
                    words = builtWords
                    metadata = lyricMetadataOf("roma" to "true")
                    lineTimelineGenerated = useLineTimeline
                    generated = !lineTimelineGenerated
                }
            }
        }

        val hasContent = line.text?.isNotBlank() == true || !line.words.isNullOrEmpty()
        val isPlain = line.words?.isEmpty() == true
        val alwaysShow = hasContent && (
                isPlain || line.metadata?.getBoolean("translation") == true
                        || line.metadata?.getBoolean("roma") == true
                        // 有词时间轴的副行（如 AMLL 背景人声）常显暗态，词到点后逐字点亮
                        || !line.words.isNullOrEmpty()
                )

        return SecondaryResult(
            line = line,
            alwaysShow = alwaysShow,
            isScrollOnly = generated && !enableRelativeHighlight,
            isNextLinePreview = isNextLinePreview,
            sustainAwareProgress = hasOriginalWords && !lineTimelineGenerated,
            isLineTimeline = lineTimelineGenerated
        )
    }

    /**
     * A word-timed line already carries the line-level timeline in [source].
     * The line-display mode intentionally collapses only that kind of line into
     * one timed text unit; lines without any word list keep their existing path.
     */
    private fun shouldUseLineTimeline(
        source: IRichLyricLine,
        contentWords: List<LyricWord>?,
        contentText: String?
    ): Boolean {
        val lineDuration = (source.end - source.begin).takeIf { it > 0L } ?: source.duration
        return displayLineByLine && !source.isTitleLine() &&
                source.begin >= 0 && lineDuration > 0L &&
                !contentText.isNullOrBlank() &&
                (!contentWords.isNullOrEmpty() || !source.words.isNullOrEmpty())
    }

    private fun textForLine(text: String?, words: List<LyricWord>?): String? =
        text?.takeIf { it.isNotBlank() } ?: words?.joinToString("") { it.text.orEmpty() }
}


