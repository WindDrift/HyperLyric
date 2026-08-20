package com.lidesheng.hyperlyric.plugin.ai.translation

import com.lidesheng.hyperlyric.plugin.api.PluginLogger
import com.lidesheng.hyperlyric.plugin.api.PluginStorage
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/** Memory LRU plus a bounded JSON index backed only by the plugin's string storage. */
internal class TranslationCache(
    private val storage: PluginStorage,
    private val logger: PluginLogger,
) {
    private companion object {
        const val MAX_ENTRIES = 1_000
        const val INDEX_KEY = "cache.index.v1"
        const val ENTRY_PREFIX = "cache.entry."
    }

    private val lock = Any()
    private val memory: MutableMap<String, List<TranslationItem>> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, List<TranslationItem>>(MAX_ENTRIES, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, List<TranslationItem>>?
                ): Boolean = size > MAX_ENTRIES
            }
        )

    fun get(key: String): CacheLookup? = synchronized(lock) {
        memory[key]?.let { return@synchronized CacheLookup(it, fromMemory = true) }

        val index = readIndexLocked()
        if (key !in index) return@synchronized null

        val raw = runCatching { storage.getString(entryKey(key)) }.getOrElse {
            logger.error("查询翻译缓存失败", it)
            return@synchronized null
        } ?: return@synchronized null
        val items = decode(raw)
        if (items.isNullOrEmpty()) {
            logger.error("查询翻译缓存失败")
            return@synchronized null
        }

        memory[key] = items
        touchIndexLocked(index, key)
        CacheLookup(items, fromMemory = false)
    }

    fun put(key: String, items: List<TranslationItem>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            val encoded = encode(items)
            runCatching { storage.putString(entryKey(key), encoded) }.onFailure {
                logger.error("写入翻译缓存失败", it)
                return
            }

            memory[key] = items
            val index = readIndexLocked().toMutableList()
            index.remove(key)
            index.add(0, key)
            while (index.size > MAX_ENTRIES) {
                val removed = index.removeAt(index.lastIndex)
                memory.remove(removed)
                runCatching { storage.remove(entryKey(removed)) }.onFailure {
                    logger.error("写入翻译缓存失败", it)
                }
            }
            runCatching { storage.putString(INDEX_KEY, encodeIndex(index)) }.onFailure {
                logger.error("写入翻译缓存失败", it)
            }
        }
    }

    private fun touchIndexLocked(index: List<String>, key: String) {
        val updated = index.toMutableList().apply {
            remove(key)
            add(0, key)
        }
        runCatching { storage.putString(INDEX_KEY, encodeIndex(updated)) }.onFailure {
            logger.error("写入翻译缓存失败", it)
        }
    }

    private fun readIndexLocked(): List<String> {
        val raw = runCatching { storage.getString(INDEX_KEY) }.getOrElse {
            logger.error("查询翻译缓存失败", it)
            return emptyList()
        } ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val key = array.optString(index, "")
                    if (key.matches(KEY_PATTERN)) add(key)
                }
            }.distinct().take(MAX_ENTRIES)
        }.getOrElse {
            logger.error("查询翻译缓存失败", it)
            emptyList()
        }
    }

    private fun encode(items: List<TranslationItem>): String = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().put("index", item.index).put("trans", item.trans))
        }
    }.toString()

    private fun decode(raw: String): List<TranslationItem>? = runCatching {
        val array = JSONArray(raw)
        buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (!item.has("index") || !item.has("trans")) continue
                val text = item.optString("trans", "").trim()
                if (text.isNotBlank()) {
                    add(TranslationItem(item.optInt("index", Int.MIN_VALUE), text))
                }
            }
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun encodeIndex(keys: List<String>): String = JSONArray(keys).toString()

    private fun entryKey(key: String): String = ENTRY_PREFIX + key

    private val KEY_PATTERN = Regex("[0-9a-f]{32}")

    data class CacheLookup(
        val items: List<TranslationItem>,
        val fromMemory: Boolean,
    )
}
