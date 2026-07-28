package com.lidesheng.hyperlyric.root.island

import android.view.View
import android.view.ViewGroup
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.utils.HookLogger
import java.util.WeakHashMap

/**
 * Owns Super Island target policy and view reconciliation.
 *
 * Xposed hookers keep responsibility for exact method signatures and
 * chain.proceed() timing, then synchronously report extracted lifecycle facts
 * here. High-frequency lyric position and color updates intentionally stay on
 * their indexed-view fast paths.
 */
internal object IslandPresentationCoordinator {
    private const val TAG = "IslandPresentation"

    enum class ReconcileReason {
        PRE_SYSTEM_UPDATE,
        SYSTEM_UPDATE_COMPLETE,
        VISIBILITY_CHANGED,
        MODULE_FIRST_BIND,
        MODULE_UPDATED,
        STABLE_REFRESH,
        LYRIC_SELF_HEAL,
        PLAYBACK_RESUME,
        FAKE_SNAPSHOT,
        FAKE_FINISHED
    }

    data class ReconcileResult(
        val decision: IslandRenderPolicy.Decision,
        val mutation: IslandInjectionReconciler.Result
    ) {
        val isTarget: Boolean
            get() = decision == IslandRenderPolicy.Decision.TARGET

        companion object {
            fun noOp(decision: IslandRenderPolicy.Decision): ReconcileResult {
                return ReconcileResult(
                    decision = decision,
                    mutation = IslandInjectionReconciler.Result.NO_OP
                )
            }
        }
    }

    @Volatile
    private var playbackActive = true
    @Volatile
    private var presentationRevision = 0L

    private val presentationStateLock = Any()
    private val attachmentLock = Any()
    private val attachmentListeners =
        WeakHashMap<ViewGroup, View.OnAttachStateChangeListener>()
    private val fakeTransitionLock = Any()
    private val fakeTransitions = WeakHashMap<ViewGroup, FakeTransitionRecord>()
    private var nextFakeTransitionGeneration = 0L

    private data class FakeTransitionRecord(
        val generation: Long,
        val realHost: IslandViewRegistry.HostToken,
        val pendingEnds: Int
    )

    fun ownerEvidence(data: Any?): IslandRenderPolicy.OwnerEvidence {
        if (data == null) return IslandRenderPolicy.OwnerEvidence.Pending
        val mediaInfo = IslandProbeUtils.extractMediaIslandInfo(data)
        if (mediaInfo != null) {
            return IslandRenderPolicy.OwnerEvidence.Media(mediaInfo.packageName)
        }
        return if (IslandProbeUtils.isMediaIsland(data)) {
            IslandRenderPolicy.OwnerEvidence.Pending
        } else {
            IslandRenderPolicy.OwnerEvidence.NotMedia
        }
    }

    fun updatePlaybackState(isPlaying: Boolean): Boolean {
        return synchronized(presentationStateLock) {
            val changed = playbackActive != isPlaying
            playbackActive = isPlaying
            if (changed) presentationRevision++
            changed
        }
    }

    fun isPlaybackActive(): Boolean = playbackActive

    fun invalidatePresentation(): Long {
        return synchronized(presentationStateLock) {
            ++presentationRevision
        }
    }

    fun currentPresentationRevision(): Long = presentationRevision

    fun isCurrentPresentation(revision: Long): Boolean {
        return presentationRevision == revision
    }

    fun isCurrentLyricOwner(mediaInfo: IslandProbeUtils.MediaIslandInfo): Boolean {
        val lyricPackageName = LyriconDataBridge.currentLyricPackageName
            ?.takeIf(String::isNotEmpty)
            ?: return false
        return mediaInfo.packageName == lyricPackageName
    }

    fun shouldRenderInjectedIsland(): Boolean {
        return IslandRenderPolicy.isPresentationAllowed(
            enabled = IslandProbeUtils.isSuperIslandEnabled(),
            playbackActive = playbackActive,
            pauseBehavior = currentPauseBehavior()
        )
    }

    fun onRealBeforeSystemUpdate(
        root: ViewGroup,
        owner: IslandRenderPolicy.OwnerEvidence
    ): ReconcileResult {
        return reconcileRealRoot(root, owner, ReconcileReason.PRE_SYSTEM_UPDATE)
    }

    fun onRealSystemUpdateComplete(
        root: ViewGroup,
        owner: IslandRenderPolicy.OwnerEvidence,
        islandData: Any?
    ): ReconcileResult {
        if (owner == IslandRenderPolicy.OwnerEvidence.NotMedia) {
            return removeRealHost(root, ReconcileReason.SYSTEM_UPDATE_COMPLETE)
        }

        if (owner is IslandRenderPolicy.OwnerEvidence.Media) {
            IslandViewRegistry.register(root, owner.packageName)
            observeRealHostAttachment(root)
        }
        val result = reconcileRealRoot(
            root = root,
            owner = owner,
            reason = ReconcileReason.SYSTEM_UPDATE_COMPLETE
        )
        if (result.isTarget) {
            HookEntry.instance?.prefs?.let { prefs ->
                IslandHostFacade.injectHostGlow(root, islandData, prefs)
            }
        }
        return result
    }

    fun onRealVisibilityChanged(
        root: ViewGroup,
        owner: IslandRenderPolicy.OwnerEvidence
    ): ReconcileResult {
        if (owner is IslandRenderPolicy.OwnerEvidence.Media) {
            IslandViewRegistry.register(root, owner.packageName)
            observeRealHostAttachment(root)
        }
        return reconcileRealRoot(root, owner, ReconcileReason.VISIBILITY_CHANGED)
    }

    fun onModuleBound(
        holderRoot: ViewGroup,
        moduleType: String?,
        owner: IslandRenderPolicy.OwnerEvidence
    ): ReconcileResult {
        return reconcileModule(
            holderRoot = holderRoot,
            moduleType = moduleType,
            owner = owner,
            reason = ReconcileReason.MODULE_FIRST_BIND
        )
    }

    fun onModuleUpdated(
        holderRoot: ViewGroup,
        moduleType: String?,
        owner: IslandRenderPolicy.OwnerEvidence
    ): ReconcileResult {
        return reconcileModule(
            holderRoot = holderRoot,
            moduleType = moduleType,
            owner = owner,
            reason = ReconcileReason.MODULE_UPDATED
        )
    }

    fun onFakeSnapshotRequested(
        fakeOwner: ViewGroup,
        snapshotRoot: ViewGroup,
        owner: IslandRenderPolicy.OwnerEvidence,
        realRoot: ViewGroup?,
        position: Long
    ): ReconcileResult {
        var realOwnerConflict = false
        val realHost = if (owner == IslandRenderPolicy.OwnerEvidence.NotMedia) {
            null
        } else {
            realRoot?.let { root ->
                val existing = IslandViewRegistry.tokenFor(root)
                val snapshotOwner = owner as? IslandRenderPolicy.OwnerEvidence.Media
                when {
                    existing != null &&
                            snapshotOwner != null &&
                            existing.packageName != snapshotOwner.packageName -> {
                        realOwnerConflict = true
                        null
                    }

                    existing != null -> existing

                    else -> {
                        val realOwner = ownerEvidence(
                            IslandTextHookerSupport.extractIslandDataFromContentOrReal(root)
                        ) as? IslandRenderPolicy.OwnerEvidence.Media
                            ?: return@let null
                        if (snapshotOwner != null &&
                            snapshotOwner.packageName != realOwner.packageName
                        ) {
                            realOwnerConflict = true
                            return@let null
                        }
                        IslandViewRegistry.register(root, realOwner.packageName).also {
                            observeRealHostAttachment(root)
                        }
                    }
                }
            }
        }
        synchronized(fakeTransitionLock) {
            if (realHost == null) {
                fakeTransitions.remove(fakeOwner)
            } else {
                val previous = fakeTransitions[fakeOwner]
                val pendingEnds = if (
                    previous?.realHost == realHost
                ) {
                    previous.pendingEnds + 1
                } else {
                    1
                }
                fakeTransitions[fakeOwner] = FakeTransitionRecord(
                    generation = ++nextFakeTransitionGeneration,
                    realHost = realHost,
                    pendingEnds = pendingEnds
                )
            }
        }
        val resolvedOwner = realHost
            ?.let { IslandRenderPolicy.OwnerEvidence.Media(it.packageName) }
            ?: owner
        val decision = if (realOwnerConflict) {
            IslandRenderPolicy.Decision.OTHER_PACKAGE
        } else {
            evaluate(owner = resolvedOwner)
        }
        val mutation = when (decision) {
            IslandRenderPolicy.Decision.TARGET -> {
                IslandInjectionReconciler.prepareFrozenSnapshot(snapshotRoot, position)
                    .also { IslandHostFacade.showFrozenSnapshot(snapshotRoot) }
            }

            IslandRenderPolicy.Decision.SUPPRESSED,
            IslandRenderPolicy.Decision.OTHER_PACKAGE,
            IslandRenderPolicy.Decision.NOT_MEDIA -> {
                IslandInjectionReconciler.restoreNative(
                    snapshotRoot,
                    IslandInjectionReconciler.Target.FakeSnapshot
                )
            }

            IslandRenderPolicy.Decision.PENDING -> IslandInjectionReconciler.Result.NO_OP
        }
        return logResult(
            reason = ReconcileReason.FAKE_SNAPSHOT,
            owner = resolvedOwner,
            result = ReconcileResult(decision, mutation)
        )
    }

    fun onFakeTransitionEnded(
        fakeOwner: ViewGroup,
        realRoot: ViewGroup
    ): ReconcileResult {
        // SystemUI does not return our generation in the end callback. Count
        // same-token overlaps, and use the real HostToken to reject every
        // transition that can be proven stale.
        val transition = synchronized(fakeTransitionLock) {
            val current = fakeTransitions[fakeOwner] ?: return@synchronized null
            if (current.realHost.root !== realRoot) {
                return@synchronized current
            }
            if (current.pendingEnds > 1) {
                fakeTransitions[fakeOwner] = current.copy(
                    pendingEnds = current.pendingEnds - 1
                )
            } else {
                fakeTransitions.remove(fakeOwner)
            }
            current
        }
        if (transition == null) {
            return restoreRealHostFromCurrentEvidence(realRoot)
        }
        if (transition.realHost.root !== realRoot ||
            !IslandViewRegistry.isCurrent(transition.realHost)
        ) {
            HookLogger.d(
                TAG,
                "忽略过期 fake 结束回调: generation=${transition.generation}"
            )
            return ReconcileResult.noOp(IslandRenderPolicy.Decision.PENDING)
        }
        val result = reconcileRealRoot(
            root = realRoot,
            owner = IslandRenderPolicy.OwnerEvidence.Media(
                transition.realHost.packageName
            ),
            reason = ReconcileReason.FAKE_FINISHED
        )
        if (result.isTarget) {
            IslandHostFacade.showRealHost(realRoot)
        }
        return result
    }

    private fun restoreRealHostFromCurrentEvidence(
        realRoot: ViewGroup
    ): ReconcileResult {
        val owner = ownerEvidence(
            IslandTextHookerSupport.extractIslandDataFromContentOrReal(realRoot)
        )
        if (owner !is IslandRenderPolicy.OwnerEvidence.Media) {
            return ReconcileResult.noOp(
                if (owner == IslandRenderPolicy.OwnerEvidence.NotMedia) {
                    IslandRenderPolicy.Decision.NOT_MEDIA
                } else {
                    IslandRenderPolicy.Decision.PENDING
                }
            )
        }
        IslandViewRegistry.register(realRoot, owner.packageName)
        observeRealHostAttachment(realRoot)
        val result = reconcileRealRoot(
            root = realRoot,
            owner = owner,
            reason = ReconcileReason.FAKE_FINISHED
        )
        if (result.isTarget) {
            IslandHostFacade.showRealHost(realRoot)
        }
        return result
    }

    fun reconcileRegisteredHost(
        token: IslandViewRegistry.HostToken,
        reason: ReconcileReason,
        expectedPresentationRevision: Long? = null
    ): ReconcileResult {
        if (!IslandViewRegistry.isCurrent(token) ||
            (expectedPresentationRevision != null &&
                    !isCurrentPresentation(expectedPresentationRevision))
        ) {
            return ReconcileResult.noOp(IslandRenderPolicy.Decision.PENDING)
        }
        return reconcileRealRoot(
            root = token.root,
            owner = IslandRenderPolicy.OwnerEvidence.Media(token.packageName),
            reason = reason
        )
    }

    fun clearRegisteredHostIfSuppressed(
        token: IslandViewRegistry.HostToken,
        expectedPresentationRevision: Long
    ): IslandInjectionReconciler.Result {
        if (!IslandViewRegistry.isCurrent(token) ||
            !isCurrentPresentation(expectedPresentationRevision) ||
            evaluate(
                IslandRenderPolicy.OwnerEvidence.Media(token.packageName)
            ) != IslandRenderPolicy.Decision.SUPPRESSED
        ) {
            return IslandInjectionReconciler.Result.NO_OP
        }
        return IslandInjectionReconciler.restoreNative(
            token.root,
            IslandInjectionReconciler.Target.RealRoot
        )
    }

    fun clearRegisteredHost(
        token: IslandViewRegistry.HostToken,
        expectedPresentationRevision: Long
    ): IslandInjectionReconciler.Result {
        if (!IslandViewRegistry.isCurrent(token) ||
            !isCurrentPresentation(expectedPresentationRevision)
        ) {
            return IslandInjectionReconciler.Result.NO_OP
        }
        return IslandInjectionReconciler.restoreNative(
            token.root,
            IslandInjectionReconciler.Target.RealRoot
        )
    }

    fun snapshotAttachedHosts(
        packageName: String? = null
    ): List<IslandViewRegistry.HostToken> {
        return IslandViewRegistry.snapshotAttached(packageName)
    }

    fun snapshotAttachedInjectedHosts(
        packageName: String? = null
    ): List<IslandViewRegistry.InjectedHostToken> {
        return IslandViewRegistry.snapshotAttachedInjectedViews(packageName)
    }

    fun isCurrentHost(token: IslandViewRegistry.HostToken): Boolean {
        return IslandViewRegistry.isCurrent(token)
    }

    fun refreshInjectedViewIndex(token: IslandViewRegistry.HostToken) {
        if (IslandViewRegistry.isCurrent(token)) {
            IslandViewRegistry.refreshInjectedViews(token.root)
        }
    }

    private fun reconcileRealRoot(
        root: ViewGroup,
        owner: IslandRenderPolicy.OwnerEvidence,
        reason: ReconcileReason
    ): ReconcileResult {
        val decision = evaluate(owner)
        val mutation = when (decision) {
            IslandRenderPolicy.Decision.TARGET -> {
                IslandInjectionReconciler.show(
                    root = root,
                    target = IslandInjectionReconciler.Target.RealRoot,
                    options = realRootOptions(reason)
                )
            }

            IslandRenderPolicy.Decision.SUPPRESSED -> {
                IslandInjectionReconciler.restoreNative(
                    root,
                    IslandInjectionReconciler.Target.RealRoot
                )
            }

            IslandRenderPolicy.Decision.OTHER_PACKAGE -> {
                if (reason == ReconcileReason.PRE_SYSTEM_UPDATE) {
                    IslandInjectionReconciler.Result.NO_OP
                } else {
                    IslandInjectionReconciler.restoreNative(
                        root,
                        IslandInjectionReconciler.Target.RealRoot
                    )
                }
            }

            IslandRenderPolicy.Decision.NOT_MEDIA -> {
                return removeRealHost(root, reason)
            }

            IslandRenderPolicy.Decision.PENDING -> IslandInjectionReconciler.Result.NO_OP
        }
        return logResult(reason, owner, ReconcileResult(decision, mutation))
    }

    private fun reconcileModule(
        holderRoot: ViewGroup,
        moduleType: String?,
        owner: IslandRenderPolicy.OwnerEvidence,
        reason: ReconcileReason
    ): ReconcileResult {
        val decision = evaluate(owner)
        val target = IslandInjectionReconciler.Target.RealModule(moduleType)
        val mutation = when (decision) {
            IslandRenderPolicy.Decision.TARGET -> {
                IslandInjectionReconciler.show(
                    root = holderRoot,
                    target = target,
                    options = moduleOptions(reason)
                )
            }

            IslandRenderPolicy.Decision.SUPPRESSED,
            IslandRenderPolicy.Decision.OTHER_PACKAGE,
            IslandRenderPolicy.Decision.NOT_MEDIA -> {
                IslandInjectionReconciler.restoreNative(holderRoot, target)
            }

            IslandRenderPolicy.Decision.PENDING -> IslandInjectionReconciler.Result.NO_OP
        }
        return logResult(reason, owner, ReconcileResult(decision, mutation))
    }

    private fun removeRealHost(
        root: ViewGroup,
        reason: ReconcileReason
    ): ReconcileResult {
        val token = IslandViewRegistry.tokenFor(root)
        stopObservingRealHostAttachment(root)
        token?.let(IslandViewRegistry::unregister)
        val mutation = IslandInjectionReconciler.restoreNative(
            root,
            IslandInjectionReconciler.Target.RealRoot
        )
        return logResult(
            reason = reason,
            owner = IslandRenderPolicy.OwnerEvidence.NotMedia,
            result = ReconcileResult(IslandRenderPolicy.Decision.NOT_MEDIA, mutation)
        )
    }

    private fun realRootOptions(reason: ReconcileReason): IslandInjectionReconciler.ShowOptions {
        return when (reason) {
            ReconcileReason.PRE_SYSTEM_UPDATE,
            ReconcileReason.VISIBILITY_CHANGED -> IslandInjectionReconciler.ShowOptions(
                structure = IslandInjectionReconciler.StructureMode.RESTORE_EXISTING,
                content = IslandInjectionReconciler.ContentMode.WHEN_LAYOUT_CHANGED,
                suppressAnimation = false,
                reconfigureExisting = false
            )

            ReconcileReason.FAKE_FINISHED -> IslandInjectionReconciler.ShowOptions(
                structure = IslandInjectionReconciler.StructureMode.RESTORE_EXISTING,
                content = IslandInjectionReconciler.ContentMode.ALWAYS,
                suppressAnimation = false,
                reconfigureExisting = false
            )

            ReconcileReason.SYSTEM_UPDATE_COMPLETE -> IslandInjectionReconciler.ShowOptions(
                structure = IslandInjectionReconciler.StructureMode.ENSURE,
                content = IslandInjectionReconciler.ContentMode.ALWAYS,
                suppressAnimation = false,
                reconfigureExisting = false
            )

            ReconcileReason.STABLE_REFRESH -> IslandInjectionReconciler.ShowOptions(
                structure = IslandInjectionReconciler.StructureMode.ENSURE,
                content = IslandInjectionReconciler.ContentMode.NONE,
                suppressAnimation = false,
                reconfigureExisting = true
            )

            ReconcileReason.LYRIC_SELF_HEAL -> IslandInjectionReconciler.ShowOptions(
                structure = IslandInjectionReconciler.StructureMode.ENSURE_IF_MISSING,
                content = IslandInjectionReconciler.ContentMode.NONE,
                suppressAnimation = true,
                reconfigureExisting = false
            )

            ReconcileReason.PLAYBACK_RESUME -> IslandInjectionReconciler.ShowOptions(
                structure = IslandInjectionReconciler.StructureMode.RESTORE_OR_ENSURE,
                content = IslandInjectionReconciler.ContentMode.NONE,
                suppressAnimation = true,
                reconfigureExisting = false
            )

            ReconcileReason.MODULE_FIRST_BIND,
            ReconcileReason.MODULE_UPDATED,
            ReconcileReason.FAKE_SNAPSHOT -> error("Unsupported real-root reason: $reason")
        }
    }

    private fun moduleOptions(reason: ReconcileReason): IslandInjectionReconciler.ShowOptions {
        return when (reason) {
            ReconcileReason.MODULE_FIRST_BIND -> IslandInjectionReconciler.ShowOptions(
                structure = IslandInjectionReconciler.StructureMode.ENSURE,
                content = IslandInjectionReconciler.ContentMode.WHEN_RESTORING_EXISTING,
                suppressAnimation = true,
                reconfigureExisting = false
            )

            ReconcileReason.MODULE_UPDATED -> IslandInjectionReconciler.ShowOptions(
                structure = IslandInjectionReconciler.StructureMode.RESTORE_EXISTING,
                content = IslandInjectionReconciler.ContentMode.WHEN_LAYOUT_CHANGED,
                suppressAnimation = false,
                reconfigureExisting = false
            )

            else -> error("Unsupported module reason: $reason")
        }
    }

    private fun evaluate(
        owner: IslandRenderPolicy.OwnerEvidence
    ): IslandRenderPolicy.Decision {
        return IslandRenderPolicy.evaluate(currentInput(owner))
    }

    private fun currentInput(
        owner: IslandRenderPolicy.OwnerEvidence
    ): IslandRenderPolicy.Input {
        return IslandRenderPolicy.Input(
            owner = owner,
            lyricPackageName = LyriconDataBridge.currentLyricPackageName,
            enabled = IslandProbeUtils.isSuperIslandEnabled(),
            playbackActive = playbackActive,
            pauseBehavior = currentPauseBehavior()
        )
    }

    private fun currentPauseBehavior(): Int {
        return HookEntry.instance?.prefs?.getInt(
            RootConstants.KEY_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE,
            RootConstants.DEFAULT_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE
        ) ?: RootConstants.DEFAULT_HOOK_ISLAND_BEHAVIOR_AFTER_PAUSE
    }

    private fun logResult(
        reason: ReconcileReason,
        owner: IslandRenderPolicy.OwnerEvidence,
        result: ReconcileResult
    ): ReconcileResult {
        if (result.mutation.layoutMayHaveChanged ||
            result.decision != IslandRenderPolicy.Decision.TARGET
        ) {
            HookLogger.d(
                TAG,
                "对账: reason=$reason, owner=$owner, decision=${result.decision}, " +
                        "layout=${result.mutation.layoutMayHaveChanged}, " +
                        "relayout=${result.mutation.relayoutRequested}"
            )
        }
        return result
    }

    private fun observeRealHostAttachment(root: ViewGroup) {
        synchronized(attachmentLock) {
            if (attachmentListeners.containsKey(root)) return
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    val attachedRoot = view as? ViewGroup ?: return
                    val token = IslandViewRegistry.markAttached(attachedRoot) ?: return
                    val expectedRevision = currentPresentationRevision()
                    attachedRoot.post {
                        reconcileRegisteredHost(
                            token = token,
                            reason = ReconcileReason.STABLE_REFRESH,
                            expectedPresentationRevision = expectedRevision
                        )
                    }
                }

                override fun onViewDetachedFromWindow(view: View) {
                    (view as? ViewGroup)?.let(IslandViewRegistry::markDetached)
                }
            }
            attachmentListeners[root] = listener
            root.addOnAttachStateChangeListener(listener)
        }
    }

    private fun stopObservingRealHostAttachment(root: ViewGroup) {
        val listener = synchronized(attachmentLock) {
            attachmentListeners.remove(root)
        } ?: return
        root.removeOnAttachStateChangeListener(listener)
    }
}
