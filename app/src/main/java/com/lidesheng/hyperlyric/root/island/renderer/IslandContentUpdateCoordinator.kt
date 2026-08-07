package com.lidesheng.hyperlyric.root.island.renderer

import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import com.lidesheng.hyperlyric.common.LyricTextColorStylePolicy
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.common.SuperIslandContentStylePolicy
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.island.config.IslandSlotRuntimeConfig
import com.lidesheng.hyperlyric.root.island.content.IslandSlotContentFacade
import com.lidesheng.hyperlyric.root.island.effects.color.IslandMusicWaveColorHooker
import com.lidesheng.hyperlyric.root.island.host.IslandHostFacade
import com.lidesheng.hyperlyric.root.island.host.IslandProbeUtils
import com.lidesheng.hyperlyric.root.island.presentation.IslandPresentationCoordinator
import com.lidesheng.hyperlyric.root.island.sizing.IslandDynamicWidthCoordinator
import com.lidesheng.hyperlyric.root.utils.CoverColorHelper
import com.lidesheng.hyperlyric.root.utils.HookLogger

/**
 * Updates the content and visual style of already injected island slots.
 *
 * Host lifecycle and playback transitions stay in their own coordinators. This object only
 * translates the current lyric/media state into view updates and keeps the dynamic-width
 * preflight callbacks next to the content application that owns them.
 */
internal object IslandContentUpdateCoordinator {

    fun invalidate() {
        IslandSlotContentFacade.invalidate()
    }

    fun updateContentForView(
        view: ViewGroup,
        packageName: String,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        playbackActive: Boolean
    ) {
        val mediaInfo = MediaMetadataHelper.getMediaInfo(view.context, packageName, HookLogger)
        prepareSharedCoverPalette(packageName, mediaInfo, prefs)
        IslandHostFacade.updateHostGlow(view, prefs)
        IslandHostFacade.updateProgressGlow(view, packageName, mediaInfo, prefs)
        updateSlot(
            view,
            IslandProbeUtils.LEFT_TEST_VIEW_TAG,
            config.leftMode,
            prefs,
            config,
            mediaInfo,
            playbackActive
        )
        updateSlot(
            view,
            IslandProbeUtils.RIGHT_TEST_VIEW_TAG,
            config.rightMode,
            prefs,
            config,
            mediaInfo,
            playbackActive
        )
        IslandMusicWaveColorHooker.refresh()
    }

    fun updateLyricContentForView(
        view: ViewGroup,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        playbackActive: Boolean
    ) {
        updateLyricSlot(
            view,
            IslandProbeUtils.LEFT_TEST_VIEW_TAG,
            config.leftMode,
            prefs,
            config,
            playbackActive
        )
        updateLyricSlot(
            view,
            IslandProbeUtils.RIGHT_TEST_VIEW_TAG,
            config.rightMode,
            prefs,
            config,
            playbackActive
        )
    }

    fun updateTextColorsForView(
        view: ViewGroup,
        packageName: String,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig
    ) {
        val mediaInfo = MediaMetadataHelper.getMediaInfo(view.context, packageName, HookLogger)
        prepareSharedCoverPalette(packageName, mediaInfo, prefs)
        updateSlotColors(
            view,
            IslandProbeUtils.LEFT_TEST_VIEW_TAG,
            config.leftMode,
            prefs,
            config,
            mediaInfo
        )
        updateSlotColors(
            view,
            IslandProbeUtils.RIGHT_TEST_VIEW_TAG,
            config.rightMode,
            prefs,
            config,
            mediaInfo
        )
    }

    fun forEachActiveHost(
        update: (
            ViewGroup,
            String,
            SharedPreferences,
            IslandSlotRuntimeConfig
        ) -> Unit
    ) {
        if (!IslandPresentationCoordinator.shouldRenderInjectedIsland()) return
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
                    !IslandPresentationCoordinator.shouldRenderInjectedIsland()
                ) {
                    return@post
                }
                val prefs = HookEntry.instance?.prefs ?: return@post
                update(
                    token.root,
                    packageName,
                    prefs,
                    IslandSlotRuntimeConfig.from(prefs)
                )
            }
        }
    }

    private fun updateLyricSlot(
        view: ViewGroup,
        tag: String,
        mode: Int,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        playbackActive: Boolean
    ) {
        if (mode != RootConstants.ISLAND_CONTENT_MODE_LYRIC) return
        val lyricView = view.findViewWithTag<View>(tag) ?: return
        val line = IslandSlotContentFacade.buildSlotLyricLine(
            view = lyricView,
            prefs = prefs,
            config = config,
            isLeft = tag == IslandProbeUtils.LEFT_TEST_VIEW_TAG
        )
        IslandSlotContentFacade.applyLyricLineContent(
            view = lyricView,
            prefs = prefs,
            config = config,
            lineOverride = line,
            playbackActive = playbackActive,
            onLineWillApply = { contentWidthPx ->
                IslandDynamicWidthCoordinator.prepareLyricWidth(view, tag, contentWidthPx)
            },
            onLineApplied = {
                IslandDynamicWidthCoordinator.clearPreflight(view, tag)
                IslandDynamicWidthCoordinator.requestRefresh(view)
            },
            onLineCancelled = {
                IslandDynamicWidthCoordinator.clearPreflight(view, tag)
            }
        )
    }

    private fun updateSlot(
        view: ViewGroup,
        tag: String,
        mode: Int,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        mediaInfo: MediaMetadataHelper.MediaInfo,
        playbackActive: Boolean
    ) {
        if (mode == RootConstants.ISLAND_CONTENT_MODE_NONE) return
        val slotView = view.findViewWithTag<View>(tag) ?: return
        val lineOverride = if (mode == RootConstants.ISLAND_CONTENT_MODE_LYRIC) {
            IslandSlotContentFacade.buildSlotLyricLine(
                view = slotView,
                prefs = prefs,
                config = config,
                isLeft = tag == IslandProbeUtils.LEFT_TEST_VIEW_TAG
            )
        } else {
            null
        }
        IslandSlotContentFacade.applySlotContent(
            view = slotView,
            prefs = prefs,
            config = config,
            mode = mode,
            lineOverride = lineOverride,
            playbackActive = playbackActive,
            mediaInfo = mediaInfo,
            onLineWillApply = { contentWidthPx ->
                IslandDynamicWidthCoordinator.prepareLyricWidth(view, tag, contentWidthPx)
            },
            onLineApplied = {
                IslandDynamicWidthCoordinator.clearPreflight(view, tag)
                IslandDynamicWidthCoordinator.requestRefresh(view)
            },
            onLineCancelled = {
                IslandDynamicWidthCoordinator.clearPreflight(view, tag)
            }
        )
    }

    private fun updateSlotColors(
        view: ViewGroup,
        tag: String,
        mode: Int,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        mediaInfo: MediaMetadataHelper.MediaInfo
    ) {
        if (mode == RootConstants.ISLAND_CONTENT_MODE_NONE) return
        val slotView = view.findViewWithTag<View>(tag) ?: return
        IslandSlotContentFacade.configureView(
            view = slotView,
            prefs = prefs,
            config = config,
            mode = mode,
            mediaInfo = mediaInfo
        )
    }

    /**
     * The MediaSession artwork is the only color source. Populate the shared palette before
     * individual consumers render so their color lifecycle does not depend on MusicWave
     * callbacks, which disappear when an external-device icon occupies that island slot.
     */
    private fun prepareSharedCoverPalette(
        packageName: String,
        mediaInfo: MediaMetadataHelper.MediaInfo,
        prefs: SharedPreferences
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
}
