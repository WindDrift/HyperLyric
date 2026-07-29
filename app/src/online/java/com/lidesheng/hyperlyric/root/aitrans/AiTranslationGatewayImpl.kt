package com.lidesheng.hyperlyric.root.aitrans

import android.content.Context
import android.content.SharedPreferences
import com.lidesheng.hyperlyric.common.AiTranslationLanguageSettings
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.lyric.model.Song
import com.lidesheng.hyperlyric.lyric.style.AiTranslationConfigs
import com.lidesheng.hyperlyric.lyric.style.AiTranslationProvider
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.utils.HookLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

class AiTranslationGatewayImpl : AiTranslationGateway.Impl {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeJob: Job? = null
    private val languageDetector = SystemLanguageDetector()

    private companion object {
        const val TAG = "AiTranslationGateway"
        const val LANGUAGE_MIN_CONFIDENCE = 0.8f
        const val LANGUAGE_MIN_CONFIDENCE_MARGIN = 0.15f
        const val LANGUAGE_SAMPLE_MAX_LENGTH = 2_000
        const val LANGUAGE_SAMPLE_MIN_LETTER_COUNT = 12
        val WHITESPACE_REGEX = Regex("\\s+")
    }

    init {
        AiTranslationGateway.register(this)
    }

    override fun init(context: Context) {
        languageDetector.init(context)
        AITranslator.init(context)
    }

    override fun translateSong(song: Song, prefs: SharedPreferences, forceOverride: Boolean): Boolean {
        val configs = buildConfigs(prefs)
        val skipLanguages = AiTranslationLanguageSettings.getSkipLanguages(prefs)
        val skipExisting = prefs.getBoolean(
            RootConstants.KEY_HOOK_AI_TRANS_SKIP_EXISTING_TRANSLATION,
            RootConstants.DEFAULT_HOOK_AI_TRANS_SKIP_EXISTING_TRANSLATION
        )
        val version = LyriconDataBridge.versionCounter.get()

        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                if (song.lyrics.isNullOrEmpty()) {
                    HookLogger.d(TAG, "跳过 AI 翻译: reason=no_lyrics, song=${song.name}")
                    return@launch
                }
                if (
                    skipExisting &&
                    !forceOverride &&
                    song.lyrics?.any { !it.translation.isNullOrBlank() } == true
                ) {
                    HookLogger.d(TAG, "跳过 AI 翻译: reason=existing_translation, song=${song.name}")
                    return@launch
                }
                if (skipLanguages.isNotEmpty() && shouldSkipForDetectedLanguage(song, skipLanguages)) {
                    return@launch
                }
                if (version != LyriconDataBridge.versionCounter.get()) return@launch

                val translatedSong = AITranslator.translateSongSync(
                    song = song,
                    configs = configs,
                    forceOverride = forceOverride
                )

                if (version != LyriconDataBridge.versionCounter.get()) {
                    return@launch
                }

                if (translatedSong !== song && translatedSong.lyrics != null) {
                    LyriconDataBridge.applyTranslation(translatedSong)
                    LyriconDataBridge.onAiTranslationComplete?.invoke()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return true
    }

    override fun cancelActiveRequests() {
        activeJob?.cancel()
        activeJob = null
        AITranslator.cancelActiveRequests()
    }

    override fun clearCache(callback: (() -> Unit)?) {
        AITranslator.clearCache(callback ?: {})
    }

    private suspend fun shouldSkipForDetectedLanguage(
        song: Song,
        skipLanguages: Set<String>
    ): Boolean {
        val sample = buildLanguageSample(song)
        if (sample.count { it.isLetterOrDigit() } < LANGUAGE_SAMPLE_MIN_LETTER_COUNT) {
            HookLogger.d(TAG, "跳过语言识别: reason=text_too_short, song=${song.name}")
            return false
        }

        val detected = languageDetector.detect(sample) ?: return false
        val confidenceMargin = detected.secondConfidence?.let {
            detected.confidence - it
        }
        val confidentEnough =
            detected.confidence >= LANGUAGE_MIN_CONFIDENCE &&
                    (confidenceMargin == null ||
                            confidenceMargin >= LANGUAGE_MIN_CONFIDENCE_MARGIN)
        val selected = detected.language in skipLanguages
        val confidence = String.format(Locale.US, "%.3f", detected.confidence)
        val margin = confidenceMargin?.let {
            String.format(Locale.US, "%.3f", it)
        } ?: "-"
        HookLogger.d(
            TAG,
            "歌词语言识别: song=${song.name}, detected=${detected.languageTag}, " +
                    "confidence=$confidence, margin=$margin, " +
                    "hypotheses=${detected.hypothesisCount}, selected=$selected, " +
                    "confident=$confidentEnough"
        )
        if (!selected || !confidentEnough) return false

        HookLogger.d(
            TAG,
            "跳过 AI 翻译: reason=selected_language, song=${song.name}, " +
                    "detected=${detected.languageTag}"
        )
        return true
    }

    private fun buildLanguageSample(song: Song): String = buildString {
        val seenLines = hashSetOf<String>()
        for (line in song.lyrics.orEmpty()) {
            val text = line.text
                ?.trim()
                ?.replace(WHITESPACE_REGEX, " ")
                ?.takeIf { it.isNotBlank() }
                ?: continue
            if (!seenLines.add(text)) continue

            if (isNotEmpty()) append('\n')
            val remaining = LANGUAGE_SAMPLE_MAX_LENGTH - length
            if (remaining <= 0) break
            append(text.take(remaining))
            if (length >= LANGUAGE_SAMPLE_MAX_LENGTH) break
        }
    }

    private fun buildConfigs(prefs: SharedPreferences): AiTranslationConfigs {
        val providerName = prefs.getString(RootConstants.KEY_HOOK_AI_TRANS_PROVIDER, AiTranslationProvider.OPENAI.name)
            ?: AiTranslationProvider.OPENAI.name
        val provider = try { AiTranslationProvider.valueOf(providerName) } catch (_: Exception) { AiTranslationProvider.OPENAI }

        return AiTranslationConfigs(
            provider = providerName,
            targetLanguage = prefs.getString(RootConstants.KEY_HOOK_AI_TRANS_TARGET_LANG, RootConstants.DEFAULT_HOOK_AI_TRANS_TARGET_LANG) ?: RootConstants.DEFAULT_HOOK_AI_TRANS_TARGET_LANG,
            apiKey = prefs.getString(RootConstants.KEY_HOOK_AI_TRANS_API_KEY, "") ?: "",
            model = prefs.getString(RootConstants.KEY_HOOK_AI_TRANS_MODEL, RootConstants.DEFAULT_HOOK_AI_TRANS_MODEL).orEmpty().ifBlank { provider.model },
            baseUrl = prefs.getString(RootConstants.KEY_HOOK_AI_TRANS_BASE_URL, RootConstants.DEFAULT_HOOK_AI_TRANS_BASE_URL).orEmpty().ifBlank { provider.url },
            prompt = prefs.getString(RootConstants.KEY_HOOK_AI_TRANS_PROMPT, RootConstants.DEFAULT_HOOK_AI_TRANS_PROMPT) ?: RootConstants.DEFAULT_HOOK_AI_TRANS_PROMPT,
            temperature = prefs.getFloat(RootConstants.KEY_HOOK_AI_TRANS_TEMPERATURE, AiTranslationConfigs.DEFAULT_TEMPERATURE),
            topP = prefs.getFloat(RootConstants.KEY_HOOK_AI_TRANS_TOP_P, AiTranslationConfigs.DEFAULT_TOP_P),
            maxTokens = prefs.getInt(RootConstants.KEY_HOOK_AI_TRANS_MAX_TOKENS, AiTranslationConfigs.DEFAULT_MAX_TOKENS)
        )
    }

}
