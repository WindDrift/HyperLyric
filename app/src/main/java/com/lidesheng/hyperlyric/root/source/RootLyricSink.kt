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
import com.lidesheng.hyperlyric.root.island.content.IslandSlotContentFacade
import com.lidesheng.hyperlyric.root.island.effects.color.IslandMusicWaveColorHooker
import com.lidesheng.hyperlyric.root.island.renderer.IslandRenderer
import com.lidesheng.hyperlyric.root.media.CurrentMediaInfoResolver
import com.lidesheng.hyperlyric.root.plugin.PluginProcessingResult
import com.lidesheng.hyperlyric.root.plugin.PluginRuntime
import com.lidesheng.hyperlyric.root.plugin.PluginSongMapper
import com.lidesheng.hyperlyric.plugin.api.PluginMediaInfo
import com.lidesheng.hyperlyric.plugin.api.PluginProcessingContext
import com.lidesheng.hyperlyric.root.utils.CoverColorHelper
import com.lidesheng.hyperlyric.root.utils.HookLogger
import kotlin.math.abs
import java.util.concurrent.atomic.AtomicLong

class RootLyricSink(
    private val renderer: IslandRenderer,
    private val context: Context,
    private val prefs: SharedPreferences? = null,
    private val pluginRuntime: PluginRuntime? = null
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
    @Volatile
    private var latestPluginMediaInfo: PluginMediaInfo? = null
    private val pluginRequestGeneration = AtomicLong(0L)
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
        activeMediaIdentity = null
        latestPluginMediaInfo = null
        val ownedSong = song?.deepCopy()
        LyriconDataBridge.updateSong(
            song = ownedSong,
            placeholderFormat = prefs?.getInt(
                RootConstants.KEY_HOOK_PLACEHOLDER_FORMAT,
                RootConstants.DEFAULT_HOOK_PLACEHOLDER_FORMAT
            ) ?: RootConstants.DEFAULT_HOOK_PLACEHOLDER_FORMAT
        )
        if (song == null) {
            pluginRuntime?.cancelActiveProcessing()
            pluginRequestGeneration.incrementAndGet()
        } else {
            startPluginProcessing()
        }
        if (song == null) {
            endColorSession()
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
        pluginRuntime?.cancelActiveProcessing()
        playbackActive = false
        cancelPendingPositionDispatch()
        lastReceivedPosition = Long.MIN_VALUE
        lastReceivedPositionTimeMs = 0L
        lastReceivedPlaybackSpeed = Float.NaN
        lastDispatchedPosition = Long.MIN_VALUE
        lastDispatchedPlaybackSpeed = Float.NaN
        currentPlaybackSpeed = 1f
        activeMediaIdentity = null
        latestPluginMediaInfo = null
        pluginRequestGeneration.incrementAndGet()
        endColorSession()
        renderer.clearAllViews()
        LyriconDataBridge.clearState()
    }

    override fun onMetadata(metadata: LyricMediaMetadata?) {
        val normalized = metadata?.normalized()
        LyriconDataBridge.updateMediaMetadata(normalized)
        normalized?.packageName?.let(LyriconDataBridge::updateLyricPackage)
        if (normalized == null) {
            latestPluginMediaInfo = null
            pluginRequestGeneration.incrementAndGet()
            pluginRuntime?.cancelActiveProcessing()
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
        LyriconDataBridge.applyResolvedMediaInfo(mediaInfo)
        latestPluginMediaInfo = mediaInfo.toPluginMediaInfo()
        val mediaChanged = activeMediaIdentity?.isCompatibleWith(mediaInfo.identity) == false
        if (mediaChanged && LyriconDataBridge.currentSong == null) {
            LyriconDataBridge.resetLyricContentForMediaChange()
            renderer.updateLyricLine()
        }
        activeMediaIdentity = mediaInfo.identity
        LyriconDataBridge.currentSongName = LyriconDataBridge.currentSong?.name
            ?.takeIf { it.isNotBlank() }
            ?: mediaInfo.title.takeIf { it.isNotBlank() }
        startPluginProcessing(latestPluginMediaInfo)
        updateColorSession(mediaInfo, reason = "metadata_changed")
        renderer.updateMetadata()
    }

    /** Starts a fresh plugin pass for the current Core-owned Song snapshot. */
    private fun startPluginProcessing(mediaInfo: PluginMediaInfo? = latestPluginMediaInfo) {
        val baseSong = LyriconDataBridge.currentSong ?: return
        val expectedVersion = LyriconDataBridge.versionCounter.get()
        val expectedRequest = pluginRequestGeneration.incrementAndGet()
        val pluginSnapshot = PluginSongMapper.toPluginSong(baseSong.deepCopy())
        pluginRuntime?.processSong(
            song = pluginSnapshot,
            processingContext = PluginProcessingContext(mediaInfo = mediaInfo)
        ) { processingResult: PluginProcessingResult ->
            mainHandler.post {
                if (pluginRequestGeneration.get() != expectedRequest) return@post
                val enhancedSong = PluginSongMapper.toInternalSong(
                    base = baseSong,
                    result = processingResult.result
                ) ?: return@post
                if (LyriconDataBridge.applyPluginEnhancement(
                        enhancedSong = enhancedSong,
                        expectedVersion = expectedVersion,
                        expectedBaseSong = baseSong
                    )
                ) {
                    renderer.updateLyricLine()
                }
            }
        }
    }

    private fun MediaMetadataHelper.MediaInfo.toPluginMediaInfo(): PluginMediaInfo =
        PluginMediaInfo(
            title = title.takeIf { it.isNotBlank() },
            artist = artist.takeIf { it.isNotBlank() },
            album = album.takeIf { it.isNotBlank() },
            duration = duration.takeIf { it > 0L }
        )

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


