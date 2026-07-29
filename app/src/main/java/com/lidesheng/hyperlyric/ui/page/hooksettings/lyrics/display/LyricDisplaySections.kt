package com.lidesheng.hyperlyric.ui.page.hooksettings.lyrics.display

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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

fun LazyListScope.lyricDisplaySections(
    textSize: Int,
    onTextSizeClick: () -> Unit,
    textSizeRatio: Float,
    onTextSizeRatioClick: () -> Unit,
    fadingEdge: Int,
    onFadingEdgeClick: () -> Unit,
    placeholderFormat: Int,
    onPlaceholderFormatChange: (Int) -> Unit,
    extractCoverColor: Boolean,
    onExtractCoverColorChange: (Boolean) -> Unit,
    extractCoverGradient: Boolean,
    onExtractCoverGradientChange: (Boolean) -> Unit,
    followStatusBarColor: Boolean,
    onFollowStatusBarColorChange: (Boolean) -> Unit,
    customFontPath: String,
    onFontPathClick: () -> Unit,
    fontWeight: Int,
    onFontWeightClick: () -> Unit,
    fontItalic: Boolean,
    onFontItalicChange: (Boolean) -> Unit,
    centerLyric: Boolean,
    onCenterLyricChange: (Boolean) -> Unit
) {
    item(key = "basic_style_title") {
        SmallTitle(text = stringResource(id = R.string.title_basic_style))
    }
    item(key = "basic_style_content") {
        val placeholderOptions = listOf(
            stringResource(id = R.string.option_placeholder_none),
            stringResource(id = R.string.option_placeholder_title_artist),
            stringResource(id = R.string.option_placeholder_title)
        )
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
                .fillMaxWidth()
        ) {
            Column {
                ArrowPreference(
                    title = stringResource(id = R.string.title_size),
                    endActions = {
                        Text(
                            "$textSize",
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions
                        )
                    },
                    onClick = onTextSizeClick
                )
                ArrowPreference(
                    title = stringResource(id = R.string.title_text_size_ratio),
                    endActions = {
                        Text(
                            stringResource(
                                id = R.string.format_percent,
                                (textSizeRatio * 100).toInt()
                            ),
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions
                        )
                    },
                    onClick = onTextSizeRatioClick
                )
                ArrowPreference(
                    title = stringResource(id = R.string.title_fading_edge),
                    endActions = {
                        Text(
                            "$fadingEdge",
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions
                        )
                    },
                    onClick = onFadingEdgeClick
                )
                SwitchPreference(
                    title = stringResource(id = R.string.title_center_lyric),
                    checked = centerLyric,
                    onCheckedChange = onCenterLyricChange
                )
                OverlayDropdownPreference(
                    title = stringResource(id = R.string.title_placeholder_format),
                    items = placeholderOptions,
                    selectedIndex = placeholderFormat,
                    onSelectedIndexChange = onPlaceholderFormatChange
                )
            }
        }
    }

    item(key = "text_color_title") {
        SmallTitle(text = stringResource(id = R.string.title_text_color))
    }
    item(key = "text_color_content") {
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
                .fillMaxWidth()
        ) {
            Column {
                SwitchPreference(
                    title = stringResource(id = R.string.title_extract_cover_color),
                    checked = extractCoverColor,
                    onCheckedChange = onExtractCoverColorChange
                )
                SwitchPreference(
                    title = stringResource(id = R.string.title_extract_cover_gradient),
                    checked = extractCoverGradient,
                    onCheckedChange = onExtractCoverGradientChange,
                    enabled = extractCoverColor
                )
                SwitchPreference(
                    title = stringResource(id = R.string.title_follow_status_bar_color),
                    checked = followStatusBarColor,
                    onCheckedChange = onFollowStatusBarColorChange
                )
            }
        }
    }

    item(key = "font_style_title") {
        SmallTitle(text = stringResource(id = R.string.title_font_style))
    }
    item(key = "font_style_content") {
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
                .fillMaxWidth()
        ) {
            Column {
                ArrowPreference(
                    title = stringResource(id = R.string.title_custom_font),
                    endActions = {
                        Text(
                            customFontPath.ifEmpty { stringResource(id = R.string.summary_default_font) },
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions
                        )
                    },
                    onClick = onFontPathClick
                )
                ArrowPreference(
                    title = stringResource(id = R.string.title_font_weight),
                    endActions = {
                        Text(
                            fontWeight.toString(),
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions
                        )
                    },
                    onClick = onFontWeightClick
                )
                SwitchPreference(
                    title = stringResource(id = R.string.title_italic),
                    checked = fontItalic,
                    onCheckedChange = onFontItalicChange
                )
            }
        }
    }
}
