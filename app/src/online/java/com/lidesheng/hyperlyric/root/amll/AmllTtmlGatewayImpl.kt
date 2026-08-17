package com.lidesheng.hyperlyric.root.amll

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.lyric.model.LyricMediaMetadata
import com.lidesheng.hyperlyric.lyric.model.Song
import com.lidesheng.hyperlyric.online.amll.AmllTtmlClient
import com.lidesheng.hyperlyric.online.amll.TtmlCacheManager
import com.lidesheng.hyperlyric.online.amll.TtmlParser
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.utils.HookLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * AMLL TTML 增强层 Online 实现
 *
 * 主歌词源 onSongChanged / onMetadata 推送后，优先用平台 songId 精确匹配 AMLL 词库，
 * 未命中回退 title/artist/album 模糊搜索；命中后解析 TTML 输出 [Song] 供调用方替换渲染。
 */
class AmllTtmlGatewayImpl : AmllTtmlGateway.Impl {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeJob: Job? = null

    @Volatile
    private var appContext: Context? = null

    private companion object {
        const val TAG = "AmllTtmlSource"
    }

    init {
        AmllTtmlGateway.register(this)
    }

    override fun init(context: Context) {
        appContext = context.applicationContext
    }

    override fun fetchTtml(
        song: Song,
        metadata: LyricMediaMetadata?,
        prefs: SharedPreferences,
        onResult: (Song?) -> Unit
    ): Boolean {
        val enabled = prefs.getBoolean(
            RootConstants.KEY_HOOK_AMLL_TTML_ENABLED,
            RootConstants.DEFAULT_HOOK_AMLL_TTML_ENABLED
        )
        if (!enabled) {
            HookLogger.d(TAG, "AMLL 未启用，跳过请求: song=${song.name}")
            return false
        }

        val context = appContext
        if (context == null) {
            HookLogger.d(TAG, "AMLL 未初始化，跳过请求: song=${song.name}")
            return false
        }

        val version = LyriconDataBridge.versionCounter.get()
        val platform = AmllPlatformIdMapper.mapPackageNameToAmllField(metadata?.packageName)
        val songId = metadata?.songId?.takeIf { it.isNotBlank() }
            ?: song.id?.takeIf { it.isNotBlank() }

        HookLogger.d(
            TAG,
            "AMLL 开始请求: song=${song.name}, platform=${platform?.name ?: "unknown"}, " +
                    "songIdPresent=${!songId.isNullOrBlank()}, " +
                    "search=[title=${metadata?.title ?: song.name}, " +
                    "artist=${metadata?.artist ?: song.artist}, album=${metadata?.album}]"
        )

        activeJob?.cancel()
        activeJob = scope.launch {
            val result = try {
                fetchTtmlInternal(
                    context = context,
                    song = song,
                    platform = platform,
                    songId = songId,
                    title = metadata?.title?.takeIf { it.isNotBlank() }
                        ?: song.name?.takeIf { it.isNotBlank() },
                    artist = metadata?.artist?.takeIf { it.isNotBlank() }
                        ?: song.artist?.takeIf { it.isNotBlank() },
                    album = metadata?.album?.takeIf { it.isNotBlank() }
                        ?: LyriconDataBridge.currentLyricMediaMetadata?.album?.takeIf { it.isNotBlank() }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (version != LyriconDataBridge.versionCounter.get()) {
                HookLogger.d(TAG, "AMLL 请求过期丢弃: reason=song_changed, song=${song.name}")
                return@launch
            }
            mainHandler.post { onResult(result) }
        }
        return true
    }

    private suspend fun fetchTtmlInternal(
        context: Context,
        song: Song,
        platform: AmllPlatformIdField?,
        songId: String?,
        title: String?,
        artist: String?,
        album: String?
    ): Song? {
        // 1. 精确匹配：songId 非空且包名可映射平台
        if (!songId.isNullOrBlank() && platform != null) {
            val cacheKey = TtmlCacheManager.exactCacheKey(platform.name.lowercase(), songId)
            val cachedTtml = TtmlCacheManager.getTtmlFromCache(context, cacheKey)
            if (cachedTtml != null) {
                return buildSong(song, cachedTtml, fromCache = true)
            }
            val item = AmllTtmlClient.fetchByPlatformId(platform, songId)
            val ttml = item?.lyrics
            if (!ttml.isNullOrBlank()) {
                HookLogger.d(TAG, "AMLL 精确命中: id=${item.id}, size=${ttml.toByteArray().size}B")
                TtmlCacheManager.saveTtmlToCache(context, cacheKey, ttml)
                return buildSong(song, ttml, fromCache = false)
            }
            HookLogger.d(TAG, "AMLL 精确未命中: platform=${platform.name}, songId=$songId")
        }

        // 2. 回退搜索：title/artist/album 模糊匹配
        if (title == null && artist == null) {
            HookLogger.d(TAG, "AMLL 搜索未触发: reason=no_title_artist")
            return null
        }

        val searchKey = TtmlCacheManager.searchCacheKey(title.orEmpty(), artist.orEmpty())
        val cachedSearchTtml = TtmlCacheManager.getTtmlFromCache(context, searchKey)
        if (cachedSearchTtml != null) {
            return buildSong(song, cachedSearchTtml, fromCache = true)
        }

        val searchItem = AmllTtmlClient.searchByMetadata(title, artist, album) ?: return null
        HookLogger.d(
            TAG,
            "AMLL 搜索命中: id=${searchItem.id}, " +
                    "music=${searchItem.musicNames?.joinToString("/")}, " +
                    "artist=${searchItem.artistNames?.joinToString("/")}"
        )
        val fullItem = searchItem.id?.let { AmllTtmlClient.fetchById(it) }
        val ttml = fullItem?.lyrics
        if (ttml.isNullOrBlank()) return null
        HookLogger.d(TAG, "AMLL 搜索回退命中: size=${ttml.toByteArray().size}B")
        TtmlCacheManager.saveTtmlToCache(context, searchKey, ttml)
        return buildSong(song, ttml, fromCache = false)
    }

    /**
     * 解析 TTML 并构造 [Song]：name/artist 保留主歌词源的值，仅 lyrics 使用 AMLL 结果。
     */
    private fun buildSong(localSong: Song, ttml: String, fromCache: Boolean): Song? {
        val lines = TtmlParser.parse(ttml)
        if (lines == null) {
            HookLogger.d(TAG, "AMLL 解析失败: fromCache=$fromCache")
            return null
        }
        return Song(
            id = localSong.id,
            name = localSong.name,
            artist = localSong.artist,
            duration = localSong.duration,
            metadata = localSong.metadata,
            lyrics = lines
        ).normalize()
    }

    override fun cancelActiveRequests() {
        activeJob?.cancel()
        activeJob = null
    }

    override fun clearCache(callback: (() -> Unit)?) {
        val context = appContext ?: return
        scope.launch {
            TtmlCacheManager.clearCache(context)
            callback?.let { mainHandler.post(it) }
        }
    }
}
