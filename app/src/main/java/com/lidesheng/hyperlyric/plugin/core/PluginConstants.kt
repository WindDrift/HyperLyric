package com.lidesheng.hyperlyric.plugin.core

import com.lidesheng.hyperlyric.plugin.api.HYPERLYRIC_PLUGIN_API_VERSION

object PluginConstants {
    const val API_VERSION = HYPERLYRIC_PLUGIN_API_VERSION

    const val REMOTE_REGISTRY_PREFS = "hyperlyric.plugin.registry"
    const val REMOTE_ENABLED_IDS_KEY = "enabled_ids"

    const val LOCAL_REGISTRY_PREFS = "hyperlyric_plugin_registry"
    const val LOCAL_INSTALLED_IDS_KEY = "installed_ids"
    const val LOCAL_MANIFEST_PREFIX = "manifest."
    const val LOCAL_FILE_PREFIX = "file."

    const val ZIP_MANIFEST = "manifest.json"
    const val ZIP_DEX = "classes.dex"

    const val MAX_PLUGIN_ZIP_BYTES = 64 * 1024 * 1024
    const val MAX_PLUGIN_DEX_BYTES = 32 * 1024 * 1024
    const val MAX_PLUGIN_DEX_FILES = 16
    const val MAX_PLUGIN_MANIFEST_BYTES = 512 * 1024

    const val MAX_PROCESSOR_TIMEOUT_MS = 15_000L

    fun configGroup(pluginId: String): String = "plugin.$pluginId"

    fun storagePreferences(pluginId: String): String = "hyperlyric_plugin_data_$pluginId"
}
