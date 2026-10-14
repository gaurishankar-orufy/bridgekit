package com.bridge.kit.ui

import androidx.compose.runtime.Composable
import com.bridge.kit.core.BridgeFeature
import com.bridge.kit.core.Notification
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

internal class FakeFeature(override val id: String, override val priority: Int = BridgeFeature.DEFAULT_PRIORITY) : BridgeFeature {
    override fun handle(n: Notification) {}
}

class SlotRegistryTest {

    private val a: @Composable () -> Unit = {}
    private val b: @Composable () -> Unit = {}
    private val admob = FakeFeature("admob")
    private val meta = FakeFeature("meta")

    @After fun reset() = SlotRegistry.clear()

    @Test fun `empty slot has no content`() {
        assertNull(SlotRegistry.content("bottomBar"))
    }

    @Test fun `lower priority number wins`() {
        SlotRegistry.set(meta, "bottomBanner", priority = 5, content = b)
        SlotRegistry.set(admob, "bottomBanner", priority = 0, content = a)
        assertSame(a, SlotRegistry.content("bottomBanner"))
    }

    @Test fun `equal priority falls back to module priority`() {
        SlotRegistry.set(FakeFeature("low", priority = 50), "topBar", priority = 0, content = b)
        SlotRegistry.set(FakeFeature("high", priority = 1), "topBar", priority = 0, content = a)
        assertSame(a, SlotRegistry.content("topBar"))
    }

    @Test fun `same owner replaces its own content`() {
        SlotRegistry.set(admob, "topBar", content = a)
        SlotRegistry.set(admob, "topBar", content = b)
        assertSame(b, SlotRegistry.content("topBar"))
    }

    @Test fun `exact tie between owners fails when strict`() {
        SlotRegistry.strict = true
        SlotRegistry.set(admob, "topBar", content = a)
        try {
            SlotRegistry.set(meta, "topBar", content = b)
            fail("expected conflict")
        } catch (e: IllegalStateException) { /* expected */ }
    }

    @Test fun `exact tie only warns when not strict, first registered wins`() {
        SlotRegistry.set(admob, "topBar", content = a)
        SlotRegistry.set(meta, "topBar", content = b)
        assertSame(a, SlotRegistry.content("topBar"))
    }

    @Test fun `overlays draw least important first so the winner is on top`() {
        SlotRegistry.addOverlay(admob, "modal", layer = 2, content = a)
        SlotRegistry.addOverlay(admob, "fam", layer = 0, priority = 0, content = a)
        SlotRegistry.addOverlay(admob, "offer", layer = 0, priority = 5, content = a)
        SlotRegistry.addOverlay(FakeFeature("x", priority = 1), "card", layer = 0, priority = 5, content = a)
        assertEquals(listOf("offer", "card", "fam"), SlotRegistry.overlays(0).map { it.id })
        assertEquals(listOf("modal"), SlotRegistry.overlays(2).map { it.id })
    }

    @Test fun `overlay with same id is replaced, and can be removed`() {
        SlotRegistry.addOverlay(admob, "banner", layer = 0, content = a)
        SlotRegistry.addOverlay(admob, "banner", layer = 1, content = b)
        assertEquals(0, SlotRegistry.overlays(0).size)
        assertEquals(1, SlotRegistry.overlays(1).size)
        SlotRegistry.removeOverlay("banner")
        assertEquals(0, SlotRegistry.overlays(1).size)
    }

    @Test fun `routes are frozen once the NavHost is built`() {
        SlotRegistry.addRoute("passcodeScreen") { }
        SlotRegistry.freezeRoutes()
        assertEquals(setOf("passcodeScreen"), SlotRegistry.routes.keys)
        try {
            SlotRegistry.addRoute("late") { }
            fail("expected frozen routes")
        } catch (e: IllegalStateException) { /* expected */ }
    }

    @Test fun `duplicate route fails when strict`() {
        SlotRegistry.strict = true
        SlotRegistry.addRoute("pdf") { }
        try {
            SlotRegistry.addRoute("pdf") { }
            fail("expected duplicate route")
        } catch (e: IllegalStateException) { /* expected */ }
    }
}
