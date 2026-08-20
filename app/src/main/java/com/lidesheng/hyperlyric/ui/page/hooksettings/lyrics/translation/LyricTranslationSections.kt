package com.lidesheng.hyperlyric.ui.page.hooksettings.lyrics.translation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lidesheng.hyperlyric.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference

fun LazyListScope.translationSections(
    lyricSource: String,
    lyricMode: Int,
    disableTranslation: Boolean,
    onDisableTranslationChange: (Boolean) -> Unit,
    translationOnly: Boolean,
    onTranslationOnlyChange: (Boolean) -> Unit,
    swapTranslation: Boolean,
    onSwapTranslationChange: (Boolean) -> Unit,
    nextLyricLine: Boolean,
    onNextLyricLineChange: (Boolean) -> Unit,
    autoSwitchTranslation: Boolean,
    onAutoSwitchTranslationChange: (Boolean) -> Unit
) {
    val supportsNextLyricLine =
        (lyricSource == "lyricon" || lyricSource == "lyricinfo") && lyricMode == 0
    val translationControlsEnabled =
        !supportsNextLyricLine || !nextLyricLine || autoSwitchTranslation

    if (supportsNextLyricLine) {
        item(key = "next_lyric_line_title") {
            SmallTitle(text = stringResource(id = R.string.title_next_lyric))
        }
        item(key = "next_lyric_line_content") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth()
            ) {
                Column {
                    SwitchPreference(
                        title = stringResource(id = R.string.title_next_lyric_line),
                        summary = stringResource(id = R.string.summary_next_lyric_line),
                        checked = nextLyricLine,
                        onCheckedChange = onNextLyricLineChange
                    )
                    SwitchPreference(
                        title = stringResource(id = R.string.title_auto_switch_translation),
                        summary = stringResource(id = R.string.summary_auto_switch_translation),
                        checked = autoSwitchTranslation,
                        onCheckedChange = onAutoSwitchTranslationChange,
                        enabled = nextLyricLine
                    )
                }
            }
        }
    }

    item(key = "translation_title") {
        SmallTitle(text = stringResource(id = R.string.title_translation))
    }
    item(key = "translation_content") {
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
                .fillMaxWidth()
        ) {
            Column {
                SwitchPreference(
                    title = stringResource(id = R.string.title_disable_translation),
                    checked = disableTranslation,
                    onCheckedChange = onDisableTranslationChange,
                    enabled = translationControlsEnabled
                )
                SwitchPreference(
                    title = stringResource(id = R.string.title_translation_only),
                    checked = translationOnly,
                    onCheckedChange = onTranslationOnlyChange,
                    enabled = translationControlsEnabled
                )
                SwitchPreference(
                    title = stringResource(id = R.string.title_swap_translation),
                    checked = swapTranslation,
                    onCheckedChange = onSwapTranslationChange,
                    enabled = translationControlsEnabled
                )
            }
        }
    }
}
