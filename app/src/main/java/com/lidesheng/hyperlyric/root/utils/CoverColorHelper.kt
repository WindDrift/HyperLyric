package com.lidesheng.hyperlyric.root.utils

import android.graphics.Bitmap
import com.lidesheng.hyperlyric.common.color.ColorExtractor
import kotlin.math.abs

object CoverColorHelper {

    /**
     * 歌词源确认的颜色会话。revision 用于隔离同一歌曲键在不同播放会话中的迟到回调，
     * mediaKey 则用于跨暂停/恢复与再次播放复用已经确认的调色板。
     */
    class ColorSession internal constructor(
        val revision: Long,
        val mediaKey: String,
        internal val packageName: String,
        internal val title: String,
        internal val artist: String
    )

    class ArtworkRequest internal constructor(
        val colorSession: ColorSession,
        val revision: Long,
        internal val fingerprint: ArtworkFingerprint
    )

    private data class CacheEntry(
        val artworkFingerprint: ArtworkFingerprint,
        val colors: Pair<IntArray, IntArray>
    )

    internal class ArtworkFingerprint(
        val pixels: IntArray
    ) {
        fun isSimilarTo(other: ArtworkFingerprint): Boolean {
            if (pixels.size != other.pixels.size || pixels.isEmpty()) return false
            var totalDelta = 0L
            pixels.indices.forEach { index ->
                val first = pixels[index]
                val second = other.pixels[index]
                totalDelta += abs((first ushr 16 and 0xFF) - (second ushr 16 and 0xFF))
                totalDelta += abs((first ushr 8 and 0xFF) - (second ushr 8 and 0xFF))
                totalDelta += abs((first and 0xFF) - (second and 0xFF))
            }
            return totalDelta <= pixels.size * RGB_CHANNEL_COUNT * MAX_AVERAGE_CHANNEL_DELTA
        }
    }

    private var sessionRevision = 0L
    private var artworkRevision = 0L
    private var activeSession: ColorSession? = null
    private var activeArtworkRequest: ArtworkRequest? = null
    private val keyedCache = LinkedHashMap<String, CacheEntry>()

    /**
     * 只有歌词源生命周期可以推进当前颜色会话。SystemUI 的封面、进度和动画回调
     * 都只能读取此状态，避免迟到的上一首歌回调把活动歌曲切回去。
     */
    @Synchronized
    fun activateSession(
        packageName: String,
        title: String,
        artist: String,
        album: String = "",
        songId: String? = null
    ): ColorSession? {
        val normalizedPackage = packageName.normalizeMediaText()
        val normalizedTitle = title.normalizeMediaText()
        val normalizedArtist = artist.normalizeMediaText()
        val normalizedSongId = songId
            ?.normalizeMediaText()
            ?.takeIf { it.isNotEmpty() && it != "0" }
        if (normalizedPackage.isEmpty() ||
            (normalizedSongId == null && normalizedTitle.isEmpty() && normalizedArtist.isEmpty())
        ) {
            return null
        }

        val mediaKey = buildMediaKey(
            packageName = normalizedPackage,
            title = normalizedTitle,
            artist = normalizedArtist,
            album = album.normalizeMediaText(),
            songId = normalizedSongId
        )
        val current = activeSession
        if (current?.mediaKey == mediaKey) {
            val updated = ColorSession(
                revision = current.revision,
                mediaKey = current.mediaKey,
                packageName = normalizedPackage,
                title = normalizedTitle.ifEmpty { current.title },
                artist = normalizedArtist.ifEmpty { current.artist }
            )
            activeSession = updated
            return updated
        }

        return ColorSession(
            revision = ++sessionRevision,
            mediaKey = mediaKey,
            packageName = normalizedPackage,
            title = normalizedTitle,
            artist = normalizedArtist
        ).also {
            activeSession = it
            activeArtworkRequest = null
        }
    }

    @Synchronized
    fun endSession(): Boolean {
        if (activeSession == null) return false
        activeSession = null
        activeArtworkRequest = null
        sessionRevision++
        return true
    }

    @Synchronized
    fun currentSession(packageName: String? = null): ColorSession? {
        val current = activeSession ?: return null
        if (packageName == null) return current
        return current.takeIf {
            it.packageName == packageName.normalizeMediaText()
        }
    }

    @Synchronized
    fun isCurrentSession(session: ColorSession): Boolean {
        return isCurrentSessionLocked(session)
    }

    /**
     * 将系统媒体元数据与歌词源确认的歌曲进行匹配。歌词标题已知时，系统标题为空
     * 也视为尚未就绪；宁可暂时使用默认色，也不能把上一首歌的封面写入当前缓存。
     */
    fun resolveArtworkRequest(
        packageName: String,
        title: String,
        artist: String,
        bitmap: Bitmap
    ): ArtworkRequest? {
        if (bitmap.isRecycled) return null
        val fingerprint = bitmapFingerprint(bitmap)
        return synchronized(this) {
            val current = activeSession ?: return@synchronized null
            if (!matchesArtworkMetadataLocked(current, packageName, title, artist)) {
                return@synchronized null
            }

            val activeRequest = activeArtworkRequest
            if (activeRequest != null &&
                isCurrentSessionLocked(activeRequest.colorSession) &&
                activeRequest.fingerprint.isSimilarTo(fingerprint)
            ) {
                return@synchronized activeRequest
            }

            ArtworkRequest(
                colorSession = current,
                revision = ++artworkRevision,
                fingerprint = fingerprint
            ).also { activeArtworkRequest = it }
        }
    }

    @Synchronized
    fun isCurrentArtwork(request: ArtworkRequest): Boolean {
        return isCurrentArtworkLocked(request)
    }

    @Synchronized
    fun currentArtworkRequest(): ArtworkRequest? {
        return activeArtworkRequest?.takeIf(::isCurrentArtworkLocked)
    }

    @Synchronized
    fun matchesCurrentArtworkMetadata(
        request: ArtworkRequest,
        packageName: String,
        title: String,
        artist: String
    ): Boolean {
        return isCurrentArtworkLocked(request) &&
                matchesArtworkMetadataLocked(
                    request.colorSession,
                    packageName,
                    title,
                    artist
                )
    }

    private fun matchesArtworkMetadataLocked(
        current: ColorSession,
        packageName: String,
        title: String,
        artist: String
    ): Boolean {
        if (current.packageName != packageName.normalizeMediaText()) return false

        val mediaTitle = title.normalizeMediaText()
        val mediaArtist = artist.normalizeMediaText()
        if (current.title.isNotEmpty()) {
            if (mediaTitle.isEmpty() || !isCompatibleTitle(current.title, mediaTitle)) {
                return false
            }
        } else {
            if (current.artist.isEmpty() ||
                mediaArtist.isEmpty() ||
                !isCompatibleArtist(current.artist, mediaArtist)
            ) {
                return false
            }
        }
        if (current.artist.isNotEmpty() &&
            mediaArtist.isNotEmpty() &&
            !isCompatibleArtist(current.artist, mediaArtist)
        ) {
            return false
        }
        return true
    }

    /**
     * 同一歌曲与同一封面只生成一次调色板。使用归一化内容指纹而不是 Bitmap
     * 实例或 generationId，暂停/恢复得到新 Bitmap 时仍可复用；若过渡期封面
     * 随后被真实封面替换，则允许纠正该歌曲的缓存。
     */
    fun extractColors(
        bitmap: Bitmap,
        useGradient: Boolean,
        request: ArtworkRequest
    ): Pair<IntArray, IntArray>? {
        if (bitmap.isRecycled) return null
        val cachedColors = synchronized(this) {
            if (!isCurrentArtworkLocked(request)) return@synchronized null
            keyedCache[request.colorSession.mediaKey]
                ?.takeIf {
                    it.artworkFingerprint.isSimilarTo(request.fingerprint)
                }
                ?.colors
        }
        if (cachedColors != null) return cachedColors.forGradient(useGradient)

        val readableBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            bitmap
        }
        val result = try {
            ColorExtractor.extractThemePalette(readableBitmap, MAX_PALETTE_COLORS)
        } finally {
            if (readableBitmap !== bitmap) readableBitmap.recycle()
        }
        val extractedColors = Pair(
            result.onWhiteBackground.toIntArray(),
            result.onBlackBackground.toIntArray()
        )
        val colors = synchronized(this) {
            if (!isCurrentArtworkLocked(request)) return@synchronized null
            val latest = keyedCache[request.colorSession.mediaKey]
            if (latest?.artworkFingerprint?.isSimilarTo(request.fingerprint) == true) {
                latest.colors
            } else {
                extractedColors.also {
                    keyedCache[request.colorSession.mediaKey] = CacheEntry(
                        request.fingerprint,
                        it
                    )
                    trimCache()
                }
            }
        }
        return colors?.forGradient(useGradient)
    }

    @Synchronized
    fun getCachedColors(
        useGradient: Boolean,
        session: ColorSession
    ): Pair<IntArray, IntArray>? {
        if (!isCurrentSessionLocked(session)) return null
        return keyedCache[session.mediaKey]?.colors?.forGradient(useGradient)
    }

    @Synchronized
    fun getCachedColors(
        useGradient: Boolean,
        request: ArtworkRequest
    ): Pair<IntArray, IntArray>? {
        if (!isCurrentArtworkLocked(request)) return null
        return keyedCache[request.colorSession.mediaKey]
            ?.takeIf {
                it.artworkFingerprint.isSimilarTo(request.fingerprint)
            }
            ?.colors
            ?.forGradient(useGradient)
    }

    @Synchronized
    fun clearCache() {
        activeSession = null
        activeArtworkRequest = null
        sessionRevision++
        keyedCache.clear()
    }

    private fun isCurrentSessionLocked(session: ColorSession): Boolean {
        val current = activeSession ?: return false
        return current.revision == session.revision && current.mediaKey == session.mediaKey
    }

    private fun isCurrentArtworkLocked(request: ArtworkRequest): Boolean {
        val current = activeArtworkRequest ?: return false
        return isCurrentSessionLocked(request.colorSession) &&
                current.revision == request.revision &&
                current.colorSession.revision == request.colorSession.revision &&
                current.colorSession.mediaKey == request.colorSession.mediaKey
    }

    private fun buildMediaKey(
        packageName: String,
        title: String,
        artist: String,
        album: String,
        songId: String?
    ): String {
        val identity = songId?.let { "id:$it" } ?: listOf(
            "meta",
            title,
            artist,
            album.takeIf { title.isEmpty() && artist.isEmpty() }.orEmpty()
        ).joinToString("\u001F")
        return "$packageName\u001F$identity"
    }

    private fun Pair<IntArray, IntArray>.forGradient(
        useGradient: Boolean
    ): Pair<IntArray, IntArray> {
        if (useGradient) return copyColors()
        val light = first.firstOrNull() ?: return copyColors()
        val dark = second.firstOrNull() ?: return copyColors()
        return Pair(
            intArrayOf(light),
            intArrayOf(dark)
        )
    }

    private fun Pair<IntArray, IntArray>.copyColors(): Pair<IntArray, IntArray> {
        return Pair(first.copyOf(), second.copyOf())
    }

    private fun String.normalizeMediaText(): String {
        return trim().lowercase().replace(WHITESPACE_REGEX, " ")
    }

    private fun isCompatibleTitle(first: String, second: String): Boolean {
        if (first == second) return true
        return first.removeVersionSuffix() == second.removeVersionSuffix()
    }

    private fun isCompatibleArtist(first: String, second: String): Boolean {
        return first == second
    }

    private fun String.removeVersionSuffix(): String {
        return replace(VERSION_SUFFIX_REGEX, "").trim()
    }

    /**
     * 将封面缩放成固定网格后比较平均像素差，忽略同一封面在尺寸、压缩上的轻微变化。
     */
    private fun bitmapFingerprint(bitmap: Bitmap): ArtworkFingerprint {
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            FINGERPRINT_GRID_SIZE,
            FINGERPRINT_GRID_SIZE,
            true
        )
        val readable = if (scaled.config == Bitmap.Config.HARDWARE) {
            scaled.copy(Bitmap.Config.ARGB_8888, false) ?: scaled
        } else {
            scaled
        }
        return try {
            val pixels = IntArray(FINGERPRINT_GRID_SIZE * FINGERPRINT_GRID_SIZE)
            readable.getPixels(
                pixels,
                0,
                FINGERPRINT_GRID_SIZE,
                0,
                0,
                FINGERPRINT_GRID_SIZE,
                FINGERPRINT_GRID_SIZE
            )
            ArtworkFingerprint(pixels)
        } finally {
            if (readable !== scaled && readable !== bitmap) readable.recycle()
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun trimCache() {
        while (keyedCache.size > MAX_CACHED_SONGS) {
            val firstKey = keyedCache.keys.firstOrNull() ?: return
            keyedCache.remove(firstKey)
        }
    }

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val VERSION_SUFFIX_REGEX = Regex("\\s*[（(\\[【][^）)\\]】]*[）)\\]】]\\s*$")
    private const val MAX_PALETTE_COLORS = 4
    private const val MAX_CACHED_SONGS = 16
    private const val FINGERPRINT_GRID_SIZE = 8
    private const val RGB_CHANNEL_COUNT = 3
    private const val MAX_AVERAGE_CHANNEL_DELTA = 12L
}
