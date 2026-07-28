package com.lidesheng.hyperlyric.root.island

import android.view.ViewGroup

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
        val hadInjectedSlots = IslandLyricTextInjector.hasInjectedLyricText(root)
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

        val contentChanged = when (options.content) {
            ContentMode.NONE -> false
            ContentMode.WHEN_LAYOUT_CHANGED -> {
                layoutMayHaveChanged && IslandLyricTextInjector.refreshCurrentContent(
                    root,
                    suppressAnimation = options.suppressAnimation
                )
            }

            ContentMode.WHEN_RESTORING_EXISTING -> {
                hadInjectedSlots && IslandLyricTextInjector.refreshCurrentContent(
                    root,
                    force = true,
                    suppressAnimation = options.suppressAnimation
                )
            }

            ContentMode.ALWAYS -> IslandLyricTextInjector.refreshCurrentContent(
                root,
                suppressAnimation = options.suppressAnimation
            )

            ContentMode.FORCE -> IslandLyricTextInjector.refreshCurrentContent(
                root,
                force = true,
                suppressAnimation = options.suppressAnimation
            )
        }

        IslandViewRegistry.refreshInjectedViews(root)
        val injectedSlotsPresent = IslandLyricTextInjector.hasInjectedLyricText(root)
        val expectsInjectedSlots = when (target) {
            Target.RealRoot,
            Target.FakeSnapshot -> IslandLyricTextInjector.expectsConfiguredSlot()

            is Target.RealModule -> {
                IslandLyricTextInjector.expectsConfiguredSlot(target.moduleType)
            }
        }
        val configuredStructureReady = when (target) {
            Target.RealRoot,
            Target.FakeSnapshot -> IslandLyricTextInjector.hasAllConfiguredSlots(root)

            is Target.RealModule -> {
                IslandLyricTextInjector.hasAllConfiguredSlots(root, target.moduleType)
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
        val result = show(
            root = root,
            target = Target.FakeSnapshot,
            options = ShowOptions(
                structure = StructureMode.RESTORE_OR_ENSURE,
                content = ContentMode.FORCE,
                suppressAnimation = true,
                reconfigureExisting = false
            )
        )
        if (result.injectedSlotsPresent == true) {
            IslandLyricTextInjector.freezeInjectedLyricProgress(root, position)
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
            injectedSlotsPresent = IslandLyricTextInjector.hasInjectedLyricText(root),
            relayoutRequested = relayoutRequested
        )
    }

    private fun reconcileRootStructure(
        root: ViewGroup,
        hadInjectedSlots: Boolean,
        options: ShowOptions
    ): Boolean {
        return when (options.structure) {
            StructureMode.ENSURE -> IslandLyricTextInjector.injectSlots(
                root,
                reconfigureExisting = options.reconfigureExisting,
                suppressAnimation = options.suppressAnimation
            )

            StructureMode.ENSURE_IF_MISSING -> {
                if (IslandLyricTextInjector.hasAllConfiguredSlots(root)) {
                    false
                } else {
                    IslandLyricTextInjector.injectSlots(
                        root,
                        reconfigureExisting = false,
                        suppressAnimation = options.suppressAnimation
                    )
                }
            }

            StructureMode.RESTORE_EXISTING -> {
                IslandLyricTextInjector.restoreExistingSlotsLightweight(root)
            }

            StructureMode.RESTORE_OR_ENSURE -> {
                if (hadInjectedSlots) {
                    IslandLyricTextInjector.restoreExistingSlotsLightweight(root)
                } else {
                    IslandLyricTextInjector.injectSlots(
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
                IslandLyricTextInjector.restoreExistingModuleSlotLightweight(root, moduleType)
            }

            StructureMode.ENSURE_IF_MISSING -> {
                if (IslandLyricTextInjector.hasAllConfiguredSlots(root, moduleType)) {
                    false
                } else {
                    IslandLyricTextInjector.injectSlots(
                        root,
                        reconfigureExisting = false,
                        suppressAnimation = options.suppressAnimation
                    )
                }
            }

            StructureMode.ENSURE -> IslandLyricTextInjector.injectSlots(
                root,
                reconfigureExisting = options.reconfigureExisting,
                suppressAnimation = options.suppressAnimation
            )

            StructureMode.RESTORE_OR_ENSURE -> {
                if (hadInjectedSlots) {
                    IslandLyricTextInjector.restoreExistingModuleSlotLightweight(root, moduleType)
                } else {
                    IslandLyricTextInjector.injectSlots(
                        root,
                        reconfigureExisting = false,
                        suppressAnimation = options.suppressAnimation
                    )
                }
            }
        }
    }
}
