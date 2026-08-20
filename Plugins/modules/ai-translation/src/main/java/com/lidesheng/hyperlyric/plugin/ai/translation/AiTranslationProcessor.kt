package com.lidesheng.hyperlyric.plugin.ai.translation

import com.lidesheng.hyperlyric.plugin.api.LyricProcessorExtension
import com.lidesheng.hyperlyric.plugin.api.PluginConfig
import com.lidesheng.hyperlyric.plugin.api.PluginContext
import com.lidesheng.hyperlyric.plugin.api.PluginSong

internal class AiTranslationProcessor(
    private val context: PluginContext,
) : LyricProcessorExtension {
    override val id: String = AI_TRANSLATION_EXTENSION_ID

    private val gatewayLogger = context.logger.withTag("AiTranslationGateway")
    private val translatorLogger = context.logger.withTag("AITranslator")
    private val engine = AiTranslationEngine(context.storage, context.logger, translatorLogger)

    override fun process(song: PluginSong): PluginSong? {
        return try {
            val config = AiTranslationConfig.from(context.config)
            if (!config.enabled) return null
            val lyrics = song.lyrics
            if (lyrics.isNullOrEmpty()) {
                gatewayLogger.debug("跳过 AI 翻译: reason=no_lyrics, song=${song.name}")
                return null
            }

            if (
                config.skipExisting &&
                !config.forceOverride &&
                lyrics.any { !it.translation.isNullOrBlank() }
            ) {
                gatewayLogger.debug("跳过 AI 翻译: reason=existing_translation, song=${song.name}")
                return null
            }

            if (config.skipLanguages.isNotEmpty()) {
                if (!TranslationLanguageDetector.hasEnoughText(song)) {
                    gatewayLogger.debug("跳过语言识别: reason=text_too_short, song=${song.name}")
                } else {
                    val detected = TranslationLanguageDetector.detect(song)
                    if (detected != null) {
                        val margin = detected.secondConfidence?.let {
                            detected.confidence - it
                        }
                        val confidentEnough = detected.confidence >= 0.8f &&
                                (margin == null || margin >= 0.15f)
                        val selected = detected.language in config.skipLanguages
                        val confidence = "%.3f".format(java.util.Locale.US, detected.confidence)
                        val marginText = margin?.let {
                            "%.3f".format(java.util.Locale.US, it)
                        } ?: "-"
                        gatewayLogger.debug(
                            "歌词语言识别: song=${song.name}, detected=${detected.languageTag}, " +
                                    "confidence=$confidence, margin=$marginText, " +
                                    "hypotheses=${detected.hypothesisCount}, selected=$selected, " +
                                    "confident=$confidentEnough"
                        )
                        if (selected && confidentEnough) {
                            gatewayLogger.debug(
                                "跳过 AI 翻译: reason=selected_language, song=${song.name}, " +
                                        "detected=${detected.languageTag}"
                            )
                            return null
                        }
                    }
                }
            }

            if (!config.isUsable) {
                translatorLogger.warn("跳过翻译：配置不完整，API Key 或其他配置为空")
                return null
            }
            translatorLogger.debug("正在翻译：${song.name}（共 ${lyrics.size} 行）")
            engine.translate(song, config)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (error: Exception) {
            translatorLogger.error("翻译过程发生错误", error)
            null
        }
    }

    fun close() {
        engine.close()
    }

    fun onConfigChanged(config: PluginConfig) {
        if (!config.getBoolean("enabled")) {
            engine.cancelPending()
        }
    }

    private companion object {
        const val AI_TRANSLATION_EXTENSION_ID = "ai.translation"
    }
}
