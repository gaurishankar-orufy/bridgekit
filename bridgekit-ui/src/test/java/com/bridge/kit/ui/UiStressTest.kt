package com.bridge.kit.ui

import androidx.compose.runtime.Composable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.random.Random

/**
 * Model-based stress tests: thousands of random operations, and after every step the library must agree
 * with a plain reference model of the rule "lower priority wins, then module priority, then first registered".
 */
class UiStressTest {

    @After fun reset() {
        SlotRegistry.clear()
        Modals.clear()
    }

    private data class Key(val priority: Int, val module: Int, val seq: Long) : Comparable<Key> {
        override fun compareTo(other: Key) = compareValuesBy(this, other, { it.priority }, { it.module }, { it.seq })
    }

    private fun content(): @Composable () -> Unit = {}   // a new lambda object each call, compared by identity

    // ---------------------------------------------------------------- single slots

    @Test fun `50k random slot claims by 500 owners across 25 slots match the model`() {
        val rnd = Random(42)
        val owners = (0 until 500).map { FakeFeature("o$it", priority = rnd.nextInt(0, 10)) }
        val model = HashMap<Pair<String, String>, Pair<Key, @Composable () -> Unit>>()   // (slot, owner) -> key, content
        var seq = 0L
        repeat(50_000) { step ->
            val owner = owners[rnd.nextInt(owners.size)]
            val slot = "slot${rnd.nextInt(25)}"
            val priority = rnd.nextInt(0, 5)
            val c = content()
            SlotRegistry.set(owner, slot, priority, c)

            val prev = model[slot to owner.id]
            val key = if (prev != null && prev.first.priority == priority) prev.first else Key(priority, owner.priority, seq++)
            model[slot to owner.id] = key to c

            if (step % 97 == 0 || step == 49_999) {
                for (s in 0 until 25) {
                    val expected = model.filterKeys { it.first == "slot$s" }.values.minByOrNull { it.first }?.second
                    if (expected == null) assertNull(SlotRegistry.content("slot$s"))
                    else assertSame("slot$s at step $step", expected, SlotRegistry.content("slot$s"))
                }
            }
        }
    }

    @Test fun `module priority decides every exact-priority tie`() {
        val owners = (0 until 1_000).map { FakeFeature("m$it", priority = 999 - it) }   // last owner has module priority 0
        val contents = owners.associateWith { content() }
        owners.forEach { SlotRegistry.set(it, "banner", priority = 0, content = contents.getValue(it)) }
        assertSame(contents.getValue(owners.last()), SlotRegistry.content("banner"))
    }

    @Test fun `strict mode rejects every exact tie and keeps the first owner`() {
        SlotRegistry.strict = true
        val first = content()
        SlotRegistry.set(FakeFeature("first"), "top", content = first)
        var rejected = 0
        repeat(2_000) {
            try { SlotRegistry.set(FakeFeature("other$it"), "top", content = content()) } catch (e: IllegalStateException) { rejected++ }
        }
        assertEquals(2_000, rejected)
        assertSame(first, SlotRegistry.content("top"))
    }

    // ---------------------------------------------------------------- overlays

    @Test fun `30k random overlay adds and removes match the model order`() {
        val rnd = Random(7)
        val owners = (0 until 50).map { FakeFeature("ov$it", priority = rnd.nextInt(0, 5)) }
        val model = LinkedHashMap<String, Pair<Int, Key>>()   // id -> layer, key
        var seq = 0L
        repeat(30_000) { step ->
            val id = "overlay${rnd.nextInt(400)}"
            if (rnd.nextInt(4) == 0) {
                SlotRegistry.removeOverlay(id); model.remove(id)
            } else {
                val owner = owners[rnd.nextInt(owners.size)]
                val layer = rnd.nextInt(4)
                val priority = rnd.nextInt(0, 6)
                SlotRegistry.addOverlay(owner, id, layer, priority, content())
                model.remove(id)
                model[id] = layer to Key(priority, owner.priority, seq++)
            }
            if (step % 101 == 0 || step == 29_999) {
                for (layer in 0 until 4) {
                    val expected = model.filterValues { it.first == layer }.entries.sortedByDescending { it.value.second }.map { it.key }
                    assertEquals("layer $layer at step $step", expected, SlotRegistry.overlays(layer).map { it.id })
                }
            }
        }
    }

    // ---------------------------------------------------------------- modals

    @Test fun `50k random show and close calls match the queue model`() {
        val rnd = Random(2026)
        val owners = (0 until 40).map { FakeFeature("mod$it", priority = rnd.nextInt(0, 5)) }
        val queue = HashMap<String, Pair<Key, @Composable () -> Unit>>()   // id -> key, latest content
        var showing: String? = null
        var seq = 0L
        repeat(50_000) { step ->
            val id = "m${rnd.nextInt(300)}"
            if (rnd.nextInt(3) == 0) {
                Modals.close(id)
                queue.remove(id)
                if (showing == id) showing = queue.minByOrNull { it.value.first }?.key
            } else {
                val owner = owners[rnd.nextInt(owners.size)]
                val priority = rnd.nextInt(0, 6)
                val c = content()
                Modals.show(owner, id, priority, c)
                val prev = queue[id]
                queue[id] = (prev?.first ?: Key(priority, owner.priority, seq++)) to c   // same id keeps its turn
                if (showing == null) showing = id
            }
            assertEquals("showing at step $step", showing, Modals.showing)
            if (showing != null) assertSame("content at step $step", queue.getValue(showing!!).second, Modals.showingContent()!!.second)
        }
    }

    @Test fun `draining a 10k queue shows modals strictly by priority, module priority, arrival`() {
        val rnd = Random(99)
        val expected = ArrayList<Pair<String, Key>>()
        Modals.show(FakeFeature("first"), "blocker", priority = 50, content = content())   // already showing, never interrupted
        repeat(10_000) { i ->
            val owner = FakeFeature("q$i", priority = rnd.nextInt(0, 4))
            val priority = rnd.nextInt(0, 4)
            Modals.show(owner, "q$i", priority, content())
            expected += "q$i" to Key(priority, owner.priority, i.toLong())
        }
        assertEquals("blocker", Modals.showing)
        val order = ArrayList<String>()
        Modals.close("blocker")
        while (Modals.showing != null) { order += Modals.showing!!; Modals.close(Modals.showing!!) }
        assertEquals(expected.sortedBy { it.second }.map { it.first }, order)
    }

    // ---------------------------------------------------------------- routes

    @Test fun `5000 routes register and freeze`() {
        repeat(5_000) { SlotRegistry.addRoute("route$it") { } }
        SlotRegistry.freezeRoutes()
        assertEquals(5_000, SlotRegistry.routes.size)
        assertEquals("route0", SlotRegistry.routes.keys.first())
        assertEquals("route4999", SlotRegistry.routes.keys.last())
    }
}
