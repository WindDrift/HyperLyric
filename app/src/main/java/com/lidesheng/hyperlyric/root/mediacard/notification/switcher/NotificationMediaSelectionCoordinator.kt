package com.lidesheng.hyperlyric.root.mediacard.notification.switcher

internal data class NotificationMediaSelectionSnapshot(
    val entries: List<Pair<String, Any>>,
    val selectedKey: String?
) {
    val selectedIndex: Int
        get() = selectedKey?.let { key ->
            entries.indexOfFirst { entry -> entry.first == key }
        }?.takeIf { it >= 0 } ?: -1
}

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

    fun isPlaying(data: Any): Boolean?

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
    private val bindSelected: (Any) -> Unit,
    private val shouldPreserveNativeOrder: () -> Boolean = { false },
    private val maxPageCount: Int = Int.MAX_VALUE
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
     * Returns the page set for the renderer. The coordinator retains every
     * active MediaData entry, but the renderer may request a bounded snapshot
     * to avoid creating one native ViewController/Holder for every session.
     *
     * Selection policy when the bound is exceeded:
     * 1. keep the currently selected session;
     * 2. keep currently playing sessions in native order;
     * 3. fill remaining slots from native order.
     *
     * The final list is filtered back to native order, so the page layout stays
     * stable while the selected session is guaranteed not to disappear.
     * Selection methods use the same bounded page set, so gestures and page
     * indicators cannot navigate to an entry that is not rendered.
     */
    fun snapshot(maxEntries: Int = Int.MAX_VALUE): NotificationMediaSelectionSnapshot {
        val allEntries = orderedKeys.mapNotNull { key ->
            entries[key]?.let { key to it.data }
        }
        if (allEntries.size <= maxEntries) {
            return NotificationMediaSelectionSnapshot(allEntries, selectedKey)
        }

        val limit = maxEntries.coerceAtLeast(1)
        val priorityKeys = LinkedHashSet<String>()
        selectedKey?.takeIf { it in entries }?.let(priorityKeys::add)
        orderedKeys.forEach { key ->
            val data = entries[key]?.data ?: return@forEach
            if (accessor.isPlaying(data) == true) priorityKeys += key
        }
        orderedKeys.forEach { key -> priorityKeys += key }

        val keptKeys = priorityKeys.take(limit).toHashSet()
        val visibleEntries = allEntries.filter { it.first in keptKeys }
        val visibleSelectedKey = selectedKey?.takeIf { key ->
            visibleEntries.any { it.first == key }
        } ?: visibleEntries.firstOrNull()?.first
        return NotificationMediaSelectionSnapshot(visibleEntries, visibleSelectedKey)
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

        reorder(preserveCurrentOrder = selectedByUser || shouldPreserveNativeOrder())
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
            if ((selectedByUser || shouldPreserveNativeOrder() || selectedKey == key) &&
                (selectedKey != previousSelectedKey || selectedDataChanged)
            ) {
                bindCurrentSelection()
            }
        }
    }

    fun onMediaDataRemoved(key: String) {
        val previousSelectedKey = selectedKey
        entries.remove(key)
        reorder(
            preserveCurrentOrder = shouldPreserveNativeOrder() ||
                (selectedByUser && selectedKey != key)
        )
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
    fun onNativeBind(data: Any?, synthetic: Boolean = false) {
        if (data == null) {
            if (entries.isEmpty()) resetSelection()
            return
        }

        val key = accessor.notificationKey(data)
        val incomingToken = accessor.sessionToken(data)
        val knownToken = key?.let { entries[it]?.sessionToken }
        val tokenChangedBeforeMediaDataUpdate = knownToken != null &&
            incomingToken != null && knownToken != incomingToken
        val userSelection = selectedKey?.takeIf { selectedByUser && it in entries }
        if (userSelection != null) {
            if (key == userSelection && tokenChangedBeforeMediaDataUpdate) {
                // MediaSortUtils can bind a newly-created session before its
                // MediaData.Listener callback replaces the coordinator entry.
                // Do not let that transient bind overwrite the selected card;
                // the later MediaData callback will perform the real refresh.
                if (!synthetic) bindCurrentSelection()
                return
            }
            if (key != userSelection) {
                // A native bind for the former top card may arrive after the
                // user selected a secondary page. It must not reorder the
                // user's page back to the middle. Single-card mode replays
                // the selected data; multi-card mode keeps its own viewport.
                if (!synthetic) bindCurrentSelection()
                return
            }

            if (synthetic) {
                // bindMediaData() was explicitly issued by this coordinator.
                // Treat it as a content refresh, not as evidence that the
                // SystemUI sort order promoted this session.
                updateSelectedToken()
                return
            }

            if (shouldPreserveNativeOrder()) {
                // Multiple sessions are allowed to remain playing. A native
                // bind is only a content update in this mode; it must not
                // move the selected card or the page indicator.
                updateSelectedToken()
                return
            }

            // The selected session itself became the native top item (usually
            // after the user pressed Play). Promote it to page zero while the
            // renderer preserves the card's current screen position.
            reorder(preserveCurrentOrder = true)
            promoteToFront(userSelection)
            updateSelectedToken()
            return
        }

        // Playing a secondary session can reorder MediaSortUtils and bind it
        // as native top without producing a new MediaData object. The native
        // bind is therefore also a list-order signal; otherwise selected B
        // remains at its old index behind A.
        reorder(preserveCurrentOrder = shouldPreserveNativeOrder())
        if (!synthetic && !shouldPreserveNativeOrder() && key != null && key in entries) {
            // During the same pipeline turn MediaSortUtils can still expose
            // its old list while topMediaData already identifies B. The
            // actual native bind is the stronger signal for the first page.
            promoteToFront(key)
        }
        if ((!selectedByUser && !shouldPreserveNativeOrder()) ||
            selectedKey == null ||
            selectedKey !in entries
        ) {
            selectedKey = key ?: orderedKeys.firstOrNull()
            selectedToken = key?.let { entries[it]?.sessionToken } ?: incomingToken
            selectedByUser = false
            return
        }

        if (tokenChangedBeforeMediaDataUpdate) {
            // The same notification key is allowed to recreate its
            // MediaSession. Treat a native bind with the new token as stale
            // until the corresponding MediaData object is visible to the
            // listener; otherwise a reused native controller can display the
            // wrong session's artwork and application identity.
            bindCurrentSelection()
            return
        }

        if (key != selectedKey) {
            // The original controller just rebound the native top card. Replay
            // the selected item after it finishes so our state remains stable.
            bindCurrentSelection()
        } else {
            selectedToken = key?.let { entries[it]?.sessionToken } ?: incomingToken
        }
    }

    fun selectRelative(step: Int) {
        val pageKeys = visiblePageKeys()
        if (step == 0 || pageKeys.size < 2) return

        val currentPageIndex = pageKeys.indexOf(selectedKey).takeIf { it >= 0 } ?: 0
        selectIndex(currentPageIndex + step)
    }

    /**
     * Selects a page without making the renderer know about notification keys.
     * A multi-panel renderer can use the same index as its child View position;
     * single-card mode keeps bindMediaData() as its own selected-card path.
     */
    fun selectIndex(index: Int) {
        val pageKeys = visiblePageKeys()
        if (pageKeys.isEmpty()) return

        val currentPageIndex = pageKeys.indexOf(selectedKey).takeIf { it >= 0 } ?: -1
        val targetIndex = index.coerceIn(0, pageKeys.lastIndex)
        if (targetIndex == currentPageIndex && selectedKey == pageKeys[targetIndex]) return

        selectedKey = pageKeys[targetIndex]
        selectedByUser = true
        updateSelectedToken()
        bindCurrentSelection()
    }

    fun selectKey(key: String) {
        val index = visiblePageKeys().indexOf(key)
        if (index >= 0) selectIndex(index)
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

    private fun visiblePageKeys(): List<String> {
        return snapshot(maxPageCount).entries.map { it.first }
    }

    private fun resetSelection() {
        selectedKey = null
        selectedToken = null
        selectedByUser = false
    }

    private fun reorder(preserveCurrentOrder: Boolean = false) {
        val nativeKeys = runCatching { nativeOrder() }.getOrDefault(emptyList())
        if (preserveCurrentOrder) {
            val currentKeys = orderedKeys.filter { it in entries }
            orderedKeys.clear()
            currentKeys.forEach { key ->
                if (key !in orderedKeys) orderedKeys += key
            }
            nativeKeys.forEach { key ->
                if (key in entries && key !in orderedKeys) orderedKeys += key
            }
            entries.keys.forEach { key ->
                if (key !in orderedKeys) orderedKeys += key
            }
            return
        }

        orderedKeys.clear()

        nativeKeys.forEach { key ->
            if (key in entries && key !in orderedKeys) orderedKeys += key
        }
        entries.keys.forEach { key ->
            if (key !in orderedKeys) orderedKeys += key
        }
    }

    private fun promoteToFront(key: String) {
        if (key !in entries) return
        orderedKeys.remove(key)
        orderedKeys.add(0, key)
    }
}
