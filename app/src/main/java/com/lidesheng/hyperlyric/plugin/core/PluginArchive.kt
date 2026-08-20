package com.lidesheng.hyperlyric.plugin.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class PluginArchive(
    val manifest: PluginManifest,
    val dexFiles: List<ByteArray>,
)

object PluginArchiveReader {
    fun read(bytes: ByteArray): PluginArchive {
        require(bytes.isNotEmpty()) { "Plugin ZIP is empty" }
        require(bytes.size <= PluginConstants.MAX_PLUGIN_ZIP_BYTES) {
            "Plugin ZIP is too large"
        }
        return read(ByteArrayInputStream(bytes))
    }

    fun read(input: InputStream): PluginArchive {
        var manifestText: String? = null
        val dexFiles = sortedMapOf<Int, ByteArray>()

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                when {
                    entry.name == PluginConstants.ZIP_MANIFEST -> {
                        require(manifestText == null) { "Duplicate manifest.json" }
                        manifestText = readEntry(zip, PluginConstants.MAX_PLUGIN_MANIFEST_BYTES)
                            .toString(Charsets.UTF_8)
                    }

                    dexIndex(entry.name) != null -> {
                        require(dexFiles.size < PluginConstants.MAX_PLUGIN_DEX_FILES) {
                            "Too many plugin dex files"
                        }
                        val index = dexIndex(entry.name)!!
                        require(index !in dexFiles) { "Duplicate ${entry.name}" }
                        dexFiles[index] = readEntry(zip, PluginConstants.MAX_PLUGIN_DEX_BYTES)
                    }
                }
            }
        }

        val manifest = manifestText?.let(PluginManifestCodec::decode)
            ?: throw IllegalArgumentException("Plugin ZIP has no manifest.json")
        require(1 in dexFiles) { "Plugin ZIP has no classes.dex" }
        return PluginArchive(manifest = manifest, dexFiles = dexFiles.values.toList())
    }

    fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= PluginConstants.MAX_PLUGIN_ZIP_BYTES) {
                "Plugin ZIP is too large"
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun readEntry(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Plugin ZIP entry is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun dexIndex(name: String): Int? {
        if (name == PluginConstants.ZIP_DEX) return 1
        if (!name.startsWith("classes") || !name.endsWith(".dex")) return null
        return name.removePrefix("classes")
            .removeSuffix(".dex")
            .toIntOrNull()
            ?.takeIf { it >= 2 }
    }
}
