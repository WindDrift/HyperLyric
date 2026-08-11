package com.lidesheng.hyperlyric.root.island.renderer

import android.os.Handler
import android.os.Looper
import android.view.View
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.lyric.view.RichLyricLineView
import com.lidesheng.hyperlyric.lyric.view.SpaceGateRichLyricLineView
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.island.config.IslandSlotRuntimeConfig
import com.lidesheng.hyperlyric.root.island.host.IslandHostFacade
import com.lidesheng.hyperlyric.root.island.host.IslandProbeUtils
import com.lidesheng.hyperlyric.root.island.presentation.IslandPresentationCoordinator
import com.lidesheng.hyperlyric.root.island.presentation.IslandReconcileReason

object BaseIslandRenderer : IslandRenderer {

    private const val REFRESH_DEBOUNCE_MS = 32L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { performRefreshActiveIsland() }
    private val textColorRefreshRunnable = Runnable { performUpdateTextColors() }

    /**
     * Source lifecycle events are the authority for lyric rendering state.
     * Hook paths must not re-query MediaSession here: during a lyric refresh the source can
     * already be stopped while the player session still reports STATE_PLAYING.
     */
    fun shouldRenderInjectedIsland(): Boolean {
        return IslandPresentationCoordinator.shouldRenderInjectedIsland()
    }

    override fun refreshActiveIsland() {
        IslandPresentationCoordinator.invalidatePresentation()
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS)
    }

    private fun performRefreshActiveIsland() {
        val prefs = HookEntry.instance?.prefs ?: return
        if (!prefs.getBoolean(
                RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND,
                RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND
            )
        ) {
            clearAllViews()
            return
        }
        if (!shouldRenderInjectedIsland()) {
            IslandPlaybackStateCoordinator.clearActiveViewsForPause()
            return
        }

        val lyricPkg =
            LyriconDataBridge.currentLyricPackageName?.takeIf { it.isNotEmpty() } ?: return
        val expectedLyricVersion = LyriconDataBridge.versionCounter.get()
        val expectedPresentationRevision =
            IslandPresentationCoordinator.currentPresentationRevision()

        IslandContentUpdateCoordinator.invalidate()

        val activeViews = IslandPresentationCoordinator.snapshotAttachedHosts(lyricPkg)
        activeViews.forEach { token ->
            if (IslandPresentationCoordinator.isHostFrozenForFakeTransition(token)) {
                return@forEach
            }
            val cv = token.root
            cv.post {
                if (IslandPresentationCoordinator.isHostFrozenForFakeTransition(token) ||
                    LyriconDataBridge.versionCounter.get() != expectedLyricVersion ||
                    LyriconDataBridge.currentLyricPackageName != lyricPkg
                ) {
                    return@post
                }
                val result = IslandPresentationCoordinator.reconcileRegisteredHost(
                    token,
                    IslandReconcileReason.STABLE_REFRESH,
                    expectedPresentationRevision
                )
                if (!result.isTarget) return@post
                val currentPrefs = HookEntry.instance?.prefs ?: return@post
                val currentConfig = IslandSlotRuntimeConfig.from(currentPrefs)
                IslandContentUpdateCoordinator.updateContentForView(
                    view = cv,
                    packageName = lyricPkg,
                    prefs = currentPrefs,
                    config = currentConfig,
                    playbackActive = IslandPresentationCoordinator.isPlaybackActive()
                )
            }
        }

    }

    override fun updateMetadata() {
        val prefs = HookEntry.instance?.prefs ?: return
        if (!prefs.getBoolean(
                RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND,
                RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND
            )
        ) {
            refreshActiveIsland()
            return
        }
        if (!shouldRenderInjectedIsland()) {
            refreshActiveIsland()
            return
        }

        val lyricPkg = LyriconDataBridge.currentLyricPackageName
            ?.takeIf { it.isNotEmpty() }
            ?: run {
                refreshActiveIsland()
                return
            }
        val expectedLyricVersion = LyriconDataBridge.versionCounter.get()
        val expectedPresentationRevision =
            IslandPresentationCoordinator.currentPresentationRevision()
        val snapshots = IslandPresentationCoordinator.snapshotAttachedInjectedHosts(lyricPkg)
        if (snapshots.isEmpty()) {
            refreshActiveIsland()
            return
        }

        snapshots.forEach { snapshot ->
            val token = snapshot.host
            if (IslandPresentationCoordinator.isHostFrozenForFakeTransition(token)) {
                return@forEach
            }
            val cv = token.root
            cv.post {
                if (!IslandPresentationCoordinator.isCurrentHost(token) ||
                    IslandPresentationCoordinator.isHostFrozenForFakeTransition(token) ||
                    !IslandPresentationCoordinator.isCurrentPresentation(
                        expectedPresentationRevision
                    ) ||
                    LyriconDataBridge.versionCounter.get() != expectedLyricVersion ||
                    LyriconDataBridge.currentLyricPackageName != lyricPkg ||
                    !shouldRenderInjectedIsland()
                ) {
                    return@post
                }
                val currentPrefs = HookEntry.instance?.prefs ?: return@post
                IslandContentUpdateCoordinator.updateMetadataForView(
                    view = cv,
                    packageName = lyricPkg,
                    prefs = currentPrefs,
                    config = IslandSlotRuntimeConfig.from(currentPrefs),
                    playbackActive = IslandPresentationCoordinator.isPlaybackActive()
                )
            }
        }
    }

    override fun updateLyricLine() {
        if ((HookEntry.instance?.prefs?.getBoolean(
                RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND,
                RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND
            )) != true
        ) return
        if (!shouldRenderInjectedIsland()) return
        val lyricPkg = LyriconDataBridge.currentLyricPackageName
        if (lyricPkg.isNullOrEmpty()) return

        val expectedLyricVersion = LyriconDataBridge.versionCounter.get()
        val expectedPresentationRevision =
            IslandPresentationCoordinator.currentPresentationRevision()

        IslandPresentationCoordinator.snapshotAttachedHosts(lyricPkg)
            .forEach { token ->
                if (IslandPresentationCoordinator.isHostFrozenForFakeTransition(token)) {
                    return@forEach
                }
                val cv = token.root
                cv.post {
                    if (IslandPresentationCoordinator.isHostFrozenForFakeTransition(token) ||
                        LyriconDataBridge.versionCounter.get() != expectedLyricVersion ||
                        LyriconDataBridge.currentLyricPackageName != lyricPkg
                    ) {
                        return@post
                    }
                    val result = IslandPresentationCoordinator.reconcileRegisteredHost(
                        token,
                        IslandReconcileReason.LYRIC_SELF_HEAL,
                        expectedPresentationRevision
                    )
                    if (!result.isTarget) return@post
                    val currentPrefs = HookEntry.instance?.prefs ?: return@post
                    val currentConfig = IslandSlotRuntimeConfig.from(currentPrefs)
                    IslandContentUpdateCoordinator.updateLyricContentForView(
                        view = cv,
                        prefs = currentPrefs,
                        config = currentConfig,
                        playbackActive = IslandPresentationCoordinator.isPlaybackActive()
                    )
                }
            }
    }

    override fun updateTextColors() {
        mainHandler.removeCallbacks(textColorRefreshRunnable)
        mainHandler.post(textColorRefreshRunnable)
    }

    private fun performUpdateTextColors() {
        IslandContentUpdateCoordinator.forEachActiveHost { view, packageName, prefs, config ->
            IslandContentUpdateCoordinator.updateTextColorsForView(
                view = view,
                packageName = packageName,
                prefs = prefs,
                config = config
            )
        }
    }

    override fun updatePosition(position: Long, playbackSpeed: Float) {
        val prefs = HookEntry.instance?.prefs ?: return
        if (!prefs.getBoolean(
                RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND,
                RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND
            )
        ) return
        if (!shouldRenderInjectedIsland()) return
        val lyricPkg = LyriconDataBridge.currentLyricPackageName ?: return
        val expectedLyricVersion = LyriconDataBridge.versionCounter.get()
        val expectedPresentationRevision =
            IslandPresentationCoordinator.currentPresentationRevision()

        IslandPresentationCoordinator.snapshotAttachedInjectedHosts(lyricPkg)
            .forEach { snapshot ->
                val token = snapshot.host
                if (IslandPresentationCoordinator.isHostFrozenForFakeTransition(token)) {
                    return@forEach
                }
                val cv = token.root
                val indexedViews = snapshot.injectedViews
                cv.post {
                    if (!IslandPresentationCoordinator.isCurrentHost(token) ||
                        IslandPresentationCoordinator.isHostFrozenForFakeTransition(token) ||
                        !IslandPresentationCoordinator.isCurrentPresentation(
                            expectedPresentationRevision
                        ) ||
                        LyriconDataBridge.versionCounter.get() != expectedLyricVersion ||
                        LyriconDataBridge.currentLyricPackageName != lyricPkg ||
                        !shouldRenderInjectedIsland()
                    ) {
                        return@post
                    }
                    val currentPrefs = HookEntry.instance?.prefs ?: return@post
                    val playbackViews: Iterable<View>
                    if (indexedViews.isEmpty()) {
                        val leftView = cv.findViewWithTag<View>(IslandProbeUtils.LEFT_TEST_VIEW_TAG)
                        val rightView = cv.findViewWithTag<View>(IslandProbeUtils.RIGHT_TEST_VIEW_TAG)
                        setPosition(leftView, position, playbackSpeed)
                        setPosition(rightView, position, playbackSpeed)
                        playbackViews = listOfNotNull(leftView, rightView)
                        IslandPresentationCoordinator.refreshInjectedViewIndex(token)
                    } else {
                        indexedViews.forEach { view ->
                            setPosition(view, position, playbackSpeed)
                        }
                        playbackViews = indexedViews
                    }
                    IslandContentUpdateCoordinator.updatePlaybackProgressForViews(
                        rootView = cv,
                        slotViews = playbackViews,
                        position = position
                    )
                    IslandHostFacade.updateProgressGlow(cv, lyricPkg, currentPrefs)
                }
            }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        IslandPlaybackStateCoordinator.onPlaybackStateChanged(
            isPlaying = isPlaying,
            onRefreshRequested = { refreshActiveIsland() },
            onClearRequested = { clearAllViews() }
        )
    }

    private fun setPosition(view: View?, position: Long, playbackSpeed: Float) {
        when (view) {
            is RichLyricLineView -> view.setPosition(position, playbackSpeed)
            is SpaceGateRichLyricLineView -> view.setPosition(position, playbackSpeed)
        }
    }

    override fun clearAllViews() {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.removeCallbacks(textColorRefreshRunnable)
        val wasPlaybackActive = IslandPresentationCoordinator.isPlaybackActive()
        if (wasPlaybackActive) {
            IslandPresentationCoordinator.updatePlaybackState(false)
        }
        // A pause callback may already have queued the native restoration. Keep the same
        // revision so a subsequent source onStop() does not cancel that first clear and cause a
        // second visible Super Island relayout.
        val expectedPresentationRevision = if (wasPlaybackActive) {
            IslandPresentationCoordinator.invalidatePresentation()
        } else {
            IslandPresentationCoordinator.currentPresentationRevision()
        }
        IslandPlaybackStateCoordinator.markClearedByPause()
        IslandPresentationCoordinator.snapshotAttachedHosts()
            .forEach { token ->
                val cv = token.root
                cv.post {
                    IslandPresentationCoordinator.clearRegisteredHost(
                        token,
                        expectedPresentationRevision
                    )
                }
            }
    }

}
