package com.lidesheng.hyperlyric.root.island

import android.content.SharedPreferences
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import com.lidesheng.hyperlyric.lyric.view.RichLyricLineView
import com.lidesheng.hyperlyric.lyric.view.SpaceGateRichLyricLineView
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.island.sizing.IslandLyricWidthCalculator
import com.lidesheng.hyperlyric.root.island.sizing.IslandLyricWidthSpec
import com.lidesheng.hyperlyric.root.island.view.MaxWidthFrameLayout
import com.lidesheng.hyperlyric.root.utils.HookLogger
import java.util.WeakHashMap

/**
 * Stage-4 / 富歌词大岛视图注入与更新调度。
 *
 * 负责在超级岛卡槽上动态注入 RichLyricLineView (Standard) 或 SpaceGateRichLyricLineView (Split)，
 * 并维护其生命周期恢复及热替换。
 */
internal object IslandLyricTextInjector {
    private const val TAG = "IslandLyricTextInjector"
    private val dynamicWidthRefreshPending = WeakHashMap<ViewGroup, Boolean>()
    private val dynamicWidthPreflightTargets =
        WeakHashMap<ViewGroup, MutableMap<String, Float>>()

    fun injectSlots(
        rootView: ViewGroup,
        reconfigureExisting: Boolean = true,
        suppressAnimation: Boolean = false
    ): Boolean {
        val prefs = HookEntry.instance?.prefs ?: return false
        val config = IslandSlotRuntimeConfig.from(prefs)

        var changed = false
        if (config.leftMode != 0) {
            changed = injectSlot(
                rootView,
                IslandProbeUtils.LEFT_PARENT_NAME,
                IslandProbeUtils.LEFT_TEST_VIEW_TAG,
                config.leftMode,
                reconfigureExisting,
                config,
                suppressAnimation
            ) || changed
        } else {
            changed = removeInjectedSlot(
                rootView,
                IslandProbeUtils.LEFT_TEST_WRAPPER_TAG
            ) || changed
        }

        if (config.rightMode != 0) {
            changed = injectSlot(
                rootView,
                IslandProbeUtils.RIGHT_PARENT_NAME,
                IslandProbeUtils.RIGHT_TEST_VIEW_TAG,
                config.rightMode,
                reconfigureExisting,
                config,
                suppressAnimation
            ) || changed
        } else {
            changed = removeInjectedSlot(
                rootView,
                IslandProbeUtils.RIGHT_TEST_WRAPPER_TAG
            ) || changed
        }

        if (config.isSplitMode) {
            linkViews(rootView)
        }

        IslandHostFacade.applyHostSettings(rootView, prefs)
        requestDynamicWidthRefresh(rootView)
        return changed
    }

    private fun removeInjectedSlot(rootView: ViewGroup, wrapperTag: String): Boolean {
        val wrapper = rootView.findViewWithTag<View>(wrapperTag) ?: return false
        val parent = wrapper.parent as? ViewGroup ?: return false
        parent.removeView(wrapper)
        IslandSlotContentAssembler.invalidate(wrapper)
        return true
    }

    fun restoreExistingSlotsLightweight(rootView: ViewGroup): Boolean {
        val prefs = HookEntry.instance?.prefs ?: return false
        val config = IslandSlotRuntimeConfig.from(prefs)

        var changed = false
        if (config.leftMode != 0) {
            changed = restoreExistingSlotLightweight(
                rootView,
                IslandProbeUtils.LEFT_PARENT_NAME,
                IslandProbeUtils.LEFT_TEST_VIEW_TAG
            ) || changed
        }
        if (config.rightMode != 0) {
            changed = restoreExistingSlotLightweight(
                rootView,
                IslandProbeUtils.RIGHT_PARENT_NAME,
                IslandProbeUtils.RIGHT_TEST_VIEW_TAG
            ) || changed
        }
        IslandHostFacade.applyHostSettings(rootView, prefs)
        return changed
    }

    fun restoreExistingModuleSlotLightweight(rootView: ViewGroup, moduleType: String?): Boolean {
        val prefs = HookEntry.instance?.prefs ?: return false
        val config = IslandSlotRuntimeConfig.from(prefs)

        var changed = false
        if (config.leftMode != 0 && (moduleType == null || moduleType.endsWith("_1"))) {
            changed = restoreExistingSlotByTagLightweight(
                rootView,
                IslandProbeUtils.LEFT_TEST_VIEW_TAG
            ) || changed
        }
        if (config.rightMode != 0 && (moduleType == null || moduleType.endsWith("_2"))) {
            changed = restoreExistingSlotByTagLightweight(
                rootView,
                IslandProbeUtils.RIGHT_TEST_VIEW_TAG
            ) || changed
        }
        if (!changed && moduleType != null && !moduleType.endsWith("_1") && !moduleType.endsWith("_2")) {
            if (config.leftMode != 0) {
                changed = restoreExistingSlotByTagLightweight(
                    rootView,
                    IslandProbeUtils.LEFT_TEST_VIEW_TAG
                ) || changed
            }
            if (config.rightMode != 0) {
                changed = restoreExistingSlotByTagLightweight(
                    rootView,
                    IslandProbeUtils.RIGHT_TEST_VIEW_TAG
                ) || changed
            }
        }

        IslandHostFacade.applyHostSettings(rootView, prefs)
        return changed
    }

    fun hasInjectedLyricText(rootView: ViewGroup): Boolean {
        return rootView.findViewWithTag<View>(IslandProbeUtils.LEFT_TEST_WRAPPER_TAG) != null ||
                rootView.findViewWithTag<View>(IslandProbeUtils.RIGHT_TEST_WRAPPER_TAG) != null ||
                rootView.findViewWithTag<View>(IslandProbeUtils.LEFT_TEST_VIEW_TAG) != null ||
                rootView.findViewWithTag<View>(IslandProbeUtils.RIGHT_TEST_VIEW_TAG) != null
    }

    fun hasInjectedLyricSlot(rootView: ViewGroup, viewTag: String): Boolean {
        return rootView.findViewWithTag<View>("${viewTag}_WRAPPER") != null ||
                rootView.findViewWithTag<View>(viewTag) != null
    }

    fun hasAllConfiguredSlots(
        rootView: ViewGroup,
        moduleType: String? = null
    ): Boolean {
        val prefs = HookEntry.instance?.prefs ?: return false
        val config = IslandSlotRuntimeConfig.from(prefs)
        val checksLeft = moduleType?.endsWith("_2") != true
        val checksRight = moduleType?.endsWith("_1") != true
        val leftReady = !checksLeft || config.leftMode == 0 ||
                hasInjectedLyricSlot(rootView, IslandProbeUtils.LEFT_TEST_VIEW_TAG)
        val rightReady = !checksRight || config.rightMode == 0 ||
                hasInjectedLyricSlot(rootView, IslandProbeUtils.RIGHT_TEST_VIEW_TAG)
        return leftReady && rightReady
    }

    fun expectsConfiguredSlot(moduleType: String? = null): Boolean {
        val prefs = HookEntry.instance?.prefs ?: return false
        val config = IslandSlotRuntimeConfig.from(prefs)
        return when {
            moduleType?.endsWith("_1") == true -> config.leftMode != 0
            moduleType?.endsWith("_2") == true -> config.rightMode != 0
            else -> config.leftMode != 0 || config.rightMode != 0
        }
    }

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
        if (config.leftMode != 0 && (includeLyricSlots || config.leftMode != 7)) {
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
        if (config.rightMode != 0 && (includeLyricSlots || config.rightMode != 7)) {
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

        if (config.isSplitMode) {
            linkViews(rootView)
        }
        requestDynamicWidthRefresh(rootView)
        return changed
    }

    /**
     * Standard lyric mode uses only the main lyric line as the width source. The secondary line
     * is intentionally excluded because it is allowed to be clipped by the lyric container.
     */
    fun requestDynamicWidthRefresh(rootView: ViewGroup) {
        val shouldPost = synchronized(dynamicWidthRefreshPending) {
            if (dynamicWidthRefreshPending[rootView] == true) {
                false
            } else {
                dynamicWidthRefreshPending[rootView] = true
                true
            }
        }
        if (!shouldPost) return

        val posted = rootView.post {
            synchronized(dynamicWidthRefreshPending) {
                dynamicWidthRefreshPending.remove(rootView)
            }
            if (IslandViewRegistry.tokenFor(rootView) == null) return@post
            val prefs = HookEntry.instance?.prefs ?: return@post
            val config = IslandSlotRuntimeConfig.from(prefs)
            if (!config.isDynamicWidth) return@post
            if (refreshDynamicLyricWidths(rootView, config)) {
                IslandHostFacade.triggerSystemRelayout(rootView)
            }
        }
        if (!posted) {
            synchronized(dynamicWidthRefreshPending) {
                dynamicWidthRefreshPending.remove(rootView)
            }
        }
    }

    /**
     * Applies the candidate lyric width before the candidate line is committed to the lyric View.
     * The candidate widths are retained until their corresponding line is actually applied so
     * left and right slots can be measured as one transaction.
     */
    fun prepareDynamicLyricWidth(
        rootView: ViewGroup,
        viewTag: String,
        contentWidthPx: Float
    ): Boolean {
        val prefs = HookEntry.instance?.prefs ?: return false
        val config = IslandSlotRuntimeConfig.from(prefs)
        if (!config.isDynamicWidth || config.modeForTag(viewTag) != 7) return false
        if (IslandViewRegistry.tokenFor(rootView) == null) return false

        val overrides = synchronized(dynamicWidthPreflightTargets) {
            val rootTargets = dynamicWidthPreflightTargets.getOrPut(rootView) { hashMapOf() }
            rootTargets[viewTag] = contentWidthPx
            rootTargets.toMap()
        }
        val changed = refreshDynamicLyricWidths(rootView, config, overrides)
        if (changed) {
            IslandHostFacade.triggerSystemRelayout(rootView)
        }
        return changed
    }

    fun clearDynamicLyricPreflight(rootView: ViewGroup, viewTag: String) {
        synchronized(dynamicWidthPreflightTargets) {
            val rootTargets = dynamicWidthPreflightTargets[rootView] ?: return
            rootTargets.remove(viewTag)
            if (rootTargets.isEmpty()) {
                dynamicWidthPreflightTargets.remove(rootView)
            }
        }
    }

    private fun refreshDynamicLyricWidths(
        rootView: ViewGroup,
        config: IslandSlotRuntimeConfig,
        contentWidthOverrides: Map<String, Float> = emptyMap()
    ): Boolean {
        if (!config.isDynamicWidth) return false

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
            config.rightMinWidthDp.toFloat(),
            config.rightMaxWidthDp.toFloat()
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
            paddingLeftPx = config.paddingLeftPx(rootView, parentName),
            paddingRightPx = config.paddingRightPx(rootView, parentName),
            minWidthDp = config.minWidthDp(parentName),
            maxWidthDp = config.maxWidthDp(parentName),
            isLeft = config.isLeftParent(parentName),
            showAlbum = config.showAlbum,
            showRhythm = config.showRhythm
        )
    }

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

    private fun injectSlot(
        rootView: ViewGroup,
        parentName: String,
        viewTag: String,
        mode: Int,
        reconfigureExisting: Boolean,
        config: IslandSlotRuntimeConfig,
        suppressAnimation: Boolean
    ): Boolean {
        val widthPx = config.widthPx(rootView, parentName) ?: return false

        val parent =
            IslandViewHelper.findViewByName(rootView, parentName) as? ViewGroup ?: return false
        val container = IslandViewHelper.findViewByName(
            parent,
            IslandProbeUtils.TEXT_CONTAINER_NAME
        ) as? ViewGroup ?: return false

        val wrapperTag = "${viewTag}_WRAPPER"

        IslandViewHelper.showForInjection(container)

        HookEntry.instance?.prefs ?: return false

        val taggedWrapper = container.findViewWithTag<View>(wrapperTag)
        val existingWrapper = taggedWrapper as? MaxWidthFrameLayout
        if (taggedWrapper != null && existingWrapper == null) {
            (taggedWrapper.parent as? ViewGroup)?.removeView(taggedWrapper)
            HookLogger.i(TAG, "已移除热重载遗留的旧歌词容器: tag=$wrapperTag")
        }
        if (existingWrapper != null) {
            existingWrapper.keepVisible = true
            var changed = updateWrapper(existingWrapper, widthPx, config, parentName)
            val targetView = existingWrapper.findViewWithTag<View>(viewTag)

            if (targetView == null) {
                existingWrapper.addView(
                    createLyricView(
                        rootView,
                        viewTag,
                        config,
                        mode,
                        suppressAnimation
                    ), createLyricTextLayoutParams()
                )
                changed = true
            } else if (!isViewTypeCorrect(targetView, config.activeMode)) {
                existingWrapper.removeView(targetView)
                IslandSlotContentAssembler.invalidate(targetView)
                existingWrapper.addView(
                    createLyricView(
                        rootView,
                        viewTag,
                        config,
                        mode,
                        suppressAnimation
                    ), createLyricTextLayoutParams()
                )
                changed = true
            } else {
                changed = restoreTargetView(
                    targetView,
                    config,
                    mode,
                    reconfigureExisting,
                    suppressAnimation
                ) || changed
            }

            if (existingWrapper.visibility != View.VISIBLE) {
                existingWrapper.visibility = View.VISIBLE
                changed = true
            }
            changed = forceWrapperLayout(existingWrapper, container, widthPx) || changed
            IslandViewHelper.hideNativeChildren(container, existingWrapper)
            return changed
        }

        val wrapperView = MaxWidthFrameLayout(rootView.context).apply {
            tag = wrapperTag
            clipChildren = true
            maxWidthPx = widthPx
            keepVisible = true
        }
        updateWrapper(wrapperView, widthPx, config, parentName)
        wrapperView.addView(
            createLyricView(rootView, viewTag, config, mode, suppressAnimation),
            createLyricTextLayoutParams()
        )

        container.addView(
            wrapperView,
            FrameLayout.LayoutParams(
                wrapperLayoutWidth(config),
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            })
        IslandViewHelper.hideNativeChildren(container, wrapperView)

        forceWrapperLayout(wrapperView, container, widthPx)

        HookLogger.d(
            TAG,
            "已注入歌词视图: tag=$viewTag，激活模式=${config.activeMode}，内容模式=$mode，宽度=${widthPx}px"
        )
        return true
    }

    private fun restoreExistingSlotLightweight(
        rootView: ViewGroup,
        parentName: String,
        viewTag: String
    ): Boolean {
        val parent =
            IslandViewHelper.findViewByName(rootView, parentName) as? ViewGroup ?: return false
        val container = IslandViewHelper.findViewByName(
            parent,
            IslandProbeUtils.TEXT_CONTAINER_NAME
        ) as? ViewGroup ?: return false
        val wrapper = container.findViewWithTag<View>("${viewTag}_WRAPPER") as? MaxWidthFrameLayout
            ?: return false
        val targetView = wrapper.findViewWithTag<View>(viewTag) ?: return false

        var changed = false
        wrapper.keepVisible = true
        if (container.visibility != View.VISIBLE) {
            IslandViewHelper.showForInjection(container)
            changed = true
        }
        if (wrapper.visibility != View.VISIBLE) {
            wrapper.visibility = View.VISIBLE
            changed = true
        }
        if (targetView.visibility != View.VISIBLE) {
            targetView.visibility = View.VISIBLE
            changed = true
        }
        IslandViewHelper.hideNativeChildren(container, wrapper)
        return changed
    }

    private fun restoreExistingSlotByTagLightweight(rootView: ViewGroup, viewTag: String): Boolean {
        val wrapper = rootView.findViewWithTag<View>("${viewTag}_WRAPPER") as? MaxWidthFrameLayout
            ?: return false
        val targetView = wrapper.findViewWithTag<View>(viewTag) ?: return false
        val container = wrapper.parent as? ViewGroup ?: return false

        var changed = false
        wrapper.keepVisible = true
        if (container.visibility != View.VISIBLE) {
            IslandViewHelper.showForInjection(container)
            changed = true
        }
        if (wrapper.visibility != View.VISIBLE) {
            wrapper.visibility = View.VISIBLE
            changed = true
        }
        if (targetView.visibility != View.VISIBLE) {
            targetView.visibility = View.VISIBLE
            changed = true
        }
        IslandViewHelper.hideNativeChildren(container, wrapper)
        return changed
    }

    private fun updateWrapper(
        wrapper: MaxWidthFrameLayout,
        widthPx: Int,
        config: IslandSlotRuntimeConfig,
        parentName: String
    ): Boolean {
        var changed = false
        val paddingLeft = config.paddingLeftPx(wrapper, parentName)
        val paddingRight = config.paddingRightPx(wrapper, parentName)
        if (wrapper.paddingLeft != paddingLeft || wrapper.paddingRight != paddingRight) {
            wrapper.setPadding(paddingLeft, wrapper.paddingTop, paddingRight, wrapper.paddingBottom)
            changed = true
        }
        if (wrapper.minimumWidth != 0) {
            wrapper.minimumWidth = 0
            changed = true
        }
        if (wrapper.maxWidthPx != widthPx) {
            wrapper.maxWidthPx = widthPx
            changed = true
        }
        val layoutParams = wrapper.layoutParams
        val expectedWidth = wrapperLayoutWidth(config)
        if (layoutParams != null && (layoutParams.width != expectedWidth || layoutParams.height != FrameLayout.LayoutParams.MATCH_PARENT)) {
            layoutParams.width = expectedWidth
            layoutParams.height = FrameLayout.LayoutParams.MATCH_PARENT
            wrapper.layoutParams = layoutParams
            changed = true
        }
        if (changed) wrapper.requestLayout()
        return changed
    }

    private fun isViewTypeCorrect(view: View, activeMode: Int): Boolean {
        return if (activeMode == 1) {
            view is SpaceGateRichLyricLineView
        } else {
            view is RichLyricLineView
        }
    }

    private fun restoreTargetView(
        targetView: View,
        config: IslandSlotRuntimeConfig,
        mode: Int,
        reconfigure: Boolean,
        suppressAnimation: Boolean = false
    ): Boolean {
        var changed = false
        val layoutParams = targetView.layoutParams
        if (layoutParams != null &&
            (layoutParams.width != FrameLayout.LayoutParams.MATCH_PARENT ||
                    layoutParams.height != FrameLayout.LayoutParams.MATCH_PARENT)
        ) {
            layoutParams.width = FrameLayout.LayoutParams.MATCH_PARENT
            layoutParams.height = FrameLayout.LayoutParams.MATCH_PARENT
            targetView.layoutParams = layoutParams
            changed = true
        }
        if (targetView.visibility != View.VISIBLE) {
            targetView.visibility = View.VISIBLE
            changed = true
        }

        val prefs = HookEntry.instance?.prefs
        if (reconfigure && prefs != null) {
            changed = IslandSlotContentAssembler.applySlotContent(
                targetView,
                prefs,
                config,
                mode,
                force = true,
                suppressAnimation = suppressAnimation
            ) || changed
        }
        return changed
    }

    private fun forceWrapperLayout(
        wrapper: MaxWidthFrameLayout,
        container: ViewGroup,
        widthPx: Int
    ): Boolean {
        val wasZeroWidth = wrapper.width == 0 || wrapper.measuredWidth == 0
        if (!wasZeroWidth) {
            return false
        }

        val heightPx = if (container.height > 0) container.height else container.measuredHeight
        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.AT_MOST)
        val heightSpec = if (heightPx > 0) {
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        } else {
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        }
        wrapper.measure(widthSpec, heightSpec)
        val finalHeight = if (heightPx > 0) heightPx else wrapper.measuredHeight
        wrapper.layout(0, 0, wrapper.measuredWidth, finalHeight)
        return wasZeroWidth
    }

    private fun wrapperLayoutWidth(config: IslandSlotRuntimeConfig): Int {
        return if (config.isSplitMode) {
            FrameLayout.LayoutParams.WRAP_CONTENT
        } else {
            FrameLayout.LayoutParams.MATCH_PARENT
        }
    }

    private fun createLyricTextLayoutParams(): FrameLayout.LayoutParams {
        return FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }

    private fun createLyricView(
        rootView: ViewGroup,
        tagValue: String,
        config: IslandSlotRuntimeConfig,
        mode: Int,
        suppressAnimation: Boolean = false
    ): View {
        val prefs = HookEntry.instance?.prefs
        val view = if (config.isSplitMode) {
            SpaceGateRichLyricLineView(rootView.context)
        } else {
            RichLyricLineView(rootView.context)
        }
        view.tag = tagValue

        if (prefs != null) {
            IslandSlotContentAssembler.applySlotContent(
                view,
                prefs,
                config,
                mode,
                force = true,
                suppressAnimation = true
            )
        }
        return view
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
        val view = rootView.findViewWithTag<View>(viewTag) ?: return false
        return IslandSlotContentAssembler.applySlotContent(
            view,
            prefs,
            config,
            mode,
            force = force,
            suppressAnimation = suppressAnimation,
            mediaInfo = mediaInfo,
            onLineWillApply = { contentWidthPx ->
                prepareDynamicLyricWidth(rootView, viewTag, contentWidthPx)
            },
            onLineApplied = {
                clearDynamicLyricPreflight(rootView, viewTag)
                requestDynamicWidthRefresh(rootView)
            },
            onLineCancelled = {
                clearDynamicLyricPreflight(rootView, viewTag)
            }
        )
    }

    fun linkViews(rootView: ViewGroup) {
        val leftView =
            rootView.findViewWithTag<View>(IslandProbeUtils.LEFT_TEST_VIEW_TAG) as? SpaceGateRichLyricLineView
        val rightView =
            rootView.findViewWithTag<View>(IslandProbeUtils.RIGHT_TEST_VIEW_TAG) as? SpaceGateRichLyricLineView

        leftView?.main?.spaceGateEnabled = false
        leftView?.secondary?.spaceGateEnabled = false
        rightView?.main?.spaceGateEnabled = false
        rightView?.secondary?.spaceGateEnabled = false

        if (leftView != null && rightView != null) {
            IslandHostFacade.logCameraCutoutInfo(rootView)
        }
    }

}
