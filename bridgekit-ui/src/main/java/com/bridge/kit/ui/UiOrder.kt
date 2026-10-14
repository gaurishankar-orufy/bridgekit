package com.bridge.kit.ui

/**
 * The one ordering rule in bridgekit-ui: lower [priority] wins (0 = highest), then lower module priority
 * (`BridgeFeature.priority`), then whoever registered first.
 */
internal data class UiOrder(val priority: Int, val modulePriority: Int, val seq: Long) : Comparable<UiOrder> {
    override fun compareTo(other: UiOrder) = COMPARATOR.compare(this, other)

    /** Same priority and same module priority: only arrival order separates them. */
    fun ties(other: UiOrder) = priority == other.priority && modulePriority == other.modulePriority

    companion object {
        private val COMPARATOR = compareBy<UiOrder>({ it.priority }, { it.modulePriority }, { it.seq })
        private var counter = 0L
        fun next(priority: Int, modulePriority: Int) = synchronized(this) { UiOrder(priority, modulePriority, counter++) }
    }
}
