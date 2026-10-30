package com.github.catatafishen.agentbridge.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Session-cost accounting for ACP usage_update agents (e.g. OpenCode): the agent reports a
 * CUMULATIVE running total per session, so the session total must be replaced on every update,
 * never summed. Summing running totals is what made a $13.52 session display as $50+.
 */
class ProcessingTimerPanelUsageTest {

    private fun panel() = ProcessingTimerPanel()

    @Test
    fun `cumulative running totals replace the session total instead of summing`() {
        val p = panel()
        // The reported real-world sequence: per-turn cumulative snapshots of one session.
        val cumulative = listOf(1.09, 9.11, 9.36, 9.85, 10.12, 10.93, 13.52)
        var previous = 0.0
        for (c in cumulative) {
            p.start()
            p.recordCumulativeSessionCost(c)
            p.recordUsage(0, 0, c - previous, fromSessionGauge = true) // orchestrator-derived diff
            p.stop()
            previous = c
        }
        // Old behavior summed the gauges: 50.45 after 6 turns, 63.98 after 7.
        assertEquals(13.52, p.getSessionSnapshot().sessionCostUsd, 1e-9)
    }

    @Test
    fun `per-turn costs still sum when no cumulative update ever arrives`() {
        val p = panel()
        p.start(); p.recordUsage(1000, 200, 0.50); p.stop()
        p.start(); p.recordUsage(3000, 400, 1.25); p.stop()
        assertEquals(1.75, p.getSessionSnapshot().sessionCostUsd, 1e-9)
    }

    @Test
    fun `mid-turn snapshot does not re-add the running turn in cumulative mode`() {
        val p = panel()
        p.start()
        p.recordCumulativeSessionCost(9.11)
        // Mid-turn the panel must show the agent's odometer, not odometer + turn diff.
        assertEquals(9.11, p.getSessionSnapshot().sessionCostUsd, 1e-9)
        p.stop()
    }

    @Test
    fun `recordUsage after cumulative mode keeps the agent-reported total`() {
        val p = panel()
        p.start()
        p.recordCumulativeSessionCost(2.00)
        p.recordUsage(500, 100, 0.75, fromSessionGauge = true)
        p.stop()
        assertEquals(2.00, p.getSessionSnapshot().sessionCostUsd, 1e-9)
    }

    @Test
    fun `per-turn costs still sum after cumulative updates were seen`() {
        val p = panel()
        // An agent that reverts to per-turn reporting after a usage_update must not freeze
        // the session total at the last cumulative value.
        p.start(); p.recordCumulativeSessionCost(2.00); p.stop()
        p.start(); p.recordUsage(500, 100, 0.75, fromSessionGauge = false); p.stop()
        p.start(); p.recordUsage(300, 50, 0.25, fromSessionGauge = false); p.stop()
        assertEquals(3.00, p.getSessionSnapshot().sessionCostUsd, 1e-9)
    }

    @Test
    fun `resetSession returns to per-turn accumulation`() {
        val p = panel()
        p.start(); p.recordCumulativeSessionCost(5.00); p.recordUsage(0, 0, 5.00); p.stop()
        p.resetSession()
        p.start(); p.recordUsage(100, 20, 0.30); p.stop()
        assertEquals(0.30, p.getSessionSnapshot().sessionCostUsd, 1e-9)
    }
}
