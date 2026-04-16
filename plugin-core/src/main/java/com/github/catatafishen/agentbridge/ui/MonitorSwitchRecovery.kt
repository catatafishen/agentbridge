package com.github.catatafishen.agentbridge.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.*
import java.awt.event.*
import java.beans.PropertyChangeListener
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Handles the JCEF chat panel's recovery when the OS relocates the window to a
 * different display, or the display topology changes (lid close/open, monitor
 * hot-plug, scale factor change). See issue #237.
 *
 * The JCEF OSR (off-screen render) pipeline caches its device scale and backing
 * surface; without an explicit recovery sequence the panel renders at the wrong
 * DPI ("jagged fonts") or freezes entirely.
 *
 * We layer multiple event sources because no single one fires reliably across
 * all scenarios on macOS:
 *
 * - [HierarchyBoundsListener] on the browser component — user drag across monitors.
 * - [PropertyChangeListener] on the **top-level [Window]** for the
 *   `"graphicsConfiguration"` property — lid-close / display disconnect. AWT
 *   only fires this PCE from [Window.setGraphicsConfiguration]; lightweight
 *   [javax.swing.JComponent]s inherit their GC from the peer and never fire.
 * - [java.awt.event.ComponentListener] on the top-level [Window] — catches
 *   macOS forced window relocation (`componentMoved`).
 * - Polling [GraphicsEnvironment.getScreenDevices] on each trigger — detects
 *   display-count changes even when the GC reference is reused.
 *
 * When any source fires, we compare a [Fingerprint] of
 * (gc identity, scale, bounds, screen count). Any change triggers recovery:
 *
 *  1. `cefBrowser.setWindowVisibility(false)` + `setWindowVisibility(true)` —
 *     tears down the OSR compositor so CEF rebuilds its `CALayer` on the new
 *     display.
 *  2. `cefBrowser.notifyScreenInfoChanged()` — CEF re-reads the device scale.
 *  3. `cefBrowser.wasResized(w, h)` — recreates the backing surface.
 *  4. `cefBrowser.invalidate()` — forces a paint.
 *  5. [onRecovered] callback (e.g. `updateThemeColors`) — runs **after** the
 *     OSR surface is live, so CSS writes actually reach the screen.
 *
 * Stability check: retry up to 20 × 150 ms (~3 s) until the component is
 * showing at non-zero size and the scale factor has remained constant across
 * two consecutive samples. macOS lid-close can take 800–1500 ms to settle
 * (window server relocation, Dock resolution, `CGDisplayReconfigurationCallback`).
 */
internal class MonitorSwitchRecovery(
    private val browser: JBCefBrowser,
    private val onRecovered: () -> Unit,
    parentDisposable: Disposable,
) : Disposable {

    private data class Fingerprint(
        val gcId: Int,
        val scaleX: Double,
        val scaleY: Double,
        val bounds: Rectangle,
        val screenCount: Int,
    )

    private val log = Logger.getInstance(MonitorSwitchRecovery::class.java)
    private val component: Component get() = browser.component
    private var last: Fingerprint? = null
    private var attachedWindow: Window? = null

    private val windowGcListener: PropertyChangeListener = PropertyChangeListener { e ->
        log.info("[monitor] window PCE '${e.propertyName}' old=${e.oldValue} new=${e.newValue}")
        check("window-pce:${e.propertyName}")
    }
    private val componentGcListener: PropertyChangeListener = PropertyChangeListener {
        check("component-pce:graphicsConfiguration")
    }
    private val windowComponentListener = object : ComponentAdapter() {
        override fun componentMoved(e: ComponentEvent?) = check("window-moved")
        override fun componentResized(e: ComponentEvent?) = check("window-resized")
    }
    private val hierarchyBoundsListener = object : HierarchyBoundsListener {
        override fun ancestorMoved(e: HierarchyEvent?) = check("ancestor-moved")
        override fun ancestorResized(e: HierarchyEvent?) = check("ancestor-resized")
    }
    private val hierarchyListener = HierarchyListener { e ->
        val flags = e.changeFlags
        val relevant = (HierarchyEvent.SHOWING_CHANGED or HierarchyEvent.PARENT_CHANGED).toLong()
        if (flags and relevant != 0L) syncWindowListeners()
    }

    init {
        Disposer.register(parentDisposable, this)
    }

    fun install() {
        component.addHierarchyBoundsListener(hierarchyBoundsListener)
        component.addHierarchyListener(hierarchyListener)
        component.addPropertyChangeListener("graphicsConfiguration", componentGcListener)
        syncWindowListeners()
        last = currentFingerprint()
        log.info("[monitor] installed; baseline=$last")
    }

    /** Manually trigger recovery (e.g. from a diagnostic action). */
    fun forceRefresh() {
        log.info("[monitor] forceRefresh() requested")
        last = currentFingerprint()
        triggerRecovery("manual")
    }

    override fun dispose() {
        runCatching { component.removeHierarchyBoundsListener(hierarchyBoundsListener) }
        runCatching { component.removeHierarchyListener(hierarchyListener) }
        runCatching { component.removePropertyChangeListener("graphicsConfiguration", componentGcListener) }
        detachWindowListeners()
    }

    private fun syncWindowListeners() {
        val w = SwingUtilities.getWindowAncestor(component)
        if (w === attachedWindow) return
        detachWindowListeners()
        if (w != null) {
            w.addPropertyChangeListener("graphicsConfiguration", windowGcListener)
            w.addComponentListener(windowComponentListener)
            attachedWindow = w
            log.info("[monitor] attached to window ${System.identityHashCode(w)} (${w.javaClass.simpleName})")
        }
    }

    private fun detachWindowListeners() {
        attachedWindow?.let { w ->
            runCatching { w.removePropertyChangeListener("graphicsConfiguration", windowGcListener) }
            runCatching { w.removeComponentListener(windowComponentListener) }
            log.info("[monitor] detached from window ${System.identityHashCode(w)}")
        }
        attachedWindow = null
    }

    private fun currentFingerprint(): Fingerprint? {
        val gc: GraphicsConfiguration = component.graphicsConfiguration ?: return null
        val tx = gc.defaultTransform
        val screens = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.size
        }.getOrDefault(-1)
        return Fingerprint(
            gcId = System.identityHashCode(gc),
            scaleX = tx.scaleX,
            scaleY = tx.scaleY,
            bounds = gc.bounds,
            screenCount = screens,
        )
    }

    private fun check(reason: String) {
        val fp = currentFingerprint() ?: return
        val prev = last
        if (prev == fp) return
        log.info("[monitor] change detected reason=$reason prev=$prev curr=$fp")
        last = fp
        triggerRecovery(reason)
    }

    private fun triggerRecovery(reason: String) {
        log.info(
            "[monitor] recovery triggered reason=$reason " +
                "isShowing=${component.isShowing} size=${component.width}x${component.height}"
        )
        val cef = browser.cefBrowser
        // Tear down the OSR compositor before resize; forces CEF to rebuild
        // its CALayer on the new display.
        runCatching { cef.setWindowVisibility(false) }
        runCatching { cef.setWindowVisibility(true) }
        // Tell CEF the device scale factor may have changed so it re-queries it
        // from the render handler before the next paint.
        runCatching { cef.notifyScreenInfoChanged() }
        refreshOsrWhenStable(attempt = 0, lastScale = -1.0, stableCount = 0)
    }

    private fun refreshOsrWhenStable(attempt: Int, lastScale: Double, stableCount: Int) {
        SwingUtilities.invokeLater {
            val comp = component
            val scale = comp.graphicsConfiguration?.defaultTransform?.scaleX ?: -1.0
            val ready = comp.isShowing && comp.width > 0 && comp.height > 0 && scale > 0
            val scaleStable = ready && scale == lastScale
            when {
                scaleStable && stableCount >= 1 -> applyOsrRefresh(attempt, comp, scale)
                attempt < MAX_ATTEMPTS -> {
                    val nextStable = if (scaleStable) stableCount + 1 else 0
                    Timer(RETRY_INTERVAL_MS) {
                        refreshOsrWhenStable(attempt + 1, scale, nextStable)
                    }.apply { isRepeats = false; start() }
                }

                else -> giveUp(attempt, comp, scale, ready)
            }
        }
    }

    private fun applyOsrRefresh(attempt: Int, comp: Component, scale: Double) {
        val cef = browser.cefBrowser
        runCatching { cef.wasResized(comp.width, comp.height) }
        runCatching { cef.invalidate() }
        runCatching { cef.notifyScreenInfoChanged() }
        log.info("[monitor] OSR refreshed after $attempt attempts; size=${comp.width}x${comp.height} scale=$scale")
        runCatching { onRecovered() }
    }

    private fun giveUp(attempt: Int, comp: Component, scale: Double, ready: Boolean) {
        log.warn("[monitor] OSR stabilisation gave up after $attempt attempts; ready=$ready scale=$scale")
        if (comp.width > 0 && comp.height > 0) {
            val cef = browser.cefBrowser
            runCatching { cef.wasResized(comp.width, comp.height) }
            runCatching { cef.invalidate() }
        }
        runCatching { onRecovered() }
    }

    private companion object {
        const val MAX_ATTEMPTS = 20
        const val RETRY_INTERVAL_MS = 150
    }
}
