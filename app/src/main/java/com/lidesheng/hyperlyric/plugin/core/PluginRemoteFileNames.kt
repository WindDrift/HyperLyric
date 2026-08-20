package com.lidesheng.hyperlyric.plugin.core

import java.security.MessageDigest

object PluginRemoteFileNames {
    fun forId(pluginId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(pluginId.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { byte -> "%02x".format(byte) }
        return "hyperlyric_plugin_$digest.zip"
    }
}
