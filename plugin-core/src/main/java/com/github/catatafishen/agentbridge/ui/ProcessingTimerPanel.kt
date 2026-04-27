package com.github.catatafishen.agentbridge.ui

import com.intellij.openapi.Disposable
import javax.swing.Timer

/**
 * Stateful accumulator for prompt-turn and session statistics. Fires [onStatsChanged] on every
 * update so observers (e.g. the Session Stats side panel) can refresh without polling.
 *
 * Lifecycle: [start] / [stop] bracket each prompt turn. Session-level aggregates persist across
 * turns until [resetSession] is called. Read the current state via [getSessionSnapshot].
 */
internal class ProcessingTimerPanel(
    private val supportsMultiplier: () -> Boolean,
    private val localPremiumRequests: () -> Double,
) : Disposable {

    /** Callback fired on every stats change (including timer ticks). */
    var onStatsChanged: Runnable? = null

    private var startedAt = 0L
    private var toolCallCount = 0
    private var addedLineCount = 0
    private var removedLineCount = 0
    private val ticker = Timer(1000) {
        onStatsChanged?.run()
    }

    private var sessionTotalTimeMs = 0L
    private var sessionTotalToolCalls = 0
    private var sessionTotalAddedLines = 0
    private var sessionTotalRemovedLines = 0
    private var sessionTurnCount = 0
    private var isRunning = false

    private var turnInputTokens = 0
    private var turnOutputTokens = 0
    private var turnCostUsd: Double? = null

    // Snapshot of the most recently completed turn — kept so the side-panel "Last turn" section
    // can stay populated between turns. The per-turn mutable fields above are reset on the next
    // start(); this field captures the final elapsed time at stop() so the display doesn't lose it.
    private var lastTurnElapsedSec = 0L

    /**
     * Premium-request weight of the most recently completed turn. Sourced from the model's
     * cost multiplier (e.g. "3x" → 3.0). 1.0 is the safe default; resets on each new turn.
     * Distinct from session-wide premium counts which live on [BillingManager].
     */
    private var lastTurnPremium = 1.0
    private var sessionTotalInputTokens = 0L
    private var sessionTotalOutputTokens = 0L
    private var sessionTotalCostUsd = 0.0

    fun start() {
        startedAt = System.currentTimeMillis()
        toolCallCount = 0
        addedLineCount = 0
        removedLineCount = 0
        turnInputTokens = 0
        turnOutputTokens = 0
        turnCostUsd = null
        lastTurnPremium = 1.0
        isRunning = true
        ticker.start()
        onStatsChanged?.run()
    }

    fun stop() {
        // Guard against unmatched stop() calls. The chat input's sending-state listener
        // fires stop() on every setSending(false), including transitions that never had a
        // preceding start() (e.g. after restoreSessionStats or after a stop already ran).
        // Without this guard, startedAt == 0L and we'd add (System.currentTimeMillis() - 0)
        // ≈ 1.7 trillion ms into sessionTotalTimeMs, producing the "Session time = millions
        // of minutes" display bug.
        if (!isRunning) return
        ticker.stop()
        isRunning = false
        lastTurnElapsedSec = (System.currentTimeMillis() - startedAt) / 1000
        sessionTotalTimeMs += System.currentTimeMillis() - startedAt
        sessionTotalToolCalls += toolCallCount
        sessionTotalAddedLines += addedLineCount
        sessionTotalRemovedLines += removedLineCount
        sessionTurnCount++
        onStatsChanged?.run()
    }

    fun recordUsage(inputTokens: Int, outputTokens: Int, costUsd: Double?) {
        turnInputTokens = inputTokens
        turnOutputTokens = outputTokens
        turnCostUsd = costUsd
        sessionTotalInputTokens += inputTokens
        sessionTotalOutputTokens += outputTokens
        if (costUsd != null) sessionTotalCostUsd += costUsd
        onStatsChanged?.run()
    }

    fun resetSession() {
        sessionTotalTimeMs = 0L
        sessionTotalToolCalls = 0
        sessionTotalAddedLines = 0
        sessionTotalRemovedLines = 0
        sessionTurnCount = 0
        sessionTotalInputTokens = 0L
        sessionTotalOutputTokens = 0L
        sessionTotalCostUsd = 0.0
        // Also clear the "Last turn" snapshot so a fresh session shows no stale per-turn data.
        toolCallCount = 0
        addedLineCount = 0
        removedLineCount = 0
        turnInputTokens = 0
        turnOutputTokens = 0
        turnCostUsd = null
        lastTurnElapsedSec = 0L
        lastTurnPremium = 1.0
        onStatsChanged?.run()
    }

    fun restoreSessionStats(
        totalTimeMs: Long, totalInputTokens: Long, totalOutputTokens: Long,
        totalCostUsd: Double, totalToolCalls: Int,
        totalLinesAdded: Int, totalLinesRemoved: Int, turnCount: Int
    ) {
        sessionTotalTimeMs = totalTimeMs
        sessionTotalInputTokens = totalInputTokens
        sessionTotalOutputTokens = totalOutputTokens
        sessionTotalCostUsd = totalCostUsd
        sessionTotalToolCalls = totalToolCalls
        sessionTotalAddedLines = totalLinesAdded
        sessionTotalRemovedLines = totalLinesRemoved
        sessionTurnCount = turnCount
        onStatsChanged?.run()
    }

    /**
     * Restores the most-recent-turn snapshot after a session reload, so the Session tab's
     * "Last turn" section shows the user's last prompt cost/usage without waiting for the
     * next turn. Distinct from [restoreSessionStats] which restores cumulative totals.
     */
    fun restoreLastTurnStats(
        elapsedSec: Long, inputTokens: Int, outputTokens: Int, costUsd: Double?,
        toolCalls: Int, linesAdded: Int, linesRemoved: Int, multiplier: String = ""
    ) {
        lastTurnElapsedSec = elapsedSec
        turnInputTokens = inputTokens
        turnOutputTokens = outputTokens
        turnCostUsd = costUsd
        toolCallCount = toolCalls
        addedLineCount = linesAdded
        removedLineCount = linesRemoved
        lastTurnPremium = BillingCalculator.parseMultiplier(multiplier.ifEmpty { "1x" })
        onStatsChanged?.run()
    }

    /**
     * Records the premium-request multiplier of the just-completed turn so the side panel's
     * "Last turn — Premium req" row can display the actual weight (e.g. "3" for Opus) instead
     * of the previous hardcoded "1". Called from [PromptOrchestrator] right after the model
     * finishes responding, regardless of whether the agent supports multipliers (defaults to 1.0).
     */
    fun setLastTurnMultiplier(multiplier: String?) {
        lastTurnPremium = BillingCalculator.parseMultiplier(multiplier?.ifEmpty { "1x" } ?: "1x")
        onStatsChanged?.run()
    }

    override fun dispose() {
        ticker.stop()
    }

    fun incrementToolCalls() {
        toolCallCount++
        onStatsChanged?.run()
    }

    fun setCodeChangeStats(added: Int, removed: Int) {
        addedLineCount = added
        removedLineCount = removed
        onStatsChanged?.run()
    }

    /**
     * Returns an immutable snapshot of all session statistics for external consumers
     * (e.g., the Session tab's detailed stats view). Includes current-turn data when running.
     */
    fun getSessionSnapshot(): SessionStatsSnapshot {
        val totalMs = sessionTotalTimeMs + if (isRunning) (System.currentTimeMillis() - startedAt) else 0
        val totalTools = sessionTotalToolCalls + if (isRunning) toolCallCount else 0
        val totalAdded = sessionTotalAddedLines + if (isRunning) addedLineCount else 0
        val totalRemoved = sessionTotalRemovedLines + if (isRunning) removedLineCount else 0
        val totalInput = sessionTotalInputTokens + if (isRunning) turnInputTokens else 0
        val totalOutput = sessionTotalOutputTokens + if (isRunning) turnOutputTokens else 0
        val totalCost = sessionTotalCostUsd + if (isRunning) (turnCostUsd ?: 0.0) else 0.0
        val totalTurns = sessionTurnCount + if (isRunning) 1 else 0
        return SessionStatsSnapshot(
            isRunning = isRunning,
            turnElapsedSec = if (isRunning) (System.currentTimeMillis() - startedAt) / 1000 else lastTurnElapsedSec,
            turnToolCalls = toolCallCount,
            turnLinesAdded = addedLineCount,
            turnLinesRemoved = removedLineCount,
            turnInputTokens = turnInputTokens,
            turnOutputTokens = turnOutputTokens,
            turnCostUsd = turnCostUsd,
            turnPremiumRequests = lastTurnPremium,
            sessionTotalTimeSec = totalMs / 1000,
            sessionTurnCount = totalTurns,
            sessionToolCalls = totalTools,
            sessionLinesAdded = totalAdded,
            sessionLinesRemoved = totalRemoved,
            sessionInputTokens = totalInput,
            sessionOutputTokens = totalOutput,
            sessionCostUsd = totalCost,
            multiplierMode = supportsMultiplier(),
            localSessionPremiumRequests = localPremiumRequests(),
        )
    }
}
