package com.lidesheng.hyperlyric.root.source

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.common.media.MediaIdentity
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import com.lidesheng.hyperlyric.lyric.model.LyricMediaMetadata
import com.lidesheng.hyperlyric.lyric.model.Song
import com.lidesheng.hyperlyric.lyric.model.interfaces.IRichLyricLine
import com.lidesheng.hyperlyric.lyric.source.LyricSink
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.amll.AmllPlatformIdMapper
import com.lidesheng.hyperlyric.root.amll.AmllTtmlGateway
import com.lidesheng.hyperlyric.root.aitrans.AiTranslationGateway
import com.lidesheng.hyperlyric.root.island.content.IslandSlotContentFacade
import com.lidesheng.hyperlyric.root.island.effects.color.IslandMusicWaveColorHooker
import com.lidesheng.hyperlyric.root.island.renderer.BaseIslandRenderer
import com.lidesheng.hyperlyric.root.island.renderer.IslandRenderer
import com.lidesheng.hyperlyric.root.media.CurrentMediaInfoResolver
import com.lidesheng.hyperlyric.root.utils.CoverColorHelper
import com.lidesheng.hyperlyric.root.utils.HookLogger
import kotlin.math.abs

class RootLyricSink(
    private val renderer: IslandRenderer,
    private val context: Context,
    private val prefs: SharedPreferences? = null
) : LyricSink {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastPositionDispatchTimeMs = 0L
    private var pendingPosition: PositionSample? = null
    private var positionDispatchScheduled = false
    private var playbackActive = false
    private var lastReceivedPosition = Long.MIN_VALUE
    private var lastReceivedPositionTimeMs = 0L
    private var lastReceivedPlaybackSpeed = Float.NaN
    private var lastDispatchedPosition = Long.MIN_VALUE
    private var lastDispatchedPlaybackSpeed = Float.NaN
    private var currentPlaybackSpeed = 1f
    private var activeMediaIdentity: MediaIdentity? = null
    /** 当前歌曲的 AMLL 请求去重 key（同一首歌仅触发一次，切歌时重置） */
    private var amllFetchKey: String? = null
    private val positionDispatchRunnable = Runnable {
        positionDispatchScheduled = false
        val latest = pendingPosition ?: return@Runnable
        pendingPosition = null
        dispatchPosition(latest)
    }

    private companion object {
        const val TAG = "RootLyricSink"
        const val MIN_POSITION_DISPATCH_INTERVAL_MS = 33L
        const val MIN_VALID_PLAYBACK_SPEED = 0.1f
        const val MAX_VALID_PLAYBACK_SPEED = 4f
        const val SPEED_CHANGE_EPSILON = 0.01f
        const val INFERRED_SPEED_BLEND = 0.75f
        const val MAX_DEBUG_TEXT_LENGTH = 80
    }

    private data class PositionSample(val position: Long, val playbackSpeed: Float)

    override fun onSongChanged(song: Song?) {
        cancelPendingPositionDispatch()
        lastReceivedPosition = Long.MIN_VALUE
        lastReceivedPositionTimeMs = 0L
        lastReceivedPlaybackSpeed = Float.NaN
        lastDispatchedPosition = Long.MIN_VALUE
        lastDispatchedPlaybackSpeed = Float.NaN
        currentPlaybackSpeed = 1f
        AmllTtmlGateway.cancelActiveRequests()
        amllFetchKey = null
        AiTranslationGateway.cancelActiveRequests()
        activeMediaIdentity = null
        LyriconDataBridge.updateSong(
            song = song,
            placeholderFormat = prefs?.getInt(
                RootConstants.KEY_HOOK_PLACEHOLDER_FORMAT,
                RootConstants.DEFAULT_HOOK_PLACEHOLDER_FORMAT
            ) ?: RootConstants.DEFAULT_HOOK_PLACEHOLDER_FORMAT
        )
        if (song == null) {
            endColorSession()
        }
        if (song != null && prefs != null) {
            // AMLL 增强层：song.id 非空且包名可映射时立即触发精确匹配；
            // 命中后跳过 AI 翻译，未命中/未触发时回落原 AI 翻译流程
            val amllTriggered = tryAmllEnhancement(song, reason = "song_changed")
            if (!amllTriggered) {
                maybeAiTranslate(song)
            }
        }
    }

    override fun onLyricLine(line: IRichLyricLine) {
        LyriconDataBridge.updateLyricLine(line)
        renderer.updateLyricLine()
    }

    override fun onPlainText(text: String?) {

        LyriconDataBridge.updateLyric(text)
        renderer.updateLyricLine()
    }

    override fun onStop() {
        AmllTtmlGateway.cancelActiveRequests()
        amllFetchKey = null
        AiTranslationGateway.cancelActiveRequests()
        playbackActive = false
        cancelPendingPositionDispatch()
        lastReceivedPosition = Long.MIN_VALUE
        lastReceivedPositionTimeMs = 0L
        lastReceivedPlaybackSpeed = Float.NaN
        lastDispatchedPosition = Long.MIN_VALUE
        lastDispatchedPlaybackSpeed = Float.NaN
        currentPlaybackSpeed = 1f
        activeMediaIdentity = null
        endColorSession()
        renderer.clearAllViews()
        LyriconDataBridge.clearState()
    }

    override fun onMetadata(metadata: LyricMediaMetadata?) {
        val normalized = metadata?.normalized()
        LyriconDataBridge.updateMediaMetadata(normalized)
        normalized?.packageName?.let(LyriconDataBridge::updateLyricPackage)
        if (normalized == null) {
            endColorSession()
            renderer.updateMetadata()
            return
        }
        val packageName = normalized.packageName
            ?: LyriconDataBridge.currentLyricPackageName
            ?: ""
        val mediaInfo = CurrentMediaInfoResolver.getMediaInfo(
            context = context,
            packageName = packageName,
            logger = HookLogger,
            sourceMetadata = normalized
        )
        val mediaChanged = activeMediaIdentity?.isCompatibleWith(mediaInfo.identity) == false
        if (mediaChanged && LyriconDataBridge.currentSong == null) {
            LyriconDataBridge.resetLyricContentForMediaChange()
            renderer.updateLyricLine()
        }
        activeMediaIdentity = mediaInfo.identity
        LyriconDataBridge.currentSongName = mediaInfo.title
            .takeIf { it.isNotBlank() }
            ?: LyriconDataBridge.currentSong?.name
        updateColorSession(mediaInfo, reason = "metadata_changed")
        // AMLL 补全触发：onSongChanged 时条件不足（songId 为空/包名未知/元数据未推送），
        // metadata 推送后补全触发（精确或 search 模糊匹配），与 onSongChanged 触发互斥
        LyriconDataBridge.currentSong?.let { currentSong ->
            tryAmllEnhancement(currentSong, reason = "metadata_changed")
        }
        renderer.updateMetadata()
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean, playbackSpeed: Float) {
        playbackActive = isPlaying
        explicitPlaybackSpeed(playbackSpeed)?.let { currentPlaybackSpeed = it }
        if (!isPlaying) cancelPendingPositionDispatch()
        renderer.onPlaybackStateChanged(isPlaying)
    }

    override fun onPositionChanged(position: Long, playbackSpeed: Float) {
        val now = SystemClock.uptimeMillis()
        val resolvedSpeed = resolvePlaybackSpeed(position, playbackSpeed, now)
        if (position == lastReceivedPosition &&
            abs(resolvedSpeed - lastReceivedPlaybackSpeed) < SPEED_CHANGE_EPSILON
        ) return
        lastReceivedPosition = position
        lastReceivedPositionTimeMs = now
        lastReceivedPlaybackSpeed = resolvedSpeed
        val lyricChanged = LyriconDataBridge.updatePosition(position)
        if (lyricChanged) {
            renderer.updateLyricLine()
        }
        val sample = PositionSample(position, resolvedSpeed)
        if (playbackActive) {
            dispatchPositionThrottled(sample, now)
        } else {
            dispatchPosition(sample, now)
        }
    }

    private fun dispatchPositionThrottled(position: PositionSample, now: Long) {
        val elapsed = now - lastPositionDispatchTimeMs
        if (elapsed >= MIN_POSITION_DISPATCH_INTERVAL_MS) {
            dispatchPosition(position, now)
            return
        }

        pendingPosition = position
        if (positionDispatchScheduled) return

        positionDispatchScheduled = true
        mainHandler.postDelayed(
            positionDispatchRunnable,
            MIN_POSITION_DISPATCH_INTERVAL_MS - elapsed
        )
    }

    private fun dispatchPosition(
        sample: PositionSample,
        now: Long = SystemClock.uptimeMillis()
    ) {
        if (sample.position == lastDispatchedPosition &&
            abs(sample.playbackSpeed - lastDispatchedPlaybackSpeed) < SPEED_CHANGE_EPSILON
        ) return
        lastPositionDispatchTimeMs = now
        lastDispatchedPosition = sample.position
        lastDispatchedPlaybackSpeed = sample.playbackSpeed
        pendingPosition = null
        renderer.updatePosition(sample.position, sample.playbackSpeed)
    }

    private fun cancelPendingPositionDispatch() {
        mainHandler.removeCallbacks(positionDispatchRunnable)
        pendingPosition = null
        positionDispatchScheduled = false
    }

    private fun resolvePlaybackSpeed(position: Long, reportedSpeed: Float, now: Long): Float {
        explicitPlaybackSpeed(reportedSpeed)?.let {
            currentPlaybackSpeed = it
            return it
        }

        if (playbackActive && lastReceivedPosition != Long.MIN_VALUE &&
            lastReceivedPositionTimeMs > 0L && now > lastReceivedPositionTimeMs &&
            position >= lastReceivedPosition
        ) {
            val elapsedMs = now - lastReceivedPositionTimeMs
            val inferred = (position - lastReceivedPosition).toFloat() / elapsedMs.toFloat()
            if (inferred in MIN_VALID_PLAYBACK_SPEED..MAX_VALID_PLAYBACK_SPEED) {
                currentPlaybackSpeed = currentPlaybackSpeed * (1f - INFERRED_SPEED_BLEND) +
                        inferred * INFERRED_SPEED_BLEND
            }
        }
        return currentPlaybackSpeed
    }

    private fun explicitPlaybackSpeed(speed: Float): Float? =
        speed.takeIf { it.isFinite() && it in MIN_VALID_PLAYBACK_SPEED..MAX_VALID_PLAYBACK_SPEED }

    private fun debugText(value: String): String {
        val normalized = value
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
            .trim()
        if (normalized.isEmpty()) return "<empty>"
        return normalized.take(MAX_DEBUG_TEXT_LENGTH).let {
            if (normalized.length > MAX_DEBUG_TEXT_LENGTH) "$it…" else it
        }
    }

    /**
     * AMLL 增强层刷新入口（开关从关闭切换为开启时由 HookEntry 调用）：
     * 重置去重 key 后对当前歌曲立即触发一次 AMLL 请求。
     */
    fun refreshAmllEnhancement() {
        val song = LyriconDataBridge.currentSong ?: return
        amllFetchKey = null
        tryAmllEnhancement(song, reason = "pref_changed")
    }

    /**
     * 尝试触发 AMLL 增强请求。触发条件：
     * - 精确匹配：songId 非空且包名可映射平台
     * - 搜索回退：songId 缺失/包名未知，但 title 或 artist 可用
     *
     * 同一首歌仅触发一次（[amllFetchKey] 去重）；AMLL pending 期间取消 AI 翻译，
     * 命中后跳过 AI 翻译，未命中/失败时在回调中回落原 AI 翻译流程。
     *
     * @return true 表示已触发 AMLL 请求（调用方应暂缓 AI 翻译等待回调）
     */
    private fun tryAmllEnhancement(song: Song, reason: String): Boolean {
        val prefs = prefs ?: return false
        val amllEnabled = prefs.getBoolean(
            RootConstants.KEY_HOOK_AMLL_TTML_ENABLED,
            RootConstants.DEFAULT_HOOK_AMLL_TTML_ENABLED
        )
        if (!amllEnabled) return false

        val metadata = LyriconDataBridge.currentLyricMediaMetadata
        val platform = AmllPlatformIdMapper.mapPackageNameToAmllField(metadata?.packageName)
        val songId = metadata?.songId?.takeIf { it.isNotBlank() }
            ?: song.id?.takeIf { it.isNotBlank() }
        val canExact = !songId.isNullOrBlank() && platform != null
        val hasSearchParam = !song.name.isNullOrBlank() || !song.artist.isNullOrBlank()
        if (!canExact && !hasSearchParam) return false

        val fetchKey = if (canExact) {
            "exact|${platform?.name}|$songId"
        } else {
            "search|${metadata?.title ?: song.name}|${metadata?.artist ?: song.artist}"
        }
        if (amllFetchKey == fetchKey) return true
        amllFetchKey = fetchKey

        HookLogger.d(
            TAG,
            "AMLL 增强触发: reason=$reason, mode=${if (canExact) "exact" else "search"}, " +
                    "song=\"${debugText(song.name.orEmpty())}\", platform=${platform?.name ?: "unknown"}"
        )
        val triggered = AmllTtmlGateway.fetchTtml(song, metadata, prefs) { amllSong ->
            onAmllResult(song, amllSong)
        }
        if (!triggered) {
            amllFetchKey = null
            return false
        }
        // AMLL pending 期间取消 AI 翻译，避免其结果覆盖即将到来的 AMLL 歌词
        AiTranslationGateway.cancelActiveRequests()
        return true
    }

    /**
     * AMLL 请求结果处理：命中时用 AMLL 歌词替换并刷新超级岛（跳过 AI 翻译）；
     * 未命中/失败时保持原歌词并回落原 AI 翻译流程。
     */
    private fun onAmllResult(localSong: Song, amllSong: Song?) {
        if (amllSong != null) {
            HookLogger.d(
                TAG,
                "AMLL 命中，替换歌词: song=\"${debugText(localSong.name.orEmpty())}\", " +
                        "lines=${amllSong.lyrics?.size ?: 0}"
            )
            LyriconDataBridge.updateSong(amllSong, currentPlaceholderFormat())
            BaseIslandRenderer.refreshActiveIsland()
        } else {
            HookLogger.d(TAG, "AMLL 未命中，保持原歌词: song=\"${debugText(localSong.name.orEmpty())}\"")
            maybeAiTranslate(localSong)
        }
    }

    private fun currentPlaceholderFormat(): Int = prefs?.getInt(
        RootConstants.KEY_HOOK_PLACEHOLDER_FORMAT,
        RootConstants.DEFAULT_HOOK_PLACEHOLDER_FORMAT
    ) ?: RootConstants.DEFAULT_HOOK_PLACEHOLDER_FORMAT

    private fun maybeAiTranslate(song: Song) {
        val prefs = prefs ?: return
        val aiEnabled = prefs.getBoolean(
            RootConstants.KEY_HOOK_AI_TRANS_ENABLE,
            RootConstants.DEFAULT_HOOK_AI_TRANS_ENABLE
        )
        if (!aiEnabled) return
        val forceOverride = prefs.getBoolean(
            RootConstants.KEY_HOOK_AI_TRANS_FORCE_OVERRIDE,
            RootConstants.DEFAULT_HOOK_AI_TRANS_FORCE_OVERRIDE
        )
        AiTranslationGateway.translateSong(song, prefs, forceOverride)
    }

    private fun updateColorSession(mediaInfo: MediaMetadataHelper.MediaInfo, reason: String) {
        val packageName = mediaInfo.identity.packageName
        val previousRevision = CoverColorHelper.currentSession()?.revision
        val current = CoverColorHelper.activateSession(
            mediaInfo = mediaInfo
        ) ?: run {
            endColorSession()
            HookLogger.dState(
                stateId = "RootLyricSink.colorSession.invalid",
                tag = TAG,
                state = "$packageName|${mediaInfo.title}|${mediaInfo.artist}|${mediaInfo.identity}"
            ) {
                "颜色会话未更新: reason=$reason, package=${packageName.ifEmpty { "<empty>" }}, " +
                        "title=\"${debugText(mediaInfo.title)}\", " +
                        "artist=\"${debugText(mediaInfo.artist)}\", identity=${mediaInfo.identity}"
            }
            return
        }
        HookLogger.dState(
            stateId = "RootLyricSink.colorSession",
            tag = TAG,
            state = "${current.revision}|${current.mediaKey}|${current.title}|${current.artist}"
        ) {
            "颜色会话已同步: reason=$reason, revision=${current.revision}, " +
                    "revisionChanged=${previousRevision != current.revision}, " +
                    "package=${packageName.ifEmpty { "<empty>" }}, " +
                    "title=\"${debugText(mediaInfo.title)}\", " +
                    "artist=\"${debugText(mediaInfo.artist)}\", " +
                    "album=\"${debugText(mediaInfo.album)}\", " +
                    "identity=${mediaInfo.identity}, mediaKeyHash=${current.mediaKey.hashCode()}"
        }
        if (previousRevision != current.revision) {
            IslandSlotContentFacade.invalidate()
            IslandMusicWaveColorHooker.refresh()
            renderer.updateTextColors()
        }
    }

    private fun endColorSession() {
        if (CoverColorHelper.endSession()) {
            IslandSlotContentFacade.invalidate()
            IslandMusicWaveColorHooker.refresh()
        }
    }

}


