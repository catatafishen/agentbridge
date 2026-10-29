package com.github.catatafishen.agentbridge.ui.sessions

import com.github.catatafishen.agentbridge.session.db.SessionSearch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How the session picker groups its results. */
enum class GroupBy(val label: String) {
    RECENT("Recent"),
    BRANCH("Branch"),
    AGENT("Agent"),
}

data class SessionGroup(val title: String, val sessions: List<SessionSearch.SessionSummary>)

/**
 * Pure grouping logic for the session picker, kept free of Swing so it can be unit tested.
 *
 * A session can legitimately appear in several branch groups: it spans many turns, and therefore
 * possibly many branches.
 */
object SessionGrouping {

    const val NO_GIT_INFO = "No git info"

    fun group(
        sessions: List<SessionSearch.SessionSummary>,
        by: GroupBy,
        currentBranch: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SessionGroup> = when (by) {
        GroupBy.RECENT -> byRecency(sessions, nowMs, zone)
        GroupBy.BRANCH -> byBranch(sessions, currentBranch)
        GroupBy.AGENT -> byAgent(sessions)
    }

    private fun byRecency(
        sessions: List<SessionSearch.SessionSummary>,
        nowMs: Long,
        zone: ZoneId,
    ): List<SessionGroup> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val byTitle = sessions.groupBy { s ->
            val day: LocalDate = Instant.ofEpochMilli(s.lastActivityMs()).atZone(zone).toLocalDate()
            when {
                !day.isBefore(today) -> "Today"
                day == today.minusDays(1) -> "Yesterday"
                day.isAfter(today.minusDays(7)) -> "Last 7 days"
                else -> "Older"
            }
        }
        return listOf("Today", "Yesterday", "Last 7 days", "Older")
            .mapNotNull { title -> byTitle[title]?.let { SessionGroup(title, it) } }
    }

    private fun byBranch(
        sessions: List<SessionSearch.SessionSummary>,
        currentBranch: String?,
    ): List<SessionGroup> {
        val groups = linkedMapOf<String, MutableList<SessionSearch.SessionSummary>>()
        val noGit = mutableListOf<SessionSearch.SessionSummary>()
        for (s in sessions) {
            if (s.branches().isEmpty()) noGit.add(s) else s.branches()
                .forEach { groups.getOrPut(it) { mutableListOf() }.add(s) }
        }
        val ordered = groups.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<SessionSearch.SessionSummary>>> { it.key == currentBranch }
                    .thenByDescending { e -> e.value.maxOf { it.lastActivityMs() } }
            )
            .map { SessionGroup(it.key, it.value) }
        return if (noGit.isEmpty()) ordered else ordered + SessionGroup(NO_GIT_INFO, noGit)
    }

    private fun byAgent(sessions: List<SessionSearch.SessionSummary>): List<SessionGroup> =
        sessions.groupBy { it.agentName() }
            .entries
            .sortedByDescending { e -> e.value.maxOf { it.lastActivityMs() } }
            .map { SessionGroup(it.key, it.value) }
}
