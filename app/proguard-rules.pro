-keep class com.lidesheng.hyperlyric.common.RootConstants { *; }
-keep class com.lidesheng.hyperlyric.common.ServiceConstants { *; }
-keep class com.lidesheng.hyperlyric.common.UIConstants { *; }

# 保护 libxposed 接口
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# 保护 Kotlin 元数据
-keep class kotlin.Metadata { *; }

# --- Compose 相关规则 (防止误删) ---
-keepattributes *Annotation*, Signature, InnerClasses
-dontwarn androidx.compose.**

# --- 歌词数据模型（Parcelable + Serializable）---
-keep class com.lidesheng.hyperlyric.lyric.model.** { *; }

# --- HyperLyric Plugin API ---
# Dynamically loaded plugin DEX files resolve this package through the host
# classloader, so its public names and method descriptors are part of the ABI.
-keep,allowoptimization interface com.lidesheng.hyperlyric.plugin.api.** { *; }
-keep,allowoptimization class com.lidesheng.hyperlyric.plugin.api.** { *; }

# --- Shizuku User Service ---
-keep class com.lidesheng.hyperlyric.service.utils.shizuku.PrivilegedServiceImpl { *; }

# --- SuperLyric API ---
-keep class com.hchen.superlyricapi.* { *; }
-dontwarn android.os.ServiceManager
