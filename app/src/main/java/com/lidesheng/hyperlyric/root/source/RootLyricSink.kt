package com.lidesheng.hyperlyric.root.source

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.lyric.model.Song
import com.lidesheng.hyperlyric.lyric.model.interfaces.IRichLyricLine
import com.lidesheng.hyperlyric.lyric.source.LyricSink
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.aitrans.AiTranslationGateway
import com.lidesheng.hyperlyric.root.island.IslandMusicWaveColorHooker
import com.lidesheng.hyperlyric.root.island.IslandSlotContentAssembler
import com.lidesheng.hyperlyric.root.island.renderer.IslandRenderer
import com.lidesheng.hyperlyric.root.utils.CoverColorHelper

class RootLyricSink(
    private val renderer: IslandRenderer,
    private val prefs: SharedPreferences? = null
) : LyricSink {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastPositionDispatchTimeMs = 0L
    private var pendingPosition: Long? = null
    private var positionDispatchScheduled = false
    private var playbackActive = false
    private var lastReceivedPosition = Long.MIN_VALUE
    private var lastDispatchedPosition = Long.MIN_VALUE
    private var lastMetadataArtist = ""
    private var lastMetadataAlbum = ""
    private val positionDispatchRunnable = Runnable {
        positionDispatchScheduled = false
        val latest = pendingPosition ?: return@Runnable
        pendingPosition = null
        dispatchPosition(latest)
    }

    private companion object {
        const val MIN_POSITION_DISPATCH_INTERVAL_MS = 33L
    }

    override fun onSongChanged(song: Any?) {
        cancelPendingPositionDispatch()
        lastReceivedPosition = Long.MIN_VALUE
        lastDispatchedPosition = Long.MIN_VALUE
        AiTranslationGateway.cancelActiveRequests()
        if (song is Song) {
            lastMetadataArtist = song.artist.orEmpty()
            lastMetadataAlbum = ""
            updateColorSession(
                title = song.name.orEmpty(),
                artist = song.artist.orEmpty(),
                album = lastMetadataAlbum,
                songId = song.id
            )
        } else {
            lastMetadataArtist = ""
            lastMetadataAlbum = ""
            endColorSession()
        }

        if (song is Song && prefs != null) {
            val aiEnabled = prefs.getBoolean(
                RootConstants.KEY_HOOK_AI_TRANS_ENABLE,
                RootConstants.DEFAULT_HOOK_AI_TRANS_ENABLE
            )
            if (aiEnabled) {
                val forceOverride = prefs.getBoolean(
                    RootConstants.KEY_HOOK_AI_TRANS_FORCE_OVERRIDE,
                    RootConstants.DEFAULT_HOOK_AI_TRANS_FORCE_OVERRIDE
                )
                AiTranslationGateway.translateSong(song, prefs, forceOverride)
            }
        }
    }

    override fun onLyricLine(line: Any?) {
        if (line is IRichLyricLine) {

            LyriconDataBridge.updateLyricLine(line)
            renderer.updateLyricLine()
        }
    }

    override fun onPlainText(text: String?) {

        LyriconDataBridge.updateLyric(text)
        renderer.updateLyricLine()
    }

    override fun onStop() {
        AiTranslationGateway.cancelActiveRequests()
        playbackActive = false
        cancelPendingPositionDispatch()
        lastReceivedPosition = Long.MIN_VALUE
        lastDispatchedPosition = Long.MIN_VALUE
        lastMetadataArtist = ""
        lastMetadataAlbum = ""
        endColorSession()
        renderer.clearAllViews()
        LyriconDataBridge.clearState()
    }

    override fun onMetadata(title: String?, artist: String?, album: String?, publisher: String?) {
        if (title != null) LyriconDataBridge.currentSongName = title
        if (artist != null) lastMetadataArtist = artist
        if (album != null) lastMetadataAlbum = album
        if (!publisher.isNullOrEmpty()) {
            LyriconDataBridge.updateLyricPackage(publisher)
        }
        val song = LyriconDataBridge.currentSong
        updateColorSession(
            title = song?.name?.takeIf { it.isNotBlank() }
                ?: LyriconDataBridge.currentSongName.orEmpty(),
            artist = song?.artist?.takeIf { it.isNotBlank() }
                ?: lastMetadataArtist,
            album = lastMetadataAlbum,
            songId = song?.id
        )
        IslandSlotContentAssembler.invalidate()
        renderer.refreshActiveIsland()
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        playbackActive = isPlaying
        if (!isPlaying) cancelPendingPositionDispatch()
        renderer.onPlaybackStateChanged(isPlaying)
    }

    override fun onPositionChanged(position: Long) {
        if (position == lastReceivedPosition) return
        lastReceivedPosition = position
        val lyricChanged = LyriconDataBridge.updatePosition(position)
        if (lyricChanged) {
            renderer.updateLyricLine()
        }
        if (playbackActive) {
            dispatchPositionThrottled(position)
        } else {
            dispatchPosition(position)
        }
    }

    private fun dispatchPositionThrottled(position: Long) {
        val now = SystemClock.uptimeMillis()
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

    private fun dispatchPosition(position: Long, now: Long = SystemClock.uptimeMillis()) {
        if (position == lastDispatchedPosition) return
        lastPositionDispatchTimeMs = now
        lastDispatchedPosition = position
        pendingPosition = null
        renderer.updatePosition(position)
    }

    private fun cancelPendingPositionDispatch() {
        mainHandler.removeCallbacks(positionDispatchRunnable)
        pendingPosition = null
        positionDispatchScheduled = false
    }

    private fun updateColorSession(
        title: String,
        artist: String,
        album: String,
        songId: String?
    ) {
        val previousRevision = CoverColorHelper.currentSession()?.revision
        val current = CoverColorHelper.activateSession(
            packageName = LyriconDataBridge.currentLyricPackageName.orEmpty(),
            title = title,
            artist = artist,
            album = album,
            songId = songId
        ) ?: return
        if (previousRevision != current.revision) {
            IslandSlotContentAssembler.invalidate()
            IslandMusicWaveColorHooker.refresh()
        }
    }

    private fun endColorSession() {
        if (CoverColorHelper.endSession()) {
            IslandSlotContentAssembler.invalidate()
            IslandMusicWaveColorHooker.refresh()
        }
    }

}


