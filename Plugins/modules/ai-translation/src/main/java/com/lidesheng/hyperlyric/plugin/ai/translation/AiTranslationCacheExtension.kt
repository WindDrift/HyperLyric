package com.lidesheng.hyperlyric.plugin.ai.translation

import com.lidesheng.hyperlyric.plugin.api.PluginCacheEntry
import com.lidesheng.hyperlyric.plugin.api.PluginCacheExtension

/** Translation owns the entry-ID mapping and cache metadata; Core only invokes this boundary. */
internal class AiTranslationCacheExtension(
    private val cache: TranslationCache,
    private val cancelPending: () -> Unit = {},
) : PluginCacheExtension {
    override val id: String = "translation"

    override fun listEntries(): List<PluginCacheEntry> = cache.listEntries()

    override fun clearAll() {
        cancelPending()
        cache.clearAll()
    }

    override fun clearEntry(entryId: String): Boolean {
        cancelPending()
        return cache.clearEntry(entryId)
    }
}
