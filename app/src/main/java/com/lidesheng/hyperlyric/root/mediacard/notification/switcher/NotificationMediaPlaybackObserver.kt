package com.lidesheng.hyperlyric.root.mediacard.notification.switcher

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import com.lidesheng.hyperlyric.root.utils.HookLogger

/**
 * Observes the session currently owned by one native media-card controller.
 *
 * HyperOS' MiuiMediaViewControllerImpl callback only observes metadata. The
 * switcher needs a small independent observer so a transport action can cause
 * a MediaData refresh even when the native controller does not immediately
 * rebind itself.
 */
internal class NotificationMediaPlaybackObserver(
    private val onMediaChanged: () -> Unit
) {
    private companion object {
        const val TAG = "NotificationMediaPlaybackObserver"
    }

    private var mediaController: MediaController? = null
    private var callback: MediaController.Callback? = null
    private var lastPlaybackState: Int? = null
    private var acceptingCallbacks = false
    private var generation = 0

    fun bind(controller: MediaController?) {
        if (mediaController === controller) return

        clear()
        if (controller == null) return

        val currentGeneration = generation
        val observerCallback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (!isCurrent(currentGeneration)) return
                val nextState = state?.state
                if (nextState == lastPlaybackState) return
                lastPlaybackState = nextState
                notifyChanged()
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                if (isCurrent(currentGeneration)) notifyChanged()
            }

            override fun onSessionDestroyed() {
                if (isCurrent(currentGeneration)) notifyChanged()
            }
        }

        mediaController = controller
        callback = observerCallback
        lastPlaybackState = runCatching { controller.playbackState?.state }.getOrNull()
        runCatching { controller.registerCallback(observerCallback) }
            .onFailure { error ->
                mediaController = null
                callback = null
                HookLogger.w(TAG, "注册媒体会话状态观察失败", error)
            }
        acceptingCallbacks = true
    }

    fun clear() {
        acceptingCallbacks = false
        generation++
        val controller = mediaController
        val observerCallback = callback
        if (controller != null && observerCallback != null) {
            runCatching { controller.unregisterCallback(observerCallback) }
                .onFailure { error ->
                    HookLogger.w(TAG, "注销媒体会话状态观察失败", error)
                }
        }
        mediaController = null
        callback = null
        lastPlaybackState = null
    }

    private fun isCurrent(callbackGeneration: Int): Boolean {
        return acceptingCallbacks && callbackGeneration == generation
    }

    private fun notifyChanged() {
        runCatching(onMediaChanged)
            .onFailure { error -> HookLogger.w(TAG, "媒体会话状态回调失败", error) }
    }
}
