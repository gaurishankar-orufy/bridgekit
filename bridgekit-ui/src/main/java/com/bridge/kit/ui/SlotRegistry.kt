package com.bridge.kit.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.navigation.NavBackStackEntry

/**
 * Where features put UI. Generic: the host app decides slot names and layer numbers.
 *
 * - Single slots: one owner on screen, highest priority wins.
 * - Overlays: many at once, drawn by layer, then priority (later = on top).
 * - Routes: registered before the host's NavHost is first composed, then frozen
 *   (a NavHost graph that changes later clears the back stack).
 *
 * Register from `BridgeFeature.onAttach` on the main thread. Slot content must not capture an Activity or View;
 * read `LocalContext.current` inside the content instead.
 */
object SlotRegistry {
    class Entry internal constructor(val id: String, val priority: Int, val content: @Composable () -> Unit)
    class Overlay internal constructor(val id: String, val layer: Int, val priority: Int, val content: @Composable () -> Unit)

    private val slots = mutableStateListOf<Pair<String, Entry>>()
    private val overlayList = mutableStateListOf<Overlay>()
    private val routeMap = LinkedHashMap<String, @Composable (NavBackStackEntry) -> Unit>()
    @Volatile private var routesFrozen = false

    /** `true` turns conflicts (same priority in one slot, duplicate route) into exceptions. Set it in debug builds. */
    @Volatile var strict: Boolean = false

    /** Puts [content] into [slot] under [id]. Same id again replaces its earlier content. */
    fun set(slot: String, id: String, priority: Int = 0, content: @Composable () -> Unit) {
        slots.removeAll { it.first == slot && it.second.id == id }
        slots.firstOrNull { it.first == slot && it.second.priority == priority }?.let {
            conflict("Slot '$slot': '${it.second.id}' and '$id' both use priority $priority")
        }
        slots += slot to Entry(id, priority, content)
    }

    /** Content that wins [slot], or `null` when no feature filled it. Reading it in composition subscribes to changes. */
    fun content(slot: String): (@Composable () -> Unit)? =
        slots.filter { it.first == slot }.maxByOrNull { it.second.priority }?.second?.content

    /** Adds an overlay. Same id again replaces it. */
    fun addOverlay(id: String, layer: Int, priority: Int = 0, content: @Composable () -> Unit) {
        overlayList.removeAll { it.id == id }
        overlayList += Overlay(id, layer, priority, content)
    }

    fun removeOverlay(id: String) {
        overlayList.removeAll { it.id == id }
    }

    /** Overlays of one [layer], lowest priority first (drawn first, so the highest ends up on top). */
    fun overlays(layer: Int): List<Overlay> = overlayList.filter { it.layer == layer }.sortedBy { it.priority }

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

    private fun conflict(msg: String) {
        if (strict) error(msg) else Log.w("BridgeKit", msg)
    }
}
