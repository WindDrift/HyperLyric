package com.lidesheng.hyperlyric.plugin.amll.ttml

import com.lidesheng.hyperlyric.plugin.api.PluginCache
import com.lidesheng.hyperlyric.plugin.api.PluginLogger
import org.json.JSONArray
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

/**
 * TTML 缓存（参照 AI 翻译插件 TranslationCache 的成熟模式）
 *
 * - 语义 key（可读，用于日志）经 SHA-256 映射为物理 key，规避宿主 PluginCache
 *   256 字符 key 上限（title/artist 可能超长）；
 * - 内存 LRU + 持久索引（JSON 数组，最近使用在前），容量 200 条，LRU 淘汰含存储删除；
 * - schema 版本进语义 key（v1），解析逻辑升级时递增即可整体失效；
 * - 永不过期（AMLL 官方承诺 id 检索结果永久不变，对齐 main 分支语义）；
 *   未命中不缓存（负缓存会导致 AMLL 库新增条目后永远搜不到）；
 * - 损坏自愈：索引/条目解析失败 → 删除对应条目并回退网络路径；
 * - 值为 TTML 原文字符串；超过宿主单值上限（2MB）的异常大 TTML 放弃缓存仅本次使用。
 */
internal class TtmlCache(
    private val storage: PluginCache,
    private val logger: PluginLogger,
) {
    companion object {
        private const val MAX_ENTRIES = 200

        /** 宿主 PluginCache 单值上限（SharedPreferencesPluginCache.MAX_VALUE_BYTES） */
        private const val MAX_VALUE_BYTES = 2 * 1024 * 1024

        private const val INDEX_KEY = "cache.index.v1"
        private const val ENTRY_PREFIX = "cache.entry.v1."
        private val KEY_PATTERN = Regex("[0-9a-f]{64}")

        /** 精确命中（平台探测）的语义 key */
        fun exactKey(platform: AmllPlatformIdField, songId: String): String =
            "amll.ttml.v1|exact|${platform.name}|$songId"

        /** 搜索命中的语义 key（title/artist 归一化保证同曲稳定） */
        fun searchKey(title: String, artist: String): String {
            val normalizedTitle = title.trim().replace(Regex("\\s+"), " ")
            val normalizedArtist = artist.trim().replace(Regex("\\s+"), " ")
            return "amll.ttml.v1|search|$normalizedTitle|$normalizedArtist"
        }

        /** 平台探测结果（songId → 命中平台名）的语义 key：二次播放跳过逐平台探测 */
        fun resolveKey(songId: String): String = "amll.ttml.v1|resolve|$songId"
    }

    private val lock = Any()

    /** 内存 LRU：物理 key → TTML 原文（物理 key 与持久索引共用同一标识） */
    private val memory: MutableMap<String, String> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(16, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, String>?
                ): Boolean = size > MAX_ENTRIES
            }
        )

    data class CacheLookup(
        val ttml: String,
        val fromMemory: Boolean,
    )

    /** 语义 key → 物理 key（SHA-256 十六进制，64 字符） */
    fun physicalKeyOf(semanticKey: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(semanticKey.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    fun get(semanticKey: String): CacheLookup? = synchronized(lock) {
        val physicalKey = physicalKeyOf(semanticKey)
        memory[physicalKey]?.let { return@synchronized CacheLookup(it, fromMemory = true) }

        val index = readIndexLocked()
        if (physicalKey !in index) return@synchronized null

        val raw = runCatching { storage.getString(entryKey(physicalKey)) }.getOrElse {
            logger.warn("查询 TTML 缓存失败: key=$semanticKey", it)
            return@synchronized null
        } ?: run {
            // 索引命中但条目缺失：损坏条目，自愈删除
            removeCorruptEntryLocked(index, physicalKey)
            return@synchronized null
        }

        memory[physicalKey] = raw
        touchIndexLocked(index, physicalKey)
        CacheLookup(raw, fromMemory = false)
    }

    fun put(semanticKey: String, ttml: String) {
        if (ttml.isEmpty()) return
        val bytes = ttml.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_VALUE_BYTES) {
            logger.warn("TTML 超过缓存单值上限，放弃缓存: size=${bytes.size}B", null)
            return
        }
        val physicalKey = physicalKeyOf(semanticKey)
        synchronized(lock) {
            runCatching { storage.putString(entryKey(physicalKey), ttml) }.onFailure {
                logger.warn("写入 TTML 缓存失败: key=$semanticKey", it)
                return
            }

            memory[physicalKey] = ttml
            val index = readIndexLocked().toMutableList()
            index.remove(physicalKey)
            index.add(0, physicalKey)
            while (index.size > MAX_ENTRIES) {
                val removed = index.removeAt(index.lastIndex)
                memory.remove(removed)
                runCatching { storage.remove(entryKey(removed)) }.onFailure {
                    logger.warn("删除 TTML 缓存失败: key=$removed", it)
                }
            }
            runCatching { storage.putString(INDEX_KEY, encodeIndex(index)) }.onFailure {
                logger.warn("写入 TTML 缓存索引失败", it)
            }
        }
    }

    fun remove(semanticKey: String) = synchronized(lock) {
        val physicalKey = physicalKeyOf(semanticKey)
        memory.remove(physicalKey)
        removeCorruptEntryLocked(readIndexLocked(), physicalKey)
    }

    private fun touchIndexLocked(index: List<String>, key: String) {
        val updated = index.toMutableList().apply {
            remove(key)
            add(0, key)
        }
        runCatching { storage.putString(INDEX_KEY, encodeIndex(updated)) }.onFailure {
            logger.warn("更新 TTML 缓存索引失败", it)
        }
    }

    private fun readIndexLocked(): List<String> {
        val raw = runCatching { storage.getString(INDEX_KEY) }.getOrElse {
            logger.warn("查询 TTML 缓存索引失败", it)
            return emptyList()
        } ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val key = array.optString(index, "")
                    if (KEY_PATTERN.matches(key)) add(key)
                }
            }.distinct().take(MAX_ENTRIES)
        }.getOrElse {
            // 索引损坏：清空重建（条目成为孤儿，LRU 淘汰/自愈路径最终清理）
            logger.warn("TTML 缓存索引损坏，重建索引", it)
            runCatching { storage.remove(INDEX_KEY) }
            emptyList()
        }
    }

    private fun removeCorruptEntryLocked(index: List<String>, key: String) {
        val updated = index.filterNot { it == key }
        runCatching { storage.remove(entryKey(key)) }.onFailure {
            logger.warn("删除 TTML 缓存失败: key=$key", it)
        }
        if (updated != index) {
            runCatching { storage.putString(INDEX_KEY, encodeIndex(updated)) }.onFailure {
                logger.warn("更新 TTML 缓存索引失败", it)
            }
        }
    }

    private fun encodeIndex(keys: List<String>): String = JSONArray(keys).toString()

    private fun entryKey(physicalKey: String): String = ENTRY_PREFIX + physicalKey
}
