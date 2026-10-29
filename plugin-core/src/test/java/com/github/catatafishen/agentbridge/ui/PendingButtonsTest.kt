package com.github.catatafishen.agentbridge.ui

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PendingButtonsTest {

    private val strip = ButtonStrip(null, listOf(ReplyButton("A", "A")), emptyList())

    @Test
    fun `take returns the registered strip once`() {
        val pending = PendingButtons()
        pending.set(strip)
        assertEquals(strip, pending.take())
        assertNull(pending.take())
    }

    @Test
    fun `latest registration replaces the earlier one`() {
        val pending = PendingButtons()
        val second = ButtonStrip("q", emptyList(), listOf(ActionButton("Push", "git_push", JsonObject())))
        pending.set(strip)
        pending.set(second)
        assertEquals(second, pending.take())
    }

    @Test
    fun `clear drops the strip`() {
        val pending = PendingButtons()
        pending.set(strip)
        pending.clear()
        assertNull(pending.take())
    }

    @Test
    fun `notes drain in order and only once`() {
        val pending = PendingButtons()
        pending.addNote("one")
        pending.addNote("two")
        assertEquals(listOf("one", "two"), pending.drainNotes())
        assertTrue(pending.drainNotes().isEmpty())
    }
}
