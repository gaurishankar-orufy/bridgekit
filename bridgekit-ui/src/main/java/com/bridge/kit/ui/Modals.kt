package com.bridge.kit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bridge.kit.core.BridgeFeature

/**
 * Dialogs and sheets from features, one at a time. The host draws [ModalHost] once.
 *
 * - The showing modal stays until its feature calls [close]; nothing covers it or takes its place.
 * - When it closes, the next one is chosen by priority (0 = highest), then the owner's module priority,
 *   then arrival order.
 * - [show] with an id that is already showing or waiting only updates its content.
 * - Whether tapping outside or back closes a modal is decided by the feature's own sheet/dialog.
 *
 * Call on the main thread. Content must not capture an Activity or View.
 */
object Modals {
    private class Entry(val id: String, val order: UiOrder, val content: @Composable () -> Unit)

    private val entries = mutableStateListOf<Entry>()
    private var showingId by mutableStateOf<String?>(null)

    fun show(
        owner: BridgeFeature,
        id: String,
        priority: Int = BridgeFeature.DEFAULT_PRIORITY,
        content: @Composable () -> Unit,
    ) {
        val i = entries.indexOfFirst { it.id == id }
        if (i >= 0) {
            entries[i] = Entry(id, entries[i].order, content)   // update in place, keep its turn
        } else {
            entries += Entry(id, UiOrder.next(priority, owner.priority), content)
        }
        if (showingId == null) showingId = id
    }

    /** Removes [id], whether it is showing or still waiting. If it was showing, the next one appears. */
    fun close(id: String) {
        val i = entries.indexOfFirst { it.id == id }   // ids are unique; removeAll { } on a SnapshotStateList is O(n²)
        if (i >= 0) entries.removeAt(i)
        if (showingId == id) showingId = entries.minByOrNull { it.order }?.id
    }

    /** Id of the modal on screen, or `null`. */
    val showing: String? get() = showingId

    internal fun showingContent(): Pair<String, @Composable () -> Unit>? {
        val id = showingId ?: return null
        return entries.firstOrNull { it.id == id }?.let { id to it.content }
    }

    /** Empties the queue. For tests and previews only. */
    fun clear() {
        entries.clear()
        showingId = null
    }
}

/** Draws the modal whose turn it is. Place once in the host's screen. */
@Composable
fun ModalHost() {
    val (id, content) = Modals.showingContent() ?: return
    key(id) { content() }
}
