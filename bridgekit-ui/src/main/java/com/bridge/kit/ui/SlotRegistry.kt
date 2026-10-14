package com.bridge.kit.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.navigation.NavBackStackEntry
import com.bridge.kit.core.BridgeFeature

/**
 * Where features put UI that is always part of the screen. Generic: the host decides slot names and layer numbers.
 *
 * Ordering everywhere: lower priority wins (0 = highest), then the owner's module priority
 * (`BridgeFeature.priority`), then first registered.
 * - Single slots: only the winner is drawn.
 * - Overlays: all are drawn, winner on top.
 * - Routes: registered before the host's NavHost is first composed, then frozen
 *   (a NavHost graph that changes later clears the back stack).
 *
 * For dialogs and sheets that come and go, use [Modals] instead.
 * Register from `BridgeFeature.onAttach` on the main thread. Content must not capture an Activity or View;
 * read `LocalContext.current` inside it.
 */
object SlotRegistry {
    private class SlotEntry(val slot: String, val ownerId: String, val order: UiOrder, val content: @Composable () -> Unit)
    class Overlay internal constructor(val id: String, val layer: Int, internal val order: UiOrder, val content: @Composable () -> Unit)

    private val slots = mutableStateListOf<SlotEntry>()
    private val overlayList = mutableStateListOf<Overlay>()
    private val routeMap = LinkedHashMap<String, @Composable (NavBackStackEntry) -> Unit>()
    @Volatile private var routesFrozen = false

    /** `true` turns real conflicts (exact priority tie in one slot, duplicate route) into exceptions. Set it in debug builds. */
    @Volatile var strict: Boolean = false

    /** Puts [owner]'s [content] into [slot]. Calling again for the same owner and slot replaces it. */
    fun set(owner: BridgeFeature, slot: String, priority: Int = BridgeFeature.DEFAULT_PRIORITY, content: @Composable () -> Unit) {
        val previous = slots.firstOrNull { it.slot == slot && it.ownerId == owner.id }
        val order = previous?.order?.takeIf { it.priority == priority } ?: UiOrder.next(priority, owner.priority)
        slots.removeAll { it.slot == slot && it.ownerId == owner.id }
        slots.firstOrNull { it.slot == slot && it.order.ties(order) }?.let {
            conflict("Slot '$slot': '${it.ownerId}' and '${owner.id}' have the same priority and module priority")
        }
        slots += SlotEntry(slot, owner.id, order, content)
    }

    /** Content that wins [slot], or `null` when no feature filled it. Reading it in composition subscribes to changes. */
    fun content(slot: String): (@Composable () -> Unit)? =
        slots.filter { it.slot == slot }.minByOrNull { it.order }?.content

    /** Adds an overlay. Same [id] again replaces it. */
    fun addOverlay(
        owner: BridgeFeature,
        id: String,
        layer: Int,
        priority: Int = BridgeFeature.DEFAULT_PRIORITY,
        content: @Composable () -> Unit,
    ) {
        overlayList.removeAll { it.id == id }
        overlayList += Overlay(id, layer, UiOrder.next(priority, owner.priority), content)
    }

    fun removeOverlay(id: String) {
        overlayList.removeAll { it.id == id }
    }

    /** Overlays of one [layer] in drawing order: least important first, so the winner is drawn last (on top). */
    fun overlays(layer: Int): List<Overlay> = overlayList.filter { it.layer == layer }.sortedByDescending { it.order }

    /** Registers a NavHost route. Only before [freezeRoutes]; afterwards it throws. */
    fun addRoute(route: String, screen: @Composable (NavBackStackEntry) -> Unit) {
        check(!routesFrozen) { "Route '$route' registered after the NavHost was built. Register routes in onAttach." }
        if (route in routeMap) conflict("Route '$route' registered twice; the later one wins")
        routeMap[route] = screen
    }

    val routes: Map<String, @Composable (NavBackStackEntry) -> Unit> get() = routeMap

    /** Called by [bridgeRoutes]. */
    fun freezeRoutes() {
        routesFrozen = true
    }

    /** Empties everything. For tests and previews only. */
    fun clear() {
        slots.clear()
        overlayList.clear()
        routeMap.clear()
        routesFrozen = false
        strict = false
    }

    internal fun conflict(msg: String) {
        if (strict) error(msg) else Log.w("BridgeKit", msg)
    }
}
