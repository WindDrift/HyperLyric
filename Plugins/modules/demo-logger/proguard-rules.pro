# The manifest resolves the entry class by its original binary name.
# Keep the no-argument constructor because Runtime instantiates it reflectively.
-keep,allowoptimization class * implements com.lidesheng.hyperlyric.plugin.api.HyperLyricPlugin {
    <init>();
}

# Runtime calls the lifecycle methods through the HyperLyricPlugin protocol.
-keepclassmembers,allowoptimization class * implements com.lidesheng.hyperlyric.plugin.api.HyperLyricPlugin {
    public void onLoad(com.lidesheng.hyperlyric.plugin.api.PluginContext);
    public void onEnable();
    public void onConfigChanged(com.lidesheng.hyperlyric.plugin.api.PluginConfig);
    public void onUnload();
}

# Runtime invokes extension properties and processors through these API interfaces.
-keepclassmembers,allowoptimization class * implements com.lidesheng.hyperlyric.plugin.api.HyperLyricExtension {
    public java.lang.String getId();
    public com.lidesheng.hyperlyric.plugin.api.PluginProcessorStage getStage();
}
-keepclassmembers,allowoptimization class * implements com.lidesheng.hyperlyric.plugin.api.LyricProcessorExtension {
    public com.lidesheng.hyperlyric.plugin.api.PluginSong process(
        com.lidesheng.hyperlyric.plugin.api.PluginSong
    );
    public com.lidesheng.hyperlyric.plugin.api.PluginSongResult processResult(
        com.lidesheng.hyperlyric.plugin.api.PluginSong
    );
    public com.lidesheng.hyperlyric.plugin.api.PluginSongResult processResult(
        com.lidesheng.hyperlyric.plugin.api.PluginSong,
        com.lidesheng.hyperlyric.plugin.api.PluginProcessingContext
    );
}

# Keep the immutable protocol DTOs and enum fields used across the host/plugin ClassLoader.
-keep,allowoptimization,allowobfuscation public class com.lidesheng.hyperlyric.plugin.api.PluginSong { *; }
-keep,allowoptimization,allowobfuscation public class com.lidesheng.hyperlyric.plugin.api.PluginSongResult { *; }
-keep,allowoptimization,allowobfuscation public enum com.lidesheng.hyperlyric.plugin.api.PluginSongField { *; }
-keep,allowoptimization,allowobfuscation public enum com.lidesheng.hyperlyric.plugin.api.PluginProcessorStage { *; }
-keep,allowoptimization,allowobfuscation public class com.lidesheng.hyperlyric.plugin.api.PluginMediaInfo { *; }
-keep,allowoptimization,allowobfuscation public class com.lidesheng.hyperlyric.plugin.api.PluginProcessingContext { *; }
-keep,allowoptimization,allowobfuscation public class com.lidesheng.hyperlyric.plugin.api.PluginLyricLine { *; }
-keep,allowoptimization,allowobfuscation public class com.lidesheng.hyperlyric.plugin.api.PluginWord { *; }
-keep,allowoptimization,allowobfuscation public class com.lidesheng.hyperlyric.plugin.api.PluginMetadata { *; }
