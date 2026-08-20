package com.lidesheng.hyperlyric.plugin.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.lidesheng.hyperlyric.plugin.api.HYPERLYRIC_PLUGIN_API_VERSION
import com.lidesheng.hyperlyric.plugin.api.PluginSettingSpec
import com.lidesheng.hyperlyric.plugin.api.PluginSettingType
import com.lidesheng.hyperlyric.plugin.core.PluginArchiveReader
import com.lidesheng.hyperlyric.plugin.core.PluginConstants
import com.lidesheng.hyperlyric.plugin.core.PluginManifest
import com.lidesheng.hyperlyric.plugin.core.PluginManifestCodec
import com.lidesheng.hyperlyric.plugin.core.PluginRemoteFileNames
import io.github.libxposed.service.XposedService

data class InstalledPlugin(
    val manifest: PluginManifest,
    val fileName: String,
    val enabled: Boolean,
)

/** App-side install/config facade. SystemUI only consumes the remote registry and ZIP files. */
class PluginRepository(private val context: Context) {
    private val registry: SharedPreferences = context.getSharedPreferences(
        PluginConstants.LOCAL_REGISTRY_PREFS,
        Context.MODE_PRIVATE
    )

    fun listInstalled(): List<InstalledPlugin> {
        val ids = registry.getStringSet(PluginConstants.LOCAL_INSTALLED_IDS_KEY, emptySet()).orEmpty()
        val enabled = registry.getStringSet(PluginConstants.REMOTE_ENABLED_IDS_KEY, emptySet()).orEmpty()
        return ids.mapNotNull { id ->
            val manifest = registry.getString(PluginConstants.LOCAL_MANIFEST_PREFIX + id, null)
                ?.let { runCatching { PluginManifestCodec.decode(it) }.getOrNull() }
                ?: return@mapNotNull null
            InstalledPlugin(
                manifest = manifest,
                fileName = registry.getString(
                    PluginConstants.LOCAL_FILE_PREFIX + id,
                    PluginRemoteFileNames.forId(id)
                ) ?: PluginRemoteFileNames.forId(id),
                enabled = id in enabled
            )
        }.sortedBy { it.manifest.name }
    }

    fun install(uri: Uri): InstalledPlugin {
        val service = requireService()
        val bytes = context.contentResolver.openInputStream(uri)?.use(PluginArchiveReader::readBounded)
            ?: throw IllegalArgumentException("无法读取插件 ZIP")
        val archive = PluginArchiveReader.read(bytes)
        require(archive.manifest.apiVersion <= HYPERLYRIC_PLUGIN_API_VERSION) {
            "插件 API 版本高于当前 HyperLyric"
        }

        val fileName = PluginRemoteFileNames.forId(archive.manifest.id)
        writeRemoteFile(service, fileName, bytes)

        val wasEnabled = archive.manifest.id in registry.getStringSet(
            PluginConstants.REMOTE_ENABLED_IDS_KEY,
            emptySet()
        ).orEmpty()
        val installedIds = registry.getStringSet(
            PluginConstants.LOCAL_INSTALLED_IDS_KEY,
            emptySet()
        ).orEmpty().toMutableSet().apply { add(archive.manifest.id) }
        registry.edit()
            .putStringSet(PluginConstants.LOCAL_INSTALLED_IDS_KEY, installedIds)
            .putString(
                PluginConstants.LOCAL_MANIFEST_PREFIX + archive.manifest.id,
                PluginManifestCodec.encode(archive.manifest)
            )
            .putString(PluginConstants.LOCAL_FILE_PREFIX + archive.manifest.id, fileName)
            .putStringSet(
                PluginConstants.REMOTE_ENABLED_IDS_KEY,
                registry.getStringSet(PluginConstants.REMOTE_ENABLED_IDS_KEY, emptySet()).orEmpty()
            )
            .apply()

        ensureDefaults(archive.manifest)
        syncConfig(service, archive.manifest)
        syncRegistry(service)

        return InstalledPlugin(archive.manifest, fileName, wasEnabled)
    }

    fun setEnabled(pluginId: String, enabled: Boolean) {
        val installed = listInstalled().firstOrNull { it.manifest.id == pluginId }
            ?: throw IllegalArgumentException("插件未安装: $pluginId")
        val service = requireService()
        // The registry is the boot-time load gate. The activation setting is a live gate for a
        // plugin that is already loaded, so disabling it takes effect without waiting for reboot.
        val ids = registry.getStringSet(
            PluginConstants.REMOTE_ENABLED_IDS_KEY,
            emptySet()
        ).orEmpty().toMutableSet()
        if (enabled) ids += pluginId else ids -= pluginId
        service.getRemotePreferences(PluginConstants.REMOTE_REGISTRY_PREFS)
            .edit()
            .putStringSet(PluginConstants.REMOTE_ENABLED_IDS_KEY, ids)
            .apply()
        registry.edit().putStringSet(PluginConstants.REMOTE_ENABLED_IDS_KEY, ids).apply()

        installed.manifest.activationSettingKey?.let { key ->
            configPreferences(pluginId).edit().putBoolean(key, enabled).apply()
            service.getRemotePreferences(PluginConstants.configGroup(pluginId))
                .edit()
                .putBoolean(key, enabled)
                .apply()
        }
    }

    fun uninstall(pluginId: String) {
        val installed = listInstalled().firstOrNull { it.manifest.id == pluginId }
            ?: throw IllegalArgumentException("插件未安装: $pluginId")
        val service = requireService()
        service.deleteRemoteFile(installed.fileName)
        runCatching {
            service.getRemotePreferences(PluginConstants.configGroup(pluginId))
                .edit()
                .clear()
                .apply()
            service.deleteRemotePreferences(PluginConstants.configGroup(pluginId))
        }
        runCatching {
            service.getRemotePreferences(PluginConstants.storagePreferences(pluginId))
                .edit()
                .clear()
                .apply()
            service.deleteRemotePreferences(PluginConstants.storagePreferences(pluginId))
        }
        configPreferences(pluginId).edit().clear().commit()

        val ids = registry.getStringSet(PluginConstants.LOCAL_INSTALLED_IDS_KEY, emptySet())
            .orEmpty().toMutableSet().apply { remove(pluginId) }
        val enabled = registry.getStringSet(PluginConstants.REMOTE_ENABLED_IDS_KEY, emptySet())
            .orEmpty().toMutableSet().apply { remove(pluginId) }
        registry.edit()
            .putStringSet(PluginConstants.LOCAL_INSTALLED_IDS_KEY, ids)
            .putStringSet(PluginConstants.REMOTE_ENABLED_IDS_KEY, enabled)
            .remove(PluginConstants.LOCAL_MANIFEST_PREFIX + pluginId)
            .remove(PluginConstants.LOCAL_FILE_PREFIX + pluginId)
            .apply()
        syncRegistry(service)
    }

    fun configPreferences(pluginId: String): SharedPreferences = context.getSharedPreferences(
        PluginConstants.configGroup(pluginId),
        Context.MODE_PRIVATE
    )

    fun ensureDefaults(manifest: PluginManifest) {
        val preferences = configPreferences(manifest.id)
        val editor = preferences.edit()
        var changed = false
        manifest.settings.settings.forEach { setting ->
            val currentValue = preferences.all[setting.key]
            if (currentValue != null && isCompatible(currentValue, setting.type)) {
                return@forEach
            }
            if (currentValue != null) editor.remove(setting.key)
            putTypedValue(editor, setting.key, setting)
            changed = true
        }
        if (changed) editor.apply()
    }

    fun setSettingValue(manifest: PluginManifest, setting: PluginSettingSpec, rawValue: String) {
        val value: Any = when (setting.type) {
            PluginSettingType.SWITCH -> rawValue.toBoolean()
            PluginSettingType.NUMBER -> rawValue.toLongOrNull() ?: return
            PluginSettingType.SLIDER -> rawValue.toFloatOrNull() ?: return
            PluginSettingType.MULTI_SELECT -> rawValue.split(',').filter(String::isNotBlank).toSet()
            else -> rawValue
        }
        val local = configPreferences(manifest.id)
        putTypedValue(local.edit(), setting.key, value).apply()
        runCatching {
            requireServiceOrNull()?.getRemotePreferences(PluginConstants.configGroup(manifest.id))
                ?.edit()
                ?.let { putTypedValue(it, setting.key, value).apply() }
        }
    }

    fun syncAllRemote(service: XposedService): Boolean {
        return runCatching {
            listInstalled().forEach { installed ->
                ensureDefaults(installed.manifest)
                syncConfig(service, installed.manifest)
            }
            syncRegistry(service)
            true
        }.getOrDefault(false)
    }

    private fun syncRegistry(service: XposedService) {
        val enabled = registry.getStringSet(
            PluginConstants.REMOTE_ENABLED_IDS_KEY,
            emptySet()
        ).orEmpty()
        service.getRemotePreferences(PluginConstants.REMOTE_REGISTRY_PREFS)
            .edit()
            .putStringSet(PluginConstants.REMOTE_ENABLED_IDS_KEY, enabled)
            .apply()
    }

    private fun syncConfig(service: XposedService, manifest: PluginManifest) {
        val local = configPreferences(manifest.id)
        val remote = service.getRemotePreferences(PluginConstants.configGroup(manifest.id))
        val editor = remote.edit()
        local.all.forEach { (key, value) -> putTypedValue(editor, key, value) }
        editor.apply()
    }

    private fun putTypedValue(
        editor: SharedPreferences.Editor,
        key: String,
        value: Any?
    ): SharedPreferences.Editor {
        when (value) {
            null -> editor.remove(key)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
        return editor
    }

    private fun putTypedValue(
        editor: SharedPreferences.Editor,
        key: String,
        setting: PluginSettingSpec
    ): SharedPreferences.Editor {
        val defaultValue = setting.defaultValue ?: when (setting.type) {
            PluginSettingType.SWITCH -> "false"
            PluginSettingType.NUMBER,
            PluginSettingType.SLIDER -> "0"
            else -> ""
        }
        val value: Any = when (setting.type) {
            PluginSettingType.SWITCH -> defaultValue.toBoolean()
            PluginSettingType.NUMBER -> defaultValue.toLongOrNull() ?: 0L
            PluginSettingType.SLIDER -> defaultValue.toFloatOrNull() ?: 0f
            PluginSettingType.MULTI_SELECT -> defaultValue
                .split(',')
                .filter(String::isNotBlank)
                .toSet()
            else -> defaultValue
        }
        return putTypedValue(editor, key, value)
    }

    private fun isCompatible(value: Any, type: PluginSettingType): Boolean = when (type) {
        PluginSettingType.SWITCH -> value is Boolean
        PluginSettingType.TEXT,
        PluginSettingType.PASSWORD,
        PluginSettingType.SELECT,
        PluginSettingType.ACTION -> value is String
        PluginSettingType.MULTI_SELECT -> value is Set<*>
        PluginSettingType.NUMBER -> value is Long
        PluginSettingType.SLIDER -> value is Float
    }

    private fun requireService(): XposedService = requireServiceOrNull()
        ?: throw IllegalStateException("Xposed Service 未连接")

    private fun requireServiceOrNull(): XposedService? =
        com.lidesheng.hyperlyric.root.RootApplication.xposedService

    private fun writeRemoteFile(service: XposedService, fileName: String, bytes: ByteArray) {
        val descriptor = service.openRemoteFile(fileName)
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            output.write(bytes)
            output.flush()
        }
    }
}
