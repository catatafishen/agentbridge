package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.ui.ResizeBurstClock.isBurstActive
import com.github.catatafishen.agentbridge.ui.ResizeBurstClock.tick

/**
 * Process-wide resize burst clock shared by all paint-caching components.
 *
 * **Who ticks:** only [NativeMarkdownPane.getPreferredSize] calls [tick], because a width
 * change observed during layout is a true window-resize signal. Paint-path components
 * (e.g. [RoundedPanel]) must NOT call [tick] — doing so creates a renewal loop where each
 * streaming repaint of a stale-width bubble re-arms the burst, suppressing all
 * NativeMarkdownPane renders until the stream pauses.
 *
 * **Who checks:** any component that wants to skip expensive work during a resize drag
 * calls [isBurstActive]. It should also schedule its own settle repaint (e.g. a Timer at
 * ~300 ms) so off-screen panels that are never directly repainted by the NativeMarkdownPane
 * settle path eventually self-correct.
 *
 * The burst window (200 ms) is strictly less than NativeMarkdownPane's settle timer
 * (250 ms), so the settle revalidate always fires after the burst has expired.
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
