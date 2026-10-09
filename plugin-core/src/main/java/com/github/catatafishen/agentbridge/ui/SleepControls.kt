package com.github.catatafishen.agentbridge.ui

/**
 * What the chat UI can do to a running `sleep` tool call.
 *
 * Implemented by the tool; handed to [ChatPanelApi.showSleepRequest] so the panel can offer
 * "+10s", "+30s" and "Skip" without knowing anything about how the tool waits. The agent never
 * learns which of these happened: it only sees the sleep finish.
 */
interface SleepControls {
    /** Absolute epoch-ms the sleep can never be extended past. */
    val maxDeadlineEpochMs: Long

    /** Lengthens the sleep by [seconds] and returns the new absolute deadline (epoch ms). */
    fun extend(seconds: Int): Long

    /** Ends the sleep now, as if its time had run out. */
    fun skip()
}
