package com.lidesheng.hyperlyric.ui.page.hooksettings.lyrics.display

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.lidesheng.hyperlyric.R
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.ui.component.NumberInputDialog
import com.lidesheng.hyperlyric.ui.component.TextInputDialog
import com.lidesheng.hyperlyric.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage
import com.lidesheng.hyperlyric.ui.page.hooksettings.lyrics.common.rememberHookConfigSaver
import com.lidesheng.hyperlyric.ui.page.hooksettings.lyrics.common.rememberHookPrefs

@Composable
fun LyricDisplayPage() {
    val prefs = rememberHookPrefs()
    val saveConfig = rememberHookConfigSaver(prefs)

    var textSize by remember {
        mutableIntStateOf(
            prefs.getInt(
                RootConstants.KEY_HOOK_TEXT_SIZE,
                RootConstants.DEFAULT_HOOK_TEXT_SIZE
            )
        )
    }
    var textSizeRatio by remember {
        mutableFloatStateOf(
            prefs.getFloat(
                RootConstants.KEY_HOOK_TEXT_SIZE_RATIO,
                RootConstants.DEFAULT_HOOK_TEXT_SIZE_RATIO
            )
        )
    }
    var fadingEdge by remember {
        mutableIntStateOf(
            prefs.getInt(
                RootConstants.KEY_HOOK_FADING_EDGE_LENGTH,
                RootConstants.DEFAULT_HOOK_FADING_EDGE_LENGTH
            )
        )
    }
    val initialFollowStatusBarColor = remember(prefs) {
        prefs.getBoolean(
            RootConstants.KEY_HOOK_FOLLOW_STATUS_BAR_TEXT_COLOR,
            RootConstants.DEFAULT_HOOK_FOLLOW_STATUS_BAR_TEXT_COLOR
        )
    }
    var extractCoverColor by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_COLOR,
                RootConstants.DEFAULT_HOOK_EXTRACT_COVER_TEXT_COLOR
            ) && !initialFollowStatusBarColor
        )
    }
    var extractCoverGradient by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_GRADIENT,
                RootConstants.DEFAULT_HOOK_EXTRACT_COVER_TEXT_GRADIENT
            )
        )
    }
    var followStatusBarColor by remember {
        mutableStateOf(initialFollowStatusBarColor)
    }
    var customFontPath by remember {
        mutableStateOf(
            prefs.getString(
                RootConstants.KEY_HOOK_CUSTOM_FONT_PATH,
                null
            ) ?: ""
        )
    }
    var fontWeight by remember {
        mutableIntStateOf(
            prefs.getInt(
                RootConstants.KEY_HOOK_FONT_WEIGHT,
                RootConstants.DEFAULT_HOOK_FONT_WEIGHT
            )
        )
    }
    var fontItalic by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_FONT_ITALIC,
                RootConstants.DEFAULT_HOOK_FONT_ITALIC
            )
        )
    }
    var centerLyric by remember {
        mutableStateOf(
            prefs.getBoolean(
                RootConstants.KEY_HOOK_CENTER_LYRIC,
                RootConstants.DEFAULT_HOOK_CENTER_LYRIC
            )
        )
    }

    var showTextSizeDialog by remember { mutableStateOf(false) }
    var showTextSizeRatioDialog by remember { mutableStateOf(false) }
    var showFadingEdgeDialog by remember { mutableStateOf(false) }
    var showFontPathDialog by remember { mutableStateOf(false) }
    var showFontWeightDialog by remember { mutableStateOf(false) }

    LaunchedEffect(initialFollowStatusBarColor) {
        if (
            initialFollowStatusBarColor &&
            prefs.getBoolean(
                RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_COLOR,
                RootConstants.DEFAULT_HOOK_EXTRACT_COVER_TEXT_COLOR
            )
        ) {
            saveConfig(RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_COLOR, false)
        }
    }

    NumberInputDialog(
        show = showTextSizeDialog,
        title = stringResource(id = R.string.title_size),
        label = stringResource(id = R.string.label_size_range),
        initialValue = textSize,
        min = 8,
        max = 16,
        onDismiss = { showTextSizeDialog = false },
        onConfirm = { value ->
            textSize = value
            saveConfig(RootConstants.KEY_HOOK_TEXT_SIZE, value)
        }
    )
    NumberInputDialog(
        show = showTextSizeRatioDialog,
        title = stringResource(id = R.string.title_text_size_ratio),
        label = stringResource(id = R.string.label_text_size_ratio_range),
        initialValue = (textSizeRatio * 100).toInt(),
        min = 10,
        max = 100,
        onDismiss = { showTextSizeRatioDialog = false },
        onConfirm = { value ->
            textSizeRatio = value.toFloat() / 100f
            saveConfig(RootConstants.KEY_HOOK_TEXT_SIZE_RATIO, textSizeRatio)
        }
    )
    NumberInputDialog(
        show = showFadingEdgeDialog,
        title = stringResource(id = R.string.title_fading_edge),
        label = stringResource(id = R.string.label_fading_edge_range),
        initialValue = fadingEdge,
        min = 0,
        max = 100,
        onDismiss = { showFadingEdgeDialog = false },
        onConfirm = { value ->
            fadingEdge = value
            saveConfig(RootConstants.KEY_HOOK_FADING_EDGE_LENGTH, value)
        }
    )
    TextInputDialog(
        show = showFontPathDialog,
        title = stringResource(id = R.string.title_custom_font),
        label = stringResource(id = R.string.label_custom_font_path),
        initialValue = customFontPath,
        onDismiss = { showFontPathDialog = false },
        onConfirm = { path ->
            customFontPath = path
            saveConfig(RootConstants.KEY_HOOK_CUSTOM_FONT_PATH, path)
        }
    )
    NumberInputDialog(
        show = showFontWeightDialog,
        title = stringResource(id = R.string.title_font_weight),
        label = stringResource(id = R.string.label_font_weight_range),
        initialValue = fontWeight,
        min = 100,
        max = 900,
        onDismiss = { showFontWeightDialog = false },
        onConfirm = { value ->
            fontWeight = value
            saveConfig(RootConstants.KEY_HOOK_FONT_WEIGHT, value)
        }
    )

    XposedLyricSettingPage(title = stringResource(id = R.string.title_text)) {
        lyricDisplaySections(
            textSize = textSize,
            onTextSizeClick = { showTextSizeDialog = true },
            textSizeRatio = textSizeRatio,
            onTextSizeRatioClick = { showTextSizeRatioDialog = true },
            fadingEdge = fadingEdge,
            onFadingEdgeClick = { showFadingEdgeDialog = true },
            extractCoverColor = extractCoverColor,
            onExtractCoverColorChange = { enabled ->
                if (enabled && followStatusBarColor) {
                    followStatusBarColor = false
                    saveConfig(RootConstants.KEY_HOOK_FOLLOW_STATUS_BAR_TEXT_COLOR, false)
                }
                extractCoverColor = enabled
                saveConfig(RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_COLOR, enabled)
            },
            extractCoverGradient = extractCoverGradient,
            onExtractCoverGradientChange = {
                extractCoverGradient = it
                saveConfig(RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_GRADIENT, it)
            },
            followStatusBarColor = followStatusBarColor,
            onFollowStatusBarColorChange = { enabled ->
                if (enabled && extractCoverColor) {
                    extractCoverColor = false
                    saveConfig(RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_COLOR, false)
                }
                followStatusBarColor = enabled
                saveConfig(RootConstants.KEY_HOOK_FOLLOW_STATUS_BAR_TEXT_COLOR, enabled)
            },
            customFontPath = customFontPath,
            onFontPathClick = { showFontPathDialog = true },
            fontWeight = fontWeight,
            onFontWeightClick = { showFontWeightDialog = true },
            fontItalic = fontItalic,
            onFontItalicChange = {
                fontItalic = it
                saveConfig(RootConstants.KEY_HOOK_FONT_ITALIC, it)
            },
            centerLyric = centerLyric,
            onCenterLyricChange = {
                centerLyric = it
                saveConfig(RootConstants.KEY_HOOK_CENTER_LYRIC, it)
            }
        )
    }
}
