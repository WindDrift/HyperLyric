package com.lidesheng.hyperlyric.root.island.presentation

import android.view.View
import android.view.ViewGroup
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.lyric.view.RichLyricLineView
import com.lidesheng.hyperlyric.lyric.view.SpaceGateRichLyricLineView
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.island.config.IslandSlotRuntimeConfig
import com.lidesheng.hyperlyric.root.island.host.IslandProbeUtils

/**
 * Controls playback state for injected lyric views while a fake snapshot is shown or a real host
 * is resumed.
 */
internal object IslandLyricPlaybackController {

    fun freezeInjectedLyricProgress(rootView: ViewGroup, position: Long) {
        val prefs = HookEntry.instance?.prefs ?: return
        val config = IslandSlotRuntimeConfig.from(prefs)

        if (config.leftMode == RootConstants.ISLAND_CONTENT_MODE_LYRIC) {
            freezeLyricView(rootView.findViewWithTag(IslandProbeUtils.LEFT_TEST_VIEW_TAG), position)
        }
        if (config.rightMode == RootConstants.ISLAND_CONTENT_MODE_LYRIC) {
            freezeLyricView(
                rootView.findViewWithTag(IslandProbeUtils.RIGHT_TEST_VIEW_TAG),
                position
            )
        }
    }

    fun resumeInjectedLyricProgress(rootView: ViewGroup, position: Long) {
        val prefs = HookEntry.instance?.prefs ?: return
        val config = IslandSlotRuntimeConfig.from(prefs)

        if (config.leftMode == RootConstants.ISLAND_CONTENT_MODE_LYRIC) {
            resumeLyricView(rootView.findViewWithTag(IslandProbeUtils.LEFT_TEST_VIEW_TAG), position)
        }
        if (config.rightMode == RootConstants.ISLAND_CONTENT_MODE_LYRIC) {
            resumeLyricView(
                rootView.findViewWithTag(IslandProbeUtils.RIGHT_TEST_VIEW_TAG),
                position
            )
        }
    }

    private fun freezeLyricView(view: View?, position: Long) {
        when (view) {
            is RichLyricLineView -> {
                view.setPlaybackActive(false)
                view.setPosition(position)
                view.setPlaybackActive(false)
            }

            is SpaceGateRichLyricLineView -> {
                view.setPlaybackActive(false)
                view.setPosition(position)
                view.setPlaybackActive(false)
            }
        }
    }

    private fun resumeLyricView(view: View?, position: Long) {
        when (view) {
            is RichLyricLineView -> {
                view.setPlaybackActive(true)
                view.setPosition(position)
            }

            is SpaceGateRichLyricLineView -> {
                view.setPlaybackActive(true)
                view.setPosition(position)
            }
        }
    }
}
