package com.lidesheng.hyperlyric.plugin.ai.translation

import com.lidesheng.hyperlyric.plugin.api.HyperLyricPlugin
import com.lidesheng.hyperlyric.plugin.api.PluginConfig
import com.lidesheng.hyperlyric.plugin.api.PluginContext

class AiTranslationPlugin : HyperLyricPlugin {
    private var processor: AiTranslationProcessor? = null

    override fun onLoad(context: PluginContext) {
        val created = AiTranslationProcessor(context)
        processor = created
        context.registerExtension(created)
        context.registerExtension(created.cacheExtension())
    }

    override fun onEnable() = Unit

    override fun onConfigChanged(config: PluginConfig) {
        processor?.onConfigChanged(config)
    }

    override fun onUnload() {
        processor?.close()
        processor = null
    }
}
