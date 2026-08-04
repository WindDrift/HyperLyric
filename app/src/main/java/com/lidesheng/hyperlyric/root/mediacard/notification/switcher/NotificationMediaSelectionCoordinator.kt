package com.lidesheng.hyperlyric.root.mediacard.notification.switcher

/**
 * Reflection-independent view of the target SystemUI MediaData model.
 *
 * The concrete MediaData class belongs to SystemUI, so the module must not put
 * that private class in its own compile-time API. Keeping this small contract
 * separate also leaves a reusable data/selection layer for a future carousel
 * renderer.
 */
internal interface NotificationMediaDataAccessor {
    fun notificationKey(data: Any): String?

    fun sessionToken(data: Any): Any?

    fun isActive(data: Any): Boolean

    fun sortKey(sortKey: Any): String?

    fun sortData(sortKey: Any): Any?
}

/**
 * Owns the multi-session list and the currently selected item.
 *
 * This class deliberately knows nothing about Views or Xposed. The renderer
 * calls [bindSelected] when the selected entry changes; single-card mode binds
 * the original controller, while a multi-card renderer moves its viewport and
 * keeps each native controller independently bound.
 */
internal class NotificationMediaSelectionCoordinator(
    private val accessor: NotificationMediaDataAccessor,
    private val nativeOrder: () -> List<String>,
    private val nativeTopKey: () -> String?,
    private val bindSelected: (Any) -> Unit
) {
    private data class Entry(
        val key: String,
        val data: Any,
        val sessionToken: Any?
    )

    private val entries = LinkedHashMap<String, Entry>()
    private val orderedKeys = ArrayList<String>()

    private var selectedKey: String? = null
    private var selectedToken: Any? = null
    private var selectedByUser = false

    val size: Int
        get() = orderedKeys.size

    /**
     * The currently selected position in [orderedKeys]. This deliberately
     * lives outside the native card View, matching MIUI 14's carousel state.
     * A missing selection is exposed as -1 until the native top item or the
     * first available entry has been adopted.
     */
    val selectedIndex: Int
        get() = selectedKey?.let(orderedKeys::indexOf)?.takeIf { it >= 0 } ?: -1

    /**
     * Returns the current native sort order together with the latest data
     * object for every active session. The View renderer uses this snapshot to
     * create or update one native card per session without owning selection
     * state itself.
     */
    fun snapshot(): List<Pair<String, Any>> {
        return orderedKeys.mapNotNull { key ->
            entries[key]?.let { key to it.data }
        }
    }

    fun seed(initialEntries: List<Pair<String, Any>>) {
        entries.clear()
        initialEntries.forEach { (key, data) ->
            if (key.isNotEmpty() && accessor.isActive(data)) {
                entries[key] = Entry(key, data, accessor.sessionToken(data))
            }
        }
        reorder()
        adoptNativeSelectionIfNeeded()
    }

    fun onMediaDataLoaded(key: String, oldKey: String?, data: Any) {
        val previousSelectedKey = selectedKey
        val previousSelectedData = selectedKey?.let { entries[it]?.data }
        if (oldKey != null && oldKey != key) {
            val oldWasSelected = selectedKey == oldKey
            entries.remove(oldKey)
            if (oldWasSelected) selectedKey = key
        }

        if (accessor.isActive(data)) {
            entries[key] = Entry(key, data, accessor.sessionToken(data))
        } else {
            entries.remove(key)
        }

        reorder()
        if (selectedKey == null || selectedKey !in entries) {
            selectedByUser = false
            adoptNativeSelectionIfNeeded()
            if (previousSelectedKey != null) {
                bindCurrentSelection()
            }
        } else {
            updateSelectedToken()
            // A selected non-top session still needs to receive metadata/action
            // updates because native SystemUI will bind only its current top.
            // Native top binding already replays unchanged selections; only
            // bind again when this callback actually replaced the selected
            // entry.
            val selectedDataChanged = selectedKey?.let { entries[it]?.data } !== previousSelectedData
            if (selectedByUser &&
                (selectedKey != previousSelectedKey || selectedDataChanged)
            ) {
                bindCurrentSelection()
            }
        }
    }

    fun onMediaDataRemoved(key: String) {
        val previousSelectedKey = selectedKey
        entries.remove(key)
        reorder()
        if (selectedKey == key || selectedKey !in entries) {
            selectedByUser = false
            adoptNativeSelectionIfNeeded()
            if (previousSelectedKey != null) {
                // Native SystemUI may keep the same top MediaData when a
                // user-selected secondary session disappears. Rebind the
                // selected entry explicitly so the single card cannot retain
                // the removed session's title, artwork, or actions.
                bindCurrentSelection()
            }
        } else {
            updateSelectedToken()
        }
    }

    /**
     * Observes the native one-card binder. Native top updates must not erase a
     * user-selected secondary session, but a new native top becomes the
     * default when the user has not selected a page yet.
     */
    fun onNativeBind(data: Any?) {
        if (data == null) {
            if (entries.isEmpty()) resetSelection()
            return
        }

        // Playing a secondary session can reorder MediaSortUtils and bind it
        // as native top without producing a new MediaData object. The native
        // bind is therefore also a list-order signal; otherwise selected B
        // remains at its old index behind A.
        reorder()
        val key = accessor.notificationKey(data)
        if (key != null && key in entries) {
            // During the same pipeline turn MediaSortUtils can still expose
            // its old list while topMediaData already identifies B. The
            // actual native bind is the stronger signal for the first page.
            orderedKeys.remove(key)
            orderedKeys.add(0, key)
        }
        if (!selectedByUser || selectedKey == null || selectedKey !in entries) {
            selectedKey = key ?: orderedKeys.firstOrNull()
            selectedToken = accessor.sessionToken(data)
            selectedByUser = false
            return
        }

        if (key != selectedKey) {
            // The original controller just rebound the native top card. Replay
            // the selected item after it finishes so our state remains stable.
            bindCurrentSelection()
        } else {
            // Same notification key can be reused by an app for a new session.
            // Keep the token in the selection state so future renderers can
            // distinguish that replacement without package/title heuristics.
            selectedToken = accessor.sessionToken(data)
        }
    }

    fun selectRelative(step: Int) {
        if (step == 0 || orderedKeys.size < 2) return

        selectIndex(currentIndex() + step)
    }

    /**
     * Selects a page without making the renderer know about notification keys.
     * A multi-panel renderer can use the same index as its child View position;
     * single-card mode keeps bindMediaData() as its own selected-card path.
     */
    fun selectIndex(index: Int) {
        if (orderedKeys.isEmpty()) return

        val currentIndex = currentIndex()
        val targetIndex = index.coerceIn(0, orderedKeys.lastIndex)
        if (targetIndex == currentIndex && selectedIndex == targetIndex) return

        selectedKey = orderedKeys[targetIndex]
        selectedByUser = true
        updateSelectedToken()
        bindCurrentSelection()
    }

    fun onDetached() {
        resetSelection()
    }

    private fun bindCurrentSelection() {
        val key = selectedKey ?: return
        val entry = entries[key] ?: return

        // Compare the session identity when both sides expose a token. The
        // notification key is the list key; MediaSession.Token is the actual
        // media-session identity used to protect selection state.
        if (selectedToken != null && entry.sessionToken != null &&
            selectedToken != entry.sessionToken
        ) {
            selectedToken = entry.sessionToken
        }
        bindSelected(entry.data)
    }

    private fun adoptNativeSelectionIfNeeded() {
        if (orderedKeys.isEmpty()) {
            resetSelection()
            return
        }

        val nativeKey = nativeTopKey()
        selectedKey = nativeKey?.takeIf { it in entries } ?: orderedKeys.first()
        updateSelectedToken()
    }

    private fun updateSelectedToken() {
        selectedToken = selectedKey?.let { entries[it]?.sessionToken }
    }

    private fun currentIndex(): Int {
        return selectedIndex.takeIf { it >= 0 }
            ?: orderedKeys.indexOfFirst { it == nativeTopKey() }.takeIf { it >= 0 }
            ?: 0
    }

    private fun resetSelection() {
        selectedKey = null
        selectedToken = null
        selectedByUser = false
    }

    private fun reorder() {
        val nativeKeys = runCatching { nativeOrder() }.getOrDefault(emptyList())
        orderedKeys.clear()

        nativeKeys.forEach { key ->
            if (key in entries && key !in orderedKeys) orderedKeys += key
        }
        entries.keys.forEach { key ->
            if (key !in orderedKeys) orderedKeys += key
        }
    }
}
