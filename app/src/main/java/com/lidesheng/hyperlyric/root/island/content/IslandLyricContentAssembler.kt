package com.lidesheng.hyperlyric.root.island.content

import android.content.SharedPreferences
import android.text.TextPaint
import android.view.View
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.common.lyric.RichLyricLineSplitter
import com.lidesheng.hyperlyric.lyric.model.RichLyricLine
import com.lidesheng.hyperlyric.lyric.model.interfaces.IRichLyricLine
import com.lidesheng.hyperlyric.lyric.model.lyricMetadataOf
import com.lidesheng.hyperlyric.lyric.view.METADATA_NEXT_LINE_PREVIEW
import com.lidesheng.hyperlyric.lyric.view.RichLyricLineView
import com.lidesheng.hyperlyric.lyric.view.SpaceGateRichLyricLineView
import com.lidesheng.hyperlyric.lyric.view.yoyo.YoYoPresets
import com.lidesheng.hyperlyric.lyric.view.yoyo.animateUpdate
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.island.config.IslandSlotRuntimeConfig
import com.lidesheng.hyperlyric.root.island.host.IslandProbeUtils
import com.lidesheng.hyperlyric.root.utils.TranslationHelper

internal object IslandLyricContentAssembler {

    fun apply(
        view: View,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        lineOverride: IRichLyricLine?,
        force: Boolean,
        playbackActive: Boolean,
        suppressAnimation: Boolean,
        onLineWillApply: ((Float) -> Boolean)?,
        onLineApplied: (() -> Unit)?,
        onLineCancelled: (() -> Unit)?
    ): Boolean {
        val targetLine = lineOverride ?: buildSlotLyricLine(
            view = view,
            prefs = prefs,
            config = config,
            isLeft = view.tag == IslandProbeUtils.LEFT_TEST_VIEW_TAG
        )
        val signature = "lyric|${lineContentSignature(targetLine)}|${config.styleSignature}"
        if (!force && IslandSlotContentSignatureCache.get(view) == signature) {
            applyPlaybackActive(view, playbackActive)
            return false
        }

        val applyLine: (View) -> Unit = { target ->
            when (target) {
                is RichLyricLineView -> {
                    target.setLineWithCallbacks(
                        targetLine,
                        onMainLineWillApply = onLineWillApply,
                        onMainLineApplied = onLineApplied,
                        onMainLineCancelled = onLineCancelled
                    )
                    target.setPlaybackActive(playbackActive)
                    if (config.lyricMarqueeEnabled) target.post { target.requestStartMarquee() }
                }

                is SpaceGateRichLyricLineView -> {
                    target.setLineWithCallbacks(
                        targetLine,
                        onMainLineWillApply = onLineWillApply,
                        onMainLineApplied = onLineApplied,
                        onMainLineCancelled = onLineCancelled
                    )
                    target.setPlaybackActive(playbackActive)
                    if (config.lyricMarqueeEnabled) target.post { target.requestStartMarquee() }
                }
            }
        }

        val suppressContentAnimation = suppressAnimation || isNextLinePreviewEnabled(
            prefs,
            config
        ) || view.parent == null || !view.isAttachedToWindow
        if (config.lyricAnimationEnabled && !suppressContentAnimation) {
            val preset = YoYoPresets.getById(config.lyricAnimationId) ?: YoYoPresets.Default
            when (view) {
                is RichLyricLineView -> view.animateUpdate(preset) { applyLine(this) }
                is SpaceGateRichLyricLineView -> view.animateUpdate(preset) { applyLine(this) }
                else -> applyLine(view)
            }
        } else {
            applyLine(view)
        }
        IslandSlotContentSignatureCache.set(view, signature)
        return true
    }

    fun applyLine(
        view: View,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        lineOverride: IRichLyricLine?,
        playbackActive: Boolean = true,
        onLineWillApply: ((Float) -> Boolean)? = null,
        onLineApplied: (() -> Unit)? = null,
        onLineCancelled: (() -> Unit)? = null
    ): Boolean = apply(
        view = view,
        prefs = prefs,
        config = config,
        lineOverride = lineOverride,
        force = false,
        playbackActive = playbackActive,
        suppressAnimation = false,
        onLineWillApply = onLineWillApply,
        onLineApplied = onLineApplied,
        onLineCancelled = onLineCancelled
    )

    fun buildSlotLyricLine(
        view: View,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        isLeft: Boolean
    ): IRichLyricLine? {
        val rawLine = processedRawLine(prefs, config)
        if (!config.isSplitMode || rawLine == null) return rawLine
        if (rawLine.text.isNullOrEmpty()) return rawLine

        val density = view.resources.displayMetrics.density
        val fallbackPaint = TextPaint().apply {
            textSize = config.textSizeSp.toFloat() * density
        }
        val textPaint = when (view) {
            is RichLyricLineView -> TextPaint(view.main.textPaint)
            is SpaceGateRichLyricLineView -> TextPaint(view.main.textPaint)
            else -> fallbackPaint
        }.takeIf { it.textSize > 0f } ?: fallbackPaint
        val secondaryPaint = when (view) {
            is RichLyricLineView -> TextPaint(view.secondary.textPaint)
            is SpaceGateRichLyricLineView -> TextPaint(view.secondary.textPaint)
            else -> TextPaint(textPaint).apply {
                textSize *= config.textSizeRatio
            }
        }.takeIf { it.textSize > 0f } ?: TextPaint(textPaint).apply {
            textSize *= config.textSizeRatio
        }

        fun contentWidthPx(widthDp: Int, parentName: String): Float {
            val wrapperWidthPx = widthDp * density
            val paddingPx = config.geometry.paddingLeftPx(view, parentName) +
                    config.geometry.paddingRightPx(view, parentName)
            return (wrapperWidthPx - paddingPx).coerceAtLeast(1f)
        }

        val leftMinContentPx = contentWidthPx(
            config.geometry.leftMinWidthDp,
            IslandProbeUtils.LEFT_PARENT_NAME
        )
        val leftMaxContentPx = contentWidthPx(
            config.geometry.leftMaxWidthDp,
            IslandProbeUtils.LEFT_PARENT_NAME
        )
        val rightMinContentPx = contentWidthPx(
            config.geometry.rightMinWidthDp,
            IslandProbeUtils.RIGHT_PARENT_NAME
        )
        val rightMaxContentPx = contentWidthPx(
            config.geometry.rightMaxWidthDp,
            IslandProbeUtils.RIGHT_PARENT_NAME
        )
        val containerWidthSpec = if (config.geometry.isDynamicWidth) {
            RichLyricLineSplitter.ContainerWidthSpec.Dynamic(
                leftMinWidthPx = leftMinContentPx,
                leftMaxWidthPx = leftMaxContentPx,
                rightMinWidthPx = rightMinContentPx,
                rightMaxWidthPx = rightMaxContentPx
            )
        } else {
            RichLyricLineSplitter.ContainerWidthSpec.Fixed(
                leftWidthPx = leftMaxContentPx,
                rightWidthPx = rightMaxContentPx
            )
        }
        val splitResult = RichLyricLineSplitter.split(
            line = rawLine,
            primaryPaint = textPaint,
            secondaryPaint = secondaryPaint,
            containerWidthSpec = containerWidthSpec
        )
        return if (isLeft) splitResult.left else splitResult.right
    }

    fun processedRawLine(
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig? = null
    ): IRichLyricLine? {
        var rawLine = LyriconDataBridge.currentLyricLine
            ?: return null

        if (config != null && isNextLinePreviewEnabled(prefs, config, rawLine)) {
            return rawLine.withNextLinePreview(LyriconDataBridge.currentNextLyricLine)
        }

        if (TranslationHelper.isTranslationOnly(prefs)) {
            rawLine = TranslationHelper.applyTranslationOnly(rawLine)
        } else if (TranslationHelper.isSwapTranslation(prefs)) {
            rawLine = TranslationHelper.swapTranslation(rawLine)
        }
        return rawLine
    }

    internal fun isNextLinePreviewEnabled(
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        currentLine: IRichLyricLine? = LyriconDataBridge.currentLyricLine
    ): Boolean {
        if (!config.nextLyricLine || config.isSplitMode) return false
        if (LyriconDataBridge.isTextMode) return false
        val source = prefs.getString(
            RootConstants.KEY_HOOK_LYRIC_SOURCE,
            RootConstants.DEFAULT_HOOK_LYRIC_SOURCE
        )
        if (source != "lyricon" && source != "lyricinfo") return false

        if (config.autoSwitchTranslation) {
            val hasSongTranslation =
                LyriconDataBridge.currentSong?.lyrics?.any { !it.translation.isNullOrBlank() } == true
            val hasLineTranslation = !currentLine?.translation.isNullOrBlank()
            if (hasSongTranslation || hasLineTranslation) return false
        }
        return true
    }

    private fun applyPlaybackActive(view: View, playbackActive: Boolean) {
        when (view) {
            is RichLyricLineView -> view.setPlaybackActive(playbackActive)
            is SpaceGateRichLyricLineView -> view.setPlaybackActive(playbackActive)
        }
    }

    private fun lineContentSignature(line: IRichLyricLine?): Int {
        if (line == null) return 0
        return listOf(
            line.begin,
            line.end,
            line.duration,
            line.text,
            line.words,
            line.secondary,
            line.secondaryWords,
            line.translation,
            line.translationWords,
            line.roma,
            line.isAlignedRight,
            line.metadata
        ).hashCode()
    }

    private fun IRichLyricLine.withNextLinePreview(nextLine: IRichLyricLine?): IRichLyricLine {
        val nextText = nextLine?.text?.takeIf { it.isNotBlank() }
        return RichLyricLine(
            begin = begin,
            end = end,
            duration = duration,
            isAlignedRight = isAlignedRight,
            metadata = lyricMetadataOf(
                *(metadata?.entries?.map { it.key to it.value } ?: emptyList()).toTypedArray(),
                METADATA_NEXT_LINE_PREVIEW to "true"
            ),
            text = text,
            words = words,
            secondary = nextText,
            secondaryWords = emptyList(),
            translation = null,
            translationWords = null,
            roma = null
        )
    }
}
