package com.lidesheng.hyperlyric.root.island.presentation

import android.view.View
import android.view.ViewGroup
import com.lidesheng.hyperlyric.root.island.IslandViewRegistry
import java.util.WeakHashMap

/**
 * Observes real Super Island host attachment without owning presentation policy.
 *
 * The callback is posted after attachment, matching the previous coordinator timing so the host
 * can finish its native attach/layout work before stable reconciliation runs.
 */
internal class IslandHostAttachmentObserver(
    private val currentPresentationRevision: () -> Long,
    private val onHostAttached: (IslandViewRegistry.HostToken, Long) -> Unit
) {
    private val lock = Any()
    private val listeners = WeakHashMap<ViewGroup, View.OnAttachStateChangeListener>()

    fun observe(root: ViewGroup) {
        synchronized(lock) {
            if (listeners.containsKey(root)) return
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    val attachedRoot = view as? ViewGroup ?: return
                    val token = IslandViewRegistry.markAttached(attachedRoot) ?: return
                    val expectedRevision = currentPresentationRevision()
                    attachedRoot.post {
                        onHostAttached(token, expectedRevision)
                    }
                }

                override fun onViewDetachedFromWindow(view: View) {
                    (view as? ViewGroup)?.let(IslandViewRegistry::markDetached)
                }
            }
            listeners[root] = listener
            root.addOnAttachStateChangeListener(listener)
        }
    }

    fun stop(root: ViewGroup) {
        val listener = synchronized(lock) {
            listeners.remove(root)
        } ?: return
        root.removeOnAttachStateChangeListener(listener)
    }
}
