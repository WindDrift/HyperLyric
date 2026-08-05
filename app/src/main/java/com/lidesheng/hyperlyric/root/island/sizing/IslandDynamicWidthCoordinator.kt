package com.lidesheng.hyperlyric.root.island.sizing

import android.view.View
import android.view.ViewGroup
import com.lidesheng.hyperlyric.lyric.view.RichLyricLineView
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.island.config.IslandSlotRuntimeConfig
import com.lidesheng.hyperlyric.root.island.host.IslandHostFacade
import com.lidesheng.hyperlyric.root.island.host.IslandProbeUtils
import com.lidesheng.hyperlyric.root.island.host.IslandViewRegistry
import com.lidesheng.hyperlyric.root.island.view.MaxWidthFrameLayout
import java.util.WeakHashMap

/**
 * Owns the root-scoped state and UI scheduling for dynamic lyric width updates.
 *
 * The actual width math stays in [IslandLyricWidthCalculator]. This coordinator only reads the
 * current lyric View state, retains preflight candidates until the line is committed, and asks
 * the SystemUI host to relayout after a wrapper width changes.
 */
internal object IslandDynamicWidthCoordinator {
    private val refreshPending = WeakHashMap<ViewGroup, Boolean>()
    private val preflightTargets = WeakHashMap<ViewGroup, MutableMap<String, Float>>()

    fun requestRefresh(rootView: ViewGroup) {
        val shouldPost = synchronized(refreshPending) {
            if (refreshPending[rootView] == true) {
                false
            } else {
                refreshPending[rootView] = true
                true
            }
        }
        if (!shouldPost) return

        val posted = rootView.post {
            synchronized(refreshPending) {
                refreshPending.remove(rootView)
            }
            if (IslandViewRegistry.tokenFor(rootView) == null) return@post
            val prefs = HookEntry.instance?.prefs ?: return@post
            val config = IslandSlotRuntimeConfig.from(prefs)
            if (!config.geometry.isDynamicWidth) return@post
            if (refreshDynamicLyricWidths(rootView, config)) {
                IslandHostFacade.triggerSystemRelayout(rootView)
            }
        }
        if (!posted) {
            synchronized(refreshPending) {
                refreshPending.remove(rootView)
            }
        }
    }

    /**
     * Applies the candidate line width before the candidate line is committed to the lyric View.
     * Candidates remain available until the corresponding line-application callback clears them.
     */
    fun prepareLyricWidth(
        rootView: ViewGroup,
        viewTag: String,
        contentWidthPx: Float
    ): Boolean {
        val prefs = HookEntry.instance?.prefs ?: return false
        val config = IslandSlotRuntimeConfig.from(prefs)
        if (!config.geometry.isDynamicWidth || config.modeForTag(viewTag) != 7) return false
        if (IslandViewRegistry.tokenFor(rootView) == null) return false

        val overrides = synchronized(preflightTargets) {
            val rootTargets = preflightTargets.getOrPut(rootView) { hashMapOf() }
            rootTargets[viewTag] = contentWidthPx
            rootTargets.toMap()
        }
        val changed = refreshDynamicLyricWidths(rootView, config, overrides)
        if (changed) {
            IslandHostFacade.triggerSystemRelayout(rootView)
        }
        return changed
    }

    fun clearPreflight(rootView: ViewGroup, viewTag: String) {
        synchronized(preflightTargets) {
            val rootTargets = preflightTargets[rootView] ?: return
            rootTargets.remove(viewTag)
            if (rootTargets.isEmpty()) {
                preflightTargets.remove(rootView)
            }
        }
    }

    private fun refreshDynamicLyricWidths(
        rootView: ViewGroup,
        config: IslandSlotRuntimeConfig,
        contentWidthOverrides: Map<String, Float> = emptyMap()
    ): Boolean {
        if (!config.geometry.isDynamicWidth) return false

        val lyricBaseWidthDp = listOf(
            dynamicLyricBaseWidthDp(
                rootView,
                IslandProbeUtils.LEFT_PARENT_NAME,
                IslandProbeUtils.LEFT_TEST_VIEW_TAG,
                config,
                contentWidthOverrides[IslandProbeUtils.LEFT_TEST_VIEW_TAG]
            ),
            dynamicLyricBaseWidthDp(
                rootView,
                IslandProbeUtils.RIGHT_PARENT_NAME,
                IslandProbeUtils.RIGHT_TEST_VIEW_TAG,
                config,
                contentWidthOverrides[IslandProbeUtils.RIGHT_TEST_VIEW_TAG]
            )
        ).filterNotNull().maxOrNull() ?: return false
        val baseWidthDp = lyricBaseWidthDp.coerceIn(
            config.geometry.rightMinWidthDp.toFloat(),
            config.geometry.rightMaxWidthDp.toFloat()
        )

        var changed = false
        if (config.leftMode != 0) {
            changed = updateDynamicSlotWidth(
                rootView,
                IslandProbeUtils.LEFT_PARENT_NAME,
                IslandProbeUtils.LEFT_TEST_VIEW_TAG,
                config,
                baseWidthDp
            ) || changed
        }
        if (config.rightMode != 0) {
            changed = updateDynamicSlotWidth(
                rootView,
                IslandProbeUtils.RIGHT_PARENT_NAME,
                IslandProbeUtils.RIGHT_TEST_VIEW_TAG,
                config,
                baseWidthDp
            ) || changed
        }
        return changed
    }

    private fun dynamicLyricBaseWidthDp(
        rootView: ViewGroup,
        parentName: String,
        viewTag: String,
        config: IslandSlotRuntimeConfig,
        contentWidthOverridePx: Float? = null
    ): Float? {
        if (config.modeForTag(viewTag) != 7) return null
        val lyricView = rootView.findViewWithTag<View>(viewTag) as? RichLyricLineView
            ?: return null
        return IslandLyricWidthCalculator.baseWidthDp(
            contentWidthPx = contentWidthOverridePx ?: lyricView.main.lineWidth,
            spec = dynamicLyricWidthSpec(rootView, parentName, config)
        )
    }

    private fun updateDynamicSlotWidth(
        rootView: ViewGroup,
        parentName: String,
        viewTag: String,
        config: IslandSlotRuntimeConfig,
        baseWidthDp: Float
    ): Boolean {
        val targetWidthPx = IslandLyricWidthCalculator.targetWidthPx(
            baseWidthDp = baseWidthDp,
            spec = dynamicLyricWidthSpec(rootView, parentName, config)
        ) ?: return false
        val wrapper = rootView.findViewWithTag<View>("${viewTag}_WRAPPER")
            as? MaxWidthFrameLayout ?: return false
        if (wrapper.maxWidthPx == targetWidthPx) return false

        wrapper.maxWidthPx = targetWidthPx
        wrapper.requestLayout()
        return true
    }

    private fun dynamicLyricWidthSpec(
        rootView: ViewGroup,
        parentName: String,
        config: IslandSlotRuntimeConfig
    ): IslandLyricWidthSpec {
        return IslandLyricWidthSpec(
            density = rootView.resources.displayMetrics.density,
            paddingLeftPx = config.geometry.paddingLeftPx(rootView, parentName),
            paddingRightPx = config.geometry.paddingRightPx(rootView, parentName),
            minWidthDp = config.geometry.minWidthDp(parentName),
            maxWidthDp = config.geometry.maxWidthDp(parentName),
            isLeft = config.geometry.isLeftParent(parentName),
            showAlbum = config.geometry.showAlbum,
            showRhythm = config.geometry.showRhythm
        )
    }
}
