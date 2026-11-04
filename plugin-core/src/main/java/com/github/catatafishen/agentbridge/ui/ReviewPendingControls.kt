package com.github.catatafishen.agentbridge.ui

/**
 * What the chat UI can do about a tool call that is paused waiting for the user to review the
 * agent's edits.
 *
 * Implemented by the review gate; handed to [ChatPanelApi.showReviewPending] so the panel can offer
 * "Approve" and "Open review" without knowing anything about how the gate waits.
 */
interface ReviewPendingControls {
    /** Approves the edits the paused tool call is waiting on, which lets it continue. */
    fun approve()

    /** Brings the review panel forward so the user can inspect the diffs. */
    fun openReview()
}
