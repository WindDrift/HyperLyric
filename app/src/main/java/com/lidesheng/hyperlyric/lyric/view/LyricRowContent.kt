package com.lidesheng.hyperlyric.lyric.view

import com.lidesheng.hyperlyric.common.lyric.METADATA_RESOLVED_SECONDARY_CONTENT
import com.lidesheng.hyperlyric.lyric.model.LyricLine
import com.lidesheng.hyperlyric.lyric.model.LyricMetadata
import com.lidesheng.hyperlyric.lyric.model.interfaces.ILyricLine

/** Container-only hints must not reset a main renderer when its sibling changes role. */
private fun rowMetadata(line: ILyricLine, main: Boolean): LyricMetadata? {
    val values = line.metadata?.filterKeys {
        !main || (it != METADATA_NEXT_LINE_PREVIEW && it != METADATA_RESOLVED_SECONDARY_CONTENT)
    }.orEmpty()
    return values.takeIf { it.isNotEmpty() }?.let(::LyricMetadata)
}

internal fun sameLyricRow(previous: ILyricLine?, next: ILyricLine?, main: Boolean): Boolean {
    if (previous == null || next == null) return previous === next
    return previous.begin == next.begin && previous.end == next.end &&
        previous.duration == next.duration && previous.text == next.text &&
        previous.words == next.words && previous.isAlignedRight == next.isAlignedRight &&
        rowMetadata(previous, main) == rowMetadata(next, main)
}

/** Owns a snapshot of committed render data, including words and renderer mode. */
internal class LyricRowContent(private val main: Boolean) {
    private var applied: LyricLine? = null
    private var appliedTimeline = false

    fun changed(line: LyricLine, isLineTimeline: Boolean): Boolean {
        if (appliedTimeline == isLineTimeline && sameLyricRow(applied, line, main)) return false
        applied = line.copy(
            words = line.words?.map { word ->
                word.copy(metadata = word.metadata?.let { LyricMetadata(it.toMap()) })
            },
            metadata = rowMetadata(line, main)
        )
        appliedTimeline = isLineTimeline
        return true
    }

    fun clear() {
        applied = null
        appliedTimeline = false
    }
}
