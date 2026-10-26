package com.bridge.kit.core

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Heavy-load tests for the event bridge. Handlers run on one dedicated "main" thread (like Android);
 * posts come from many threads at once. Each test prints how long it took.
 */
class NotificationCenterStressTest {

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        fun destroy() { lifecycle.currentState = Lifecycle.State.DESTROYED }
    }

    private class Feature(
        override val id: String,
        override val listensTo: Set<String>? = emptySet(),
        private val onHandle: (Notification) -> Unit = {},
    ) : BridgeFeature {
        val received = AtomicInteger()
        val own = AtomicInteger()
        override fun handle(n: Notification) {
            received.incrementAndGet()
            if (n.category == id) own.incrementAndGet()
            onHandle(n)
        }
    }

    private val main = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
    private val center = NotificationCenter(dispatch = { main.execute(it) })
    private val context: Context = ContextWrapper(null)

    @After fun shutdown() {
        main.shutdownNow()
    }

    /** Waits until everything queued on the main thread so far (and anything it queued) has run. */
    private fun drain() {
        repeat(3) { main.submit {}.get(60, TimeUnit.SECONDS) }
    }

    /** Runs [block] on the main thread and waits (observe/addObserver are main-thread calls). */
    private fun <T> onMain(block: () -> T): T = main.submit<T> { block() }.get(60, TimeUnit.SECONDS)

    private fun register(features: List<BridgeFeature>, owner: LifecycleOwner = Owner(), failOnDuplicate: Boolean = true) =
        onMain { center.register(features, owner, context, failOnDuplicate) }

    private fun threads(count: Int, body: (Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(count)
        val start = CountDownLatch(1)
        val done = (0 until count).map { t -> pool.submit { start.await(); body(t) } }
        start.countDown()
        done.forEach { it.get(120, TimeUnit.SECONDS) }
        pool.shutdown()
    }

    private inline fun <T> timed(label: String, block: () -> T): T {
        val t0 = System.nanoTime()
        val result = block()
        println("[stress] $label: ${(System.nanoTime() - t0) / 1_000_000} ms")
        return result
    }

    private fun observerCount(): Int =
        (NotificationCenter::class.java.getDeclaredField("observers").apply { isAccessible = true }.get(center) as List<*>).size

    // ---------------------------------------------------------------- volume and ordering

    @Test fun `200k posts from one thread arrive complete and in order`() {
        val n = 200_000
        val got = ArrayList<Int>(n)
        onMain { center.observe("seq", Owner()) { got += it.int("i") } }
        timed("200k ordered posts") {
            for (i in 0 until n) center.post("seq", "tick", mapOf("i" to i))
            drain()
        }
        assertEquals(n, got.size)
        for (i in 0 until n) if (got[i] != i) throw AssertionError("out of order at $i: ${got[i]}")
    }

    @Test fun `32 threads x 20k posts - nothing lost, each thread's order kept`() {
        val threads = 32
        val perThread = 20_000
        val lastSeen = IntArray(threads) { -1 }
        var total = 0
        onMain {
            center.observe("load", Owner()) {
                val t = it.int("t"); val i = it.int("i")
                if (i != lastSeen[t] + 1) throw AssertionError("thread $t: got $i after ${lastSeen[t]}")
                lastSeen[t] = i
                total++
            }
        }
        timed("32x20k concurrent posts") {
            threads(threads) { t -> for (i in 0 until perThread) center.post("load", "tick", mapOf("t" to t, "i" to i)) }
            drain()
        }
        assertEquals(threads * perThread, total)
        lastSeen.forEach { assertEquals(perThread - 1, it) }
    }

    @Test fun `post result is right for every call while 16 threads post`() {
        onMain { center.observe("present", Owner()) {} }
        val wrong = AtomicInteger()
        threads(16) {
            repeat(20_000) { i ->
                if (!center.post("present", "x")) wrong.incrementAndGet()
                if (center.post("absent-$i", "x")) wrong.incrementAndGet()
            }
        }
        drain()
        assertEquals(0, wrong.get())
    }

    // ---------------------------------------------------------------- many features

    @Test fun `300 features x 30k posts - each feature gets exactly its own messages`() {
        val features = (0 until 300).map { Feature("f$it") }
        assertEquals(300, register(features).size)
        val posts = 30_000
        timed("300 features, 30k posts from 8 threads") {
            threads(8) { t -> for (i in t until posts step 8) center.post("f${i % 300}", "call", mapOf("i" to i)) }
            drain()
        }
        features.forEach { assertEquals(posts / 300, it.received.get()) }
    }

    @Test fun `default listensTo null with 200 features - every feature hears every post exactly once`() {
        val features = (0 until 200).map { Feature("w$it", listensTo = null) }
        register(features)
        val posts = 3_000
        timed("200 wildcard features x 3k posts") {
            for (i in 0 until posts) center.post("w${i % 200}", "call")
            drain()
        }
        features.forEach {
            assertEquals("every post reaches every wildcard feature once", posts, it.received.get())
            assertEquals("own category not doubled", posts / 200, it.own.get())
        }
    }

    @Test fun `wildcard listeners never make post return true, even under load`() {
        register((0 until 100).map { Feature("only-wild-$it", listensTo = null) })
        val wronglyTrue = AtomicInteger()
        threads(8) { repeat(10_000) { i -> if (center.post("nobody-owns-$i", "x")) wronglyTrue.incrementAndGet() } }
        drain()
        assertEquals(0, wronglyTrue.get())
    }

    @Test fun `1000 observers on one category - each called once per post`() {
        val calls = IntArray(1000)
        onMain { val o = Owner(); for (k in 0 until 1000) center.observe("fan", o) { calls[k]++ } }
        timed("fan-out 1000 observers x 1k posts") {
            repeat(1_000) { center.post("fan", "x") }
            drain()
        }
        calls.forEach { assertEquals(1_000, it) }
    }

    @Test fun `5000 features with 50 ids - duplicates skipped in release mode`() {
        val features = (0 until 5_000).map { Feature("dup${it % 50}") }
        val attached = register(features, failOnDuplicate = false)
        assertEquals(50, attached.size)
        center.post("dup7", "x"); drain()
        assertEquals(1, features.filter { it.id == "dup7" }.sumOf { it.received.get() })
    }

    // ---------------------------------------------------------------- re-entrancy

    @Test fun `handler that keeps posting - a 100k chain completes without stack overflow`() {
        val n = 100_000
        val count = AtomicInteger()
        val finished = CountDownLatch(1)
        onMain {
            center.observe("chain", Owner()) {
                val i = it.int("i")
                count.incrementAndGet()
                if (i + 1 < n) center.post("chain", "next", mapOf("i" to i + 1)) else finished.countDown()
            }
        }
        timed("100k re-entrant chain") {
            center.post("chain", "next", mapOf("i" to 0))
            assertTrue("chain did not finish", finished.await(60, TimeUnit.SECONDS))
        }
        assertEquals(n, count.get())
    }

    @Test fun `posts made inside a handler run after the remaining handlers of the current post`() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        onMain {
            val o = Owner()
            center.observe("a", o) { order += "a1"; center.post("b", "x") }
            center.observe("a", o) { order += "a2" }
            center.observe("b", o) { order += "b" }
        }
        repeat(5_000) { center.post("a", "x") }
        drain()
        assertEquals(15_000, order.size)
        // a nested post never cuts in between the handlers of the post being delivered
        order.filter { it != "b" }.chunked(2).forEach { assertEquals(listOf("a1", "a2"), it) }
        for (i in order.indices) if (order[i] == "b") assertTrue("b cut in before a2 at $i", i == 0 || order[i - 1] != "a1")
        assertEquals(5_000, order.count { it == "b" })
    }

    // ---------------------------------------------------------------- lifecycle churn

    @Test fun `10k owners created and destroyed leave no observers behind`() {
        timed("10k owner churn") {
            repeat(10_000) {
                onMain {
                    val o = Owner()
                    center.observe("churn", o) {}
                    center.observeAll(o) {}
                    o.destroy()
                }
            }
        }
        assertEquals(0, observerCount())
        assertFalse(center.post("churn", "x"))
    }

    @Test fun `observers added and destroyed on main while 8 threads post - no crash, nothing delivered after destroy`() {
        val afterDestroy = AtomicInteger()
        val delivered = AtomicInteger()
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val posters = Executors.newFixedThreadPool(8)
        repeat(8) { posters.submit { while (!stop.get()) center.post("live", "x") } }
        repeat(2_000) {
            onMain {
                val o = Owner()
                var dead = false
                center.observe("live", o) { if (dead) afterDestroy.incrementAndGet() else delivered.incrementAndGet() }
                o.destroy()
                dead = true
            }
        }
        stop.set(true)
        posters.shutdown(); posters.awaitTermination(30, TimeUnit.SECONDS)
        drain()
        assertEquals("handler ran after its owner was destroyed", 0, afterDestroy.get())
        assertEquals(0, observerCount())
        println("[stress] live churn delivered ${delivered.get()} while owners were alive")
    }

    // ---------------------------------------------------------------- errors

    @Test fun `half the handlers throw on every post - the other half still get everything`() {
        val ok = AtomicInteger()
        onMain {
            val o = Owner()
            repeat(50) { center.observe("boom", o) { throw IllegalStateException("handler failure") } }
            repeat(50) { center.observe("boom", o) { ok.incrementAndGet() } }
        }
        timed("exception storm 50 throwing handlers x 5k posts") {
            repeat(5_000) { center.post("boom", "x") }
            drain()
        }
        assertEquals(50 * 5_000, ok.get())
    }

    @Test fun `throwing onAttach and throwing handle do not stop other features`() {
        val bad = object : BridgeFeature {
            override val id = "bad"
            override fun onAttach(context: Context): Boolean = throw RuntimeException("attach failure")
            override fun handle(n: Notification) {}
        }
        val throwing = Feature("throwing", onHandle = { throw RuntimeException("handle failure") })
        val good = Feature("good")
        assertEquals(listOf("throwing", "good"), register(listOf(bad, throwing, good)))
        repeat(1_000) { center.post("throwing", "x"); center.post("good", "x") }
        drain()
        assertEquals(1_000, good.received.get())
        assertFalse(center.post("bad", "x"))
    }

    // ---------------------------------------------------------------- request / reply

    @Test fun `2000 concurrent requests each get their own reply`() = runBlocking {
        onMain { center.observe("echo", Owner()) { it.reply(mapOf("id" to it.int("id"))) } }
        val replies = timed("2000 concurrent requests") {
            (0 until 2_000).map { id ->
                async(Dispatchers.Default) { id to center.request("echo", "ping", mapOf("id" to id), timeoutMs = 10_000) }
            }.awaitAll()
        }
        replies.forEach { (id, reply) -> assertEquals(id, reply!!["id"]) }
    }

    @Test fun `replies sent later from background threads all arrive`() = runBlocking {
        val workers = Executors.newFixedThreadPool(8)
        onMain { center.observe("slow", Owner()) { n -> workers.execute { Thread.sleep(1); n.reply(mapOf("id" to n.int("id"))) } } }
        val replies = (0 until 500).map { id ->
            async(Dispatchers.Default) { center.request("slow", "work", mapOf("id" to id), timeoutMs = 20_000)?.get("id") }
        }.awaitAll()
        workers.shutdown()
        assertEquals((0 until 500).toList(), replies)
    }

    @Test fun `timeouts for silent listeners do not hold up the answered requests`() = runBlocking {
        onMain { center.observe("half", Owner()) { if (it.int("id") % 2 == 0) it.reply(mapOf("ok" to true)) } }
        val t0 = System.nanoTime()
        val results = (0 until 400).map { id ->
            async(Dispatchers.Default) { id to center.request("half", "q", mapOf("id" to id), timeoutMs = 300) }
        }.awaitAll()
        val ms = (System.nanoTime() - t0) / 1_000_000
        results.forEach { (id, r) -> if (id % 2 == 0) assertEquals(true, r!!["ok"]) else assertEquals(null, r) }
        assertTrue("400 requests with 300 ms timeouts took $ms ms", ms < 10_000)
    }

    @Test fun `requestAll with 100 responders x 300 parallel calls returns 100 replies each`() = runBlocking {
        onMain { val o = Owner(); repeat(100) { k -> center.observe("all", o) { it.reply(mapOf("k" to k)); it.reply(mapOf("k" to -1)) } } }
        val results = timed("requestAll 100 responders x 300 calls") {
            (0 until 300).map { async(Dispatchers.Default) { center.requestAll("all", "status", timeoutMs = 20_000) } }.awaitAll()
        }
        results.forEach { replies ->
            assertEquals(100, replies.size)
            assertEquals("duplicate replies from one listener must be ignored", (0 until 100).toSet(), replies.map { it["k"] }.toSet())
        }
    }

    @Test fun `requestAll ignores wildcard listeners at scale`() = runBlocking {
        onMain {
            val o = Owner()
            repeat(10) { center.observe("mixed", o) { it.reply(mapOf("from" to "named")) } }
            repeat(200) { center.observeAll(o) { it.reply(mapOf("from" to "wildcard")) } }
        }
        val replies = center.requestAll("mixed", "q", timeoutMs = 10_000)
        assertEquals(10, replies.size)
        assertTrue(replies.all { it["from"] == "named" })
    }

    // ---------------------------------------------------------------- publish / latest

    @Test fun `publish and latest from 16 threads - final values are the last published per key`() {
        val keys = 64
        val rounds = 5_000
        threads(16) { t ->
            for (r in 0 until rounds) {
                val key = (t * 4 + r) % keys
                center.publish("state", "k$key", "t$t-r$r")
                val seen = center.latest("state", "k$key")
                if (seen !is String) throw AssertionError("latest returned $seen")
            }
        }
        drain()
        // one writer at the end per key decides the value; it must be some published value
        for (k in 0 until keys) assertTrue(center.latest("state", "k$k").toString().startsWith("t"))
        threads(1) { for (k in 0 until keys) center.publish("state", "k$k", "final-$k") }
        for (k in 0 until keys) assertEquals("final-$k", center.latest("state", "k$k"))
    }

    @Test fun `observer of a published value sees every publish in order`() {
        val seen = ArrayList<Int>()
        onMain { center.observe("onesignal", Owner(), "playerId") { seen += it.payload["value"] as Int } }
        for (i in 0 until 50_000) center.publish("onesignal", "playerId", i)
        drain()
        assertEquals((0 until 50_000).toList(), seen)
        assertEquals(49_999, center.latest("onesignal", "playerId"))
    }

    // ---------------------------------------------------------------- tap and payloads

    @Test fun `tap sees every post exactly once from 16 threads`() {
        val tapped = ConcurrentHashMap<String, AtomicInteger>()
        center.tap = { tapped.getOrPut(it.category) { AtomicInteger() }.incrementAndGet() }
        threads(16) { t -> repeat(5_000) { center.post("tap$t", "x") } }
        drain()
        assertEquals(16, tapped.size)
        tapped.values.forEach { assertEquals(5_000, it.get()) }
    }

    @Test fun `1 MB payloads arrive intact`() {
        val big = "x".repeat(1_000_000)
        val sizes = Collections.synchronizedList(mutableListOf<Int>())
        onMain { center.observe("big", Owner()) { sizes += it.str("data")!!.length } }
        repeat(50) { center.post("big", "blob", mapOf("data" to big)) }
        drain()
        assertEquals(List(50) { 1_000_000 }, sizes)
    }
}
