package com.github.catatafishen.agentbridge.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Detects prolonged EDT (Event Dispatch Thread) freezes and forces JCEF OSR
 * recovery on all registered browsers when a freeze ends.
 *
 * Registered as a project-level service so all [ChatConsolePanel] instances
 * within the same project share a single heartbeat timer and recovery registry.
 *
 * ## Why this exists
 *
 * JCEF's off-screen rendering (OSR) depends on Swing's EDT paint cycle to
 * display frames. When EDT is blocked for a long time (e.g. 30+ seconds due
 * to cascading VCS `git log` / `git blame` operations triggered by bulk file
 * opens), the JCEF compositor and Swing component can desynchronize:
 *
 * 1. CEF's renderer thread continues delivering frames via `onPaint()`.
 * 2. Swing can't process `repaint()` calls because EDT is frozen.
 * 3. After EDT unblocks, CEF may stop delivering new frames because it
 *    believes the last frame was acknowledged (the callback returned),
 *    but the Swing component never actually painted it.
 * 4. Without a `wasResized()` or `invalidate()` signal, no fresh frame
 *    is ever sent — the panel appears permanently frozen.
 *
 * The JS engine remains alive (`executeJs` calls succeed, DOM is updated),
 * but the visual viewport is stuck on the pre-freeze frame.
 *
 * ## How it works
 *
 * A Swing [Timer] fires every [HEARTBEAT_INTERVAL_MS] on EDT. Each tick
 * records `System.nanoTime()`. If the delta between consecutive heartbeats
 * exceeds [FREEZE_THRESHOLD_MS], a freeze is detected. When detected, all
 * registered browsers are recovered using the same sequence as
 * [MonitorSwitchRecovery]: `setWindowVisibility(false/true)` +
 * `notifyScreenInfoChanged()` + `wasResized()` + `invalidate()`.
 *
 * ## Incident reference
 *
 * First observed 2026-05-09: 10+ simultaneous `read_file` MCP tool calls
 * each opened a file in the editor via `followFileIfEnabled`, triggering
 * IntelliJ VCS annotations (`git log` 54s + `git blame` 80s). EDT was
 * blocked for 72 seconds. JCEF chat panel never recovered visually despite
 * `executeJs` calls continuing to flow.
 *
 * See `DEVELOPMENT.md` § "JCEF OSR Freeze After Prolonged EDT Block" for
 * the full chain of events.
 */
@Service(Service.Level.PROJECT)
internal class EdtFreezeRecovery(
    @Suppress("unused") private val project: Project,
) : Disposable {

    private val log = Logger.getInstance(EdtFreezeRecovery::class.java)

    private data class BrowserEntry(
        val browser: JBCefBrowser,
        val onRecovered: () -> Unit,
    )

    private val browsers = mutableListOf<BrowserEntry>()
    private var lastHeartbeatNanos = System.nanoTime()

    private val heartbeatTimer = Timer(HEARTBEAT_INTERVAL_MS) {
        val now = System.nanoTime()
        val deltaMs = (now - lastHeartbeatNanos) / 1_000_000
        lastHeartbeatNanos = now

        if (deltaMs > FREEZE_THRESHOLD_MS) {
            log.error(
                "EDT was unresponsive for ${deltaMs}ms (threshold: ${FREEZE_THRESHOLD_MS}ms). " +
                    "Triggering JCEF OSR recovery on ${browsers.size} browser(s). " +
                    "This is typically caused by blocking operations on EDT such as " +
                    "VCS annotations (git log/blame) triggered by bulk file opens."
            )
            recoverAll()
        }
    }.apply { isRepeats = true }

    init {
        heartbeatTimer.start()
        log.info("EDT freeze recovery installed (interval=${HEARTBEAT_INTERVAL_MS}ms, threshold=${FREEZE_THRESHOLD_MS}ms)")
    }

    /**
     * Register a JCEF browser for freeze recovery.
     * [onRecovered] is called after the OSR refresh (e.g. to replay DOM state).
     */
    fun register(browser: JBCefBrowser, onRecovered: () -> Unit) {
        browsers.add(BrowserEntry(browser, onRecovered))
    }

    fun unregister(browser: JBCefBrowser) {
        browsers.removeAll { it.browser === browser }
    }

    private fun recoverAll() {
        for (entry in browsers.toList()) {
            recoverBrowser(entry)
        }
    }

    private fun recoverBrowser(entry: BrowserEntry) {
        val cef = entry.browser.cefBrowser
        val comp = entry.browser.component
        log.error(
            "Recovering JCEF browser: isShowing=${comp.isShowing} " +
                "size=${comp.width}x${comp.height}"
        )
        runCatching { cef.setWindowVisibility(false) }
        runCatching { cef.setWindowVisibility(true) }
        runCatching { cef.notifyScreenInfoChanged() }
        if (comp.width > 0 && comp.height > 0) {
            runCatching { cef.wasResized(comp.width, comp.height) }
        }
        runCatching { cef.invalidate() }
        // Antipattern (DESIGN-PRINCIPLES.md): SwingUtilities.invokeLater bypasses IntelliJ's modality-aware
        // dispatcher. Kept here intentionally: during an EDT freeze, IntelliJ's own dispatcher may be stuck.
        // Raw Swing dispatch is the only reliable path to schedule recovery work.
        SwingUtilities.invokeLater {
            runCatching { entry.onRecovered() }
        }
    }

    override fun dispose() {
        heartbeatTimer.stop()
        browsers.clear()
    }

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 5_000
        private const val FREEZE_THRESHOLD_MS = 30_000
    }
}
