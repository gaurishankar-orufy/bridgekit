package com.bridge.kit.ui

import androidx.compose.runtime.Composable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ModalsTest {

    private val nfc = FakeFeature("nfc")
    private val paywall = FakeFeature("paywall")
    private val a: @Composable () -> Unit = {}
    private val b: @Composable () -> Unit = {}

    @After fun reset() = Modals.clear()

    @Test fun `first modal shows at once`() {
        Modals.show(nfc, "nfcSheet", content = a)
        assertEquals("nfcSheet", Modals.showing)
    }

    @Test fun `a higher priority modal waits for the showing one`() {
        Modals.show(nfc, "nfcSheet", priority = 50, content = a)
        Modals.show(paywall, "paywall", priority = 0, content = b)
        assertEquals("nfcSheet", Modals.showing)   // never interrupted
        Modals.close("nfcSheet")
        assertEquals("paywall", Modals.showing)
    }

    @Test fun `next is chosen by priority, then module priority, then arrival`() {
        Modals.show(nfc, "current", content = a)
        Modals.show(FakeFeature("m1", priority = 9), "late-low-module", priority = 0, content = a)
        Modals.show(FakeFeature("m2", priority = 2), "high-module", priority = 0, content = a)
        Modals.show(FakeFeature("m3", priority = 2), "high-module-later", priority = 0, content = a)
        Modals.show(paywall, "low-priority", priority = 5, content = a)

        val order = mutableListOf<String>()
        while (Modals.showing != null) { order += Modals.showing!!; Modals.close(Modals.showing!!) }
        assertEquals(listOf("current", "high-module", "high-module-later", "late-low-module", "low-priority"), order)
    }

    @Test fun `show with the same id updates in place and keeps its turn`() {
        Modals.show(nfc, "nfcSheet", content = a)
        Modals.show(paywall, "paywall", content = a)
        Modals.show(nfc, "nfcSheet", content = b)
        assertEquals("nfcSheet", Modals.showing)
        assertSame(b, Modals.showingContent()!!.second)
    }

    @Test fun `closing a waiting modal removes it without touching the showing one`() {
        Modals.show(nfc, "nfcSheet", content = a)
        Modals.show(paywall, "paywall", content = a)
        Modals.close("paywall")
        assertEquals("nfcSheet", Modals.showing)
        Modals.close("nfcSheet")
        assertNull(Modals.showing)
    }

    @Test fun `closing an unknown id does nothing`() {
        Modals.show(nfc, "nfcSheet", content = a)
        Modals.close("nope")
        assertEquals("nfcSheet", Modals.showing)
    }
}
