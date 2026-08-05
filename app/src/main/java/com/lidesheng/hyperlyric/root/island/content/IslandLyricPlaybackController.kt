package com.lidesheng.hyperlyric.root.island.content

import android.view.View
import android.view.ViewGroup
import com.lidesheng.hyperlyric.lyric.view.RichLyricLineView
import com.lidesheng.hyperlyric.lyric.view.SpaceGateRichLyricLineView
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.island.IslandProbeUtils
import com.lidesheng.hyperlyric.root.island.IslandSlotRuntimeConfig

/**
 * Controls playback state for injected lyric views while a fake snapshot is shown or a real host
 * is resumed.
 */
internal object IslandLyricPlaybackController {

    fun freezeInjectedLyricProgress(rootView: ViewGroup, position: Long) {
        val prefs = HookEntry.instance?.prefs ?: return
        val config = IslandSlotRuntimeConfig.from(prefs)

        if (config.leftMode == 7) {
            freezeLyricView(rootView.findViewWithTag(IslandProbeUtils.LEFT_TEST_VIEW_TAG), position)
        }
        if (config.rightMode == 7) {
            freezeLyricView(
                rootView.findViewWithTag(IslandProbeUtils.RIGHT_TEST_VIEW_TAG),
                position
            )
        }
    }

    fun resumeInjectedLyricProgress(rootView: ViewGroup, position: Long) {
        val prefs = HookEntry.instance?.prefs ?: return
        val config = IslandSlotRuntimeConfig.from(prefs)

        if (config.leftMode == 7) {
            resumeLyricView(rootView.findViewWithTag(IslandProbeUtils.LEFT_TEST_VIEW_TAG), position)
        }
        if (config.rightMode == 7) {
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
