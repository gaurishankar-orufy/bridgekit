package com.bridge.kit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.composable

/** Draws whatever won [name], or [fallback] when no feature filled it. */
@Composable
fun Slot(name: String, fallback: @Composable () -> Unit = {}) {
    (SlotRegistry.content(name) ?: fallback)()
}

/**
 * Draws one overlay [layer]. Call it after the host's own `BackHandler`, so an open overlay's
 * `BackHandler(enabled = isOpen)` wins.
 */
@Composable
fun Overlays(layer: Int) {
    SlotRegistry.overlays(layer).forEach { o -> key(o.id) { o.content() } }
}

/** Adds every feature route to this NavHost and freezes the route list. */
fun NavGraphBuilder.bridgeRoutes() {
    SlotRegistry.freezeRoutes()
    SlotRegistry.routes.forEach { (route, screen) -> composable(route) { screen(it) } }
}

/** Navigates if the route exists in the graph; returns `false` instead of crashing when its module is not in the build. */
fun NavController.navigateIfExists(route: String, builder: NavOptionsBuilder.() -> Unit = {}): Boolean =
    try {
        navigate(route, builder)
        true
    } catch (e: IllegalArgumentException) {   // destination not in the graph
        false
    } catch (e: IllegalStateException) {      // no graph set yet
        false
    }
