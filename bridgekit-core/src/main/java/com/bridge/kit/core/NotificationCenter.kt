package com.bridge.kit.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ServiceLoader
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Message board addressed by `(category, name)`. Any module can post, observe and reply.
 * Nobody listening is a normal answer: [post] returns `false`, [request] `null`, [requestAll] empty, [latest] `null`.
 *
 * Handlers always run on the main thread, in post order, one at a time. [dispatch] is replaceable for tests.
 */
class NotificationCenter(
    private val dispatch: (() -> Unit) -> Unit = ::postToMain,
) {
    /** [category] `null` = every category (wildcard). [key] groups the observers of one feature so it is called once per notification. */
    private class Observer(val category: String?, val name: String?, val key: Any, val handler: (Notification) -> Unit) {
        val counts get() = category != null
        fun matches(c: String, n: String) = category == null || (category == c && (name == null || name == n))
    }

    private val observers = CopyOnWriteArrayList<Observer>()
    private val store = HashMap<String, Any?>()
    @Volatile private var registered = false

    /** Sees every notification before its handlers run; route it to a debugger or logger. */
    @Volatile var tap: ((Notification) -> Unit)? = null

    /** Returns `true` if at least one non-wildcard observer listens to [category] / [name]. Callable from any thread. */
    fun post(
        category: String,
        name: String,
        payload: Map<String, Any?> = emptyMap(),
        onReply: ((Map<String, Any?>) -> Unit)? = null,
    ): Boolean {
        val targets = targets(category, name)
        deliver(category, name, payload, targets) { onReply }
        return targets.any { it.counts }
    }

    /** [name] `null` = every name in [category]. Removed when [owner] is destroyed. Call on the main thread. */
    fun observe(category: String, owner: LifecycleOwner, name: String? = null, handler: (Notification) -> Unit) =
        add(Observer(category, name, Any(), handler), owner)

    /** Every category. Never counts for [post]'s result or [requestAll]'s expected replies. Call on the main thread. */
    fun observeAll(owner: LifecycleOwner, handler: (Notification) -> Unit) =
        add(Observer(null, null, Any(), handler), owner)

    /** First reply, or `null` when nobody listens or nobody replies within [timeoutMs]. */
    suspend fun request(
        category: String,
        name: String,
        payload: Map<String, Any?> = emptyMap(),
        timeoutMs: Long = 2000,
    ): Map<String, Any?>? {
        val first = CompletableDeferred<Map<String, Any?>>()
        if (!post(category, name, payload) { first.complete(it) }) return null
        return withTimeoutOrNull(timeoutMs) { first.await() }
    }

    /** One reply per listening observer (wildcards excluded), whatever arrived before [timeoutMs]. */
    suspend fun requestAll(
        category: String,
        name: String,
        payload: Map<String, Any?> = emptyMap(),
        timeoutMs: Long = 2000,
    ): List<Map<String, Any?>> {
        val targets = targets(category, name)
        val expected = targets.count { it.counts }
        if (expected == 0) return emptyList()
        val replies = LinkedHashMap<Any, Map<String, Any?>>()
        val done = CompletableDeferred<Unit>()
        deliver(category, name, payload, targets) { o ->
            if (!o.counts) null else { data ->
                synchronized(replies) {
                    if (replies.putIfAbsent(o.key, data) == null && replies.size >= expected) done.complete(Unit)
                }
            }
        }
        withTimeoutOrNull(timeoutMs) { done.await() }
        return synchronized(replies) { replies.values.toList() }
    }

    /** Stores [value] as the latest for `category.name` and posts it as payload `{"value": value}`. */
    fun publish(category: String, name: String, value: Any?) {
        synchronized(store) { store["$category.$name"] = value }
        post(category, name, mapOf("value" to value))
    }

    /** Last [publish]ed value, synchronously, from any thread. `null` if never published (e.g. module absent). */
    fun latest(category: String, name: String): Any? = synchronized(store) { store["$category.$name"] }

    /**
     * Finds every [BridgeFeature] in this build and attaches it. Runs once per center; later calls return an empty list.
     * Call on the main thread, after the host's config is loaded. Returns the ids that attached.
     */
    fun registerAll(owner: LifecycleOwner, context: Context): List<String> {
        if (registered) return emptyList()
        registered = true
        // Two-argument form: R8 rewrites it to direct constructor calls. Keep no -keep rule on BridgeFeature.
        val features = ServiceLoader.load(BridgeFeature::class.java, BridgeFeature::class.java.classLoader)
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return register(features, owner, context, failOnDuplicate = debuggable)
    }

    internal fun register(
        features: Iterable<BridgeFeature>,
        owner: LifecycleOwner,
        context: Context,
        failOnDuplicate: Boolean,
    ): List<String> {
        val seen = HashSet<String>()
        val attached = ArrayList<String>()
        for (f in features) {
            if (!seen.add(f.id)) {
                val msg = "Two BridgeFeatures claim id '${f.id}'; the second (${f.javaClass.name}) is ignored"
                if (failOnDuplicate) error(msg) else { Log.w(TAG, msg); continue }
            }
            val ok = try { f.onAttach(context) } catch (e: Exception) { Log.e(TAG, "onAttach failed for '${f.id}'", e); false }
            if (!ok) continue
            val key = Any()
            add(Observer(f.id, null, key, f::handle), owner)
            val extra = f.listensTo
            if (extra == null) add(Observer(null, null, key, f::handle), owner)
            else (extra - f.id).forEach { add(Observer(it, null, key, f::handle), owner) }
            attached += f.id
        }
        return attached
    }

    private fun targets(category: String, name: String) = observers.filter { it.matches(category, name) }

    private fun deliver(
        category: String,
        name: String,
        payload: Map<String, Any?>,
        targets: List<Observer>,
        replyFor: (Observer) -> ((Map<String, Any?>) -> Unit)?,
    ) = dispatch {
        tap?.invoke(Notification(category, name, payload))
        val called = HashSet<Any>()
        for (o in targets) {
            if (o !in observers || !called.add(o.key)) continue   // removed since posting, or this feature already called
            try {
                o.handler(Notification(category, name, payload, replyFor(o)))
            } catch (e: Exception) {
                Log.e(TAG, "Handler failed for $category.$name", e)
            }
        }
    }

    private fun add(o: Observer, owner: LifecycleOwner) {
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return
        observers += o
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                observers -= o
                owner.lifecycle.removeObserver(this)
            }
        })
    }

    companion object {
        private const val TAG = "BridgeKit"

        /** The shared center. Features and the host use this one. */
        val default = NotificationCenter()

        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
        private fun postToMain(block: () -> Unit) {
            mainHandler.post(block)
        }
    }
}
