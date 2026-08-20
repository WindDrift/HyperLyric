package com.lidesheng.hyperlyric.ui.page.hooksettings.lyrics.translation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.lidesheng.hyperlyric.R
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import com.lidesheng.hyperlyric.ui.page.hooksettings.lyrics.common.rememberHookConfigSaver
import com.lidesheng.hyperlyric.ui.page.hooksettings.lyrics.common.rememberHookPrefs

@Composable
fun LyricTranslationPage() {
    val prefs = rememberHookPrefs()
    val saveConfig = rememberHookConfigSaver(prefs)

    val lyricMode by remember {
        mutableIntStateOf(
            prefs.getInt(
                RootConstants.KEY_HOOK_LYRIC_MODE,
                RootConstants.DEFAULT_HOOK_LYRIC_MODE
            )
        )
    }
    val lyricSource by remember {
        mutableStateOf(
            prefs.getString(
                RootConstants.KEY_HOOK_LYRIC_SOURCE,
                RootConstants.DEFAULT_HOOK_LYRIC_SOURCE
            ) ?: "lyricon"
        )
    }
    var disableTranslation by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_DISABLE_TRANSLATION,
                RootConstants.DEFAULT_HOOK_DISABLE_TRANSLATION
            )
        )
    }
    var translationOnly by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_TRANSLATION_ONLY,
                RootConstants.DEFAULT_HOOK_TRANSLATION_ONLY
            )
        )
    }
    var swapTranslation by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_SWAP_TRANSLATION,
                RootConstants.DEFAULT_HOOK_SWAP_TRANSLATION
            )
        )
    }
    var nextLyricLine by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_NEXT_LYRIC_LINE,
                RootConstants.DEFAULT_HOOK_NEXT_LYRIC_LINE
            )
        )
    }
    var autoSwitchTranslation by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_AUTO_SWITCH_TRANSLATION,
                RootConstants.DEFAULT_HOOK_AUTO_SWITCH_TRANSLATION
            )
        )
    }
    XposedLyricSettingPage(title = stringResource(id = R.string.title_double_line_content)) {
        translationSections(
            lyricSource = lyricSource,
            lyricMode = lyricMode,
            disableTranslation = disableTranslation,
            onDisableTranslationChange = {
                disableTranslation = it
                saveConfig(RootConstants.KEY_HOOK_DISABLE_TRANSLATION, it)
            },
            translationOnly = translationOnly,
            onTranslationOnlyChange = {
                translationOnly = it
                saveConfig(RootConstants.KEY_HOOK_TRANSLATION_ONLY, it)
                if (it && swapTranslation) {
                    swapTranslation = false
                    saveConfig(RootConstants.KEY_HOOK_SWAP_TRANSLATION, false)
                }
            },
            swapTranslation = swapTranslation,
            onSwapTranslationChange = {
                swapTranslation = it
                saveConfig(RootConstants.KEY_HOOK_SWAP_TRANSLATION, it)
                if (it && translationOnly) {
                    translationOnly = false
                    saveConfig(RootConstants.KEY_HOOK_TRANSLATION_ONLY, false)
                }
            },
            nextLyricLine = nextLyricLine,
            onNextLyricLineChange = {
                nextLyricLine = it
                saveConfig(RootConstants.KEY_HOOK_NEXT_LYRIC_LINE, it)
            },
            autoSwitchTranslation = autoSwitchTranslation,
            onAutoSwitchTranslationChange = {
                autoSwitchTranslation = it
                saveConfig(RootConstants.KEY_HOOK_AUTO_SWITCH_TRANSLATION, it)
            },
        )
    }
}
