package com.lidesheng.hyperlyric.root.island.presentation

import android.view.ViewGroup
import com.lidesheng.hyperlyric.root.island.host.IslandViewRegistry
import java.util.WeakHashMap

/**
 * Stores the latest fake-to-real transition record for each fake host.
 *
 * SystemUI does not return our generation in the transition-end callback, so the latest record
 * for a fake owner remains authoritative. The registry owns only this state; transition decisions
 * and view mutations stay in
 * [com.lidesheng.hyperlyric.root.island.presentation.IslandPresentationCoordinator].
 */
internal object IslandFakeTransitionRegistry {
    data class Record(
        val generation: Long,
        val realHost: IslandViewRegistry.HostToken,
        val frozenPosition: Long,
        val lyricVersion: Int
    )

    private val lock = Any()
    private val transitions = WeakHashMap<ViewGroup, Record>()
    private var nextGeneration = 0L

    fun remember(
        fakeOwner: ViewGroup,
        realHost: IslandViewRegistry.HostToken,
        frozenPosition: Long,
        lyricVersion: Int
    ): Record {
        return synchronized(lock) {
            Record(
                generation = ++nextGeneration,
                realHost = realHost,
                frozenPosition = frozenPosition,
                lyricVersion = lyricVersion
            ).also { transitions[fakeOwner] = it }
        }
    }

    fun find(fakeOwner: ViewGroup): Record? {
        return synchronized(lock) {
            transitions[fakeOwner]
        }
    }

    fun remove(fakeOwner: ViewGroup): Record? {
        return synchronized(lock) {
            transitions.remove(fakeOwner)
        }
    }

    fun isHostFrozen(token: IslandViewRegistry.HostToken): Boolean {
        return synchronized(lock) {
            transitions.values.any { transition ->
                transition.realHost == token
            }
        }
    }

    fun discardForHost(token: IslandViewRegistry.HostToken) {
        synchronized(lock) {
            val iterator = transitions.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value.realHost == token) {
                    iterator.remove()
                }
            }
        }
    }
}
