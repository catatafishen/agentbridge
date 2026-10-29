package com.github.catatafishen.agentbridge.ui.sessions

import com.github.catatafishen.agentbridge.session.db.SessionSearch
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

class SessionFormatTest {

    private fun summary(
        branches: List<String> = listOf("feature/x", "master"),
        inferred: Set<String> = setOf("master"),
        commits: List<SessionSearch.CommitRef> = listOf(SessionSearch.CommitRef("a".repeat(40), "Fix thing")),
    ) = SessionSearch.SessionSummary(
        "s", "Copilot", "My session", "first prompt", null, 1000, 2000, 3, 10, 4, branches, inferred, commits
    )

    @Test
    fun `relative time uses minutes hours and days then falls back to a date`() {
        val now = 10_000_000_000L
        assertEquals("just now", SessionFormat.relativeTime(now - 5_000, now))
        assertEquals("5m ago", SessionFormat.relativeTime(now - 5 * 60_000, now))
        assertEquals("3h ago", SessionFormat.relativeTime(now - 3 * 3_600_000, now))
        assertEquals("2d ago", SessionFormat.relativeTime(now - 2 * 86_400_000L, now))
        assertTrue(
            SessionFormat.relativeTime(now - 30 * 86_400_000L, now, ZoneOffset.UTC)
                .matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
        )
        assertEquals("", SessionFormat.relativeTime(0, now))
    }

    @Test
    fun `branch summary marks inferred branches and counts the rest`() {
        assertEquals("feature/x, ~master", SessionFormat.branchSummary(listOf("feature/x", "master"), setOf("master")))
        assertEquals("a, b +2", SessionFormat.branchSummary(listOf("a", "b", "c", "d"), emptySet()))
        assertEquals("", SessionFormat.branchSummary(emptyList(), emptySet()))
    }

    @Test
    fun `detail line includes agent turns lines branches and commits`() {
        val line = SessionFormat.detailLine(summary())
        assertTrue(line.contains("Copilot"))
        assertTrue(line.contains("3 turns"))
        assertTrue(line.contains("+10/\u22124"))
        assertTrue(line.contains("~master"))
        assertTrue(line.contains("1 commit"))
        assertFalse(line.contains("1 commits"))
    }

    @Test
    fun `highlight bolds the match case insensitively and escapes html`() {
        assertEquals("Fix the <b>Login</b> &lt;bug&gt;", SessionFormat.highlight("Fix the Login <bug>", "login"))
        assertEquals("a &amp; b", SessionFormat.highlight("a & b", ""))
        assertEquals("nothing here", SessionFormat.highlight("nothing here", "zzz"))
    }

    @Test
    fun `truncate flattens whitespace and adds an ellipsis`() {
        assertEquals("a b", SessionFormat.truncate("a\n  b", 10))
        assertEquals("abcd\u2026", SessionFormat.truncate("abcdefgh", 5))
    }

    @Test
    fun `preview lists turns with clickable commit links and inferred legend`() {
        val turns = listOf(
            SessionSearch.TurnPreview(
                "t1", "Implement <login>", 1000, "feature/x",
                listOf(SessionSearch.CommitRef("b".repeat(40), "Add login")), 5, 0,
            )
        )
        val html = SessionFormat.previewHtml(summary(), turns, "login")
        assertTrue(html.contains("href='commit:" + "b".repeat(40) + "'"))
        assertTrue(html.contains("bbbbbbb"))
        assertTrue(html.contains("&lt;<b>login</b>&gt;"))
        assertTrue(html.contains("inferred from commits"))
        assertTrue(html.contains("~master"))
    }

    @Test
    fun `preview of a session without turns says so`() {
        assertTrue(
            SessionFormat.previewHtml(summary(emptyList(), emptySet(), emptyList()), emptyList())
                .contains("No prompts recorded")
        )
    }
}
