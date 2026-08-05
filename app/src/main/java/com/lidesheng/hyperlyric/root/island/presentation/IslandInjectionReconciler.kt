package com.lidesheng.hyperlyric.root.island.presentation

import android.view.ViewGroup
import com.lidesheng.hyperlyric.root.island.content.IslandLyricContentRefresher
import com.lidesheng.hyperlyric.root.island.content.IslandLyricPlaybackController
import com.lidesheng.hyperlyric.root.island.host.IslandHostFacade
import com.lidesheng.hyperlyric.root.island.host.IslandViewRegistry
import com.lidesheng.hyperlyric.root.island.structure.IslandSlotStructureInjector

/**
 * The single upper-level entry for Super Island lyric structure, visibility,
 * native restoration, and SystemUI relayout mutations.
 *
 * High-frequency lyric, position, and color content updates intentionally use
 * indexed-view fast paths. The underlying injector still reports a coarse
 * Boolean, so the first
 * structured result deliberately calls it [Result.layoutMayHaveChanged].
 */
internal object IslandInjectionReconciler {

    sealed interface Target {
        data object RealRoot : Target
        data class RealModule(val moduleType: String?) : Target
        data object FakeSnapshot : Target
    }

    enum class StructureMode {
        ENSURE,
        ENSURE_IF_MISSING,
        RESTORE_EXISTING,
        RESTORE_OR_ENSURE
    }

    enum class ContentMode {
        NONE,
        WHEN_LAYOUT_CHANGED,
        WHEN_RESTORING_EXISTING,
        ALWAYS,
        FORCE
    }

    data class ShowOptions(
        val structure: StructureMode,
        val content: ContentMode,
        val suppressAnimation: Boolean,
        val reconfigureExisting: Boolean
    )

    enum class Outcome {
        APPLIED,
        RESTORED_NATIVE,
        NO_OP,
        TARGET_STRUCTURE_MISSING
    }

    data class Result(
        val outcome: Outcome,
        val layoutMayHaveChanged: Boolean,
        val contentChanged: Boolean,
        val injectedSlotsPresent: Boolean?,
        val relayoutRequested: Boolean
    ) {
        companion object {
            val NO_OP = Result(
                outcome = Outcome.NO_OP,
                layoutMayHaveChanged = false,
                contentChanged = false,
                injectedSlotsPresent = null,
                relayoutRequested = false
            )
        }
    }

    fun show(
        root: ViewGroup,
        target: Target,
        options: ShowOptions
    ): Result {
        val hadInjectedSlots = IslandSlotStructureInjector.hasInjectedLyricText(root)
        val layoutMayHaveChanged = when (target) {
            Target.RealRoot,
            Target.FakeSnapshot -> reconcileRootStructure(
                root = root,
                hadInjectedSlots = hadInjectedSlots,
                options = options
            )

            is Target.RealModule -> reconcileModuleStructure(
                root = root,
                moduleType = target.moduleType,
                hadInjectedSlots = hadInjectedSlots,
                options = options
            )
        }

        var contentWasRefreshed = false
        val contentChanged = when (options.content) {
            ContentMode.NONE -> false
            ContentMode.WHEN_LAYOUT_CHANGED -> {
                if (!layoutMayHaveChanged) {
                    false
                } else {
                    contentWasRefreshed = true
                    IslandLyricContentRefresher.refreshCurrentContent(
                        root,
                        suppressAnimation = options.suppressAnimation
                    )
                }
            }

            ContentMode.WHEN_RESTORING_EXISTING -> {
                if (!hadInjectedSlots) {
                    false
                } else {
                    contentWasRefreshed = true
                    IslandLyricContentRefresher.refreshCurrentContent(
                        root,
                        force = true,
                        suppressAnimation = options.suppressAnimation
                    )
                }
            }

            ContentMode.ALWAYS -> {
                contentWasRefreshed = true
                IslandLyricContentRefresher.refreshCurrentContent(
                    root,
                    suppressAnimation = options.suppressAnimation
                )
            }

            ContentMode.FORCE -> {
                contentWasRefreshed = true
                IslandLyricContentRefresher.refreshCurrentContent(
                    root,
                    force = true,
                    suppressAnimation = options.suppressAnimation
                )
            }
        }

        if (contentWasRefreshed) {
            IslandSlotStructureInjector.linkViews(root)
        }
        IslandViewRegistry.refreshInjectedViews(root)
        val injectedSlotsPresent = IslandSlotStructureInjector.hasInjectedLyricText(root)
        val expectsInjectedSlots = when (target) {
            Target.RealRoot,
            Target.FakeSnapshot -> IslandSlotStructureInjector.expectsConfiguredSlot()

            is Target.RealModule -> {
                IslandSlotStructureInjector.expectsConfiguredSlot(target.moduleType)
            }
        }
        val configuredStructureReady = when (target) {
            Target.RealRoot,
            Target.FakeSnapshot -> IslandSlotStructureInjector.hasAllConfiguredSlots(root)

            is Target.RealModule -> {
                IslandSlotStructureInjector.hasAllConfiguredSlots(root, target.moduleType)
            }
        }
        val relayoutRequested = target == Target.RealRoot && layoutMayHaveChanged
        if (relayoutRequested) {
            IslandHostFacade.triggerSystemRelayout(root)
        }

        return Result(
            outcome = when {
                expectsInjectedSlots && !configuredStructureReady -> {
                    Outcome.TARGET_STRUCTURE_MISSING
                }
                layoutMayHaveChanged || contentChanged -> Outcome.APPLIED
                else -> Outcome.NO_OP
            },
            layoutMayHaveChanged = layoutMayHaveChanged,
            contentChanged = contentChanged,
            injectedSlotsPresent = injectedSlotsPresent,
            relayoutRequested = relayoutRequested
        )
    }

    fun prepareFrozenSnapshot(root: ViewGroup, position: Long): Result {
        return prepareFrozenTransitionHost(
            root = root,
            target = Target.FakeSnapshot,
            position = position
        )
    }

    fun prepareFrozenRealHost(root: ViewGroup, position: Long): Result {
        return prepareFrozenTransitionHost(
            root = root,
            target = Target.RealRoot,
            position = position,
            content = ContentMode.FORCE
        )
    }

    fun restoreFrozenRealHost(root: ViewGroup, position: Long): Result {
        return prepareFrozenTransitionHost(
            root = root,
            target = Target.RealRoot,
            position = position,
            content = ContentMode.NONE
        )
    }

    private fun prepareFrozenTransitionHost(
        root: ViewGroup,
        target: Target,
        position: Long,
        content: ContentMode = ContentMode.FORCE
    ): Result {
        val result = show(
            root = root,
            target = target,
            options = ShowOptions(
                structure = StructureMode.RESTORE_OR_ENSURE,
                content = content,
                suppressAnimation = true,
                reconfigureExisting = false
            )
        )
        if (result.injectedSlotsPresent == true) {
            IslandLyricPlaybackController.freezeInjectedLyricProgress(root, position)
        }
        return result
    }

    fun restoreNative(root: ViewGroup, target: Target): Result {
        val layoutMayHaveChanged = IslandHostFacade.clearInjectedViews(root)
        val relayoutRequested = target == Target.RealRoot && layoutMayHaveChanged
        if (relayoutRequested) {
            IslandHostFacade.triggerSystemRelayout(root)
        }
        return Result(
            outcome = if (layoutMayHaveChanged) {
                Outcome.RESTORED_NATIVE
            } else {
                Outcome.NO_OP
            },
            layoutMayHaveChanged = layoutMayHaveChanged,
            contentChanged = false,
            injectedSlotsPresent = IslandSlotStructureInjector.hasInjectedLyricText(root),
            relayoutRequested = relayoutRequested
        )
    }

    private fun reconcileRootStructure(
        root: ViewGroup,
        hadInjectedSlots: Boolean,
        options: ShowOptions
    ): Boolean {
        return when (options.structure) {
            StructureMode.ENSURE -> IslandSlotStructureInjector.injectSlots(
                root,
                reconfigureExisting = options.reconfigureExisting,
                suppressAnimation = options.suppressAnimation
            )

            StructureMode.ENSURE_IF_MISSING -> {
                if (IslandSlotStructureInjector.hasAllConfiguredSlots(root)) {
                    false
                } else {
                    IslandSlotStructureInjector.injectSlots(
                        root,
                        reconfigureExisting = false,
                        suppressAnimation = options.suppressAnimation
                    )
                }
            }

            StructureMode.RESTORE_EXISTING -> {
                IslandSlotStructureInjector.restoreExistingSlotsLightweight(root)
            }

            StructureMode.RESTORE_OR_ENSURE -> {
                if (hadInjectedSlots) {
                    IslandSlotStructureInjector.restoreExistingSlotsLightweight(root)
                } else {
                    IslandSlotStructureInjector.injectSlots(
                        root,
                        reconfigureExisting = false,
                        suppressAnimation = options.suppressAnimation
                    )
                }
            }
        }
    }

    private fun reconcileModuleStructure(
        root: ViewGroup,
        moduleType: String?,
        hadInjectedSlots: Boolean,
        options: ShowOptions
    ): Boolean {
        return when (options.structure) {
            StructureMode.RESTORE_EXISTING -> {
                IslandSlotStructureInjector.restoreExistingModuleSlotLightweight(root, moduleType)
            }

            StructureMode.ENSURE_IF_MISSING -> {
                if (IslandSlotStructureInjector.hasAllConfiguredSlots(root, moduleType)) {
                    false
                } else {
                    IslandSlotStructureInjector.injectSlots(
                        root,
                        reconfigureExisting = false,
                        suppressAnimation = options.suppressAnimation
                    )
                }
            }

            StructureMode.ENSURE -> IslandSlotStructureInjector.injectSlots(
                root,
                reconfigureExisting = options.reconfigureExisting,
                suppressAnimation = options.suppressAnimation
            )

            StructureMode.RESTORE_OR_ENSURE -> {
                if (hadInjectedSlots) {
                    IslandSlotStructureInjector.restoreExistingModuleSlotLightweight(root, moduleType)
                } else {
                    IslandSlotStructureInjector.injectSlots(
                        root,
                        reconfigureExisting = false,
                        suppressAnimation = options.suppressAnimation
                    )
                }
            }
        }
    }
}
