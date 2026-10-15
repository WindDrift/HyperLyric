package com.lidesheng.hyperlyric.ui.utils

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.lidesheng.hyperlyric.R
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarResult

internal fun tryStartExternalActivity(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

@Composable
internal fun rememberExternalLinkOpener(snackbarHostState: SnackbarHostState): (String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val failedMessage = stringResource(R.string.open_link_failed)
    val copyLabel = stringResource(R.string.copy_link)
    val copiedMessage = stringResource(R.string.copied)
    return remember(context, scope, snackbarHostState, failedMessage, copyLabel, copiedMessage) {
        { url ->
            val targetUrl = url.trim()
            if (!tryStartExternalActivity(context, Intent(Intent.ACTION_VIEW, targetUrl.toUri()))) {
                scope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = failedMessage,
                        actionLabel = copyLabel
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText(copyLabel, targetUrl))
                        snackbarHostState.showSnackbar(message = copiedMessage)
                    }
                }
            }
        }
    }
}
