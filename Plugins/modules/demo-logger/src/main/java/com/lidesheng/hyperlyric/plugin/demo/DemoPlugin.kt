package com.lidesheng.hyperlyric.plugin.demo

import com.lidesheng.hyperlyric.plugin.api.HyperLyricPlugin
import com.lidesheng.hyperlyric.plugin.api.LyricProcessorExtension
import com.lidesheng.hyperlyric.plugin.api.PluginConfig
import com.lidesheng.hyperlyric.plugin.api.PluginContext
import com.lidesheng.hyperlyric.plugin.api.PluginMetadata
import com.lidesheng.hyperlyric.plugin.api.PluginSong

class DemoPlugin : HyperLyricPlugin {
    private companion object {
        const val EXTENSION_ID = "demo.logger"
    }

    private lateinit var context: PluginContext

    override fun onLoad(context: PluginContext) {
        this.context = context
        context.registerExtension(LoggerProcessor(context))
        context.logger.info("lifecycle=onLoad, extensions=1")
    }

    override fun onEnable() {
        context.logger.info("lifecycle=onEnable")
    }

    override fun onConfigChanged(config: PluginConfig) {
        context.logger.debug(
            "lifecycle=onConfigChanged, log_song=${config.getBoolean("log_song", true)}"
        )
    }

    override fun onUnload() {
        context.logger.info("lifecycle=onUnload")
    }

    private class LoggerProcessor(private val context: PluginContext) : LyricProcessorExtension {
        override val id: String = EXTENSION_ID

        override fun process(song: PluginSong): PluginSong {
            if (context.config.getBoolean("log_song", true)) {
                context.logger.info(
                    "event=processSong, id=${song.id.orEmpty()}, " +
                            "name=${song.name.orEmpty()}, artist=${song.artist.orEmpty()}, " +
                            "lines=${song.lyrics?.size ?: 0}"
                )
            }
            val metadata = song.metadata ?: PluginMetadata()
            return song.copy(
                metadata = metadata.copy(
                    values = metadata.values + ("hyperlyric.demo" to "processed")
                )
            )
        }
    }
}
