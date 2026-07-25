package com.lidesheng.hyperlyric.root.aitrans

import android.content.Context
import android.content.SharedPreferences
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

class AiTranslationGatewayImpl : AiTranslationGateway.Impl {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeJob: Job? = null

    init {
        AiTranslationGateway.register(this)
    }

    override fun init(context: Context) {
        AITranslator.init(context)
    }

    override fun translateSong(song: Song, prefs: SharedPreferences, forceOverride: Boolean): Boolean {
        val configs = buildConfigs(prefs)
        val autoIgnoreChinese = prefs.getBoolean(
            RootConstants.KEY_HOOK_AI_TRANS_AUTO_IGNORE_CHINESE,
            RootConstants.DEFAULT_HOOK_AI_TRANS_AUTO_IGNORE_CHINESE
        )
        val version = LyriconDataBridge.versionCounter.get()

        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                val ratio = calculateChineseRatio(song)
                val percentage = String.format(java.util.Locale.US, "%.1f%%", ratio * 100)
                if (autoIgnoreChinese) {
                    HookLogger.d("AiTranslationGateway", "歌曲 ${song.name}（中文占比 $percentage)")
                }
                if (autoIgnoreChinese && ratio > 0.5f) {
                    HookLogger.d("AiTranslationGateway", "歌曲 ${song.name}（中文占比 $percentage），已自动跳过AI翻译")
                    return@launch
                }
                val skipExisting = prefs.getBoolean(
                    RootConstants.KEY_HOOK_AI_TRANS_SKIP_EXISTING_TRANSLATION,
                    RootConstants.DEFAULT_HOOK_AI_TRANS_SKIP_EXISTING_TRANSLATION
                )
                if (skipExisting && !forceOverride) {
                    val hasTranslation = song.lyrics?.any { !it.translation.isNullOrBlank() } == true
                    if (hasTranslation) {
                        HookLogger.d("AiTranslationGateway", "歌曲 ${song.name} 已有翻译，跳过AI翻译")
                        return@launch
                    }
                }
                if (song.lyrics.isNullOrEmpty()) {
                    HookLogger.d("AiTranslationGateway", "歌曲 ${song.name} 无歌词，跳过AI翻译")
                    return@launch
                }
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

    private fun calculateChineseRatio(song: Song): Float {
        val totalChars = song.lyrics?.flatMap { it.text.orEmpty().toList() }
            ?.filterNot { it.isWhitespace() || isPunctuation(it) } ?: return 1.0f
        if (totalChars.isEmpty()) return 1.0f

        val chineseHanCount = totalChars.count { isChineseHan(it) }
        return chineseHanCount.toFloat() / totalChars.size
    }

    private fun isChineseHan(c: Char): Boolean {
        return try {
            Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
        } catch (_: Exception) {
            val ub = Character.UnicodeBlock.of(c)
            ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                    ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                    ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                    ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
        }
    }

    private fun isPunctuation(c: Char): Boolean {
        return !c.isLetterOrDigit() && !c.isWhitespace()
    }
}
