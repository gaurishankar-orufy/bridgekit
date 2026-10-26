package com.bridge.kit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Draws the BridgeKit composables for real (Robolectric) and checks what is on screen and what recomposes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposeSlotsTest {

    @get:Rule val rule = createComposeRule()

    private val meta = FakeFeature("meta")
    private val admob = FakeFeature("admob")

    @After fun reset() {
        SlotRegistry.clear()
        Modals.clear()
    }

    private fun texts(tag: String): List<String> =
        rule.onNodeWithTag(tag, useUnmergedTree = true).onChildren().fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString { it.text } }

    // ---------------------------------------------------------------- single slots

    @Test fun `empty slot draws the fallback, then the winner, then a higher priority takes over`() {
        rule.setContent { Slot("bottomBanner") { BasicText("fallback") } }
        rule.onNodeWithText("fallback").assertExists()

        rule.runOnIdle { SlotRegistry.set(admob, "bottomBanner", priority = 1) { BasicText("admob") } }
        rule.onNodeWithText("admob").assertExists()
        rule.onNodeWithText("fallback").assertDoesNotExist()

        rule.runOnIdle { SlotRegistry.set(meta, "bottomBanner", priority = 0) { BasicText("meta") } }
        rule.onNodeWithText("meta").assertExists()
        rule.onNodeWithText("admob").assertDoesNotExist()
    }

    @Test fun `updating one slot does not recompose the content of the other slots`() {
        val compositions = IntArray(20)
        rule.runOnIdle {
            repeat(20) { i -> SlotRegistry.set(meta, "s$i") { SideEffect { compositions[i]++ }; BasicText("s$i") } }
        }
        rule.setContent { Column { repeat(20) { Slot("s$it") } } }
        rule.waitForIdle()
        repeat(100) { k ->
            rule.runOnIdle { SlotRegistry.set(meta, "s0") { SideEffect { compositions[0]++ }; BasicText("s0 v$k") } }
            rule.waitForIdle()
        }
        rule.onNodeWithText("s0 v99").assertExists()
        println("[compose] slot s0 content composed ${compositions[0]} times; other slots: ${compositions.drop(1).distinct()}")
        assertEquals(101, compositions[0])
        compositions.drop(1).forEach { assertEquals("an untouched slot's content recomposed", 1, it) }
    }

    @Test fun `cost of updating one slot - 1 slot vs 50 slots on screen`() {
        var slotCount by mutableStateOf(1)
        rule.runOnIdle { repeat(50) { i -> SlotRegistry.set(meta, "s$i") { BasicText("s$i") } } }
        rule.setContent { Column { repeat(slotCount) { Slot("s$it") } } }
        rule.waitForIdle()

        fun msPerUpdate(updates: Int): Double {
            val t0 = System.nanoTime()
            repeat(updates) { k ->
                rule.runOnIdle { SlotRegistry.set(meta, "s0") { BasicText("v$k") } }
                rule.waitForIdle()
            }
            return (System.nanoTime() - t0) / 1e6 / updates
        }
        msPerUpdate(50)   // warm-up
        val one = msPerUpdate(300)
        rule.runOnIdle { slotCount = 50 }
        rule.waitForIdle()
        msPerUpdate(50)
        val fifty = msPerUpdate(300)
        println("[compose] update + recompose one slot: %.3f ms with 1 slot on screen, %.3f ms with 50 slots".format(one, fifty))
        assertTrue("updating one slot got ${fifty / one}x slower with 50 slots", fifty < one * 10)
    }

    // ---------------------------------------------------------------- overlays

    @Test fun `overlays draw least important first and keep their state when others come and go`() {
        val tokens = HashMap<String, Any>()
        val overlay: (String) -> @Composable () -> Unit = { label ->
            @Composable { val token = remember { Any() }; SideEffect { tokens[label] = token }; BasicText(label) }
        }
        rule.setContent { Box(Modifier.testTag("layer")) { Overlays(0) } }
        rule.runOnIdle { SlotRegistry.addOverlay(meta, "mid", layer = 0, priority = 5, content = overlay("mid")) }
        rule.waitForIdle()
        val midToken = tokens.getValue("mid")

        rule.runOnIdle {
            SlotRegistry.addOverlay(meta, "top", layer = 0, priority = 0, content = overlay("top"))
            SlotRegistry.addOverlay(admob, "low", layer = 0, priority = 9, content = overlay("low"))   // drawn before "mid"
        }
        assertEquals(listOf("low", "mid", "top"), texts("layer"))

        rule.runOnIdle { SlotRegistry.removeOverlay("top"); SlotRegistry.removeOverlay("low") }
        assertEquals(listOf("mid"), texts("layer"))
        rule.runOnIdle { assertSame("mid lost its remembered state", midToken, tokens.getValue("mid")) }
    }

    @Test fun `overlays only draw their own layer`() {
        rule.setContent {
            Box(Modifier.testTag("l0")) { Overlays(0) }
            Box(Modifier.testTag("l2")) { Overlays(2) }
        }
        rule.runOnIdle {
            SlotRegistry.addOverlay(meta, "fab", layer = 0) { BasicText("fab") }
            SlotRegistry.addOverlay(meta, "dialog", layer = 2) { BasicText("dialog") }
        }
        assertEquals(listOf("fab"), texts("l0"))
        assertEquals(listOf("dialog"), texts("l2"))
    }

    // ---------------------------------------------------------------- modals

    @Test fun `ModalHost shows one modal at a time, in turn, and updates in place`() {
        val tokens = HashMap<String, Any>()
        val modal: (String) -> @Composable () -> Unit = { label ->
            @Composable { val token = remember { Any() }; SideEffect { tokens["nfc"] = token }; BasicText(label) }
        }
        rule.setContent { ModalHost() }
        rule.runOnIdle {
            Modals.show(meta, "nfc", priority = 5, content = modal("scan: hold near tag"))
            Modals.show(admob, "paywall", priority = 0) { BasicText("paywall") }
        }
        rule.onNodeWithText("scan: hold near tag").assertExists()
        rule.onNodeWithText("paywall").assertDoesNotExist()   // higher priority waits, never interrupts
        val first = rule.runOnIdle { tokens.getValue("nfc") }

        rule.runOnIdle { Modals.show(meta, "nfc", content = modal("scan: new message")) }   // same id = update in place
        rule.onNodeWithText("scan: new message").assertExists()
        rule.runOnIdle { assertSame("in-place update lost remembered state", first, tokens.getValue("nfc")) }

        rule.runOnIdle { Modals.close("nfc") }
        rule.onNodeWithText("paywall").assertExists()
        rule.runOnIdle { Modals.close("paywall") }
        rule.onNodeWithText("paywall").assertDoesNotExist()
    }

    // ---------------------------------------------------------------- routes

    @Test fun `bridgeRoutes adds feature screens and navigateIfExists refuses missing ones`() {
        SlotRegistry.addRoute("passcodeScreen") { BasicText("passcode screen") }
        lateinit var nav: NavHostController
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "mainScreen") {
                composable("mainScreen") { BasicText("main screen") }
                bridgeRoutes()
            }
        }
        rule.onNodeWithText("main screen").assertExists()

        rule.runOnIdle { assertTrue(nav.navigateIfExists("passcodeScreen")) }
        rule.onNodeWithText("passcode screen").assertExists()

        rule.runOnIdle { assertFalse("missing route must not crash", nav.navigateIfExists("strippedModuleScreen")) }
        rule.onNodeWithText("passcode screen").assertExists()
    }
}
