package com.bridge.kit.core

/**
 * One message on the [NotificationCenter], addressed by [category] + [name].
 *
 * The constructor is public so features can be unit-tested with a hand-made notification.
 */
class Notification(
    val category: String,
    val name: String,
    val payload: Map<String, Any?> = emptyMap(),
    private val onReply: ((Map<String, Any?>) -> Unit)? = null,
) {
    /** Answers the poster's `onReply` / `request`. No-op for a plain post. May be called later, and more than once. */
    fun reply(data: Map<String, Any?> = emptyMap()) {
        onReply?.invoke(data)
    }

    fun str(key: String): String? = payload[key]?.toString()

    fun bool(key: String): Boolean = when (val v = payload[key]) {
        is Boolean -> v
        is String -> v.toBoolean()
        else -> false
    }

    fun int(key: String): Int = when (val v = payload[key]) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: 0
        else -> 0
    }

    override fun toString() = "Notification($category.$name, $payload)"
}
