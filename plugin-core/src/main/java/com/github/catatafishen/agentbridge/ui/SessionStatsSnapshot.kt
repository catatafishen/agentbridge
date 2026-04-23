package com.github.catatafishen.agentbridge.ui

/**
 * Immutable snapshot of session-level and turn-level statistics, produced by
 * [ProcessingTimerPanel] on every data change. Consumed by the side panel's Session
 * tab to render labeled stat rows without coupling to Swing internals.
 */
data class SessionStatsSnapshot(
    val isRunning: Boolean,
    val turnElapsedSec: Long,
    val turnToolCalls: Int,
    val turnLinesAdded: Int,
    val turnLinesRemoved: Int,
    val turnInputTokens: Int,
    val turnOutputTokens: Int,
    val turnCostUsd: Double?,
    /**
     * Premium-request weight of the most recent turn (e.g. 3.0 for an Opus turn, 1.0 default).
     * Used by the side panel's "Last turn — Premium req" row to show the actual multiplier
     * instead of a hardcoded "1". Defaults to 1.0 when no multiplier is known.
     */
    val turnPremiumRequests: Double = 1.0,
    val sessionTotalTimeSec: Long,
    val sessionTurnCount: Int,
    val sessionToolCalls: Int,
    val sessionLinesAdded: Int,
    val sessionLinesRemoved: Int,
    val sessionInputTokens: Long,
    val sessionOutputTokens: Long,
    val sessionCostUsd: Double,
    /** True when the agent uses premium-request multipliers (Copilot) rather than raw tokens. */
    val multiplierMode: Boolean,
    /**
     * Weighted premium-request count for this session, accounting for model multipliers
     * (e.g. Opus counts as 3 requests per turn). Sourced from [BillingManager.localSessionPremiumRequests].
     * Note: not persisted across session restores — resets to 0.0 after reopening the IDE.
     */
    val localSessionPremiumRequests: Double,
)
