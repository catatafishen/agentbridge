package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.ui.ResizeBurstClock.isBurstActive
import com.github.catatafishen.agentbridge.ui.ResizeBurstClock.tick

/**
 * Process-wide resize burst clock shared by all paint-caching components.
 *
 * **Who ticks:** [NativeChatPanel]'s scroll viewport `ComponentListener` calls [tick] on
 * every `componentResized` event, i.e. whenever the chat panel is being dragged/resized.
 * This keeps the signal tied to genuine window-resize events rather than to layout passes,
 * which previously caused a cascading settle-timer renewal loop: each settle-phase layout
 * pass would call [tick] from `NativeMarkdownPane.getPreferredSize`, re-arming the burst
 * and keeping all bubble backgrounds stale until the stream paused.
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
