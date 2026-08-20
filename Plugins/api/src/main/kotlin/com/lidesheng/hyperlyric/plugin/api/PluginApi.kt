package com.lidesheng.hyperlyric.plugin.api

/** The first stable HyperLyric plugin API contract. */
public const val HYPERLYRIC_PLUGIN_API_VERSION: Int = 1

/** ZIP entry point. A plugin may register one or more extensions during [onLoad]. */
public interface HyperLyricPlugin {
    public fun onLoad(context: PluginContext)

    public fun onEnable() {}

    public fun onConfigChanged(config: PluginConfig) {}

    public fun onUnload() {}
}

/** A capability supplied by a plugin. */
public interface HyperLyricExtension {
    public val id: String
}

/** The first extension type supported by the runtime. */
public interface LyricProcessorExtension : HyperLyricExtension {
    /**
     * Process an immutable snapshot off the SystemUI main thread.
     *
     * Returning null means that this processor has no result. The host keeps the previous
     * snapshot and continues with the next enabled processor.
     */
    public fun process(song: PluginSong): PluginSong?
}

/** The only host object made available to a plugin. */
public interface PluginContext {
    public val pluginId: String
    public val hostApiVersion: Int
    public val config: PluginConfig
    public val logger: PluginLogger
    public val storage: PluginStorage

    public fun registerExtension(extension: HyperLyricExtension)
}

/** Read-only from the plugin's point of view; values are changed by the HyperLyric App. */
public interface PluginConfig {
    public fun getBoolean(key: String, defaultValue: Boolean = false): Boolean
    public fun getString(key: String, defaultValue: String? = null): String?
    public fun getLong(key: String, defaultValue: Long = 0L): Long
    public fun getFloat(key: String, defaultValue: Float = 0f): Float
    public fun getStringSet(key: String, defaultValue: Set<String> = emptySet()): Set<String>
}

/**
 * Small host-owned logging surface; it deliberately does not expose Android or Xposed objects.
 * The runtime uses the plugin ID as the log source name and forwards messages to the host's
 * Xposed/LSPosed log path.
 */
public interface PluginLogger {
    public fun debug(message: String)
    public fun info(message: String)
    public fun warn(message: String, throwable: Throwable? = null)
    public fun error(message: String, throwable: Throwable? = null)

    /** Creates a logger that keeps the host log format but uses a stable component tag. */
    public fun withTag(tag: String): PluginLogger = this
}

/** Namespaced key/value storage owned by the host runtime. */
public interface PluginStorage {
    public fun getString(key: String, defaultValue: String? = null): String?
    public fun putString(key: String, value: String)
    public fun remove(key: String)
    public fun clear()
}

/** Stable media snapshot passed across the plugin boundary. */
public data class PluginSong(
    val id: String? = null,
    val name: String? = null,
    val artist: String? = null,
    val duration: Long = 0L,
    val metadata: PluginMetadata? = null,
    val lyrics: List<PluginLyricLine>? = null,
)

public data class PluginMetadata(
    val values: Map<String, String?> = emptyMap(),
)

public data class PluginLyricLine(
    val begin: Long = 0L,
    val end: Long = 0L,
    val duration: Long = 0L,
    val isAlignedRight: Boolean = false,
    val metadata: PluginMetadata? = null,
    val text: String? = null,
    val words: List<PluginWord>? = null,
    val secondary: String? = null,
    val secondaryWords: List<PluginWord>? = null,
    val translation: String? = null,
    val translationWords: List<PluginWord>? = null,
    val roma: String? = null,
)

public data class PluginWord(
    val begin: Long = 0L,
    val end: Long = 0L,
    val duration: Long = 0L,
    val text: String? = null,
    val metadata: PluginMetadata? = null,
)

/** Semantic setting types used by the App renderer, independent of Miuix class names. */
public enum class PluginSettingType(public val wireName: String) {
    SWITCH("switch"),
    TEXT("text"),
    PASSWORD("password"),
    SELECT("select"),
    MULTI_SELECT("multiSelect"),
    NUMBER("number"),
    SLIDER("slider"),
    ACTION("action");

    public companion object {
        public fun fromWire(value: String): PluginSettingType? =
            entries.firstOrNull { it.wireName == value }
    }
}

/** Semantic placement for a setting's current value in the host settings row. */
public enum class PluginSettingValuePresentation(public val wireName: String) {
    DEFAULT("default"),
    END_ACTION("endAction"),
    SUMMARY("summary"),
    SUMMARY_PREVIEW("summaryPreview");

    public companion object {
        public fun fromWire(value: String): PluginSettingValuePresentation? =
            entries.firstOrNull { it.wireName == value }
    }
}

/** Keyboard intent for text settings; the host maps this to its native input component. */
public enum class PluginSettingInputType(public val wireName: String) {
    DEFAULT("default"),
    URI("uri"),
    NUMBER("number");

    public companion object {
        public fun fromWire(value: String): PluginSettingInputType? =
            entries.firstOrNull { it.wireName == value }
    }
}

public data class PluginSettingOption(
    val value: String,
    val label: String,
    val labelByLocale: Map<String, String> = emptyMap(),
)

public data class PluginSettingSpec(
    val type: PluginSettingType,
    val key: String,
    val title: String,
    val summary: String? = null,
    val defaultValue: String? = null,
    val options: List<PluginSettingOption> = emptyList(),
    val min: Float? = null,
    val max: Float? = null,
    val step: Float? = null,
    val titleByLocale: Map<String, String> = emptyMap(),
    val summaryByLocale: Map<String, String> = emptyMap(),
    val dialogSummary: String? = null,
    val dialogSummaryByLocale: Map<String, String> = emptyMap(),
    val emptyValueSummary: String? = null,
    val emptyValueSummaryByLocale: Map<String, String> = emptyMap(),
    val valuePresentation: PluginSettingValuePresentation =
        PluginSettingValuePresentation.DEFAULT,
    val previewLineCount: Int = 2,
    val inputType: PluginSettingInputType = PluginSettingInputType.DEFAULT,
    val conflictsWith: List<String> = emptyList(),
    /** Whether this setting value may be included in a full HyperLyric backup. */
    val backup: Boolean = true,
)

public data class PluginSettingsSchema(
    val settings: List<PluginSettingSpec> = emptyList(),
)
