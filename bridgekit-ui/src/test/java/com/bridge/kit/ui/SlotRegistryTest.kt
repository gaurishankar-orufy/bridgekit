package com.bridge.kit.ui

import androidx.compose.runtime.Composable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class SlotRegistryTest {

    private val a: @Composable () -> Unit = {}
    private val b: @Composable () -> Unit = {}

    @After fun reset() = SlotRegistry.clear()

    @Test fun `empty slot has no content`() {
        assertNull(SlotRegistry.content("bottomBar"))
    }

    @Test fun `highest priority wins`() {
        SlotRegistry.set("bottomBanner", "meta", priority = 5, content = b)
        SlotRegistry.set("bottomBanner", "admob", priority = 10, content = a)
        assertSame(a, SlotRegistry.content("bottomBanner"))
    }

    @Test fun `same id replaces its content`() {
        SlotRegistry.set("topBar", "rich", content = a)
        SlotRegistry.set("topBar", "rich", content = b)
        assertSame(b, SlotRegistry.content("topBar"))
    }

    @Test fun `equal priority from different ids fails when strict`() {
        SlotRegistry.strict = true
        SlotRegistry.set("topBar", "one", content = a)
        try {
            SlotRegistry.set("topBar", "two", content = b)
            fail("expected conflict")
        } catch (e: IllegalStateException) { /* expected */ }
    }

    @Test fun `equal priority only warns when not strict`() {
        SlotRegistry.set("topBar", "one", content = a)
        SlotRegistry.set("topBar", "two", content = b)
        assertSame(a, SlotRegistry.content("topBar"))   // first registered keeps the tie
    }

    @Test fun `overlays filter by layer and sort by priority`() {
        SlotRegistry.addOverlay("modal", layer = 2, content = a)
        SlotRegistry.addOverlay("fam", layer = 0, priority = 5, content = a)
        SlotRegistry.addOverlay("offer", layer = 0, priority = 1, content = a)
        assertEquals(listOf("offer", "fam"), SlotRegistry.overlays(0).map { it.id })
        assertEquals(listOf("modal"), SlotRegistry.overlays(2).map { it.id })
    }

    @Test fun `overlay with same id is replaced, and can be removed`() {
        SlotRegistry.addOverlay("banner", layer = 0, content = a)
        SlotRegistry.addOverlay("banner", layer = 1, content = b)
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
