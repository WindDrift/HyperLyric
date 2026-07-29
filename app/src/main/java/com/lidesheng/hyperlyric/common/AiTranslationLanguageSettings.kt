package com.lidesheng.hyperlyric.common

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Locale

object AiTranslationLanguageSettings {
    const val LANGUAGE_CHINESE = "zh"
    const val LANGUAGE_ENGLISH = "en"
    const val LANGUAGE_JAPANESE = "ja"
    const val LANGUAGE_KOREAN = "ko"
    const val LANGUAGE_SPANISH = "es"

    fun getSkipLanguages(prefs: SharedPreferences): Set<String> {
        val storedLanguages = if (prefs.contains(RootConstants.KEY_HOOK_AI_TRANS_SKIP_LANGUAGES)) {
            runCatching {
                prefs.getStringSet(
                    RootConstants.KEY_HOOK_AI_TRANS_SKIP_LANGUAGES,
                    RootConstants.DEFAULT_HOOK_AI_TRANS_SKIP_LANGUAGES
                )
            }.getOrNull().orEmpty()
        } else if (
            prefs.getBoolean(
                RootConstants.KEY_HOOK_AI_TRANS_AUTO_IGNORE_CHINESE,
                RootConstants.DEFAULT_HOOK_AI_TRANS_AUTO_IGNORE_CHINESE
            )
        ) {
            setOf(LANGUAGE_CHINESE)
        } else {
            emptySet()
        }

        return storedLanguages.mapNotNullTo(linkedSetOf(), ::normalizeLanguageCode)
    }

    fun migrateLegacyPreference(prefs: SharedPreferences) {
        val languages = getSkipLanguages(prefs)
        val currentLanguages = runCatching {
            prefs.getStringSet(RootConstants.KEY_HOOK_AI_TRANS_SKIP_LANGUAGES, null)
        }.getOrNull()?.toSet()

        if (
            !prefs.contains(RootConstants.KEY_HOOK_AI_TRANS_SKIP_LANGUAGES) ||
            currentLanguages != languages
        ) {
            prefs.edit {
                putStringSet(RootConstants.KEY_HOOK_AI_TRANS_SKIP_LANGUAGES, languages)
            }
        }
    }

    private fun normalizeLanguageCode(languageCode: String): String? {
        val normalized = Locale.forLanguageTag(languageCode.trim().replace('_', '-'))
            .language
            .lowercase(Locale.ROOT)
        return normalized.takeIf { it.isNotBlank() }
    }
}
