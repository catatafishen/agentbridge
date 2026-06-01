package com.github.catatafishen.agentbridge.ui

/**
 * Process-wide resize burst clock shared by all paint-caching components
 * ([NativeMarkdownPane], [RoundedPanel], etc.).
 *
 * When any component detects a width change during layout or paint it calls [tick];
 * any component that wants to skip expensive work during a window resize drag
 * checks [isBurstActive] to decide whether to blit a stale cached image instead.
 *
 * The burst window (200 ms) is strictly less than NativeMarkdownPane's settle timer
 * (250 ms), so the settle revalidate always fires after the burst has expired and
 * triggers one accurate final render.
 */
internal object ResizeBurstClock {
    private const val BURST_WINDOW_NS = 200L * 1_000_000

    @Volatile
    private var lastNs: Long = 0L

    /** Record a resize event — call when a width change is detected during layout or paint. */
    fun tick() {
        lastNs = System.nanoTime()
    }

    /** Returns true if we are currently inside a resize burst drag. */
    fun isBurstActive(): Boolean = (System.nanoTime() - lastNs) < BURST_WINDOW_NS
}
