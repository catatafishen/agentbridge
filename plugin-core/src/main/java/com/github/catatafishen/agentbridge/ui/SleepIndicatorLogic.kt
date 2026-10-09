package com.github.catatafishen.agentbridge.ui

/**
 * Which `sleep` call the working indicator is currently showing.
 *
 * Calls arrive from tool threads and are applied on the EDT in order, so a late [end] for a sleep that has
 * already been replaced (or cleared with the indicator) must not tear down the newer one's countdown.
 */
class SleepRequestTracker {
    private var activeReqId: String? = null

    fun begin(reqId: String) {
        activeReqId = reqId
    }

    /** Returns `true` if [reqId] is the active request, which is then cleared; `false` for a stale or unknown id. */
    fun end(reqId: String): Boolean {
        if (activeReqId != reqId) return false
        activeReqId = null
        return true
    }

    fun clear() {
        activeReqId = null
    }
}

/** Pure text and button-state rules for the working indicator's countdown mode. */
object WaitCountdown {

    /** The "Sleeping… 12s / 60s" style label: seconds elapsed since [startMs] out of the total up to [deadlineMs]. */
    @JvmStatic
    fun label(prefix: String, startMs: Long, deadlineMs: Long, nowMs: Long): String {
        val elapsed = (nowMs - startMs) / 1000
        val total = (deadlineMs - startMs) / 1000
        return "$prefix… ${elapsed}s / ${total}s"
    }

    /** Whether the "+Ns" buttons still have room to extend; they disable once the deadline reaches its cap. */
    @JvmStatic
    fun canExtend(deadlineMs: Long, maxDeadlineMs: Long): Boolean = deadlineMs < maxDeadlineMs
}
