package com.lidesheng.hyperlyric.plugin.ai.translation

import com.lidesheng.hyperlyric.plugin.api.PluginLogger
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginStorage

internal class AiTranslationEngine(
    storage: PluginStorage,
    logger: PluginLogger,
    private val translatorLogger: PluginLogger,
) {
    private val cache = TranslationCache(storage, logger.withTag("AITranslationCache"))
    private val client = OpenAiTranslationClient(
        logger = logger.withTag("OpenAiTranslationClient"),
        parserLogger = logger.withTag("AITranslationResponseParser")
    )
    private val scheduler = TranslationScheduler(cache, logger.withTag("AITranslationScheduler"))

    fun translate(song: PluginSong, config: AiTranslationConfig): PluginSong? {
        val lyrics = song.lyrics ?: return null
        val originalLines = lyrics.map { it.text?.trim().orEmpty() }
        val key = TranslationKey.calculate(song, originalLines, config.targetLanguage)

        cache.get(key)?.let { cached ->
            if (cached.fromMemory) {
                translatorLogger.debug("缓存命中：从内存加载了 ${song.name} 的翻译")
            } else {
                translatorLogger.debug("记录命中：从本地存储加载了 ${song.name} 的翻译")
            }
            return TranslationApplicator.apply(
                song,
                cached.items,
                config.forceOverride,
                translatorLogger.withTag("AITranslationApplicator")
            )
        }

        translatorLogger.debug("正在请求 AI：本地无记录，准备发起在线翻译")
        val results = scheduler.getOrEnqueue(
            key = key,
            songName = song.name.orEmpty()
        ) {
            client.request(config, song, originalLines)
        }
        if (results.isNullOrEmpty()) {
            translatorLogger.warn("翻译失败：未能获取到 ${song.name} 的 AI 翻译")
            return null
        }
        return TranslationApplicator.apply(
            song,
            results,
            config.forceOverride,
            translatorLogger.withTag("AITranslationApplicator")
        )
    }

    fun close() {
        scheduler.close()
    }

    fun cancelPending() {
        scheduler.cancelAll()
    }
}
