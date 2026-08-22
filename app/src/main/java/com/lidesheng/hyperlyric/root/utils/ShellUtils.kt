package com.lidesheng.hyperlyric.root.utils

import com.lidesheng.hyperlyric.plugin.core.PluginCacheFileLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

object ShellUtils {
    data class RootPluginCacheFile(
        val fileName: String,
        val sizeBytes: Long,
        val legacyPreferences: Boolean,
        val absolutePath: String,
    )

    sealed interface RootPluginCacheQuery {
        data class Available(val files: List<RootPluginCacheFile>) : RootPluginCacheQuery
        data object RootUnavailable : RootPluginCacheQuery
        data object InvalidPluginId : RootPluginCacheQuery
    }

    private data class RootCommandResult(
        val exitCode: Int,
        val standardOutput: String,
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    suspend fun restartSystemUI(): Boolean {
        return killAppProcess("com.android.systemui")
    }

    /**
     * 移植HyperCeiler的功能
     */
    suspend fun killAppProcess(packageName: String, signal: Int = 15): Boolean {
        val script = $$"""
            pid=$(pgrep -f "$$packageName" | grep -v $$)
            if [ -z "$pid" ]; then
                pids=""
                pid=$(ps -A -o PID,ARGS=CMD | grep "$$packageName" | grep -v "grep")
                for i in $pid; do
                    case "$i" in
                        ''|*[!0-9]*) ;;
                        *) pids="$pids $i" ;;
                    esac
                done
                pid=$pids
            fi
            
            killed=0
            if [ -n "$pid" ]; then
                for i in $pid; do
                    kill -s $$signal "$i" >/dev/null 2>&1
                    kill -s 9 "$i" >/dev/null 2>&1
                    if [ $? -eq 0 ]; then
                        killed=1
                    fi
                done
            fi
            
            if [ $killed -eq 1 ]; then
                exit 0
            else
                exit 1
            fi
        """.trimIndent()

        return execRootScriptSilent("nsenter --mount=/proc/1/ns/mnt -- sh", script)
    }

    suspend fun execRootScriptSilent(cmd: String, inputScript: String? = null): Boolean {
        return execRootScript(cmd, inputScript)?.isSuccess == true
    }

    /**
     * Read-only fallback for cache inspection when the injected SystemUI runtime has not yet
     * reloaded. It never parses or mutates a plugin's cache body.
     */
    suspend fun querySystemUiPluginCacheFiles(pluginId: String): RootPluginCacheQuery {
        if (!PluginCacheFileLayout.isValidPluginId(pluginId)) {
            return RootPluginCacheQuery.InvalidPluginId
        }
        val script = $$"""
            for data_root in /data/user/0 /data/user_de/0; do
                cache_dir="$data_root/com.android.systemui/$${PluginCacheFileLayout.rootRelativeDirectory(pluginId)}"
                legacy_prefs="$data_root/com.android.systemui/shared_prefs/hyperlyric_plugin_cache_$${pluginId}.xml"
                if [ -d "$cache_dir" ]; then
                    for file in "$cache_dir"/*.cache; do
                        [ -f "$file" ] || continue
                        size=$(wc -c < "$file" 2>/dev/null | tr -d ' ')
                        printf 'file\t%s\t%s\t%s\n' "$file" "${file##*/}" "$size"
                    done
                fi
                if [ -f "$legacy_prefs" ]; then
                    size=$(wc -c < "$legacy_prefs" 2>/dev/null | tr -d ' ')
                    printf 'legacy\t%s\t%s\t%s\n' "$legacy_prefs" "${legacy_prefs##*/}" "$size"
                fi
            done
        """.trimIndent()
        val result = execRootScript("nsenter --mount=/proc/1/ns/mnt -- sh", script)
            ?: return RootPluginCacheQuery.RootUnavailable
        if (!result.isSuccess) return RootPluginCacheQuery.RootUnavailable
        return RootPluginCacheQuery.Available(parsePluginCacheFiles(result.standardOutput))
    }

    internal fun parsePluginCacheFiles(output: String): List<RootPluginCacheFile> = output
        .lineSequence()
        .mapNotNull { line ->
            val parts = line.split('\t')
            val legacy = when (parts.getOrNull(0)) {
                "file" -> false
                "legacy" -> true
                else -> return@mapNotNull null
            }
            val absolutePath = parts.getOrNull(1)?.takeIf { it.startsWith("/") }
                ?: return@mapNotNull null
            val fileName = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val sizeBytes = parts.getOrNull(3)?.toLongOrNull()?.takeIf { it >= 0L }
                ?: return@mapNotNull null
            RootPluginCacheFile(fileName, sizeBytes, legacy, absolutePath)
        }
        .take(128)
        .toList()

    private suspend fun execRootScript(
        cmd: String,
        inputScript: String? = null
    ): RootCommandResult? {
        return withContext(Dispatchers.IO) {
            var process: Process? = null
            var os: DataOutputStream? = null
            try {
                process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                if (inputScript != null) {
                    os = DataOutputStream(process.outputStream)
                    os.write(inputScript.toByteArray(Charsets.UTF_8))
                    os.writeBytes("\nexit\n")
                    os.flush()
                }
                val standardOutput = process.inputStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()
                return@withContext RootCommandResult(exitCode, standardOutput)
            } catch (_: Exception) {
                return@withContext null
            } finally {
                try {
                    os?.close()
                } catch (_: Exception) {
                }
                process?.destroy()
            }
        }
    }

}
