package com.github.catatafishen.agentbridge.ui

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [save] once the agent has sent nothing for [delayMs]. This saves what the agent sends after its turn
 * (a background sub-agent's report), which no turn-end save covers.
 *
 * [onActivity] runs for every streamed chunk, so it only records the time; a single pending check
 * reschedules itself while chunks keep coming.
 *
 * @param schedule runs the given task after the given delay in milliseconds, on a background thread
 */
class QuietSaveScheduler(
    private val delayMs: Long,
    private val nowMs: () -> Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val save: Runnable,
) {
    @Volatile
    private var lastActivityMs = 0L
    private val checkPending = AtomicBoolean(false)

    fun onActivity() {
        lastActivityMs = nowMs()
        if (checkPending.compareAndSet(false, true)) schedule(Runnable { check() }, delayMs)
    }

    private fun check() {
        val quietFor = nowMs() - lastActivityMs
        if (quietFor < delayMs) {
            schedule(Runnable { check() }, delayMs - quietFor)
            return
        }
        // Cleared before saving, so activity during the save schedules the next one.
        checkPending.set(false)
        save.run()
    }
}
