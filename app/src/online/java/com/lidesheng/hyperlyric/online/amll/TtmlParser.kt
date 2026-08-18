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
 * - span 之间的空白文本节点保留为单词分隔符（英文歌词），含换行的格式化空白忽略
 * - `ttm:agent` 对唱标记 → metadata["amll:agent"]（渲染层不区分，仅元数据保留）
 * - `ttm:role="x-bg"` 背景人声 → secondary/secondaryWords（优先级高于翻译/罗马音）；
 *   内部嵌套的无 role 逐字 span 递归解析为 secondaryWords（供副行逐字表演），
 *   嵌套的翻译/罗马音丢弃；内部无逐字 span 但外层自带时间轴时回退为整段词
 * - `ttm:role="x-translation"` 翻译 → 按 xml:lang 从候选中挑选一条写入 translation
 *   （优先级：完全匹配 > 简繁脚本等价 > 主语言前缀 > 无语言标记 > 第一个；行内无 x-bg 时）
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

    /** 候选翻译：同一行的多个翻译 span 按 xml:lang 归集（相邻同语言的拼接） */
    private class TranslationCandidate(val lang: String?) {
        val text = StringBuilder()
        val words = mutableListOf<LyricWord>()
    }

    /** 解析过程中的 `<p>` 中间载体 */
    private class ParsedParagraph {
        var begin = -1L
        var end = -1L
        var agent: String? = null
        val mainWords = mutableListOf<LyricWord>()
        val mainExtraText = StringBuilder()
        val bgWords = mutableListOf<LyricWord>()
        val bgExtraText = StringBuilder()
        val translations = mutableListOf<TranslationCandidate>()
        var romaText: String? = null

        /**
         * span 之间的空白分隔符（AMLL 英文歌词的单词间空格是独立的空白文本节点），
         * 由下一个主歌词内容消费；含换行的空白视为 XML 格式化噪声，不会置位
         */
        var pendingSpace = false

        /** bg 内部的空白分隔符（如 "(Fast lane)" 的词间空格），机制同主行 pendingSpace */
        var bgPendingSpace = false

        /** 是否已有主歌词内容（分隔符仅在已有内容之后生效，行首空白忽略） */
        fun hasMainContent(): Boolean = mainWords.isNotEmpty() || mainExtraText.isNotEmpty()

        /** 是否已有背景人声内容（分隔符仅在已有内容之后生效） */
        fun hasBgContent(): Boolean = bgWords.isNotEmpty() || bgExtraText.isNotEmpty()

        /** 消费待拼接空格：已有内容且新文本非空白开头时补一个空格 */
        fun consumePendingSpace(text: String): String {
            if (!pendingSpace) return text
            pendingSpace = false
            if (!hasMainContent() || text.isEmpty() || text[0].isWhitespace()) return text
            return " $text"
        }

        /** 消费 bg 待拼接空格（机制同主行） */
        fun consumeBgPendingSpace(text: String): String {
            if (!bgPendingSpace) return text
            bgPendingSpace = false
            if (!hasBgContent() || text.isEmpty() || text[0].isWhitespace()) return text
            return " $text"
        }

        /** 收集无时间轴的主歌词文本 */
        fun appendMainExtra(text: String) {
            mainExtraText.append(consumePendingSpace(text))
        }

        /** 收集无时间轴的背景人声文本 */
        fun appendBgExtra(text: String) {
            bgExtraText.append(consumeBgPendingSpace(text))
        }

        /** 收集一条候选翻译（相邻同语言的拼接为一条） */
        fun addTranslation(lang: String?, text: String, word: LyricWord?) {
            if (text.isEmpty()) return
            val target = translations.lastOrNull()?.takeIf { it.lang == lang }
                ?: TranslationCandidate(lang).also { translations.add(it) }
            target.text.append(text)
            if (word != null) target.words.add(word)
        }
    }

    /**
     * 解析 TTML 字符串为歌词行列表。
     *
     * @param ttml TTML 原文
     * @param preferredLang 首选翻译语言（BCP 47 标签，如 zh-CN），用于从多语言候选中挑选翻译；
     * 默认取系统语言
     * @return 解析成功返回按时间顺序的行列表；解析失败返回 null（调用方走未命中回落）
     */
    fun parse(
        ttml: String,
        preferredLang: String? = Locale.getDefault().toLanguageTag()
    ): List<RichLyricLine>? {
        return try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(StringReader(ttml))
            val paragraphs = parseDocument(parser)
            if (paragraphs.isEmpty()) {
                HookLogger.d(TAG, "TTML 解析未命中: reason=no_paragraph")
                return null
            }
            val lines = buildLines(paragraphs, preferredLang)
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
                    if (text.isNotBlank()) {
                        // 非纯空白文本：无 span 的 `<p>` 直接文本，消费待拼接空格后收集
                        paragraph.appendMainExtra(text.trim())
                    } else if (text.isNotEmpty() && !text.contains('\n') && !text.contains('\r')) {
                        // span 之间的同行空白（AMLL 英文歌词的单词分隔空格）：记为待拼接分隔符；
                        // 含换行的空白视为 XML 格式化噪声忽略（避免 CJK 逐字歌词被错误加空格）
                        paragraph.pendingSpace = true
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
        val lang = attrValue(parser, "lang")
        val begin = parseTimeAttr(parser, "begin")
        val end = parseTimeAttr(parser, "end")

        when (role) {
            ROLE_BG -> parseBgSpan(parser, paragraph, begin, end)

            ROLE_TRANSLATION -> {
                val text = readTextUntilEnd(parser, TAG_SPAN)
                if (text.isNotEmpty()) {
                    paragraph.addTranslation(lang, text, buildWordOrNull(begin, end, text))
                }
            }

            ROLE_ROMAN -> {
                val text = readTextUntilEnd(parser, TAG_SPAN)
                if (paragraph.romaText == null) {
                    paragraph.romaText = text
                }
            }

            else -> {
                val text = readTextUntilEnd(parser, TAG_SPAN)
                if (begin >= 0) {
                    paragraph.mainWords.add(
                        buildWord(begin, end, paragraph.consumePendingSpace(text))
                    )
                } else if (text.isNotBlank()) {
                    paragraph.appendMainExtra(text.trim())
                } else if (text.isNotEmpty()) {
                    // 无时间轴的纯空白 span：同样视为单词分隔符
                    paragraph.pendingSpace = true
                }
            }
        }
    }

    /**
     * 解析 x-bg 背景人声 span（调用时位于 x-bg 的 START_TAG，返回时位于其 END_TAG）。
     *
     * 递归遍历内部节点：
     * - 无 role 且带 begin/end 的子 span → bgWords（真逐字时间轴，供副行逐字表演）
     * - 无 role 的无时间子 span / 直接文本 → bgExtraText（无逐字时间轴的兜底文本）
     * - 带其他 role 的子 span（嵌套翻译/罗马音等）→ 丢弃
     * - 同行空白文本节点 → 待拼接空格（如 "(Fast" 与 "lane)" 之间的分隔空格）
     *
     * 若内部没有任何逐字 span、仅有无时间文本，且外层 x-bg 自带 begin/end，
     * 则回退为一个覆盖外层时间轴的整段词（兼容无嵌套结构的扁平 TTML）
     */
    private fun parseBgSpan(parser: XmlPullParser, paragraph: ParsedParagraph, outerBegin: Long, outerEnd: Long) {
        val startWordCount = paragraph.bgWords.size
        val startExtraLength = paragraph.bgExtraText.length
        val targetDepth = parser.depth
        var eventType = parser.next()
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == TAG_SPAN) {
                        parseBgChildSpan(parser, paragraph)
                    } else {
                        skipCurrentElement(parser)
                    }
                }

                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    val text = parser.text.orEmpty()
                    if (text.isNotBlank()) {
                        paragraph.appendBgExtra(text.trim())
                    } else if (text.isNotEmpty() && !text.contains('\n') && !text.contains('\r')) {
                        paragraph.bgPendingSpace = true
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == TAG_SPAN && parser.depth == targetDepth) {
                        if (paragraph.bgWords.size == startWordCount &&
                            paragraph.bgExtraText.length > startExtraLength && outerBegin >= 0
                        ) {
                            // 扁平兜底：新增的无时间文本用外层整体时间轴并成一个词
                            val text = paragraph.bgExtraText.substring(startExtraLength)
                            paragraph.bgExtraText.setLength(startExtraLength)
                            paragraph.bgWords.add(buildWord(outerBegin, outerEnd, text))
                        }
                        return
                    }
                }
            }
            eventType = parser.next()
        }
    }

    /**
     * 解析 x-bg 内部的单个子 span（调用时位于其 START_TAG，返回时位于其 END_TAG）。
     * 无 role 的子 span 按主行同款机制收集；带 role 的子 span（嵌套翻译/罗马音）丢弃。
     */
    private fun parseBgChildSpan(parser: XmlPullParser, paragraph: ParsedParagraph) {
        val role = attrValue(parser, "role")
        if (role != null) {
            // 嵌套翻译/罗马音等：按既定决策丢弃（连带其全部内容）
            readTextUntilEnd(parser, TAG_SPAN)
            return
        }
        val begin = parseTimeAttr(parser, "begin")
        val end = parseTimeAttr(parser, "end")
        val text = readTextUntilEnd(parser, TAG_SPAN)
        if (begin >= 0) {
            paragraph.bgWords.add(buildWord(begin, end, paragraph.consumeBgPendingSpace(text)))
        } else if (text.isNotBlank()) {
            paragraph.appendBgExtra(text.trim())
        } else if (text.isNotEmpty()) {
            paragraph.bgPendingSpace = true
        }
    }

    /** 跳过当前元素的全部内容（调用时位于 START_TAG，返回时位于其匹配的 END_TAG） */
    private fun skipCurrentElement(parser: XmlPullParser) {
        val depth = parser.depth
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> if (parser.depth == depth) return
                XmlPullParser.END_DOCUMENT -> return
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

    private fun buildLines(
        paragraphs: List<ParsedParagraph>,
        preferredLang: String?
    ): List<RichLyricLine> {
        val merged = mutableListOf<RichLyricLine>()
        for (paragraph in paragraphs) {
            val line = buildLine(paragraph, preferredLang) ?: continue
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

    private fun buildLine(paragraph: ParsedParagraph, preferredLang: String?): RichLyricLine? {
        val bgText = buildString {
            paragraph.bgWords.forEach { append(it.text.orEmpty()) }
            append(paragraph.bgExtraText.toString().trim())
        }
        val hasBg = bgText.isNotBlank()
        val mainText = buildString {
            paragraph.mainWords.forEach { append(it.text.orEmpty()) }
            append(paragraph.mainExtraText.toString().trim())
        }
        if (mainText.isBlank() && !hasBg) return null

        // 同一行既有 x-bg 又有翻译/罗马音时，优先填充 secondary（背景人声），跳过翻译与罗马音
        val pickedTranslation = if (hasBg) null else pickTranslation(paragraph.translations, preferredLang)

        val begin = paragraph.begin.coerceAtLeast(0L)
        val end = if (paragraph.end >= paragraph.begin && paragraph.end >= 0) paragraph.end else begin

        return RichLyricLine(
            begin = begin,
            end = end,
            text = mainText.takeIf { it.isNotBlank() },
            words = paragraph.mainWords.takeIf { it.isNotEmpty() },
            secondary = bgText.trim().takeIf { it.isNotBlank() },
            secondaryWords = paragraph.bgWords.takeIf { it.isNotEmpty() },
            translation = pickedTranslation?.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
            translationWords = pickedTranslation?.words?.takeIf { it.isNotEmpty() },
            roma = if (hasBg) null else paragraph.romaText?.trim()?.takeIf { it.isNotBlank() },
            metadata = paragraph.agent?.let { LyricMetadata(mapOf(METADATA_KEY_AGENT to it)) }
        )
    }

    // ==================== 翻译语言挑选 ====================

    /** 中文简体区域（zh-Hans 脚本等价） */
    private val SIMPLIFIED_CHINESE_REGIONS = setOf("cn", "sg")

    /** 中文繁体区域（zh-Hant 脚本等价） */
    private val TRADITIONAL_CHINESE_REGIONS = setOf("tw", "hk", "mo")

    /**
     * 从候选翻译中挑选首选语言的一条。
     *
     * 优先级：完全匹配 > 简繁脚本等价（zh-CN↔zh-Hans、zh-TW↔zh-Hant 等）
     * > 主语言前缀匹配 > 无语言标记的候选 > 第一个候选（保底有内容）
     */
    private fun pickTranslation(
        candidates: List<TranslationCandidate>,
        preferredLang: String?
    ): TranslationCandidate? {
        if (candidates.isEmpty()) return null
        if (preferredLang != null) {
            val preferred = preferredLang.lowercase(Locale.US)
            // 1. 完全匹配
            candidates.firstOrNull { it.lang?.lowercase(Locale.US) == preferred }
                ?.let { return it }
            // 2. 简繁脚本等价
            val preferredScript = chineseScriptOf(preferred)
            if (preferredScript != null) {
                candidates.firstOrNull {
                    chineseScriptOf(it.lang?.lowercase(Locale.US)) == preferredScript
                }?.let { return it }
            }
            // 3. 主语言前缀匹配（如 zh-CN 匹配 zh / zh-Hant；en-US 匹配 en-GB）
            val mainLanguage = preferred.substringBefore('-')
            candidates.firstOrNull { candidate ->
                candidate.lang?.lowercase(Locale.US)?.let { lang ->
                    lang == mainLanguage || lang.startsWith("$mainLanguage-")
                } == true
            }?.let { return it }
        }
        // 4. 无语言标记的候选（TTML 未标注 xml:lang 时的默认译文）
        candidates.firstOrNull { it.lang == null }?.let { return it }
        // 5. 回退第一个候选
        return candidates.first()
    }

    /**
     * 中文简繁脚本归类：zh-Hans/zh-CN/zh-SG* → "hans"，zh-Hant/zh-TW/zh-HK/zh-MO* → "hant"；
     * 非中文或无法判断返回 null
     */
    private fun chineseScriptOf(lang: String?): String? {
        if (lang == null) return null
        val parts = lang.lowercase(Locale.US).split('-')
        if (parts.firstOrNull() != "zh") return null
        return when {
            "hans" in parts || parts.any { it in SIMPLIFIED_CHINESE_REGIONS } -> "hans"
            "hant" in parts || parts.any { it in TRADITIONAL_CHINESE_REGIONS } -> "hant"
            else -> null
        }
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

    /** 带时间轴才生成词，否则返回 null（翻译 span 通常无时间轴） */
    private fun buildWordOrNull(begin: Long, end: Long, text: String): LyricWord? =
        if (begin >= 0) buildWord(begin, end, text) else null

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
        val bgWordTimingCount = lines.count { !it.secondaryWords.isNullOrEmpty() }
        val agentCount = lines.count { it.metadata?.contains(METADATA_KEY_AGENT) == true }
        val translationCount = lines.count { !it.translation.isNullOrBlank() }
        HookLogger.d(
            TAG,
            "TTML 解析完成: lines=${lines.size}, wordTiming=$wordTimingCount, " +
                    "bg=$bgCount, bgWordTiming=$bgWordTimingCount, " +
                    "agent=$agentCount, translation=$translationCount"
        )
    }
}
