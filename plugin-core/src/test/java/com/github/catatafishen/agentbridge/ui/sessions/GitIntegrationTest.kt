package com.github.catatafishen.agentbridge.ui.sessions

import com.github.catatafishen.agentbridge.session.db.SessionSearch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitIntegrationTest {

    private fun turn(prompt: String, session: String = "", agent: String = "Copilot") =
        SessionSearch.CommitTurn("s1", session, agent, "t1", prompt, 0)

    @Test
    fun `remote prefixes are stripped from branch names`() {
        assertEquals("feature/x", ShowSessionsForBranchAction.stripRemote("origin/feature/x"))
        assertEquals("master", ShowSessionsForBranchAction.stripRemote("upstream/master"))
    }

    @Test
    fun `local branches with a slash are left alone`() {
        assertEquals("feature/x", ShowSessionsForBranchAction.stripRemote("feature/x"))
        assertEquals("master", ShowSessionsForBranchAction.stripRemote("master"))
    }

    @Test
    fun `commit prompt label shows the prompt and the session name`() {
        val label = CommitPromptsPresenter.label(turn("Fix the flaky login test", session = "Login work"))
        assertTrue(label.startsWith("Fix the flaky login test"), label)
        assertTrue(label.contains("Login work"), label)
    }

    @Test
    fun `commit prompt label falls back to the agent and handles empty prompts`() {
        val label = CommitPromptsPresenter.label(turn("", session = "", agent = "Claude"))
        assertTrue(label.contains("(empty prompt)"), label)
        assertTrue(label.contains("Claude"), label)
    }

    @Test
    fun `long prompts are shortened in the label`() {
        val label = CommitPromptsPresenter.label(turn("x".repeat(500)))
        assertTrue(label.length < 200, "label length ${label.length}")
        assertTrue(label.contains("\u2026"))
    }
}
