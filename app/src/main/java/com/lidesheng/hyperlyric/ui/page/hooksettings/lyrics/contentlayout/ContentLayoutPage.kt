package com.lidesheng.hyperlyric.ui.page.hooksettings.lyrics.contentlayout

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.lidesheng.hyperlyric.R
import com.lidesheng.hyperlyric.common.MusicInfoLayoutPolicy
import com.lidesheng.hyperlyric.common.PrefsBridge
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.common.UIConstants
import com.lidesheng.hyperlyric.ui.page.hooksettings.lyrics.common.XposedLyricSettingPage

@Composable
fun ContentLayoutPage() {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
    }
    var firstLine by remember(prefs) {
        mutableStateOf(
            readFields(
                prefs = prefs,
                key = RootConstants.KEY_HOOK_ISLAND_MUSIC_INFO_FIRST_LINE,
                defaultFields = MusicInfoLayoutPolicy.defaultFirstLine
            )
        )
    }
    var secondLine by remember(prefs) {
        mutableStateOf(
            readFields(
                prefs = prefs,
                key = RootConstants.KEY_HOOK_ISLAND_MUSIC_INFO_SECOND_LINE,
                defaultFields = MusicInfoLayoutPolicy.defaultSecondLine
            )
        )
    }
    var separator by remember(prefs) {
        mutableStateOf(
            ContentLayoutSeparator.values().firstOrNull {
                it.key == MusicInfoLayoutPolicy.readSeparator(prefs)
            } ?: ContentLayoutSeparator.Hyphen
        )
    }
    var editingRow by remember { mutableStateOf<Int?>(null) }

    val currentEditingRow = editingRow
    val currentFields = when (currentEditingRow) {
        0 -> firstLine
        1 -> secondLine
        else -> emptyList()
    }
    val availableFields = ContentLayoutField.values().toList()

    ContentLayoutEditorBottomSheet(
        show = currentEditingRow != null,
        title = stringResource(
            id = if (currentEditingRow == 1) {
                R.string.title_content_layout_second_line
            } else {
                R.string.title_content_layout_first_line
            }
        ),
        currentFields = currentFields,
        availableFields = availableFields,
        onDismiss = { editingRow = null },
        onConfirm = { selectedFields ->
            when (currentEditingRow) {
                0 -> {
                    firstLine = selectedFields
                    PrefsBridge.putString(
                        RootConstants.KEY_HOOK_ISLAND_MUSIC_INFO_FIRST_LINE,
                        selectedFields.joinToString(",") { it.key }
                    )
                }

                1 -> {
                    secondLine = selectedFields
                    PrefsBridge.putString(
                        RootConstants.KEY_HOOK_ISLAND_MUSIC_INFO_SECOND_LINE,
                        selectedFields.joinToString(",") { it.key }
                    )
                }
            }
        }
    )

    XposedLyricSettingPage(title = stringResource(id = R.string.title_content_layout)) {
        contentLayoutSections(
            firstLine = firstLine,
            secondLine = secondLine,
            separator = separator,
            onEditField = { editingRow = it },
            onSeparatorChange = {
                separator = it
                PrefsBridge.putString(
                    RootConstants.KEY_HOOK_ISLAND_MUSIC_INFO_SEPARATOR,
                    it.key
                )
            }
        )
    }
}

private fun readFields(
    prefs: android.content.SharedPreferences,
    key: String,
    defaultFields: List<String>
): List<ContentLayoutField> {
    return MusicInfoLayoutPolicy.readFields(prefs, key, defaultFields)
        .mapNotNull { storedKey ->
            ContentLayoutField.values().firstOrNull { it.key == storedKey }
        }
}
