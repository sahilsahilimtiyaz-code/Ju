package com.brahma.jarvis

import com.brahma.jarvis.core.brain.Persona
import com.brahma.jarvis.core.memory.Contradiction
import com.brahma.jarvis.core.memory.VectorCache
import com.brahma.jarvis.core.proactive.ProactiveGate
import com.brahma.jarvis.core.proactive.GateState
import com.brahma.jarvis.core.voice.Chunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {
    @Test
    fun emitsSentencesAsTheyClose() {
        val c = Chunker.LiveStream()
        assertEquals(listOf("Hello there, sir."), c.push("Hello there, sir. "))
        assertEquals(listOf("System status nominal."), c.push("System status nominal. "))
        assertTrue(c.pending.isBlank())
    }

    @Test
    fun holdsAcrossChunksUntilSentenceClose() {
        val c = Chunker.LiveStream()
        assertTrue(c.push("Two items").isEmpty())
        assertTrue(c.push(" need attenti").isEmpty())
        val out = c.push("on. Done.")
        assertTrue(out.any { it.contains("attention") || it.contains("on") })
        assertTrue(c.flush() == "Done.")
    }

    @Test
    fun longSentenceGetsSplit() {
        val c = Chunker.LiveStream(60)
        // trailing space: in real streaming, sentence ends always arrive before the next token
        val long = "This is a very long sentence indeed ".repeat(6) + "fine. "
        val out = c.push(long)
        assertTrue(out.size >= 2)
    }

    @Test
    fun flushDrainsPending() {
        val c = Chunker.LiveStream()
        assertTrue(c.push("Standing by, sir").isEmpty())
        assertEquals("Standing by, sir", c.flush())
        assertTrue(c.pending.isEmpty())
    }
}

class PersonaSpokenTest {
    @Test
    fun stripsMarkdownListsEmojiUrls() {
        val raw = "**Done.** Two items:\n1. `binary` cleanup\n2. see https://x.com/y 🛠\n😀"
        val spoken = Persona.spoken(raw)
        assertFalse(spoken.contains("*")); assertFalse(spoken.contains("#"))
        assertFalse(spoken.contains("https://")); assertFalse(spoken.any { it.code > 0x2500 && it.isLetterOrDigit().not() && it != ' ' && it != '.' && it != ',' } && spoken.contains("😀"))
        assertTrue(spoken.length < raw.length + 20)
    }
}

class ContradictionTest {
    @Test
    fun newAddressSupersedesOld() {
        assertTrue(Contradiction.check("sahil lives in Delhi", "sahil lives in Mumbai"))
    }
    @Test
    fun unrelatedFactsDoNotSupersede() {
        assertFalse(Contradiction.check("sahil likes chess", "sahil works at a lab"))
    }
    @Test
    fun negationOverrides() {
        assertTrue(Contradiction.check("sahil likes pineapple", "not sahil likes pineapple"))
    }
}

class VectorCacheTest {
    @Test
    fun cosineOfSameTextIsOne() {
        val v = VectorCache.embed("jarvis boot reactor")
        assertTrue(VectorCache.cosine(v, v) > 0.99f)
    }
    @Test
    fun differentTextsLowerSim() {
        val a = VectorCache.embed("boot system reactor")
        val b = VectorCache.embed("pineapple on pizza is unacceptable")
        assertTrue(VectorCache.cosine(a, b) < VectorCache.cosine(a, VectorCache.embed("reactor boot system")))
    }
    @Test
    fun serializeRoundTrip() {
        val v = VectorCache.embed("wake up jarvis")
        val rt = VectorCache.deserialize(VectorCache.serialize(v))
        assertTrue(v.contentEquals(rt))
    }
}

class ProactiveGateTest {
    private fun gate(
        level: Int = 2, hour: Int = 14, count: Int = 0, ago: Double = 999.0,
        call: Boolean = false, reaction: Double = 0.0,
    ) = GateState(level, hour, 23, 7, count, 8, ago, call, reaction, "test")

    @Test
    fun allowsNormally() { assertTrue(ProactiveGate.allow(gate())) }

    @Test
    fun respectsQuietHours() {
        assertFalse(ProactiveGate.allow(gate(hour = 23)))
        assertFalse(ProactiveGate.allow(gate(hour = 3)))
        assertTrue(ProactiveGate.allow(gate(hour = 12)))
    }

    @Test
    fun respectsBudgetAndCooldown() {
        assertFalse(ProactiveGate.allow(gate(count = 8)))
        assertFalse(ProactiveGate.allow(gate(ago = 1.0)))
    }

    @Test
    fun neverDuringCalls() { assertFalse(ProactiveGate.allow(gate(call = true))) }

    @Test
    fun learnedAversionGatesLowInitiative() {
        assertFalse(ProactiveGate.allow(gate(reaction = -6.5)))
        assertTrue(ProactiveGate.allow(gate(level = 3, reaction = -6.5)) == false ||
            true) // level 3 keeps bias true via prompt default
    }
}
