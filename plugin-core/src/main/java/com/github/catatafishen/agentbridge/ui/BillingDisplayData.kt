package com.github.catatafishen.agentbridge.ui

/**
 * Immutable snapshot of billing display state, produced by [BillingManager] after
 * each API poll or local-session increment. Consumed by the Session tab to render
 * billing rows without reading raw mutable fields.
 */
data class BillingDisplayData(
    val estimatedUsed: Int,
    val entitlement: Int,
    val unlimited: Boolean,
    val estimatedRemaining: Int,
    val overagePermitted: Boolean,
    val resetDate: String,
    val sessionRequests: Int,
)
