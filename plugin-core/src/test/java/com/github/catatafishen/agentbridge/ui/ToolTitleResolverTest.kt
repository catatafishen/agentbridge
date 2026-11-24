package com.github.catatafishen.agentbridge.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ToolTitleResolverTest {

    private val ids = listOf("run_nested_session", "git_status", "status", "sleep", "read_file")
    private val displayNames = mapOf("run nested session" to "run_nested_session", "git status" to "git_status")

    private fun resolve(title: String): String? =
        ToolTitleResolver.resolve(title, ids) { displayNames[it.lowercase()] }

    @Test
    fun `an id is its own tool`() {
        assertEquals("run_nested_session", resolve("run_nested_session"))
    }

    @Test
    fun `every prefix convention of the agents leads to the tool`() {
        val titles = listOf(
            "agentbridge-run_nested_session",      // Copilot
            "agentbridge_run_nested_session",      // OpenCode, Vibe, Junie
            "mcp_agentbridge_run_nested_session",  // Hermes
            "@agentbridge/run_nested_session",     // Kiro
            "Running: @agentbridge/run_nested_session",
            "Tool: agentbridge/run_nested_session", // Junie
            "agentbridgenested-run_nested_session", // a nested Copilot session
        )
        for (title in titles) {
            assertEquals("run_nested_session", resolve(title), title)
        }
    }

    @Test
    fun `a server prefix that no client knows still leads to the tool`() {
        assertEquals("run_nested_session", resolve("some-other-server_run_nested_session"))
        assertEquals("read_file", resolve("srv/read_file"))
    }

    @Test
    fun `a display name leads to the tool without regard to case`() {
        assertEquals("run_nested_session", resolve("Run Nested Session"))
        assertEquals("git_status", resolve("GIT STATUS"))
    }

    @Test
    fun `the longest id wins when one id ends with another`() {
        assertEquals("git_status", resolve("mcp_agentbridge_git_status"))
        assertEquals("status", resolve("agentbridge-status"))
    }

    @Test
    fun `surrounding blanks do not matter`() {
        assertEquals("sleep", resolve("  agentbridge-sleep \n"))
    }

    @Test
    fun `an id inside a longer word is not the tool`() {
        assertNull(resolve("rerun_nested_session"))
        assertNull(resolve("xsleep"))
    }

    @Test
    fun `an id in the middle of a title is not the tool`() {
        assertNull(resolve("run_nested_session-extra"))
        assertNull(resolve("agentbridge-sleep-for-a-while"))
    }

    @Test
    fun `an id after a space is not the tool, so a sentence is not taken for one`() {
        assertNull(resolve("Find usages in sleep"))
        assertNull(resolve("Looking at git status"))
    }

    @Test
    fun `a title that names no tool names none`() {
        assertNull(resolve("Confirm new file exists"))
        assertNull(resolve("bash"))
        assertNull(resolve("agentbridge-"))
    }

    @Test
    fun `a blank title names no tool`() {
        assertNull(resolve(""))
        assertNull(resolve("   "))
    }

    @Test
    fun `no tools means no tool`() {
        assertNull(ToolTitleResolver.resolve("agentbridge-sleep", emptyList()) { null })
    }
}
