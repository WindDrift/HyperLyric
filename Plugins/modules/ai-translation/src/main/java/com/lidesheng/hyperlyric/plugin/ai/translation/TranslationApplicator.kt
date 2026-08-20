package com.lidesheng.hyperlyric.plugin.ai.translation

import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginLogger

internal object TranslationApplicator {
    fun apply(
        song: PluginSong,
        items: List<TranslationItem>,
        forceOverride: Boolean,
        logger: PluginLogger,
    ): PluginSong? {
        val byIndex = items.associateBy { it.index }
        var appliedCount = 0
        var changed = false
        val newLyrics = song.lyrics?.mapIndexed { index, line ->
            val translation = byIndex[index]?.trans?.trim()
            if (
                !translation.isNullOrBlank() &&
                (forceOverride || line.translation.isNullOrBlank()) &&
                !translation.equals(line.text?.trim(), ignoreCase = true)
            ) {
                appliedCount++
                changed = true
                // No translationWords are fabricated: the response has no reliable word timing.
                line.copy(translation = translation, translationWords = null)
            } else {
                line
            }
        }
        logger.debug("应用翻译结果: song=${song.name}, lines=$appliedCount")
        return if (changed) song.copy(lyrics = newLyrics) else null
    }
}
