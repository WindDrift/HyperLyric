package com.lidesheng.hyperlyric.common.lyric

import com.lidesheng.hyperlyric.lyric.model.RichLyricLine
import com.lidesheng.hyperlyric.lyric.model.interfaces.IRichLyricLine

internal const val METADATA_KEY_ALIGNMENT_RESOLVED = "hyperlyric:alignment-resolved"

/**
 * AMLL TTML 解析器写入的来源标识，用于保留歌词来源信息。
 * 长间奏倒计时不再依赖此标识，只根据逐字时间轴判断。
 */
internal const val METADATA_KEY_AMLL_TTML_SOURCE = "amll:ttml"

/**
 * The host-side presentation boundary for one lyric frame.
 *
 * A source may provide more than two active lines, but the Super Island renderer intentionally
 * consumes at most one primary row and one secondary row. The source data itself stays untouched.
 */
internal data class LyricPresentation(
    val primary: IRichLyricLine?,
    val secondary: IRichLyricLine? = null,
    val secondaryContent: LyricSecondaryContent? = null
)

/**
 * Resolves generic rich-lyric data into the two rows available to the host renderer.
 *
 * This does not parse a source format. Providers publish already parsed lines and optional
 * metadata; the host only chooses which of those lines to display and applies the configured
 * two-agent alignment convention.
 */
internal object LyricPresentationResolver {

    fun resolve(
        activeLines: List<IRichLyricLine>,
        nextLine: IRichLyricLine?,
        songLines: List<IRichLyricLine>?,
        settings: LyricContentDisplaySettings,
        autoDuet: Boolean,
        position: Long? = null
    ): LyricPresentation {
        val alignedLines = alignAgents(
            lines = activeLines,
            songLines = songLines,
            enabled = autoDuet
        )
        val visibleLines = alignedLines.filter { hasLyricContent(it) }
        val currentPrimary = visibleLines.firstOrNull()
            ?: alignedLines.firstOrNull()
            ?: return LyricPresentation(null)
        // Derive row ownership from this continuous overlap, not a View's previous frame or
        // the first singer in the entire song. A completed main row may remain while its
        // independently timed sibling is still singing. Gap/preview rows never enter this path.
        val duetPrimary = if (autoDuet && settings.showOverlappingLine && position != null &&
            visibleLines.any { it.begin <= position && position < it.end }
        ) {
            primaryInOverlap(songLines, position)?.let {
                alignAgents(listOf(it), songLines, enabled = true).first()
            }
        } else {
            null
        }
        var primary = duetPrimary ?: currentPrimary
        // A timing collision alone is ambiguous: ordinary sources commonly use
        // end == next.begin for adjacent lines. Only a pair with explicit,
        // distinct vocal identities may occupy the independent duet row.
        fun overlappingLineFor(line: IRichLyricLine) = visibleLines.firstOrNull {
            hasDistinctVocalIdentities(line, it)
        }
        var overlappingLine = overlappingLineFor(primary)
        var selectedContent = settings.preferredContentFor(
            line = primary,
            nextLine = nextLine,
            songLines = songLines,
            overlappingLine = overlappingLine
        )
        // Retaining an ended row is meaningful only when the duet row actually wins the
        // user's secondary-content policy. Otherwise render the current singer normally.
        if (duetPrimary != null && position != null && position >= duetPrimary.end &&
            selectedContent != LyricSecondaryContent.OVERLAPPING_LINE
        ) {
            primary = currentPrimary
            overlappingLine = overlappingLineFor(primary)
            selectedContent = settings.preferredContentFor(primary, nextLine, songLines, overlappingLine)
        }
        return LyricPresentation(
            primary = primary,
            secondary = overlappingLine.takeIf {
                selectedContent == LyricSecondaryContent.OVERLAPPING_LINE
            },
            secondaryContent = selectedContent
        )
    }

    private fun alignAgents(
        lines: List<IRichLyricLine>,
        songLines: List<IRichLyricLine>?,
        enabled: Boolean
    ): List<IRichLyricLine> {
        if (!enabled) return lines

        val agents = laneOrder(songLines.orEmpty() + lines)
        val hasExplicitAgentTypes = (songLines.orEmpty() + lines).any {
            it.agentType() != null
        }
        if (hasExplicitAgentTypes) {
            return alignTypedAgents(lines)
        }
        if (agents.size < 2) return lines

        // Keep the first singer on the normal/left side and group subsequent agents on the
        // opposite side. This works with arbitrary provider IDs instead of assuming v1/v2.
        val rightByAgent = agents.withIndex().associate { indexed ->
            indexed.value to (indexed.index > 0)
        }
        return lines.map { line ->
            if (line.metadata?.getBoolean(METADATA_KEY_ALIGNMENT_RESOLVED) == true) {
                return@map line
            }
            val right = line.agentId()?.let { rightByAgent[it] } ?: return@map line
            if (line.isAlignedRight) return@map line
            line.withAlignment(right)
        }
    }

    /**
     * AMLL declares `group` agents as a single singer and `other` agents as the first
     * alternating singer. This is deliberately host-side so the rule also applies to future
     * non-AMLL sources that publish the same metadata contract.
     */
    private fun alignTypedAgents(lines: List<IRichLyricLine>): List<IRichLyricLine> {
        var lastAgent: String? = null
        var lastRight = false
        return lines.map { line ->
            if (line.metadata?.getBoolean(METADATA_KEY_ALIGNMENT_RESOLVED) == true) {
                return@map line
            }
            val agent = line.agentId() ?: return@map line
            if (line.agentType() == "group") return@map line

            val right = when {
                lastAgent == null -> {
                    lastAgent = agent
                    lastRight = line.agentType() == "other"
                    lastRight
                }

                lastAgent == agent -> lastRight
                else -> {
                    lastAgent = agent
                    lastRight = !lastRight
                    lastRight
                }
            }
            if (line.isAlignedRight) line else line.withAlignment(right)
        }
    }

    /** Song lines are ordered by begin, as required by the shared timing navigator. */
    private fun primaryInOverlap(
        songLines: List<IRichLyricLine>?,
        position: Long
    ): IRichLyricLine? {
        var overlapEnd = Long.MIN_VALUE
        var mainAgent: String? = null
        var mainLine: IRichLyricLine? = null
        var hasDuet = false
        for (line in songLines.orEmpty()) {
            if (line.begin > position) break
            if (line.end <= line.begin || !hasLyricContent(line)) continue
            val agent = line.agentId()
            if (line.begin >= overlapEnd) {
                mainAgent = agent
                mainLine = line
                hasDuet = false
            } else if (mainAgent != null && agent != null && agent != mainAgent) {
                hasDuet = true
            }
            if (agent == mainAgent) mainLine = line
            overlapEnd = maxOf(overlapEnd, line.end)
        }
        return mainLine.takeIf { hasDuet && position < overlapEnd }
    }

    /** Distinct vocal identities in song order, used only for horizontal alignment. */
    private fun laneOrder(lines: List<IRichLyricLine>): List<String> = buildList {
        lines.forEach { line ->
            val agent = line.agentId() ?: return@forEach
            if (agent !in this) add(agent)
        }
    }

    private fun hasDistinctVocalIdentities(
        primary: IRichLyricLine,
        candidate: IRichLyricLine
    ): Boolean {
        val primaryAgent = primary.agentId() ?: return false
        val candidateAgent = candidate.agentId() ?: return false
        return primaryAgent != candidateAgent
    }

    private fun IRichLyricLine.withAlignment(isAlignedRight: Boolean): IRichLyricLine {
        if (this.isAlignedRight == isAlignedRight) return this
        return RichLyricLine(
            begin = begin,
            end = end,
            duration = duration,
            isAlignedRight = isAlignedRight,
            metadata = metadata,
            text = text,
            words = words,
            secondary = secondary,
            secondaryWords = secondaryWords,
            translation = translation,
            translationWords = translationWords,
            roma = roma
        )
    }
}

private fun hasLyricContent(line: IRichLyricLine?): Boolean =
    line != null && (!line.text.isNullOrBlank() || !line.words.isNullOrEmpty())
