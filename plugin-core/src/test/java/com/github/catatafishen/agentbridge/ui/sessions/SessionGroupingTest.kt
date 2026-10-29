package com.github.catatafishen.agentbridge.ui.sessions

import com.github.catatafishen.agentbridge.session.db.SessionSearch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class SessionGroupingTest {

    private val zone = ZoneOffset.UTC
    private val now = ZonedDateTime.of(2026, 3, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun daysAgo(d: Long) = now - d * 86_400_000L

    private fun session(
        id: String,
        agent: String = "Copilot",
        lastMs: Long = now,
        branches: List<String> = emptyList(),
        inferred: Set<String> = emptySet(),
    ) = SessionSearch.SessionSummary(
        id, agent, "", "first", null, lastMs, lastMs, 1, 3, 1, branches, inferred, emptyList()
    )

    @Test
    fun `recent grouping buckets by day and drops empty buckets`() {
        val groups = SessionGrouping.group(
            listOf(
                session("a", lastMs = now - 1000),
                session("b", lastMs = daysAgo(1)),
                session("c", lastMs = daysAgo(3)),
                session("d", lastMs = daysAgo(30)),
            ),
            GroupBy.RECENT, nowMs = now, zone = zone,
        )
        assertEquals(listOf("Today", "Yesterday", "Last 7 days", "Older"), groups.map { it.title })
        assertEquals(listOf("a"), groups[0].sessions.map { it.id() })
    }

    @Test
    fun `recent grouping omits buckets without sessions`() {
        val groups =
            SessionGrouping.group(listOf(session("a", lastMs = daysAgo(30))), GroupBy.RECENT, nowMs = now, zone = zone)
        assertEquals(listOf("Older"), groups.map { it.title })
    }

    @Test
    fun `a session spanning several branches appears in each branch group`() {
        val groups = SessionGrouping.group(
            listOf(session("multi", branches = listOf("feature/a", "master"))),
            GroupBy.BRANCH,
        )
        assertEquals(setOf("feature/a", "master"), groups.map { it.title }.toSet())
        assertTrue(groups.all { g -> g.sessions.any { it.id() == "multi" } })
    }

    @Test
    fun `current branch group is pinned first`() {
        val groups = SessionGrouping.group(
            listOf(
                session("new", lastMs = now, branches = listOf("master")),
                session("old", lastMs = daysAgo(5), branches = listOf("feature/x")),
            ),
            GroupBy.BRANCH, currentBranch = "feature/x",
        )
        assertEquals("feature/x", groups.first().title)
    }

    @Test
    fun `branch groups are ordered by most recent activity when no current branch`() {
        val groups = SessionGrouping.group(
            listOf(
                session("old", lastMs = daysAgo(5), branches = listOf("feature/x")),
                session("new", lastMs = now, branches = listOf("master")),
            ),
            GroupBy.BRANCH,
        )
        assertEquals(listOf("master", "feature/x"), groups.map { it.title })
    }

    @Test
    fun `sessions without git info go to a trailing no git info group`() {
        val groups = SessionGrouping.group(
            listOf(session("nogit"), session("git", branches = listOf("master"))),
            GroupBy.BRANCH,
        )
        assertEquals(SessionGrouping.NO_GIT_INFO, groups.last().title)
        assertEquals(listOf("nogit"), groups.last().sessions.map { it.id() })
    }

    @Test
    fun `agent grouping groups by agent name`() {
        val groups = SessionGrouping.group(
            listOf(session("a", agent = "Claude"), session("b", agent = "Copilot"), session("c", agent = "Claude")),
            GroupBy.AGENT,
        )
        assertEquals(setOf("Claude", "Copilot"), groups.map { it.title }.toSet())
        assertEquals(2, groups.first { it.title == "Claude" }.sessions.size)
    }
}
