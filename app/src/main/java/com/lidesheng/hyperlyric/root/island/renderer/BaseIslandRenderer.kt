package com.lidesheng.hyperlyric.root.island.renderer

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.common.LyricTextColorStylePolicy
import com.lidesheng.hyperlyric.common.SuperIslandContentStylePolicy
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import com.lidesheng.hyperlyric.lyric.view.RichLyricLineView
import com.lidesheng.hyperlyric.lyric.view.SpaceGateRichLyricLineView
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.island.config.IslandSlotRuntimeConfig
import com.lidesheng.hyperlyric.root.island.content.IslandSlotContentFacade
import com.lidesheng.hyperlyric.root.island.effects.color.IslandMusicWaveColorHooker
import com.lidesheng.hyperlyric.root.island.effects.glow.IslandProgressGlowController
import com.lidesheng.hyperlyric.root.island.host.IslandHostFacade
import com.lidesheng.hyperlyric.root.island.host.IslandProbeUtils
import com.lidesheng.hyperlyric.root.island.presentation.IslandPresentationCoordinator
import com.lidesheng.hyperlyric.root.island.presentation.IslandReconcileReason
import com.lidesheng.hyperlyric.root.island.sizing.IslandDynamicWidthCoordinator
import com.lidesheng.hyperlyric.root.utils.CoverColorHelper
import com.lidesheng.hyperlyric.root.utils.HookLogger

object BaseIslandRenderer : IslandRenderer {

    private const val REFRESH_DEBOUNCE_MS = 32L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { performRefreshActiveIsland() }
    private val textColorRefreshRunnable = Runnable { performUpdateTextColors() }

    @Volatile
    private var clearedByPause = false

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
            clearActiveViewsForPause()
            return
        }

        val lyricPkg =
            LyriconDataBridge.currentLyricPackageName?.takeIf { it.isNotEmpty() } ?: return
        val expectedLyricVersion = LyriconDataBridge.versionCounter.get()
        val expectedPresentationRevision =
            IslandPresentationCoordinator.currentPresentationRevision()

        IslandSlotContentFacade.invalidate()

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
                updateContentForView(cv, lyricPkg, currentPrefs, currentConfig)
            }
        }

        HookLogger.d("BaseIslandRenderer", "已刷新活动媒体岛: 数量=${activeViews.size}")
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
                    if (result.mutation.relayoutRequested) {
                        HookLogger.d("BaseIslandRenderer", "歌词更新时已补齐缺失的首帧注入")
                    }
                    val currentPrefs = HookEntry.instance?.prefs ?: return@post
                    val currentConfig = IslandSlotRuntimeConfig.from(currentPrefs)
                    updateLyricContentForView(cv, currentPrefs, currentConfig)
                }
            }
    }

    override fun updateTextColors() {
        mainHandler.removeCallbacks(textColorRefreshRunnable)
        mainHandler.post(textColorRefreshRunnable)
    }

    private fun performUpdateTextColors() {
        forEachActiveHost { cv, packageName, prefs, config ->
            val mediaInfo = MediaMetadataHelper.getMediaInfo(cv.context, packageName, HookLogger)
            prepareSharedCoverPalette(packageName, mediaInfo, prefs)
            updateSlotColors(
                cv,
                IslandProbeUtils.LEFT_TEST_VIEW_TAG,
                config.leftMode,
                prefs,
                config,
                mediaInfo
            )
            updateSlotColors(
                cv,
                IslandProbeUtils.RIGHT_TEST_VIEW_TAG,
                config.rightMode,
                prefs,
                config,
                mediaInfo
            )
        }
    }

    override fun updatePosition(position: Long) {
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
                    if (indexedViews.isEmpty()) {
                        setPosition(
                            cv.findViewWithTag(IslandProbeUtils.LEFT_TEST_VIEW_TAG),
                            position
                        )
                        setPosition(
                            cv.findViewWithTag(IslandProbeUtils.RIGHT_TEST_VIEW_TAG),
                            position
                        )
                        IslandPresentationCoordinator.refreshInjectedViewIndex(token)
                    } else {
                        indexedViews.forEach { view -> setPosition(view, position) }
                    }
                    IslandHostFacade.updateProgressGlow(cv, lyricPkg, currentPrefs)
                }
            }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        val prefs = HookEntry.instance?.prefs ?: return
        if (!prefs.getBoolean(
                RootConstants.KEY_HOOK_ENABLE_SUPER_ISLAND,
                RootConstants.DEFAULT_HOOK_ENABLE_SUPER_ISLAND
            )
        ) {
            clearAllViews()
            return
        }
        val stateChanged = IslandPresentationCoordinator.updatePlaybackState(isPlaying)
        if (stateChanged) {
            IslandProgressGlowController.onPlaybackStateChanged(isPlaying)
        }
        HookLogger.d("BaseIslandRenderer", "播放状态变化: 正在播放=$isPlaying")
        val behavior = prefs.getInt(
            RootConstants.KEY_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE,
            RootConstants.DEFAULT_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE
        )

        if (isPlaying) {
            if (clearedByPause) {
                clearedByPause = false
                restoreActiveViewsAfterPause()
            } else if (stateChanged) {
                applyPlaybackStateToActiveViews(true)
            }
            HookLogger.d("BaseIslandRenderer", "播放已继续，等待进度或歌词事件")
        } else if (behavior == 0) {
            if (!clearedByPause) {
                clearActiveViewsForPause()
                HookLogger.d("BaseIslandRenderer", "已暂停，恢复原生媒体岛")
            } else {
                HookLogger.d("BaseIslandRenderer", "忽略重复暂停状态，原生媒体岛已恢复")
            }
        } else if (stateChanged) {
            applyPlaybackStateToActiveViews(false)
            HookLogger.d("BaseIslandRenderer", "已暂停，保留当前歌词注入")
        }
    }

    private fun restoreActiveViewsAfterPause() {
        val lyricPkg = LyriconDataBridge.currentLyricPackageName
            ?.takeIf { it.isNotEmpty() }
            ?: return
        val expectedLyricVersion = LyriconDataBridge.versionCounter.get()
        val expectedPresentationRevision =
            IslandPresentationCoordinator.currentPresentationRevision()
        val activeViews = IslandPresentationCoordinator.snapshotAttachedHosts(lyricPkg)
        if (activeViews.isEmpty()) {
            refreshActiveIsland()
            return
        }

        activeViews.forEach { token ->
            if (IslandPresentationCoordinator.isHostFrozenForFakeTransition(token)) {
                return@forEach
            }
            val cv = token.root
            cv.post {
                if (IslandPresentationCoordinator.isHostFrozenForFakeTransition(token) ||
                    !shouldRenderInjectedIsland() ||
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
                val expectsInjectedView = config.leftMode != 0 || config.rightMode != 0
                if (expectsInjectedView &&
                    result.mutation.injectedSlotsPresent == false
                ) {
                    refreshActiveIsland()
                    return@post
                }

                setPlaybackActiveRecursively(cv, true)
            }
        }
    }

    private fun clearActiveViewsForPause() {
        val lyricPkg = LyriconDataBridge.currentLyricPackageName
        val expectedPresentationRevision =
            IslandPresentationCoordinator.currentPresentationRevision()
        IslandPresentationCoordinator.snapshotAttachedHosts()
            .filter { token -> lyricPkg == null || token.packageName == lyricPkg }
            .forEach { token ->
                val cv = token.root
                cv.post {
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
                val cv = token.root
                val indexedViews = snapshot.injectedViews
                cv.post {
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
                        setPlaybackActiveRecursively(cv, isPlaying)
                        IslandPresentationCoordinator.refreshInjectedViewIndex(token)
                    } else {
                        indexedViews.forEach { view ->
                            setPlaybackActive(view, isPlaying)
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

    private fun setPosition(view: View?, position: Long) {
        when (view) {
            is RichLyricLineView -> view.setPosition(position)
            is SpaceGateRichLyricLineView -> view.setPosition(position)
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

    override fun clearAllViews() {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.removeCallbacks(textColorRefreshRunnable)
        IslandPresentationCoordinator.updatePlaybackState(false)
        val expectedPresentationRevision =
            IslandPresentationCoordinator.invalidatePresentation()
        clearedByPause = true
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

    private fun updateContentForView(
        cv: ViewGroup,
        packageName: String,
        prefs: android.content.SharedPreferences,
        config: IslandSlotRuntimeConfig
    ) {
        val mediaInfo = MediaMetadataHelper.getMediaInfo(cv.context, packageName, HookLogger)
        prepareSharedCoverPalette(packageName, mediaInfo, prefs)
        IslandHostFacade.updateHostGlow(cv, prefs)
        IslandHostFacade.updateProgressGlow(cv, packageName, mediaInfo, prefs)
        updateSlot(
            cv,
            IslandProbeUtils.LEFT_TEST_VIEW_TAG,
            config.leftMode,
            prefs,
            config,
            mediaInfo
        )
        updateSlot(
            cv,
            IslandProbeUtils.RIGHT_TEST_VIEW_TAG,
            config.rightMode,
            prefs,
            config,
            mediaInfo
        )
        IslandMusicWaveColorHooker.refresh()
    }

    private fun forEachActiveHost(
        update: (
            ViewGroup,
            String,
            android.content.SharedPreferences,
            IslandSlotRuntimeConfig
        ) -> Unit
    ) {
        if (!shouldRenderInjectedIsland()) return
        val packageName = LyriconDataBridge.currentLyricPackageName
            ?.takeIf { it.isNotEmpty() }
            ?: return
        val expectedLyricVersion = LyriconDataBridge.versionCounter.get()
        val expectedPresentationRevision =
            IslandPresentationCoordinator.currentPresentationRevision()

        IslandPresentationCoordinator.snapshotAttachedHosts(packageName).forEach { token ->
            if (IslandPresentationCoordinator.isHostFrozenForFakeTransition(token)) {
                return@forEach
            }
            token.root.post {
                if (!IslandPresentationCoordinator.isCurrentHost(token) ||
                    IslandPresentationCoordinator.isHostFrozenForFakeTransition(token) ||
                    !IslandPresentationCoordinator.isCurrentPresentation(
                        expectedPresentationRevision
                    ) ||
                    LyriconDataBridge.versionCounter.get() != expectedLyricVersion ||
                    LyriconDataBridge.currentLyricPackageName != packageName ||
                    !shouldRenderInjectedIsland()
                ) {
                    return@post
                }
                val prefs = HookEntry.instance?.prefs ?: return@post
                update(token.root, packageName, prefs, IslandSlotRuntimeConfig.from(prefs))
            }
        }
    }

    /**
     * The MediaSession artwork is the only color source. Populate the shared palette before
     * individual consumers render so their color lifecycle does not depend on MusicWave
     * callbacks, which disappear when an external-device icon occupies that island slot.
     */
    private fun prepareSharedCoverPalette(
        packageName: String,
        mediaInfo: MediaMetadataHelper.MediaInfo,
        prefs: android.content.SharedPreferences
    ) {
        val usesCoverPalette =
            LyricTextColorStylePolicy.usesCoverColor(
                LyricTextColorStylePolicy.read(prefs)
            ) ||
                    SuperIslandContentStylePolicy.usesMusicWaveCoverColor(
                        SuperIslandContentStylePolicy.readMusicWaveStyle(prefs)
                    ) ||
                    prefs.getBoolean(
                        RootConstants.KEY_HOOK_ISLAND_GLOW_EXTRACT_COLOR,
                        RootConstants.DEFAULT_HOOK_ISLAND_GLOW_EXTRACT_COLOR
                    )
        if (!usesCoverPalette) return

        val albumArt = mediaInfo.albumArt?.takeUnless { it.isRecycled } ?: return
        CoverColorHelper.ensureArtworkColors(
            packageName = packageName,
            title = mediaInfo.title,
            artist = mediaInfo.artist,
            bitmap = albumArt
        )
    }

    private fun updateLyricContentForView(
        cv: ViewGroup,
        prefs: android.content.SharedPreferences,
        config: IslandSlotRuntimeConfig
    ) {
        updateLyricSlot(cv, IslandProbeUtils.LEFT_TEST_VIEW_TAG, config.leftMode, prefs, config)
        updateLyricSlot(cv, IslandProbeUtils.RIGHT_TEST_VIEW_TAG, config.rightMode, prefs, config)
    }

    private fun updateLyricSlot(
        cv: ViewGroup,
        tag: String,
        mode: Int,
        prefs: android.content.SharedPreferences,
        config: IslandSlotRuntimeConfig
    ) {
        if (mode != 7) return
        val view = cv.findViewWithTag<View>(tag) ?: return
        val line = IslandSlotContentFacade.buildSlotLyricLine(
            view = view,
            prefs = prefs,
            config = config,
            isLeft = tag == IslandProbeUtils.LEFT_TEST_VIEW_TAG
        )
        IslandSlotContentFacade.applyLyricLineContent(
            view = view,
            prefs = prefs,
            config = config,
            lineOverride = line,
            playbackActive = IslandPresentationCoordinator.isPlaybackActive(),
            onLineWillApply = { contentWidthPx ->
                IslandDynamicWidthCoordinator.prepareLyricWidth(cv, tag, contentWidthPx)
            },
            onLineApplied = {
                IslandDynamicWidthCoordinator.clearPreflight(cv, tag)
                IslandDynamicWidthCoordinator.requestRefresh(cv)
            },
            onLineCancelled = {
                IslandDynamicWidthCoordinator.clearPreflight(cv, tag)
            }
        )
    }

    private fun updateSlot(
        cv: ViewGroup,
        tag: String,
        mode: Int,
        prefs: android.content.SharedPreferences,
        config: IslandSlotRuntimeConfig,
        mediaInfo: MediaMetadataHelper.MediaInfo
    ) {
        if (mode == 0) return
        val view = cv.findViewWithTag<View>(tag) ?: return
        val lineOverride = if (mode == 7) {
            IslandSlotContentFacade.buildSlotLyricLine(
                view = view,
                prefs = prefs,
                config = config,
                isLeft = tag == IslandProbeUtils.LEFT_TEST_VIEW_TAG
            )
        } else {
            null
        }
        IslandSlotContentFacade.applySlotContent(
            view = view,
            prefs = prefs,
            config = config,
            mode = mode,
            lineOverride = lineOverride,
            playbackActive = IslandPresentationCoordinator.isPlaybackActive(),
            mediaInfo = mediaInfo,
            onLineWillApply = { contentWidthPx ->
                IslandDynamicWidthCoordinator.prepareLyricWidth(cv, tag, contentWidthPx)
            },
            onLineApplied = {
                IslandDynamicWidthCoordinator.clearPreflight(cv, tag)
                IslandDynamicWidthCoordinator.requestRefresh(cv)
            },
            onLineCancelled = {
                IslandDynamicWidthCoordinator.clearPreflight(cv, tag)
            }
        )
    }

    private fun updateSlotColors(
        cv: ViewGroup,
        tag: String,
        mode: Int,
        prefs: android.content.SharedPreferences,
        config: IslandSlotRuntimeConfig,
        mediaInfo: MediaMetadataHelper.MediaInfo
    ) {
        if (mode == 0) return
        val view = cv.findViewWithTag<View>(tag) ?: return
        IslandSlotContentFacade.configureView(
            view = view,
            prefs = prefs,
            config = config,
            mode = mode,
            mediaInfo = mediaInfo
        )
    }

}
