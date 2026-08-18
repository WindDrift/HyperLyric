package com.lidesheng.hyperlyric.online.amll

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.lidesheng.hyperlyric.root.amll.AmllPlatformIdField
import com.lidesheng.hyperlyric.root.utils.HookLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.await
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AMLL TTML DataBase 网络客户端
 *
 * - 独立 OkHttp 实例（连接超时 5s、读取超时 8s），不污染 [com.lidesheng.hyperlyric.online.LyricApiProvider] 的共享客户端
 * - HTTP 429/5xx 指数退避重试：初始 1s、倍率 2、最多 3 次（1s/2s/4s）
 * - 网络异常（SocketTimeoutException/UnknownHostException/IOException）不重试，直接返回 null
 */
object AmllTtmlClient {

    private const val TAG = "AmllTtmlSource"
    private const val BASE_URL = "https://api.amll.dev/"
    private const val INITIAL_RETRY_DELAY_MS = 1000L
    private const val RETRY_BACKOFF_MULTIPLIER = 2L
    private const val MAX_RETRIES = 3

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val api by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AmllTtmlApi::class.java)
    }

    /**
     * 按平台 ID 精确获取歌词（如网易云 ncmMusicId）。
     *
     * @return 命中且 lyrics 非空时返回 [SongItem]；未命中/空 lyrics/失败返回 null
     */
    suspend fun fetchByPlatformId(field: AmllPlatformIdField, songId: String): SongItem? {
        val response = executeWithRetry("platform_${field.name}") {
            when (field) {
                AmllPlatformIdField.NCM -> api.getLyric(
                    id = null, ncmMusicId = songId, qqMusicId = null,
                    appleMusicId = null, spotifyId = null
                ).await()
                AmllPlatformIdField.QQ -> api.getLyric(
                    id = null, ncmMusicId = null, qqMusicId = songId,
                    appleMusicId = null, spotifyId = null
                ).await()
                AmllPlatformIdField.APPLE -> api.getLyric(
                    id = null, ncmMusicId = null, qqMusicId = null,
                    appleMusicId = songId, spotifyId = null
                ).await()
                AmllPlatformIdField.SPOTIFY -> api.getLyric(
                    id = null, ncmMusicId = null, qqMusicId = null,
                    appleMusicId = null, spotifyId = songId
                ).await()
            }
        } ?: return null
        return extractWithLyrics(response.data)
    }

    /**
     * 按 AMLL 内部 id 精确获取歌词（search 回退路径使用）。
     *
     * @return 命中且 lyrics 非空时返回 [SongItem]；未命中/空 lyrics/失败返回 null
     */
    suspend fun fetchById(id: Long): SongItem? {
        val response = executeWithRetry("id_$id") {
            api.getLyric(
                id = id, ncmMusicId = null, qqMusicId = null,
                appleMusicId = null, spotifyId = null
            ).await()
        } ?: return null
        return extractWithLyrics(response.data)
    }

    /**
     * 按歌名/歌手/专辑模糊搜索，返回最佳匹配条目。空字段不传，由 AMLL 服务端按 AND 交集匹配。
     * 服务端排序不保证语义一致（翻唱/Live/串烧可能排在原版之前），而命中结果会被永久缓存，
     * 因此客户端对返回条目做 title/artist 校验，仅接受可交叉验证的条目。
     *
     * @return 首个通过客户端校验的条目（不含 lyrics）；无结果/校验失败返回 null
     */
    suspend fun searchByMetadata(title: String?, artist: String?, album: String?): SongItem? {
        val musicName = title?.takeIf { it.isNotBlank() }
        val artistName = artist?.takeIf { it.isNotBlank() }
        val albumName = album?.takeIf { it.isNotBlank() }
        if (musicName == null && artistName == null && albumName == null) {
            HookLogger.d(TAG, "AMLL 搜索未命中: reason=no_search_params")
            return null
        }
        val response = executeWithRetry("search") {
            api.searchLyrics(
                musicName = musicName,
                artistName = artistName,
                albumName = albumName
            ).await()
        } ?: return null
        val items = response.data?.items.orEmpty()
        val item = items.firstOrNull { isPlausibleMatch(it, musicName, artistName) }
        if (item == null) {
            HookLogger.d(
                TAG,
                "AMLL 搜索未命中: reason=${if (items.isEmpty()) "empty_items" else "no_plausible_match"}, " +
                        "total=${items.size}, first=${items.firstOrNull()?.musicNames?.joinToString("/") ?: "-"}"
            )
            return null
        }
        return item
    }

    /**
     * 客户端搜索结果校验：请求携带的 title/artist 须与条目交叉匹配（忽略大小写与多余空白），
     * 提供了哪个参数就校验哪个；条目缺失对应字段时该校验不通过。
     * - title：任一 musicNames 与 title 互为包含
     * - artist：按常见分隔符拆分后，任一 token 对互为包含
     */
    private fun isPlausibleMatch(item: SongItem, title: String?, artist: String?): Boolean {
        if (title != null) {
            val names = item.musicNames.orEmpty()
            if (names.isEmpty() || names.none { fuzzyContains(it, title) }) return false
        }
        if (artist != null) {
            val requestTokens = splitArtistTokens(artist)
            val candidateTokens = item.artistNames.orEmpty().flatMap { splitArtistTokens(it) }
            if (requestTokens.isEmpty() || candidateTokens.isEmpty() ||
                requestTokens.none { request -> candidateTokens.any { fuzzyContains(it, request) } }
            ) return false
        }
        return true
    }

    /** 归一化（小写 + 压缩空白）后的双向包含匹配：任一方包含另一方即视为匹配 */
    private fun fuzzyContains(a: String, b: String): Boolean {
        val na = a.trim().lowercase().replace(Regex("\\s+"), " ")
        val nb = b.trim().lowercase().replace(Regex("\\s+"), " ")
        if (na.isEmpty() || nb.isEmpty()) return false
        return na.contains(nb) || nb.contains(na)
    }

    /** 按常见艺人分隔符拆分（/ 、 ， , & ; ；），去除空 token */
    private fun splitArtistTokens(value: String): List<String> =
        value.split('/', '、', ',', '，', '&', ';', '；')
            .mapNotNull { it.trim().takeIf { token -> token.isNotEmpty() } }

    /**
     * 提取携带非空 lyrics 的条目；status=200 但 lyrics 为空字符串/null 视为未命中。
     */
    private fun extractWithLyrics(item: SongItem?): SongItem? {
        if (item == null || item.lyrics.isNullOrBlank()) {
            HookLogger.d(TAG, "AMLL 精确未命中: reason=empty_lyrics")
            return null
        }
        return item
    }

    /**
     * 带指数退避的请求执行器：
     * - HTTP 429/5xx → 重试（1s/2s/4s，最多 3 次）
     * - IOException（含超时/断网）→ 不重试
     * - 其余 HTTP 错误 → 不重试
     */
    private suspend fun <T> executeWithRetry(requestLabel: String, request: suspend () -> T): T? {
        var retryDelay = INITIAL_RETRY_DELAY_MS
        var attempt = 0
        while (true) {
            try {
                return request()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                HookLogger.d(TAG, "AMLL 网络异常: type=${e.javaClass.simpleName}, request=$requestLabel")
                return null
            } catch (e: HttpException) {
                val code = e.code()
                val retryable = code == 429 || code in 500..599
                if (!retryable || attempt >= MAX_RETRIES) {
                    HookLogger.d(
                        TAG,
                        "AMLL 抓取失败: reason=http_$code, retries=$attempt, request=$requestLabel"
                    )
                    return null
                }
                attempt++
                HookLogger.d(
                    TAG,
                    "AMLL 限流/服务错误: code=$code, retry=$attempt/$MAX_RETRIES, " +
                            "delay=${retryDelay}ms, request=$requestLabel"
                )
                delay(retryDelay)
                retryDelay *= RETRY_BACKOFF_MULTIPLIER
            }
        }
    }
}
