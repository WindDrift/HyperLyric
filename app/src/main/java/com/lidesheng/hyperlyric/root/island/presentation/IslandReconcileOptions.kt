package com.lidesheng.hyperlyric.root.island.presentation

import com.lidesheng.hyperlyric.root.island.IslandInjectionReconciler
import com.lidesheng.hyperlyric.root.island.IslandPresentationCoordinator

/**
 * Maps a presentation reconciliation reason to the mutation options required by each target.
 *
 * The coordinator decides whether a target should be shown; this object only describes how that
 * target is restored or updated. Keeping the mapping here prevents target-specific mutation flags
 * from being mixed with lifecycle and policy decisions.
 */
internal object IslandReconcileOptions {
    fun realRoot(
        reason: IslandPresentationCoordinator.ReconcileReason
    ): IslandInjectionReconciler.ShowOptions {
        return when (reason) {
            IslandPresentationCoordinator.ReconcileReason.PRE_SYSTEM_UPDATE,
            IslandPresentationCoordinator.ReconcileReason.VISIBILITY_CHANGED ->
                IslandInjectionReconciler.ShowOptions(
                    structure = IslandInjectionReconciler.StructureMode.RESTORE_EXISTING,
                    content = IslandInjectionReconciler.ContentMode.WHEN_LAYOUT_CHANGED,
                    suppressAnimation = false,
                    reconfigureExisting = false
                )

            IslandPresentationCoordinator.ReconcileReason.FAKE_FINISHED ->
                IslandInjectionReconciler.ShowOptions(
                    structure = IslandInjectionReconciler.StructureMode.RESTORE_EXISTING,
                    content = IslandInjectionReconciler.ContentMode.ALWAYS,
                    suppressAnimation = false,
                    reconfigureExisting = false
                )

            IslandPresentationCoordinator.ReconcileReason.SYSTEM_UPDATE_COMPLETE ->
                IslandInjectionReconciler.ShowOptions(
                    structure = IslandInjectionReconciler.StructureMode.ENSURE,
                    content = IslandInjectionReconciler.ContentMode.ALWAYS,
                    suppressAnimation = false,
                    reconfigureExisting = false
                )

            IslandPresentationCoordinator.ReconcileReason.STABLE_REFRESH ->
                IslandInjectionReconciler.ShowOptions(
                    structure = IslandInjectionReconciler.StructureMode.ENSURE,
                    content = IslandInjectionReconciler.ContentMode.NONE,
                    suppressAnimation = false,
                    reconfigureExisting = true
                )

            IslandPresentationCoordinator.ReconcileReason.LYRIC_SELF_HEAL ->
                IslandInjectionReconciler.ShowOptions(
                    structure = IslandInjectionReconciler.StructureMode.ENSURE_IF_MISSING,
                    content = IslandInjectionReconciler.ContentMode.NONE,
                    suppressAnimation = true,
                    reconfigureExisting = false
                )

            IslandPresentationCoordinator.ReconcileReason.PLAYBACK_RESUME ->
                IslandInjectionReconciler.ShowOptions(
                    structure = IslandInjectionReconciler.StructureMode.RESTORE_OR_ENSURE,
                    content = IslandInjectionReconciler.ContentMode.NONE,
                    suppressAnimation = true,
                    reconfigureExisting = false
                )

            IslandPresentationCoordinator.ReconcileReason.MODULE_FIRST_BIND,
            IslandPresentationCoordinator.ReconcileReason.MODULE_UPDATED,
            IslandPresentationCoordinator.ReconcileReason.FAKE_SNAPSHOT ->
                error("Unsupported real-root reason: $reason")
        }
    }

    fun module(
        reason: IslandPresentationCoordinator.ReconcileReason
    ): IslandInjectionReconciler.ShowOptions {
        return when (reason) {
            IslandPresentationCoordinator.ReconcileReason.MODULE_FIRST_BIND ->
                IslandInjectionReconciler.ShowOptions(
                    structure = IslandInjectionReconciler.StructureMode.ENSURE,
                    content = IslandInjectionReconciler.ContentMode.WHEN_RESTORING_EXISTING,
                    suppressAnimation = true,
                    reconfigureExisting = false
                )

            IslandPresentationCoordinator.ReconcileReason.MODULE_UPDATED ->
                IslandInjectionReconciler.ShowOptions(
                    structure = IslandInjectionReconciler.StructureMode.RESTORE_EXISTING,
                    content = IslandInjectionReconciler.ContentMode.WHEN_LAYOUT_CHANGED,
                    suppressAnimation = false,
                    reconfigureExisting = false
                )

            else -> error("Unsupported module reason: $reason")
        }
    }
}
