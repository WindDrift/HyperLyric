/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.lidesheng.hyperlyric.lyric.view.line

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.text.TextPaint
import androidx.core.graphics.withSave
import com.lidesheng.hyperlyric.lyric.view.line.model.LyricModel
import com.lidesheng.hyperlyric.lyric.view.line.model.WordModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal class TextDrawer {
    private var bgColors = intArrayOf(Color.GRAY)
    private var hlColors = intArrayOf(Color.WHITE)

    private val cjkMotionSpec = MotionSpec(
        animateByChar = true,
        distributeCharsEvenly = false,
        liftFactor = DEFAULT_CJK_LIFT_FACTOR,
        waveFactor = DEFAULT_CJK_WAVE_FACTOR
    )
    private val latinMotionSpec = MotionSpec(
        animateByChar = false,
        distributeCharsEvenly = false,
        liftFactor = DEFAULT_LATIN_LIFT_FACTOR,
        waveFactor = DEFAULT_LATIN_WAVE_FACTOR
    )

    var cjkLiftFactor: Float
        get() = cjkMotionSpec.liftFactor
        set(value) {
            cjkMotionSpec.liftFactor = value
        }
    var cjkWaveFactor: Float
        get() = cjkMotionSpec.waveFactor
        set(value) {
            cjkMotionSpec.waveFactor = value
        }
    var latinByCharacter: Boolean
        get() = latinMotionSpec.animateByChar
        set(value) {
            latinMotionSpec.animateByChar = value
            latinMotionSpec.distributeCharsEvenly = value
        }
    var latinLiftFactor: Float
        get() = latinMotionSpec.liftFactor
        set(value) {
            latinMotionSpec.liftFactor = value
        }
    var latinWaveFactor: Float
        get() = latinMotionSpec.waveFactor
        set(value) {
            latinMotionSpec.waveFactor = value
        }

    val isRainbowBg get() = bgColors.size > 1
    val isRainbowHl get() = hlColors.size > 1

    private val fontMetrics = Paint.FontMetrics()
    private var baselineOffset = 0f

    private val bgRainbowCache = RainbowShaderCache()
    private val hlRainbowCache = RainbowShaderCache()
    private var cachedSolidHighlightShader: LinearGradient? = null
    private var lastSolidHighlightWidth = -1f
    private var lastSolidHighlightColor = 0
    private var cachedAlphaMaskShader: LinearGradient? = null
    private var lastAlphaMaskTotalWidth = -1f
    private var lastHighlightWidth = -1f

    fun setColors(background: IntArray, highlight: IntArray) {
        if (background.isNotEmpty()) bgColors = background
        if (highlight.isNotEmpty()) hlColors = highlight
    }

    fun updateMetrics(paint: TextPaint) {
        paint.getFontMetrics(fontMetrics)
        baselineOffset = -(fontMetrics.descent + fontMetrics.ascent) / 2f
    }

    fun clearShaderCache() {
        bgRainbowCache.clear()
        hlRainbowCache.clear()
        cachedSolidHighlightShader = null
        lastSolidHighlightWidth = -1f
        lastSolidHighlightColor = 0
        cachedAlphaMaskShader = null
        lastAlphaMaskTotalWidth = -1f
        lastHighlightWidth = -1f
    }

    fun motionBottomPadding(model: LyricModel, textSize: Float): Float {
        if (model.words.isEmpty()) return 0f
        val cjkOffset = cjkMotionSpec.effectiveOffset(textSize)
        val latinOffset = latinMotionSpec.effectiveOffset(textSize)
        return max(cjkOffset, latinOffset)
    }

    fun draw(
        canvas: Canvas,
        model: LyricModel,
        viewWidth: Int,
        viewHeight: Int,
        scrollX: Float,
        isOverflow: Boolean,
        highlightWidth: Float,
        useGradient: Boolean,
        scrollOnly: Boolean,
        charMotionEnabled: Boolean,
        centerIfPossible: Boolean,
        rightIfPossible: Boolean,
        bgPaint: TextPaint,
        hlPaint: TextPaint,
        normPaint: TextPaint
    ) {
        val motionPadding = if (charMotionEnabled && !scrollOnly) {
            motionBottomPadding(model, bgPaint.textSize)
        } else {
            0f
        }
        val contentHeight = (viewHeight.toFloat() - motionPadding).coerceAtLeast(0f)
        val y = contentHeight / 2f + baselineOffset
        canvas.withSave {
            val xOffset = when {
                isOverflow -> scrollX
                model.isAlignedRight || rightIfPossible -> viewWidth - model.width
                centerIfPossible -> (viewWidth - model.width) / 2f
                else -> 0f
            }
            translate(xOffset, 0f)
            val visibleStart = (-xOffset).coerceAtLeast(0f)
            val visibleEnd = (viewWidth - xOffset).coerceAtMost(model.width)
            if (visibleEnd <= visibleStart) return@withSave

            if (scrollOnly) {
                canvas.drawText(model.wordText, 0f, y, normPaint)
                return@withSave
            }

            if (isRainbowBg) {
                bgPaint.shader = getOrCreateRainbowShader(model.width, bgColors, bgRainbowCache)
            } else {
                bgPaint.shader = null
            }

            if (charMotionEnabled) {
                val bgClipStart = if (useGradient) 0f else highlightWidth
                drawAnimatedUnits(
                    canvas,
                    model,
                    highlightWidth,
                    max(bgClipStart, visibleStart),
                    visibleEnd,
                    viewHeight,
                    y,
                    bgPaint
                )
            } else if (!useGradient) {
                canvas.withSave {
                    canvas.clipRect(highlightWidth, 0f, Float.MAX_VALUE, viewHeight.toFloat())
                    canvas.drawText(model.wordText, 0f, y, bgPaint)
                }
            } else {
                canvas.drawText(model.wordText, 0f, y, bgPaint)
            }

            if (highlightWidth > 0f) {
                canvas.withSave {
                    canvas.clipRect(0f, 0f, highlightWidth, viewHeight.toFloat())

                    val atEnd = highlightWidth >= model.width
                    if (useGradient && !atEnd) {
                        val baseShader = if (isRainbowHl) {
                            getOrCreateRainbowShader(model.width, hlColors, hlRainbowCache)
                        } else {
                            getOrCreateSolidHighlightShader(model.width, hlPaint.color)
                        }
                        val maskShader = getOrCreateAlphaMaskShader(model.width, highlightWidth)
                        hlPaint.shader =
                            ComposeShader(baseShader, maskShader, PorterDuff.Mode.DST_IN)
                    } else {
                        if (isRainbowHl) {
                            hlPaint.shader =
                                getOrCreateRainbowShader(model.width, hlColors, hlRainbowCache)
                        } else {
                            hlPaint.shader = null
                        }
                    }
                    if (charMotionEnabled) {
                        drawAnimatedUnits(
                            canvas,
                            model,
                            highlightWidth,
                            visibleStart,
                            min(highlightWidth, visibleEnd),
                            viewHeight,
                            y,
                            hlPaint
                        )
                    } else {
                        canvas.drawText(model.wordText, 0f, y, hlPaint)
                    }
                }
            }
        }
    }

    private fun drawAnimatedUnits(
        canvas: Canvas,
        model: LyricModel,
        highlightWidth: Float,
        clipStart: Float,
        clipEnd: Float,
        viewHeight: Int,
        baselineY: Float,
        paint: TextPaint
    ) {
        model.words.forEach { word ->
            val motionSpec = word.motionSpec()
            if (!motionSpec.animateByChar) {
                drawAnimatedTextUnit(
                    canvas = canvas,
                    text = word.text,
                    start = 0,
                    end = word.text.length,
                    drawX = word.startPosition,
                    unitStart = word.startPosition,
                    unitEnd = word.endPosition,
                    motionStart = word.startPosition,
                    motionEnd = word.endPosition,
                    highlightWidth = highlightWidth,
                    clipStart = clipStart,
                    clipEnd = clipEnd,
                    viewHeight = viewHeight,
                    baselineY = baselineY,
                    paint = paint,
                    motionSpec = motionSpec
                )
                return@forEach
            }

            val evenMotionWidth = if (motionSpec.distributeCharsEvenly) {
                word.textWidth / word.chars.size.coerceAtLeast(1)
            } else {
                0f
            }
            for (i in word.chars.indices) {
                val charStart = word.charStartPositions[i]
                val charEnd = word.charEndPositions[i]
                val motionStart = if (motionSpec.distributeCharsEvenly) {
                    word.startPosition + evenMotionWidth * i
                } else {
                    charStart
                }
                val motionEnd = if (motionSpec.distributeCharsEvenly) {
                    motionStart + evenMotionWidth
                } else {
                    charEnd
                }
                drawAnimatedTextUnit(
                    canvas = canvas,
                    text = word.text,
                    start = i,
                    end = i + 1,
                    drawX = charStart,
                    unitStart = charStart,
                    unitEnd = charEnd,
                    motionStart = motionStart,
                    motionEnd = motionEnd,
                    highlightWidth = highlightWidth,
                    clipStart = clipStart,
                    clipEnd = clipEnd,
                    viewHeight = viewHeight,
                    baselineY = baselineY,
                    paint = paint,
                    motionSpec = motionSpec
                )
            }
        }
    }

    private fun drawAnimatedTextUnit(
        canvas: Canvas,
        text: String,
        start: Int,
        end: Int,
        drawX: Float,
        unitStart: Float,
        unitEnd: Float,
        motionStart: Float,
        motionEnd: Float,
        highlightWidth: Float,
        clipStart: Float,
        clipEnd: Float,
        viewHeight: Int,
        baselineY: Float,
        paint: TextPaint,
        motionSpec: MotionSpec
    ) {
        if (unitEnd <= clipStart || unitStart >= clipEnd) return

        val visibleLeft = unitStart.coerceAtLeast(clipStart)
        val visibleRight = unitEnd.coerceAtMost(clipEnd)
        val liftY = computeUnitLift(
            highlightWidth,
            motionStart,
            motionEnd,
            paint.textSize,
            motionSpec
        )

        canvas.withSave {
            clipRect(visibleLeft, 0f, visibleRight, viewHeight.toFloat())
            drawText(text, start, end, drawX, baselineY + liftY, paint)
        }
    }

    private fun computeUnitLift(
        highlightWidth: Float,
        unitStart: Float,
        unitEnd: Float,
        textSize: Float,
        motionSpec: MotionSpec
    ): Float {
        if (motionSpec.liftFactor <= 0f || motionSpec.waveFactor <= 0f) return 0f
        val maxOffset = textSize * motionSpec.liftFactor
        val unitCenter = (unitStart + unitEnd) / 2f
        val waveLength = textSize * motionSpec.waveFactor
        val phase = ((highlightWidth - unitCenter) / waveLength).coerceIn(0f, 1f)
        return maxOffset * (1f - easeOutQuint(phase))
    }

    private fun WordModel.motionSpec(): MotionSpec =
        if (containsCjk) cjkMotionSpec else latinMotionSpec

    private fun easeOutQuint(value: Float): Float {
        val inverse = 1f - value
        return 1f - inverse * inverse * inverse * inverse * inverse
    }

    private class MotionSpec(
        var animateByChar: Boolean,
        var distributeCharsEvenly: Boolean,
        var liftFactor: Float,
        var waveFactor: Float
    ) {
        fun effectiveOffset(textSize: Float): Float =
            if (waveFactor > 0f && liftFactor > 0f) textSize * liftFactor else 0f
    }

    private fun getOrCreateRainbowShader(
        totalWidth: Float,
        colors: IntArray,
        cache: RainbowShaderCache
    ): Shader {
        val colorsHash = colors.contentHashCode()
        if (cache.shader == null || cache.totalWidth != totalWidth ||
            cache.colorsHash != colorsHash
        ) {
            cache.shader = LinearGradient(
                0f, 0f, totalWidth, 0f,
                colors, null, Shader.TileMode.CLAMP
            )
            cache.totalWidth = totalWidth
            cache.colorsHash = colorsHash
        }
        return cache.shader!!
    }

    private fun getOrCreateSolidHighlightShader(totalWidth: Float, color: Int): Shader {
        if (cachedSolidHighlightShader == null || lastSolidHighlightWidth != totalWidth ||
            lastSolidHighlightColor != color
        ) {
            cachedSolidHighlightShader = LinearGradient(
                0f, 0f, totalWidth, 0f,
                color, color, Shader.TileMode.CLAMP
            )
            lastSolidHighlightWidth = totalWidth
            lastSolidHighlightColor = color
        }
        return cachedSolidHighlightShader!!
    }

    private fun getOrCreateAlphaMaskShader(totalWidth: Float, highlightWidth: Float): Shader {
        val edgePosition = if (totalWidth > 0f) {
            max(highlightWidth / totalWidth, 0.9f).coerceIn(0f, 1f)
        } else {
            1f
        }
        if (cachedAlphaMaskShader == null || lastAlphaMaskTotalWidth != totalWidth ||
            abs(lastHighlightWidth - highlightWidth) > 0.1f
        ) {
            cachedAlphaMaskShader = LinearGradient(
                0f, 0f, highlightWidth, 0f,
                intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, edgePosition, 1f),
                Shader.TileMode.CLAMP
            )
            lastAlphaMaskTotalWidth = totalWidth
            lastHighlightWidth = highlightWidth
        }
        return cachedAlphaMaskShader!!
    }

    private class RainbowShaderCache {
        var shader: LinearGradient? = null
        var totalWidth = -1f
        var colorsHash = 0

        fun clear() {
            shader = null
            totalWidth = -1f
            colorsHash = 0
        }
    }

    private companion object {
        const val DEFAULT_CJK_LIFT_FACTOR = 0.055f
        const val DEFAULT_CJK_WAVE_FACTOR = 2.8f
        const val DEFAULT_LATIN_LIFT_FACTOR = 0.08f
        const val DEFAULT_LATIN_WAVE_FACTOR = 2.0f
    }
}

