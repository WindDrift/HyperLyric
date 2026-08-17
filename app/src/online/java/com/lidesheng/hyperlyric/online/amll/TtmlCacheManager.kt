package com.lidesheng.hyperlyric.online.amll

import android.content.Context
import com.lidesheng.hyperlyric.root.utils.HookLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AMLL TTML 本地缓存管理器
 *
 * - 缓存目录：`context.externalCacheDir/amll_ttml/`（兜底 `context.cacheDir/amll_ttml/`）
 * - 缓存 key：精确命中 `{platform}_{songId}.ttml`（如 `ncm_3325283031.ttml`）；
 *   搜索命中 `search_{normalizedTitle}_{normalizedArtist}.ttml`
 * - 缓存不过期（AMLL 官方承诺 id/filename 检索结果永久不变），由用户手动清理
 */
object TtmlCacheManager {

    private const val TAG = "AmllTtmlSource"
    private const val CACHE_DIR_NAME = "amll_ttml"
    private const val CACHE_FILE_EXTENSION = ".ttml"

    private fun getCacheDir(context: Context): File {
        val baseDir = context.externalCacheDir ?: context.cacheDir
        val dir = File(baseDir, CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_").trim()

    /** 构造精确命中（平台 ID / AMLL id）的缓存 key */
    fun exactCacheKey(platform: String, songId: String): String =
        "${sanitizeFileName(platform)}_${sanitizeFileName(songId)}$CACHE_FILE_EXTENSION"

    /** 构造搜索命中的缓存 key */
    fun searchCacheKey(title: String, artist: String): String =
        "search_${sanitizeFileName(title)}_${sanitizeFileName(artist)}$CACHE_FILE_EXTENSION"

    /** 读取缓存的 TTML；未命中返回 null */
    suspend fun getTtmlFromCache(context: Context, cacheKey: String): String? =
        withContext(Dispatchers.IO) {
            val file = File(getCacheDir(context), cacheKey)
            if (!file.exists() || !file.isFile) return@withContext null
            val content = try {
                file.readText()
            } catch (e: Exception) {
                HookLogger.d(TAG, "TTML 缓存读取失败: key=$cacheKey, type=${e.javaClass.simpleName}")
                return@withContext null
            }
            HookLogger.d(
                TAG,
                "TTML 缓存命中: key=$cacheKey, size=${content.toByteArray().size}B"
            )
            content
        }

    /** 持久化 TTML 到缓存 */
    suspend fun saveTtmlToCache(context: Context, cacheKey: String, ttml: String) {
        withContext(Dispatchers.IO) {
            try {
                val file = File(getCacheDir(context), cacheKey)
                file.writeText(ttml)
                HookLogger.d(
                    TAG,
                    "TTML 缓存已保存: key=$cacheKey, size=${ttml.toByteArray().size}B"
                )
            } catch (e: Exception) {
                HookLogger.d(TAG, "TTML 缓存保存失败: key=$cacheKey, type=${e.javaClass.simpleName}")
            }
        }
    }

    /** 删除整个缓存目录 */
    fun clearCache(context: Context) {
        val dir = File(context.externalCacheDir ?: context.cacheDir, CACHE_DIR_NAME)
        if (!dir.exists()) return
        val deleted = dir.deleteRecursively()
        HookLogger.d(TAG, "TTML 缓存已清理: path=${dir.absolutePath}, success=$deleted")
    }
}
