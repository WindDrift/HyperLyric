package com.lidesheng.hyperlyric.ui.page.hooksettings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lidesheng.hyperlyric.R
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import com.lidesheng.hyperlyric.ui.page.hooksettings.lyrics.common.rememberHookConfigSaver
import com.lidesheng.hyperlyric.ui.page.hooksettings.lyrics.common.rememberHookPrefs
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

@Composable
fun LyricSourcePage() {
    val prefs = rememberHookPrefs()
    val saveConfig = rememberHookConfigSaver(prefs)
    var lyricSource by remember {
        mutableStateOf(
            prefs.getString(
                RootConstants.KEY_HOOK_LYRIC_SOURCE,
                RootConstants.DEFAULT_HOOK_LYRIC_SOURCE
            ) ?: RootConstants.DEFAULT_HOOK_LYRIC_SOURCE
        )
    }
    val sourceOptions = listOf(
        stringResource(R.string.lyric_source_lyricon),
        stringResource(R.string.lyric_source_superlyric),
        stringResource(R.string.lyric_source_lyricinfo)
    )
    val sourceIds = listOf("lyricon", "superlyric", "lyricinfo")
    val hookEnabled = prefs.getBoolean(
        RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND,
        RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND
    )

    XposedLyricSettingPage(title = stringResource(R.string.title_lyric_source)) {
        lyricSourcePageSections(
            lyricSource = lyricSource,
            sourceOptions = sourceOptions,
            sourceIds = sourceIds,
            enabled = hookEnabled,
            onLyricSourceChange = { newSource ->
                lyricSource = newSource
                saveConfig(RootConstants.KEY_HOOK_LYRIC_SOURCE, newSource)
            }
        )
    }
}

private fun LazyListScope.lyricSourcePageSections(
    lyricSource: String,
    sourceOptions: List<String>,
    sourceIds: List<String>,
    enabled: Boolean,
    onLyricSourceChange: (String) -> Unit
) {
    item(key = "lyric_source") {
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
        ) {
            OverlayDropdownPreference(
                title = stringResource(R.string.title_lyric_source),
                items = sourceOptions,
                selectedIndex = sourceIds.indexOf(lyricSource).coerceAtLeast(0),
                enabled = enabled,
                onSelectedIndexChange = { index ->
                    onLyricSourceChange(sourceIds[index])
                }
            )
        }
    }
}
