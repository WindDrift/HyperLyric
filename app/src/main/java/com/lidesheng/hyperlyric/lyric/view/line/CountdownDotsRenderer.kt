/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.lidesheng.hyperlyric.lyric.view.line

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextPaint
import com.lidesheng.hyperlyric.lyric.view.line.model.LyricModel

internal class CountdownDotsRenderer : LineRenderer {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var backgroundColors = intArrayOf(Color.argb(128, 255, 255, 255))
    private var highlightColors = intArrayOf(Color.WHITE)
    private val progressAnimator = ProgressAnimator()
    private var textSize = 0f
    private var lastPosition = Long.MIN_VALUE

    private var backgroundShader: Shader? = null
    private var highlightShader: Shader? = null
    private var shaderStart = Float.NaN
    private var shaderEnd = Float.NaN
    private var shaderBackgroundHash = 0
    private var shaderHighlightHash = 0

    var isGradientEnabled = true
        set(value) {
            if (field == value) return
            field = value
            clearShaders()
        }

    override var centerIfPossible = false

    override val isPlaying: Boolean get() = progressAnimator.isAnimating
    override val isFinished: Boolean get() = progressAnimator.currentWidth >= 1f
    override val isStarted: Boolean get() = progressAnimator.currentWidth > 0f

    fun setTextSize(size: Float) {
        if (textSize == size) return
        textSize = size
        clearShaders()
    }

    fun setColors(background: IntArray, highlight: IntArray) {
        if (background.isNotEmpty()) backgroundColors = background.copyOf()
        if (highlight.isNotEmpty()) highlightColors = highlight.copyOf()
        clearShaders()
    }

    fun contentWidth(): Float {
        val radius = baseRadius()
        val maxRadius = radius * MAX_SCALE
        val gap = radius * GAP_FACTOR
        return maxRadius * 2f * DOT_COUNT + gap * (DOT_COUNT - 1)
    }

    fun syncFrom(other: CountdownDotsRenderer) {
        progressAnimator.syncFrom(other.progressAnimator)
        lastPosition = other.lastPosition
    }

    fun freeze() {
        progressAnimator.stopAtCurrent()
    }

    override fun seek(
        model: LyricModel,
        state: LineState,
        posMs: Long,
        viewWidth: Int,
        viewHeight: Int
    ) {
        progressAnimator.jumpTo(progressAt(posMs, model))
        lastPosition = posMs
    }

    override fun update(
        model: LyricModel,
        state: LineState,
        posMs: Long,
        viewWidth: Int,
        viewHeight: Int
    ) {
        if (lastPosition != Long.MIN_VALUE && posMs < lastPosition) {
            seek(model, state, posMs, viewWidth, viewHeight)
            return
        }

        val exactProgress = progressAt(posMs, model)
        if (exactProgress >= 1f) {
            progressAnimator.jumpTo(1f)
            lastPosition = posMs
            return
        }
        if (progressAnimator.currentWidth == 0f && exactProgress > 0f) {
            progressAnimator.jumpTo(exactProgress)
        }

        val segment = (exactProgress * DOT_COUNT).toInt().coerceIn(0, DOT_COUNT - 1)
        val target = (segment + 1).toFloat() / DOT_COUNT
        if (target != progressAnimator.targetWidth || !progressAnimator.isAnimating) {
            progressAnimator.animateTo(target, remainingSegmentDuration(posMs, model, segment))
        }
        lastPosition = posMs
    }

    override fun step(
        deltaNanos: Long,
        model: LyricModel,
        state: LineState,
        viewWidth: Int
    ): Boolean = progressAnimator.step(deltaNanos)

    override fun draw(
        canvas: Canvas,
        model: LyricModel,
        paint: TextPaint,
        state: LineState,
        viewWidth: Int,
        viewHeight: Int
    ) {
        if (textSize <= 0f || viewWidth <= 0 || viewHeight <= 0) return

        val radius = baseRadius()
        val maxRadius = radius * MAX_SCALE
        val gap = radius * GAP_FACTOR
        val width = contentWidth()
        val startX = when {
            model.isAlignedRight -> viewWidth - width
            centerIfPossible -> (viewWidth - width) / 2f
            else -> 0f
        }.coerceAtLeast(0f)
        val centerY = viewHeight / 2f

        updateShaders(startX, startX + width)

        val progress = progressAnimator.currentWidth
        repeat(DOT_COUNT) { index ->
            val localProgress = (progress * DOT_COUNT - index).coerceIn(0f, 1f)
            val easedProgress = smoothStep(localProgress)
            val currentRadius = radius * (1f + SCALE_AMOUNT * easedProgress)
            val highlightAlpha = (easedProgress * 255f).toInt()
            val centerX = startX + maxRadius + index * (maxRadius * 2f + gap)

            canvas.drawCircle(centerX, centerY, currentRadius, backgroundPaint)
            if (highlightAlpha <= 0) return@repeat

            highlightPaint.alpha = highlightAlpha
            canvas.drawCircle(centerX, centerY, currentRadius, highlightPaint)
        }
        highlightPaint.alpha = 255
    }

    override fun reset(state: LineState) {
        progressAnimator.reset()
        lastPosition = Long.MIN_VALUE
        state.reset()
    }

    private fun progressAt(posMs: Long, model: LyricModel): Float {
        val span = (model.end - model.begin).takeIf { it > 0L } ?: model.duration
        if (span <= 0L) return 0f
        return ((posMs - model.begin).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    }

    private fun remainingSegmentDuration(
        posMs: Long,
        model: LyricModel,
        segment: Int
    ): Long {
        val span = (model.end - model.begin).takeIf { it > 0L } ?: model.duration
        if (span <= 0L) return 0L
        val segmentEnd = model.begin + span * (segment + 1) / DOT_COUNT
        return (segmentEnd - posMs).coerceAtLeast(0L)
    }

    private fun baseRadius(): Float = textSize * RADIUS_TEXT_SIZE_FACTOR

    private fun updateShaders(start: Float, end: Float) {
        val backgroundHash = backgroundColors.contentHashCode()
        val highlightHash = highlightColors.contentHashCode()
        val geometryChanged = shaderStart != start || shaderEnd != end
        if (
            geometryChanged ||
            shaderBackgroundHash != backgroundHash ||
            shaderHighlightHash != highlightHash
        ) {
            shaderStart = start
            shaderEnd = end
            shaderBackgroundHash = backgroundHash
            shaderHighlightHash = highlightHash
            backgroundShader = createShader(backgroundColors, start, end)
            highlightShader = createShader(highlightColors, start, end)
        }

        backgroundPaint.color = backgroundColors.firstOrNull() ?: Color.GRAY
        highlightPaint.color = highlightColors.firstOrNull() ?: Color.WHITE
        backgroundPaint.shader = backgroundShader
        highlightPaint.shader = highlightShader
    }

    private fun createShader(colors: IntArray, start: Float, end: Float): Shader? {
        if (!isGradientEnabled || colors.size < 2 || end <= start) return null
        val positions = FloatArray(colors.size) { index ->
            index.toFloat() / (colors.size - 1)
        }
        return LinearGradient(
            start,
            0f,
            end,
            0f,
            colors,
            positions,
            Shader.TileMode.CLAMP
        )
    }

    private fun clearShaders() {
        backgroundShader = null
        highlightShader = null
        shaderStart = Float.NaN
        shaderEnd = Float.NaN
    }

    private fun smoothStep(value: Float): Float = value * value * (3f - 2f * value)

    private companion object {
        const val DOT_COUNT = 3
        const val RADIUS_TEXT_SIZE_FACTOR = 0.375f
        const val GAP_FACTOR = 0.55f
        const val SCALE_AMOUNT = 0.2f
        const val MAX_SCALE = 1f + SCALE_AMOUNT
    }
}
