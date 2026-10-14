package com.bridge.kit.core

import android.content.Context

/**
 * A pluggable feature. Discovered at runtime by [NotificationCenter.registerAll] through
 * `META-INF/services/com.bridge.kit.core.BridgeFeature`, so implementations need a public no-arg constructor.
 */
interface BridgeFeature {
    /** The category this feature owns. Always listened. Must be unique. */
    val id: String

    /**
     * Extra categories to listen to, on top of [id].
     * - `null` (default): every category. Wildcard listening never counts as "someone listens" for
     *   [NotificationCenter.post] / [NotificationCenter.requestAll], so a missing module is still detected.
     * - a set: those categories plus [id].
     * - empty set: only [id].
     *
     * Because the default hears everything, [handle] should match on `category` and `name` together.
     */
    val listensTo: Set<String>? get() = null

    /**
     * Module priority: 0 = highest. Breaks ties when two of this module's UI entries and another module's
     * have the same priority (slots, overlays, modals in bridgekit-ui). Default [DEFAULT_PRIORITY].
     */
    val priority: Int get() = DEFAULT_PRIORITY

    /** Called once at registration, on the main thread. Return `false` to stay silent (not configured / disabled). */
    fun onAttach(context: Context): Boolean = true

    /** Every notification this feature listens to. Main thread, in post order. */
    fun handle(n: Notification)

    companion object {
        /** Priority used when none is given. Lower numbers win; 0 always beats an unspecified priority. */
        const val DEFAULT_PRIORITY = 100
    }
}
