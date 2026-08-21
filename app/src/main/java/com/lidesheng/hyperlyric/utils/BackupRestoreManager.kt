package com.lidesheng.hyperlyric.utils

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import com.lidesheng.hyperlyric.R
import com.lidesheng.hyperlyric.plugin.api.HYPERLYRIC_PLUGIN_API_VERSION
import com.lidesheng.hyperlyric.plugin.app.PluginBackupSnapshot
import com.lidesheng.hyperlyric.plugin.app.PluginRepository
import com.lidesheng.hyperlyric.plugin.core.PluginArchiveReader
import com.lidesheng.hyperlyric.plugin.core.PluginConstants
import com.lidesheng.hyperlyric.root.RootApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.lidesheng.hyperlyric.common.ServiceConstants
import com.lidesheng.hyperlyric.common.SyllablePreferencePolicy
import com.lidesheng.hyperlyric.common.UIConstants

object BackupRestoreManager {
    private const val BACKUP_MANIFEST_ENTRY = "backup.json"
    private const val PLUGIN_ENTRY_PREFIX = "plugins/"
    private const val FULL_BACKUP_VERSION = 2
    private const val MAX_BACKUP_MANIFEST_BYTES = 512 * 1024
    private const val MAX_BACKUP_PLUGIN_COUNT = 32

    data class RestoreResult(
        val success: Boolean,
        val restoredPluginCount: Int = 0,
        val empty: Boolean = false,
    )

    suspend fun buildBackupJson(context: Context): String = withContext(Dispatchers.IO) {
        buildBackupDocument(context).toString(2)
    }

    suspend fun writeFullBackup(context: Context, output: OutputStream): Int =
        withContext(Dispatchers.IO) {
            val snapshots = PluginRepository(context).exportPluginBackups()
            val manifest = buildBackupDocument(context, snapshots).toString(2)
            ZipOutputStream(output).use { zip ->
                writeZipEntry(zip, BACKUP_MANIFEST_ENTRY, manifest.toByteArray(Charsets.UTF_8))
                snapshots.forEach { snapshot ->
                    val entryName = pluginEntryName(snapshot.fileName)
                    require(isSafePluginEntryName(entryName)) {
                        "Invalid plugin backup file name"
                    }
                    writeZipEntry(
                        zip,
                        entryName,
                        snapshot.archive
                    )
                }
            }
            snapshots.size
        }

    suspend fun restoreFromUri(context: Context, uri: Uri): RestoreResult =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { source ->
                    val input = PushbackInputStream(BufferedInputStream(source), 4)
                    val signature = ByteArray(4)
                    val count = input.read(signature)
                    if (count > 0) input.unread(signature, 0, count)
                    if (count >= 2 && signature[0].toInt() == 0x50 && signature[1].toInt() == 0x4b) {
                        restoreFromZip(context, input)
                    } else {
                        val json = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        if (json.isBlank()) {
                            RestoreResult(success = false, empty = true)
                        } else {
                            RestoreResult(restoreJsonDocument(context, JSONObject(json)))
                        }
                    }
                } ?: RestoreResult(false)
            }.getOrDefault(RestoreResult(false))
        }

    private fun buildBackupDocument(
        context: Context,
        plugins: List<PluginBackupSnapshot>? = null,
    ): JSONObject = JSONObject().apply {
        put("version", if (plugins == null) 1 else FULL_BACKUP_VERSION)
        put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        put("config", buildHostConfig(context))
        plugins?.let { snapshots ->
            put("plugins", JSONArray().apply {
                snapshots.forEach { snapshot ->
                    put(
                        JSONObject()
                            .put("id", snapshot.id)
                            .put("version", snapshot.version)
                            .put("enabled", snapshot.enabled)
                            .put("archive", pluginEntryName(snapshot.fileName))
                            .put("settings", snapshot.settings)
                    )
                }
            })
        }
    }

    private fun buildHostConfig(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        return JSONObject().apply {
            prefs.all.forEach { (key, value) ->
                if (isSensitivePreferenceKey(key)) return@forEach
                when (value) {
                    is Boolean -> put(key, value)
                    is Int -> put(key, value)
                    is Float -> put(key, value.toDouble())
                    is Long -> put(key, value)
                    is String -> put(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        put(key, (value as Set<String>).joinToString(","))
                    }
                }
            }
        }
    }

    suspend fun restoreFromJson(context: Context, json: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { restoreJsonDocument(context, JSONObject(json)) }.getOrDefault(false)
    }

    private fun restoreJsonDocument(context: Context, root: JSONObject): Boolean {
        if (root.optInt("version", -1) < 1) return false
        val config = root.optJSONObject("config") ?: return false
        restoreHostConfig(context, config)
        return true
    }

    private fun restoreHostConfig(context: Context, config: JSONObject) {
        val prefs = context.getSharedPreferences(UIConstants.PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            val keys = config.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = config.get(key)
                if (key == "key_send_normal_notification" ||
                    key == "key_send_focus_notification" ||
                    key == "key_persistent_foreground" ||
                    isSensitivePreferenceKey(key)
                ) continue
                if (key == ServiceConstants.KEY_NOTIFICATION_WHITELIST) {
                    val raw = value.toString()
                    val set = if (raw.isBlank()) {
                        emptySet()
                    } else {
                        raw.split(",").map { it.trim() }.filter(String::isNotEmpty).toSet()
                    }
                    putStringSet(key, set)
                    continue
                }
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Double, is Float -> putFloat(key, (value as Number).toFloat())
                    is Long -> putLong(key, value)
                    is String -> putString(key, value)
                }
            }
        }
        val syllableSettings = SyllablePreferencePolicy.read(prefs)
        val syllableEditor = prefs.edit()
        SyllablePreferencePolicy.write(syllableEditor, syllableSettings)
        syllableEditor.apply()
    }

    private fun restoreFromZip(context: Context, input: InputStream): RestoreResult {
        var manifestText: String? = null
        val pluginArchives = linkedMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                when {
                    entry.name == BACKUP_MANIFEST_ENTRY -> {
                        require(manifestText == null) { "Duplicate backup.json" }
                        manifestText = readEntry(zip, MAX_BACKUP_MANIFEST_BYTES)
                            .toString(Charsets.UTF_8)
                    }

                    entry.name.startsWith(PLUGIN_ENTRY_PREFIX) -> {
                        require(pluginArchives.size < MAX_BACKUP_PLUGIN_COUNT) {
                            "Too many plugin backup entries"
                        }
                        require(isSafePluginEntryName(entry.name)) {
                            "Invalid plugin backup entry"
                        }
                        require(entry.name !in pluginArchives) {
                            "Duplicate plugin backup entry"
                        }
                        pluginArchives[entry.name] = readEntry(
                            zip,
                            PluginConstants.MAX_PLUGIN_ZIP_BYTES
                        )
                    }
                }
            }
        }

        val root = manifestText?.let(::JSONObject)
            ?: throw IllegalArgumentException("Backup ZIP has no backup.json")
        require(root.optInt("version", -1) >= FULL_BACKUP_VERSION) {
            "Unsupported full backup version"
        }
        val plugins = root.optJSONArray("plugins") ?: JSONArray()
        require(plugins.length() <= MAX_BACKUP_PLUGIN_COUNT) {
            "Too many plugins in backup"
        }

        val seenIds = mutableSetOf<String>()
        val pending = buildList(plugins.length()) {
            for (index in 0 until plugins.length()) {
                val item = plugins.optJSONObject(index)
                    ?: throw IllegalArgumentException("Plugin backup metadata must be an object")
                val id = item.optString("id").takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("Plugin backup has no id")
                require(seenIds.add(id)) { "Duplicate plugin backup id: $id" }
                val archiveName = item.optString("archive")
                    .takeIf { isSafePluginEntryName(it) }
                    ?: throw IllegalArgumentException("Invalid plugin backup archive")
                val archiveBytes = pluginArchives[archiveName]
                    ?: throw IllegalArgumentException("Plugin archive is missing: $archiveName")
                val archive = PluginArchiveReader.read(archiveBytes)
                require(archive.manifest.id == id) {
                    "Plugin backup id does not match archive"
                }
                require(archive.manifest.apiVersion <= HYPERLYRIC_PLUGIN_API_VERSION) {
                    "Plugin API is newer than current HyperLyric"
                }
                add(
                    PendingPluginRestore(
                        id = id,
                        enabled = item.optBoolean("enabled", false),
                        archive = archiveBytes,
                        settings = item.optJSONObject("settings") ?: JSONObject()
                    )
                )
            }
        }

        if (pending.isNotEmpty() && RootApplication.xposedService == null) {
            throw IllegalStateException("Xposed Service 未连接")
        }
        if (!restoreJsonDocument(context, root)) return RestoreResult(false)

        val repository = PluginRepository(context)
        pending.forEach { plugin ->
            val installed = repository.installArchive(plugin.archive)
            require(installed.manifest.id == plugin.id) {
                "Restored plugin id does not match metadata"
            }
            repository.restorePluginSettings(installed.manifest, plugin.settings)
            repository.setEnabled(plugin.id, plugin.enabled)
        }
        return RestoreResult(success = true, restoredPluginCount = pending.size)
    }

    private data class PendingPluginRestore(
        val id: String,
        val enabled: Boolean,
        val archive: ByteArray,
        val settings: JSONObject,
    )

    private fun pluginEntryName(fileName: String): String = PLUGIN_ENTRY_PREFIX + fileName

    private fun isSafePluginEntryName(name: String): Boolean =
        name.startsWith(PLUGIN_ENTRY_PREFIX) &&
                name.removePrefix(PLUGIN_ENTRY_PREFIX)
                    .matches(Regex("[A-Za-z0-9._-]+\\.zip"))

    private fun readEntry(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Backup entry is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun writeZipEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        try {
            zip.write(bytes)
        } finally {
            zip.closeEntry()
        }
    }

    private fun isSensitivePreferenceKey(key: String): Boolean =
        key.endsWith("_api_key", ignoreCase = true)
}

class BackupRestoreHelper(
    context: Context,
    private val backupLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    private val fullBackupLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    private val restoreLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    private val pluginRepository = PluginRepository(context.applicationContext)

    private val hasInstalledPlugins: Boolean
        get() = pluginRepository.listInstalled().isNotEmpty()

    fun launchBackup() {
        val dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
        if (hasInstalledPlugins) {
            fullBackupLauncher.launch("hyperlyric_full_backup_$dateTime.zip")
        } else {
            backupLauncher.launch("hyperlyric_backup_$dateTime.json")
        }
    }

    fun launchRestore() {
        restoreLauncher.launch(arrayOf("application/json", "application/zip"))
    }
}

@Composable
fun rememberBackupRestoreHelper(snackbarHostState: SnackbarHostState): BackupRestoreHelper {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val msgBackupSuccess = stringResource(R.string.toast_backup_success)
    val fmtBackupFailed = stringResource(R.string.toast_backup_failed)
    val msgRestoreEmpty = stringResource(R.string.toast_restore_empty)
    val msgRestoreSuccess = stringResource(R.string.toast_restore_success)
    val msgRestoreInvalid = stringResource(R.string.toast_restore_invalid)
    val msgRestoreFailed = stringResource(R.string.toast_restore_failed)
    val fmtRestorePlugins = stringResource(R.string.toast_restore_success_with_plugins)

    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                try {
                    val jsonBytes = BackupRestoreManager.buildBackupJson(context).toByteArray(Charsets.UTF_8)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(jsonBytes)
                            it.flush()
                        }
                    }
                    snackbarHostState.showSnackbar(
                        message = msgBackupSuccess,
                        duration = SnackbarDuration.Custom(2000L)
                    )
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar(
                        message = fmtBackupFailed.format(e.message),
                        duration = SnackbarDuration.Custom(2000L)
                    )
                }
            }
        }
    )

    val fullBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
        onResult = { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            BackupRestoreManager.writeFullBackup(context, output)
                        } ?: error("无法打开备份文件")
                    }
                    snackbarHostState.showSnackbar(
                        message = msgBackupSuccess,
                        duration = SnackbarDuration.Custom(2000L)
                    )
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar(
                        message = fmtBackupFailed.format(e.message),
                        duration = SnackbarDuration.Custom(2000L)
                    )
                }
            }
        }
    )

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                try {
                    val result = BackupRestoreManager.restoreFromUri(context, uri)
                    if (result.success) RootApplication.syncAllPreferences()
                    val message = if (result.empty) {
                        msgRestoreEmpty
                    } else if (!result.success) {
                        msgRestoreInvalid
                    } else if (result.restoredPluginCount > 0) {
                        fmtRestorePlugins.format(result.restoredPluginCount)
                    } else {
                        msgRestoreSuccess
                    }
                    snackbarHostState.showSnackbar(
                        message = message,
                        duration = SnackbarDuration.Custom(2000L)
                    )
                } catch (_: Exception) {
                    snackbarHostState.showSnackbar(
                        message = msgRestoreFailed,
                        duration = SnackbarDuration.Custom(2000L)
                    )
                }
            }
        }
    )

    return remember(backupLauncher, fullBackupLauncher, restoreLauncher) {
        BackupRestoreHelper(context, backupLauncher, fullBackupLauncher, restoreLauncher)
    }
}
