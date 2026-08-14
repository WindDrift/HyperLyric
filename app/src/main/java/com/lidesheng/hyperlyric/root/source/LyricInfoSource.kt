package com.lidesheng.hyperlyric.root.source

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.lidesheng.hyperlyric.common.lyric.LyricInfoParser
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import com.lidesheng.hyperlyric.lyric.model.LyricMediaMetadata
import com.lidesheng.hyperlyric.lyric.source.LyricSink
import com.lidesheng.hyperlyric.lyric.source.LyricSource
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.utils.HookLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LyricInfoSource(private val context: Context) : LyricSource {

    private companion object {
        const val TAG = "LyricInfoSource"
    }

    override val id = "lyricinfo"
    override val displayName = "LyricInfo"

    private val manager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val trackedControllers =
        java.util.concurrent.ConcurrentHashMap<MediaController, MediaController.Callback>()
    private var sink: LyricSink? = null

    private var lastLyricHash: Int = 0
    private var hasLyrics: Boolean = false
    private var activePkg: String? = null
    private var activeController: MediaController? = null
    private var lastMetadataKey: String? = null

    private var positionJob: Job? = null
    private val positionJob_supervisor = SupervisorJob()
    private val positionScope = CoroutineScope(Dispatchers.Main + positionJob_supervisor)

    private val sessionListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            onActiveSessionsChanged(controllers)
        }

    override fun isAvailable() = true

    override fun start(sink: LyricSink) {
        this.sink = sink
        trackedControllers.clear()
        try {
            manager.addOnActiveSessionsChangedListener(sessionListener, null)
            onActiveSessionsChanged(manager.getActiveSessions(null))
            HookLogger.d(TAG, "数据源已启动")
        } catch (e: Exception) {
            HookLogger.e(TAG, "数据源启动失败", e)
        }
    }

    override fun stop() {
        stopPositionPolling()
        try {
            manager.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {
        }
        trackedControllers.forEach { (ctrl, cb) ->
            try {
                ctrl.unregisterCallback(cb)
            } catch (_: Exception) {
            }
        }
        trackedControllers.clear()
        clearLyrics()
        sink?.onStop(); sink = null
    }

    private fun clearLyrics() {
        hasLyrics = false
        lastLyricHash = 0
        activePkg = null
        activeController = null
        lastMetadataKey = null
        stopPositionPolling()
    }

    private fun onActiveSessionsChanged(controllers: List<MediaController>?) {
        if (controllers == null) return
        val currentSessions = controllers.toSet()
        trackedControllers.keys.filter { it !in currentSessions }.forEach { dead ->
            trackedControllers.remove(dead)?.let {
                try {
                    dead.unregisterCallback(it)
                } catch (_: Exception) {
                }
            }
        }
        val activeToken = activeController?.sessionToken
        if (activeToken != null && controllers.none { it.sessionToken == activeToken }) {
            sink?.onStop()
            clearLyrics()
        }
        for (ctrl in controllers) {
            if (!trackedControllers.containsKey(ctrl)) {
                val cb = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) =
                        onMetadataUpdate(ctrl)

                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        if (state?.state == PlaybackState.STATE_PLAYING) {
                            onMetadataUpdate(ctrl, state)
                        } else if (isCurrentController(ctrl)) {
                            handlePlaybackState(ctrl, state)
                        }
                    }

                    override fun onSessionDestroyed() =
                        onActiveSessionsChanged(manager.getActiveSessions(null))
                }
                try {
                    ctrl.registerCallback(cb); trackedControllers[ctrl] = cb; onMetadataUpdate(ctrl)
                } catch (_: Exception) {
                }
            }
        }

        // Existing controllers do not receive a registration-time metadata callback here.
        // If the selected session disappeared while another session is already playing,
        // explicitly give that session a chance to take over.
        if (activeToken != null && controllers.none { it.sessionToken == activeToken }) {
            controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?.let { onMetadataUpdate(it) }
        }
    }

    /**
     * 只有当前歌词会话可以继续更新歌词；其他会话必须先进入播放状态才能接管。
     */
    private fun onMetadataUpdate(
        controller: MediaController,
        playbackStateOverride: PlaybackState? = null
    ) {
        val metadata = controller.metadata ?: return
        val pkg = controller.packageName ?: return
        val isCurrent = isCurrentController(controller)
        val playbackState = playbackStateOverride ?: controller.playbackState

        // Opening another music app can publish its lyricInfo while it is paused. That
        // metadata must not replace the session that is currently feeding the island.
        if (!isCurrent && playbackState?.state != PlaybackState.STATE_PLAYING) return

        val lyricInfoRaw = try {
            metadata.getString("lyricInfo")
        } catch (_: Exception) {
            null
        }
        val currentHash = lyricInfoRaw?.hashCode() ?: 0

        if (!lyricInfoRaw.isNullOrBlank() && currentHash != 0) {
            if (currentHash == lastLyricHash && pkg == activePkg) {
                if (!isCurrent) {
                    activeController = controller
                    handlePlaybackState(controller, playbackState)
                } else if (playbackStateOverride != null) {
                    handlePlaybackState(controller, playbackState)
                }
                return
            }

            if (HookLogger.isDebugEnabled) {
                logDiagnosis(lyricInfoRaw)
            }
            val payload = LyricInfoParser.parsePayload(lyricInfoRaw)
            val song = payload?.song
            if (payload != null && song?.lyrics?.isNullOrEmpty() == false) {
                val mediaMetadata = LyricMediaMetadata(
                    sourceId = id,
                    packageName = pkg,
                    songId = payload.songId,
                    title = payload.title,
                    artist = payload.artist,
                    album = payload.album
                )
                val metadataKey = listOf(
                    pkg,
                    payload.songId,
                    payload.title,
                    payload.artist,
                    payload.album
                ).joinToString("\u001F")
                lastLyricHash = currentHash
                hasLyrics = true
                activePkg = pkg
                activeController = controller
                LyriconDataBridge.updateLyricPackage(pkg)
                sink?.onSongChanged(song)
                lastMetadataKey = metadataKey
                sink?.onMetadata(mediaMetadata)
                handlePlaybackState(controller, playbackState)
                HookLogger.d(
                    TAG,
                    "歌词已就绪: song=${song.name.orEmpty()}, " +
                            "lines=${song.lyrics!!.size}"
                )
            } else if (
                hasLyrics &&
                pkg == activePkg &&
                isCurrentController(controller) &&
                playbackState?.state == PlaybackState.STATE_PLAYING
            ) {
                // A non-empty lyricInfo payload can still contain an empty or invalid lyric body.
                // Do not keep the previous song's full-song state in that case.
                sink?.onStop()
                clearLyrics()
                HookLogger.d(TAG, "歌词已清除: package=$pkg, reason=empty_or_invalid_lyric_info")
            }
        } else if (
            hasLyrics &&
            pkg == activePkg &&
            isCurrent &&
            playbackState?.state == PlaybackState.STATE_PLAYING
        ) {
            // A playing track without LyricInfo must not keep the previous track's injected view.
            sink?.onStop()
            LyriconDataBridge.clearState()
            clearLyrics()
            HookLogger.d(TAG, "歌词已清除: package=$pkg, reason=missing_lyric_info")
        }
    }

    private fun isCurrentController(controller: MediaController): Boolean =
        controller.sessionToken == activeController?.sessionToken

    private fun logDiagnosis(json: String) {
        val diagnosis = LyricInfoParser.diagnose(json) ?: return
        HookLogger.d(
            TAG,
            "songName=${diagnosis.songName} | artist=${diagnosis.artist} | " +
                    "songId=${diagnosis.songId} | format=${diagnosis.format} | " +
                    "translation=${diagnosis.translationFormat} | " +
                    "lyric=${diagnosis.lyricLength}chars | " +
                    diagnosis.lyricPreview.joinToString(" | ")
        )
    }

    private fun startPositionPolling(controller: MediaController) {
        positionJob?.cancel()
        positionJob = positionScope.launch {
            while (isActive) {
                try {
                    val state = controller.playbackState
                    if (state?.state != PlaybackState.STATE_PLAYING) {
                        dispatchPosition(state)
                        break
                    }
                    val position = MediaMetadataHelper.estimatePlaybackPosition(state)
                    if (position >= 0L && activeController?.sessionToken == controller.sessionToken) {
                        sink?.onPositionChanged(position, state.playbackSpeed)
                    }
                } catch (_: Exception) {
                }
                delay(33)
            }
        }
    }

    private fun handlePlaybackState(controller: MediaController, state: PlaybackState?) {
        if (controller.sessionToken != activeController?.sessionToken) return
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        sink?.onPlaybackStateChanged(isPlaying, state?.playbackSpeed ?: Float.NaN)
        dispatchPosition(state)
        if (isPlaying) {
            startPositionPolling(controller)
        } else {
            stopPositionPolling()
        }
    }

    private fun dispatchPosition(state: PlaybackState?) {
        val position = MediaMetadataHelper.estimatePlaybackPosition(state)
        if (position >= 0L) {
            sink?.onPositionChanged(position, state?.playbackSpeed ?: Float.NaN)
        }
    }

    private fun stopPositionPolling() {
        positionJob?.cancel()
        positionJob = null
    }
}
