package com.bridge.kit.core

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NotificationCenterTest {

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        fun destroy() { lifecycle.currentState = Lifecycle.State.DESTROYED }
    }

    private val center = NotificationCenter(dispatch = { it() })   // run handlers immediately
    private val owner = Owner()
    private val context: Context = ContextWrapper(null)

    private class Feature(
        override val id: String,
        override val listensTo: Set<String>? = null,
        private val attach: Boolean = true,
        private val onHandle: (Notification) -> Unit = {},
    ) : BridgeFeature {
        val got = mutableListOf<String>()
        override fun onAttach(context: Context) = attach
        override fun handle(n: Notification) { got += "${n.category}.${n.name}"; onHandle(n) }
    }

    // ---- post / observe

    @Test fun `post with nobody listening returns false`() {
        assertFalse(center.post("x", "y"))
    }

    @Test fun `post reaches observer with payload and returns true`() {
        var seen: Notification? = null
        center.observe("haptics", owner) { seen = it }
        assertTrue(center.post("haptics", "vibrate", mapOf("ms" to 20)))
        assertEquals(20, seen!!.int("ms"))
    }

    @Test fun `name filter delivers only that name`() {
        val got = mutableListOf<String>()
        center.observe("app", owner, name = "openUrl") { got += it.name }
        assertFalse(center.post("app", "navigate"))
        assertTrue(center.post("app", "openUrl"))
        assertEquals(listOf("openUrl"), got)
    }

    @Test fun `wildcard observer receives everything but does not count`() {
        val got = mutableListOf<String>()
        center.observeAll(owner) { got += "${it.category}.${it.name}" }
        assertFalse(center.post("onesignal", "setEmail"))
        assertEquals(listOf("onesignal.setEmail"), got)
    }

    @Test fun `destroyed owner removes its observers`() {
        var calls = 0
        center.observe("a", owner) { calls++ }
        owner.destroy()
        assertFalse(center.post("a", "b"))
        assertEquals(0, calls)
    }

    @Test fun `observing with an already destroyed owner is ignored`() {
        owner.destroy()
        center.observe("a", owner) { fail("must not be called") }
        assertFalse(center.post("a", "b"))
    }

    @Test fun `a failing handler does not stop the others`() {
        var second = false
        center.observe("a", owner) { throw IllegalStateException("boom") }
        center.observe("a", owner) { second = true }
        center.post("a", "b")
        assertTrue(second)
    }

    @Test fun `tap sees every post, even unobserved`() {
        val tapped = mutableListOf<String>()
        center.tap = { tapped += it.name }
        center.post("nobody", "here")
        assertEquals(listOf("here"), tapped)
    }

    @Test fun `reply reaches onReply`() {
        center.observe("a", owner) { it.reply(mapOf("ok" to true)) }
        var reply: Map<String, Any?>? = null
        center.post("a", "b") { reply = it }
        assertEquals(true, reply!!["ok"])
    }

    // ---- request / requestAll

    @Test fun `request returns first reply`() = runTest {
        center.observe("loc", owner) { it.reply(mapOf("lat" to 1.5)) }
        assertEquals(1.5, center.request("loc", "current")!!["lat"])
    }

    @Test fun `request returns null when nobody listens`() = runTest {
        assertNull(center.request("loc", "current"))
    }

    @Test fun `request returns null when nobody replies in time`() = runTest {
        center.observe("loc", owner) { }
        assertNull(center.request("loc", "current", timeoutMs = 100))
    }

    @Test fun `requestAll collects one reply per listener and ignores wildcards`() = runTest {
        center.observe("status", owner) { it.reply(mapOf("from" to "a")); it.reply(mapOf("from" to "a-again")) }
        center.observe("status", owner) { it.reply(mapOf("from" to "b")) }
        center.observeAll(owner) { it.reply(mapOf("from" to "wildcard")) }
        val replies = center.requestAll("status", "visible")
        assertEquals(listOf("a", "b"), replies.map { it["from"] })
    }

    @Test fun `requestAll returns what arrived before the timeout`() = runTest {
        center.observe("status", owner) { it.reply(mapOf("from" to "a")) }
        center.observe("status", owner) { /* never replies */ }
        assertEquals(1, center.requestAll("status", "visible", timeoutMs = 100).size)
    }

    @Test fun `requestAll with nobody listening is empty`() = runTest {
        assertTrue(center.requestAll("status", "visible").isEmpty())
    }

    // ---- publish / latest

    @Test fun `publish stores latest and posts value`() {
        var posted: Any? = null
        center.observe("onesignal", owner, "playerId") { posted = it.payload["value"] }
        assertNull(center.latest("onesignal", "playerId"))
        center.publish("onesignal", "playerId", "abc")
        assertEquals("abc", center.latest("onesignal", "playerId"))
        assertEquals("abc", posted)
    }

    // ---- register (what registerAll does after ServiceLoader)

    @Test fun `listensTo null hears every category, own category once`() {
        val f = Feature("haptics")
        center.register(listOf(f), owner, context, failOnDuplicate = true)
        assertTrue(center.post("haptics", "vibrate"))
        assertFalse(center.post("app", "pageFinished"))   // heard, but wildcard does not count
        assertEquals(listOf("haptics.vibrate", "app.pageFinished"), f.got)
    }

    @Test fun `listensTo set hears id plus those categories only`() {
        val f = Feature("onesignal", listensTo = setOf("app", "session"))
        center.register(listOf(f), owner, context, failOnDuplicate = true)
        assertTrue(center.post("onesignal", "setEmail"))
        assertTrue(center.post("session", "login"))
        center.post("other", "thing")
        assertEquals(listOf("onesignal.setEmail", "session.login"), f.got)
    }

    @Test fun `empty listensTo hears only id`() {
        val f = Feature("haptics", listensTo = emptySet())
        center.register(listOf(f), owner, context, failOnDuplicate = true)
        center.post("app", "pageFinished")
        center.post("haptics", "vibrate")
        assertEquals(listOf("haptics.vibrate"), f.got)
    }

    @Test fun `listing own id in listensTo still delivers once`() {
        val f = Feature("a", listensTo = setOf("a"))
        center.register(listOf(f), owner, context, failOnDuplicate = true)
        center.post("a", "b")
        assertEquals(listOf("a.b"), f.got)
    }

    @Test fun `onAttach false means the feature does not exist`() {
        val f = Feature("ads", attach = false)
        val attached = center.register(listOf(f), owner, context, failOnDuplicate = true)
        assertTrue(attached.isEmpty())
        assertFalse(center.post("ads", "show"))
        assertTrue(f.got.isEmpty())
    }

    @Test fun `duplicate id fails in debug`() {
        try {
            center.register(listOf(Feature("a"), Feature("a")), owner, context, failOnDuplicate = true)
            fail("expected duplicate id error")
        } catch (e: IllegalStateException) { /* expected */ }
    }

    @Test fun `duplicate id is skipped in release`() {
        val first = Feature("a", listensTo = emptySet())
        val second = Feature("a", listensTo = emptySet())
        val attached = center.register(listOf(first, second), owner, context, failOnDuplicate = false)
        center.post("a", "b")
        assertEquals(listOf("a"), attached)
        assertEquals(1, first.got.size)
        assertTrue(second.got.isEmpty())
    }

    @Test fun `feature reply goes back to poster`() {
        val f = Feature("haptics", onHandle = { it.reply(mapOf("isSupported" to true)) })
        center.register(listOf(f), owner, context, failOnDuplicate = true)
        var reply: Map<String, Any?>? = null
        center.post("haptics", "isHapticSupported") { reply = it }
        assertEquals(true, reply!!["isSupported"])
    }

    // ---- Notification helpers

    @Test fun `typed payload helpers`() {
        val n = Notification("c", "n", mapOf("s" to "x", "b" to "true", "i" to "7", "d" to 2.9))
        assertEquals("x", n.str("s"))
        assertNull(n.str("missing"))
        assertTrue(n.bool("b"))
        assertFalse(n.bool("missing"))
        assertEquals(7, n.int("i"))
        assertEquals(2, n.int("d"))
        assertEquals(0, n.int("missing"))
    }
}
