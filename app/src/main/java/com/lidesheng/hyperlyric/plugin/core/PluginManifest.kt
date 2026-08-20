package com.lidesheng.hyperlyric.plugin.core

import com.lidesheng.hyperlyric.plugin.api.PluginSettingOption
import com.lidesheng.hyperlyric.plugin.api.PluginSettingSpec
import com.lidesheng.hyperlyric.plugin.api.PluginSettingType
import com.lidesheng.hyperlyric.plugin.api.PluginSettingsSchema
import org.json.JSONArray
import org.json.JSONObject

data class PluginManifest(
    val id: String,
    val name: String,
    val summary: String,
    val version: String,
    val apiVersion: Int,
    val entry: String,
    val author: String? = null,
    val settings: PluginSettingsSchema = PluginSettingsSchema(),
)

object PluginManifestCodec {
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val entryPattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")

    fun decode(jsonText: String): PluginManifest {
        val json = JSONObject(jsonText)
        val id = json.requiredString("id")
        val name = json.requiredString("name")
        val summary = json.requiredString("summary")
        val author = json.optionalString("author")?.takeIf { it.isNotBlank() }
        val version = json.requiredString("version")
        val apiVersion = json.optInt("apiVersion", -1)
        val entry = json.requiredString("entry")

        require(idPattern.matches(id)) { "Invalid plugin id" }
        require(name.isNotBlank()) { "Plugin name is blank" }
        require(version.isNotBlank()) { "Plugin version is blank" }
        require(apiVersion > 0) { "Invalid plugin apiVersion" }
        require(entryPattern.matches(entry)) { "Invalid plugin entry" }

        return PluginManifest(
            id = id,
            name = name,
            summary = summary,
            version = version,
            apiVersion = apiVersion,
            entry = entry,
            author = author,
            settings = PluginSettingsSchema(decodeSettings(json.optJSONArray("settings")))
        )
    }

    fun encode(manifest: PluginManifest): String {
        val json = JSONObject()
            .put("id", manifest.id)
            .put("name", manifest.name)
            .put("summary", manifest.summary)
            .also { json ->
                manifest.author?.takeIf { it.isNotBlank() }?.let { json.put("author", it) }
            }
            .put("version", manifest.version)
            .put("apiVersion", manifest.apiVersion)
            .put("entry", manifest.entry)

        val settings = JSONArray()
        manifest.settings.settings.forEach { setting ->
            val settingJson = JSONObject()
                .put("type", setting.type.wireName)
                .put("key", setting.key)
                .put("title", setting.title)
            setting.summary?.let { settingJson.put("summary", it) }
            setting.defaultValue?.let { settingJson.put("default", encodeDefault(setting.type, it)) }
            if (setting.options.isNotEmpty()) {
                settingJson.put(
                    "options",
                    JSONArray().apply {
                        setting.options.forEach { option ->
                            put(JSONObject().put("value", option.value).put("label", option.label))
                        }
                    }
                )
            }
            setting.min?.let { settingJson.put("min", it) }
            setting.max?.let { settingJson.put("max", it) }
            setting.step?.let { settingJson.put("step", it) }
            settings.put(settingJson)
        }
        if (settings.length() > 0) json.put("settings", settings)
        return json.toString()
    }

    private fun decodeSettings(array: JSONArray?): List<PluginSettingSpec> {
        if (array == null) return emptyList()
        val keys = HashSet<String>()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                    ?: throw IllegalArgumentException("Plugin setting must be an object")
                val type = PluginSettingType.fromWire(item.requiredString("type"))
                    ?: throw IllegalArgumentException("Unsupported plugin setting type")
                val key = item.requiredString("key")
                val title = item.requiredString("title")
                require(key.matches(Regex("[A-Za-z0-9._-]{1,128}"))) {
                    "Invalid plugin setting key"
                }
                require(keys.add(key)) { "Duplicate plugin setting key: $key" }

                val options = decodeOptions(item.optJSONArray("options"))
                if (type == PluginSettingType.SELECT || type == PluginSettingType.MULTI_SELECT) {
                    require(options.isNotEmpty()) { "Selection setting has no options: $key" }
                }

                add(
                    PluginSettingSpec(
                        type = type,
                        key = key,
                        title = title,
                        summary = item.optionalString("summary"),
                        defaultValue = item.optionalValue("default"),
                        options = options,
                        min = item.optNullableFloat("min"),
                        max = item.optNullableFloat("max"),
                        step = item.optNullableFloat("step")
                    )
                )
            }
        }
    }

    private fun decodeOptions(array: JSONArray?): List<PluginSettingOption> {
        if (array == null) return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.opt(index)
                if (item is JSONObject) {
                    add(
                        PluginSettingOption(
                            value = item.requiredString("value"),
                            label = item.requiredString("label")
                        )
                    )
                } else if (item != null && item !== JSONObject.NULL) {
                    val value = item.toString()
                    add(PluginSettingOption(value = value, label = value))
                }
            }
        }
    }

    private fun encodeDefault(type: PluginSettingType, value: String): Any = when (type) {
        PluginSettingType.SWITCH -> value.toBooleanStrictOrNull() ?: value
        PluginSettingType.NUMBER -> value.toLongOrNull() ?: value
        PluginSettingType.SLIDER -> value.toFloatOrNull() ?: value
        else -> value
    }

    private fun JSONObject.requiredString(key: String): String =
        optionalString(key)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing manifest field: $key")

    private fun JSONObject.optionalString(key: String): String? =
        opt(key)?.takeUnless { it === JSONObject.NULL }?.toString()

    private fun JSONObject.optionalValue(key: String): String? = optionalString(key)

    private fun JSONObject.optNullableFloat(key: String): Float? =
        opt(key)?.takeUnless { it === JSONObject.NULL }?.toString()?.toFloatOrNull()
}
