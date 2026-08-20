package com.lidesheng.hyperlyric.root.island.renderer

import android.view.ViewGroup
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.island.config.IslandSlotRuntimeConfig
import com.lidesheng.hyperlyric.root.island.hooks.IslandWidthLimitHooker
import com.lidesheng.hyperlyric.root.island.presentation.IslandNativeRefreshCoordinator
import com.lidesheng.hyperlyric.root.island.presentation.IslandPresentationCoordinator

/**
 * Coordinates settings changes that affect Xiaomi's media-island layout or native artwork views.
 * Xiaomi owns the rebind/measure/layout step; HyperLyric only reapplies its content afterward.
 */
internal object IslandSettingsRefreshCoordinator {
    fun request() {
        IslandPresentationCoordinator.invalidatePresentation()
        IslandNativeRefreshCoordinator.request(
            onComplete = ::refreshHyperLyricContent
        )
    }

    private fun refreshHyperLyricContent(root: ViewGroup) {
        val packageName = LyriconDataBridge.currentLyricPackageName
            ?.takeIf { it.isNotEmpty() }
            ?: return
        val prefs = HookEntry.instance?.prefs ?: return
        IslandWidthLimitHooker.refresh(root)
        IslandContentUpdateCoordinator.updateContentForView(
            view = root,
            packageName = packageName,
            prefs = prefs,
            config = IslandSlotRuntimeConfig.from(prefs),
            playbackActive = IslandPresentationCoordinator.isPlaybackActive()
        )
    }
}
