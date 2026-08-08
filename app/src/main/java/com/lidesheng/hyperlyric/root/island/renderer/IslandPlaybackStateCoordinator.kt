package com.lidesheng.hyperlyric.root.island.renderer

import android.view.View
import android.view.ViewGroup
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.lyric.view.RichLyricLineView
import com.lidesheng.hyperlyric.lyric.view.SpaceGateRichLyricLineView
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.island.config.IslandSlotRuntimeConfig
import com.lidesheng.hyperlyric.root.island.effects.glow.IslandProgressGlowController
import com.lidesheng.hyperlyric.root.island.presentation.IslandPresentationCoordinator
import com.lidesheng.hyperlyric.root.island.presentation.IslandReconcileReason

/**
 * Owns pause/resume behavior for injected lyric views.
 *
 * Refreshing content remains the renderer's responsibility. This coordinator only applies the
 * playback policy and keeps the pause-cleared state beside the transitions that consume it.
 */
internal object IslandPlaybackStateCoordinator {
    private var clearedByPause = false

    fun markClearedByPause() {
        clearedByPause = true
    }

    fun onPlaybackStateChanged(
        isPlaying: Boolean,
        onRefreshRequested: () -> Unit,
        onClearRequested: () -> Unit
    ) {
        val prefs = HookEntry.instance?.prefs ?: return
        if (!prefs.getBoolean(
                RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND,
                RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND
            )
        ) {
            onClearRequested()
            return
        }

        val stateChanged = IslandPresentationCoordinator.updatePlaybackState(isPlaying)
        if (stateChanged) {
            IslandProgressGlowController.onPlaybackStateChanged(isPlaying)
        }
        val behavior = prefs.getInt(
            RootConstants.KEY_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE,
            RootConstants.DEFAULT_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE
        )

        if (isPlaying) {
            if (clearedByPause) {
                clearedByPause = false
                restoreActiveViewsAfterPause(onRefreshRequested)
            } else if (stateChanged) {
                applyPlaybackStateToActiveViews(true)
            }
        } else if (behavior == 0) {
            if (!clearedByPause) {
                clearActiveViewsForPauseInternal()
            }
        } else if (stateChanged) {
            applyPlaybackStateToActiveViews(false)
        }
    }

    private fun restoreActiveViewsAfterPause(onRefreshRequested: () -> Unit) {
        val lyricPkg = LyriconDataBridge.currentLyricPackageName
            ?.takeIf { it.isNotEmpty() }
            ?: return
        val expectedLyricVersion = LyriconDataBridge.versionCounter.get()
        val expectedPresentationRevision =
            IslandPresentationCoordinator.currentPresentationRevision()
        val activeViews = IslandPresentationCoordinator.snapshotAttachedHosts(lyricPkg)
        if (activeViews.isEmpty()) {
            onRefreshRequested()
            return
        }

        activeViews.forEach { token ->
            if (IslandPresentationCoordinator.isHostFrozenForFakeTransition(token)) {
                return@forEach
            }
            val view = token.root
            view.post {
                if (IslandPresentationCoordinator.isHostFrozenForFakeTransition(token) ||
                    !IslandPresentationCoordinator.shouldRenderInjectedIsland() ||
                    !IslandPresentationCoordinator.isCurrentPresentation(
                        expectedPresentationRevision
                    ) ||
                    LyriconDataBridge.versionCounter.get() != expectedLyricVersion ||
                    LyriconDataBridge.currentLyricPackageName != lyricPkg
                ) {
                    return@post
                }

                val result = IslandPresentationCoordinator.reconcileRegisteredHost(
                    token,
                    IslandReconcileReason.PLAYBACK_RESUME,
                    expectedPresentationRevision
                )
                if (!result.isTarget) return@post
                val currentPrefs = HookEntry.instance?.prefs ?: return@post
                val config = IslandSlotRuntimeConfig.from(currentPrefs)
                val expectsInjectedView =
                    config.leftMode != RootConstants.ISLAND_CONTENT_MODE_NONE ||
                            config.rightMode != RootConstants.ISLAND_CONTENT_MODE_NONE
                if (expectsInjectedView && result.mutation.injectedSlotsPresent == false) {
                    onRefreshRequested()
                    return@post
                }

                setPlaybackActiveRecursively(view, true)
            }
        }
    }

    fun clearActiveViewsForPause() {
        if (clearedByPause) return
        clearActiveViewsForPauseInternal()
    }

    private fun clearActiveViewsForPauseInternal() {
        val lyricPkg = LyriconDataBridge.currentLyricPackageName
        val expectedPresentationRevision =
            IslandPresentationCoordinator.currentPresentationRevision()
        IslandPresentationCoordinator.snapshotAttachedHosts()
            .filter { token -> lyricPkg == null || token.packageName == lyricPkg }
            .forEach { token ->
                token.root.post {
                    IslandPresentationCoordinator.clearRegisteredHostIfSuppressed(
                        token,
                        expectedPresentationRevision
                    )
                }
            }
        clearedByPause = true
    }

    private fun applyPlaybackStateToActiveViews(isPlaying: Boolean) {
        val lyricPkg = LyriconDataBridge.currentLyricPackageName
        val expectedPresentationRevision =
            IslandPresentationCoordinator.currentPresentationRevision()
        IslandPresentationCoordinator.snapshotAttachedInjectedHosts(lyricPkg)
            .forEach { snapshot ->
                val token = snapshot.host
                if (IslandPresentationCoordinator.isHostFrozenForFakeTransition(token)) {
                    return@forEach
                }
                val view = token.root
                val indexedViews = snapshot.injectedViews
                view.post {
                    val currentLyricPackage = LyriconDataBridge.currentLyricPackageName
                    if (!IslandPresentationCoordinator.isCurrentHost(token) ||
                        IslandPresentationCoordinator.isHostFrozenForFakeTransition(token) ||
                        !IslandPresentationCoordinator.isCurrentPresentation(
                            expectedPresentationRevision
                        ) ||
                        IslandPresentationCoordinator.isPlaybackActive() != isPlaying ||
                        (currentLyricPackage != null &&
                                currentLyricPackage != token.packageName)
                    ) {
                        return@post
                    }
                    if (indexedViews.isEmpty()) {
                        setPlaybackActiveRecursively(view, isPlaying)
                        IslandPresentationCoordinator.refreshInjectedViewIndex(token)
                    } else {
                        indexedViews.forEach { injectedView ->
                            setPlaybackActive(injectedView, isPlaying)
                        }
                    }
                }
            }
    }

    private fun setPlaybackActive(view: View, isPlaying: Boolean) {
        when (view) {
            is RichLyricLineView -> view.setPlaybackActive(isPlaying)
            is SpaceGateRichLyricLineView -> view.setPlaybackActive(isPlaying)
        }
    }

    private fun setPlaybackActiveRecursively(view: View, isPlaying: Boolean) {
        when (view) {
            is RichLyricLineView,
            is SpaceGateRichLyricLineView -> setPlaybackActive(view, isPlaying)

            is ViewGroup -> {
                for (index in 0 until view.childCount) {
                    setPlaybackActiveRecursively(view.getChildAt(index), isPlaying)
                }
            }
        }
    }
}
