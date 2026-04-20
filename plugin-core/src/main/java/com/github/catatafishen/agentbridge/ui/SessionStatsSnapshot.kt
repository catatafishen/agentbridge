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
