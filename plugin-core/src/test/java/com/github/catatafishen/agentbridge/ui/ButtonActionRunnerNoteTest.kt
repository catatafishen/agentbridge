package com.github.catatafishen.agentbridge.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ButtonActionRunnerNoteTest {

    @Test
    fun `failure note keeps the reason on one line`() {
        val note = ButtonActionRunner.noteFor(
            ButtonActionRunner.Outcome(false, "User ran action \"Push\" (git_push): failed\nrejected: non-fast-forward")
        )
        assertEquals("User ran action \"Push\" (git_push): failed rejected: non-fast-forward", note)
    }

    @Test
    fun `long detail is truncated`() {
        val note = ButtonActionRunner.noteFor(ButtonActionRunner.Outcome(true, "x".repeat(5000)))
        assertTrue(note.length <= 401)
        assertTrue(note.endsWith("…"))
    }
}
