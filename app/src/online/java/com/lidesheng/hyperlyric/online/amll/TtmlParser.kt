package com.lidesheng.hyperlyric.online.amll

import android.util.Xml
import com.lidesheng.hyperlyric.lyric.model.LyricMetadata
import com.lidesheng.hyperlyric.lyric.model.LyricWord
import com.lidesheng.hyperlyric.lyric.model.RichLyricLine
import com.lidesheng.hyperlyric.root.utils.HookLogger
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.Locale

/**
 * AMLL TTML 解析器
 *
 * 解析 W3C TTML + Apple Music SMPTE 风格 span 时间轴，输出 [RichLyricLine] 列表。
 * 支持：
 * - `<p>` 行级 begin/end 与 `<span>` 逐字 begin/end
 * - `ttm:agent` 对唱标记 → metadata["amll:agent"]（渲染层不区分，仅元数据保留）
 * - `ttm:role="x-bg"` 背景人声 → secondary/secondaryWords（优先级高于翻译/罗马音）
 * - `ttm:role="x-translation"` 翻译 → translation/translationWords（行内无 x-bg 时）
 * - `ttm:role="x-roman"` 罗马音 → roma（行内无 x-bg 时）
 * - 同 begin 时间多行压缩：第一行主行、第二行副行（secondary）、第三行及以后丢弃
 * - `itunes:song-part` 段落标记忽略
 */
object TtmlParser {

    private const val TAG = "AmllTtmlSource"

    /** TTML 命名空间下的行/文本元素本地名 */
    private const val TAG_PARAGRAPH = "p"
    private const val TAG_SPAN = "span"

    private const val ROLE_BG = "x-bg"
    private const val ROLE_TRANSLATION = "x-translation"
    private const val ROLE_ROMAN = "x-roman"

    const val METADATA_KEY_AGENT = "amll:agent"

    /** 解析过程中的 `<p>` 中间载体 */
    private class ParsedParagraph {
        var begin = -1L
        var end = -1L
        var agent: String? = null
        val mainWords = mutableListOf<LyricWord>()
        val mainExtraText = StringBuilder()
        val bgWords = mutableListOf<LyricWord>()
        val bgText = StringBuilder()
        val translationWords = mutableListOf<LyricWord>()
        val translationText = StringBuilder()
        var romaText: String? = null
    }

    /**
     * 解析 TTML 字符串为歌词行列表。
     *
     * @return 解析成功返回按时间顺序的行列表；解析失败返回 null（调用方走未命中回落）
     */
    fun parse(ttml: String): List<RichLyricLine>? {
        return try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(StringReader(ttml))
            val paragraphs = parseDocument(parser)
            if (paragraphs.isEmpty()) {
                HookLogger.d(TAG, "TTML 解析未命中: reason=no_paragraph")
                return null
            }
            val lines = buildLines(paragraphs)
            if (lines.isEmpty()) {
                HookLogger.d(TAG, "TTML 解析未命中: reason=no_valid_line")
                return null
            }
            logStats(lines)
            lines
        } catch (e: Exception) {
            HookLogger.d(TAG, "TTML 解析失败: type=${e.javaClass.simpleName}")
            null
        }
    }

    // ==================== 文档遍历 ====================

    private fun parseDocument(parser: XmlPullParser): List<ParsedParagraph> {
        val paragraphs = mutableListOf<ParsedParagraph>()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == TAG_PARAGRAPH) {
                paragraphs.add(parseParagraph(parser))
            }
            eventType = parser.next()
        }
        return paragraphs
    }

    /**
     * 解析单个 `<p>` 元素（调用时 parser 位于 p 的 START_TAG，返回时位于 p 的 END_TAG）。
     */
    private fun parseParagraph(parser: XmlPullParser): ParsedParagraph {
        val paragraph = ParsedParagraph()
        paragraph.begin = parseTimeAttr(parser, "begin")
        paragraph.end = parseTimeAttr(parser, "end")
        paragraph.agent = attrValue(parser, "agent")

        val paragraphDepth = parser.depth
        var eventType = parser.next()
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == TAG_SPAN) {
                        parseSpanInto(parser, paragraph)
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text.orEmpty()
                    // 仅收集非纯空白文本（无 span 的 `<p>` 直接文本；span 间格式化空白忽略）
                    if (text.isNotBlank()) {
                        paragraph.mainExtraText.append(text.trim())
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == TAG_PARAGRAPH && parser.depth == paragraphDepth) {
                        return paragraph
                    }
                }
            }
            eventType = parser.next()
        }
        return paragraph
    }

    /**
     * 解析单个 `<span>` 元素并按 ttm:role 分流（返回时位于 span 的 END_TAG）。
     */
    private fun parseSpanInto(parser: XmlPullParser, paragraph: ParsedParagraph) {
        val role = attrValue(parser, "role")
        val begin = parseTimeAttr(parser, "begin")
        val end = parseTimeAttr(parser, "end")
        val text = readTextUntilEnd(parser, TAG_SPAN)
        if (text.isEmpty() && begin < 0) return

        when (role) {
            ROLE_BG -> {
                paragraph.bgText.append(text)
                if (begin >= 0) {
                    paragraph.bgWords.add(buildWord(begin, end, text))
                }
            }

            ROLE_TRANSLATION -> {
                paragraph.translationText.append(text)
                if (begin >= 0) {
                    paragraph.translationWords.add(buildWord(begin, end, text))
                }
            }

            ROLE_ROMAN -> {
                if (paragraph.romaText == null) {
                    paragraph.romaText = text
                }
            }

            else -> {
                if (begin >= 0) {
                    paragraph.mainWords.add(buildWord(begin, end, text))
                } else if (text.isNotBlank()) {
                    paragraph.mainExtraText.append(text.trim())
                }
            }
        }
    }

    /**
     * 读取元素内全部文本，直到当前深度的目标标签 END_TAG。
     */
    private fun readTextUntilEnd(parser: XmlPullParser, targetTag: String): String {
        val sb = StringBuilder()
        val targetDepth = parser.depth
        while (true) {
            when (val eventType = parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(parser.text.orEmpty())

                XmlPullParser.END_TAG -> {
                    if (parser.name == targetTag && parser.depth == targetDepth) {
                        return sb.toString()
                    }
                }

                XmlPullParser.END_DOCUMENT -> return sb.toString()
            }
        }
    }

    // ==================== 行构造与合并 ====================

    private fun buildLines(paragraphs: List<ParsedParagraph>): List<RichLyricLine> {
        val merged = mutableListOf<RichLyricLine>()
        for (paragraph in paragraphs) {
            val line = buildLine(paragraph) ?: continue
            val last = merged.lastOrNull()
            if (last != null && last.begin == line.begin) {
                // 同 begin 多行压缩：第二行作为副行（先出现者的 x-bg 优先），第三行及以后丢弃
                val secondaryCandidate = line.text?.takeIf { it.isNotBlank() }
                    ?: line.secondary?.takeIf { it.isNotBlank() }
                if (last.secondary.isNullOrBlank() && secondaryCandidate != null) {
                    last.secondary = secondaryCandidate
                    last.secondaryWords = line.words ?: line.secondaryWords
                }
                continue
            }
            merged.add(line)
        }
        return merged
    }

    private fun buildLine(paragraph: ParsedParagraph): RichLyricLine? {
        val hasBg = paragraph.bgText.isNotBlank()
        val mainText = buildString {
            paragraph.mainWords.forEach { append(it.text.orEmpty()) }
            append(paragraph.mainExtraText.toString().trim())
        }
        if (mainText.isBlank() && !hasBg) return null

        val begin = paragraph.begin.coerceAtLeast(0L)
        val end = if (paragraph.end >= paragraph.begin && paragraph.end >= 0) paragraph.end else begin

        return RichLyricLine(
            begin = begin,
            end = end,
            text = mainText.takeIf { it.isNotBlank() },
            words = paragraph.mainWords.takeIf { it.isNotEmpty() },
            secondary = paragraph.bgText.toString().trim().takeIf { it.isNotBlank() },
            secondaryWords = paragraph.bgWords.takeIf { it.isNotEmpty() },
            // 同一行既有 x-bg 又有翻译/罗马音时，优先填充 secondary（背景人声），跳过翻译与罗马音
            translation = if (hasBg) null
            else paragraph.translationText.toString().trim().takeIf { it.isNotBlank() },
            translationWords = if (hasBg) null else paragraph.translationWords.takeIf { it.isNotEmpty() },
            roma = if (hasBg) null else paragraph.romaText?.trim()?.takeIf { it.isNotBlank() },
            metadata = paragraph.agent?.let { LyricMetadata(mapOf(METADATA_KEY_AGENT to it)) }
        )
    }

    // ==================== 属性与时间解析 ====================

    /** 按本地名读取元素属性值（ttm:agent → "agent"，itunes:song-part → "song-part"） */
    private fun attrValue(parser: XmlPullParser, localName: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == localName) {
                return parser.getAttributeValue(i)?.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun parseTimeAttr(parser: XmlPullParser, localName: String): Long =
        attrValue(parser, localName)?.let { parseTtmlTime(it) } ?: -1L

    private fun buildWord(begin: Long, end: Long, text: String): LyricWord =
        LyricWord(
            begin = begin,
            end = if (end >= begin) end else begin,
            text = text
        )

    /**
     * 解析 TTML 时间表达式为毫秒。
     *
     * 支持格式：
     * - `HH:MM:SS.mmm`（如 `00:01:23.456`）
     * - `MM:SS.mmm`（如 `01:23.456`）
     * - `SS.sss s`（如 `83.456s`、`83456ms`，无单位默认秒）
     *
     * @return 毫秒值；无法解析返回 -1
     */
    fun parseTtmlTime(expr: String): Long {
        val normalized = expr.trim().lowercase(Locale.US)
        if (normalized.isEmpty()) return -1L

        if (!normalized.contains(':')) {
            val (numericPart, unit) = when {
                normalized.endsWith("ms") -> normalized.removeSuffix("ms") to "ms"
                normalized.endsWith("s") -> normalized.removeSuffix("s") to "s"
                normalized.endsWith("h") -> normalized.removeSuffix("h") to "h"
                normalized.endsWith("m") -> normalized.removeSuffix("m") to "m"
                else -> normalized to "s"
            }
            val value = numericPart.toDoubleOrNull() ?: return -1L
            val multiplier = when (unit) {
                "ms" -> 0.001
                "m" -> 60.0
                "h" -> 3600.0
                else -> 1.0
            }
            return (value * multiplier * 1000).toLong()
        }

        val parts = normalized.split(':')
        if (parts.size !in 2..3) return -1L
        var totalSeconds = 0.0
        for (part in parts) {
            val value = part.toDoubleOrNull() ?: return -1L
            totalSeconds = totalSeconds * 60 + value
        }
        return (totalSeconds * 1000).toLong()
    }

    // ==================== 统计日志 ====================

    private fun logStats(lines: List<RichLyricLine>) {
        val wordTimingCount = lines.count { !it.words.isNullOrEmpty() }
        val bgCount = lines.count { !it.secondary.isNullOrBlank() }
        val agentCount = lines.count { it.metadata?.contains(METADATA_KEY_AGENT) == true }
        val translationCount = lines.count { !it.translation.isNullOrBlank() }
        HookLogger.d(
            TAG,
            "TTML 解析完成: lines=${lines.size}, wordTiming=$wordTimingCount, " +
                    "bg=$bgCount, agent=$agentCount, translation=$translationCount"
        )
    }
}
