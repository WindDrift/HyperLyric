package com.lidesheng.hyperlyric.plugin.ai.translation

import com.lidesheng.hyperlyric.plugin.api.PluginSong
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object TranslationKey {
    fun calculate(song: PluginSong, lines: List<String>, targetLanguage: String): String {
        val source = buildString {
            append("target=").appendLine(targetLanguage)
            append("title=").appendLine(song.name.orEmpty())
            append("artist=").appendLine(song.artist.orEmpty())
            append("album=").appendLine(song.album.orEmpty())
            append("duration=").appendLine(song.duration)
            lines.forEachIndexed { index, line ->
                append(index).append(':').appendLine(line)
            }
        }
        return MessageDigest.getInstance("MD5")
            .digest(source.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
