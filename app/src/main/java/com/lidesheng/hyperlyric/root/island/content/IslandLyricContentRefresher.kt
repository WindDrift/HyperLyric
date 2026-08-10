package com.lidesheng.hyperlyric.root.island.content

import android.content.SharedPreferences
import android.view.ViewGroup
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.island.config.IslandSlotRuntimeConfig
import com.lidesheng.hyperlyric.root.island.host.IslandProbeUtils
import com.lidesheng.hyperlyric.root.island.sizing.IslandDynamicWidthCoordinator
import com.lidesheng.hyperlyric.root.utils.HookLogger

/**
 * Refreshes the content of already injected lyric and metadata slots.
 *
 * Slot structure is owned by [com.lidesheng.hyperlyric.root.island.structure.IslandSlotStructureInjector].
 * Width preflight remains attached to the line-application callbacks so a new lyric line is
 * measured before it becomes visible.
 */
internal object IslandLyricContentRefresher {

    fun refreshCurrentContent(
        rootView: ViewGroup,
        includeLyricSlots: Boolean = true,
        force: Boolean = false,
        suppressAnimation: Boolean = false
    ): Boolean {
        val prefs = HookEntry.instance?.prefs ?: return false
        val config = IslandSlotRuntimeConfig.from(prefs)
        val packageName = LyriconDataBridge.currentLyricPackageName.orEmpty()
        val mediaInfo = MediaMetadataHelper.getMediaInfo(rootView.context, packageName, HookLogger)

        var changed = false
        if (config.leftMode != RootConstants.ISLAND_CONTENT_MODE_NONE &&
            (includeLyricSlots || config.leftMode != RootConstants.ISLAND_CONTENT_MODE_LYRIC)
        ) {
            changed = refreshSlotContent(
                rootView,
                IslandProbeUtils.LEFT_TEST_VIEW_TAG,
                config.leftMode,
                prefs,
                config,
                force,
                suppressAnimation,
                mediaInfo
            ) || changed
        }
        if (config.rightMode != RootConstants.ISLAND_CONTENT_MODE_NONE &&
            (includeLyricSlots || config.rightMode != RootConstants.ISLAND_CONTENT_MODE_LYRIC)
        ) {
            changed = refreshSlotContent(
                rootView,
                IslandProbeUtils.RIGHT_TEST_VIEW_TAG,
                config.rightMode,
                prefs,
                config,
                force,
                suppressAnimation,
                mediaInfo
            ) || changed
        }

        IslandDynamicWidthCoordinator.requestRefresh(rootView)
        return changed
    }

    private fun refreshSlotContent(
        rootView: ViewGroup,
        viewTag: String,
        mode: Int,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        force: Boolean,
        suppressAnimation: Boolean,
        mediaInfo: MediaMetadataHelper.MediaInfo
    ): Boolean {
        val view = rootView.findViewWithTag<android.view.View>(viewTag) ?: return false
        val contentChanged = IslandSlotContentFacade.applySlotContent(
            view,
            prefs,
            config,
            mode,
            force = force,
            suppressAnimation = suppressAnimation,
            mediaInfo = mediaInfo,
            onLineWillApply = { contentWidthPx ->
                IslandDynamicWidthCoordinator.prepareLyricWidth(rootView, viewTag, contentWidthPx)
            },
            onLineApplied = {
                IslandDynamicWidthCoordinator.clearPreflight(rootView, viewTag)
                IslandDynamicWidthCoordinator.requestRefresh(rootView)
            },
            onLineCancelled = {
                IslandDynamicWidthCoordinator.clearPreflight(rootView, viewTag)
            }
        )
        if (mode == RootConstants.ISLAND_CONTENT_MODE_CUSTOM_MUSIC_INFO &&
            IslandDynamicWidthCoordinator.cacheMetadataWidth(rootView, viewTag)
        ) {
            return true
        }
        return contentChanged
    }
}
