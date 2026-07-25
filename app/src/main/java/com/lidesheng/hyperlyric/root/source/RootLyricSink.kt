package com.lidesheng.hyperlyric.root.source

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.lidesheng.hyperlyric.lyric.source.LyricSink
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.island.IslandSlotContentAssembler
import com.lidesheng.hyperlyric.root.island.renderer.IslandRenderer
import com.lidesheng.hyperlyric.root.aitrans.AiTranslationGateway
import com.lidesheng.hyperlyric.root.utils.HookLogger
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.lyric.model.Song
import com.lidesheng.hyperlyric.lyric.model.interfaces.IRichLyricLine

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
        renderer.clearAllViews()
        LyriconDataBridge.clearState()
    }

    override fun onMetadata(title: String?, artist: String?, album: String?, publisher: String?) {
        if (title != null) LyriconDataBridge.currentSongName = title
        if (!publisher.isNullOrEmpty()) {
            LyriconDataBridge.updateLyricPackage(publisher)
        }
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
        mainHandler.postDelayed(positionDispatchRunnable, MIN_POSITION_DISPATCH_INTERVAL_MS - elapsed)
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

}


