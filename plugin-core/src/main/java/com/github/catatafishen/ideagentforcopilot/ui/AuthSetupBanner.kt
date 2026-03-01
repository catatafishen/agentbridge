package com.github.catatafishen.ideagentforcopilot.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.SwingUtilities

/**
 * Reusable warning banner for auth / setup prerequisite checks.
 *
 * Shows a yellow warning strip: [⚠ icon] [text label] [install button] [action button] [retry button]
 *
 * The banner self-shows/hides by polling [diagnosticsFn].  When the diagnostic clears, [onFixed]
 * is called and the banner hides.  Polling uses [AppExecutorUtil] so no raw executor threads are
 * ever leaked.
 *
 * Callers configure text and button visibility in [onDiagUpdate], which fires on the EDT whenever
 * a new (non-null) diagnostic value arrives.
 */
class AuthSetupBanner(
    retryTooltip: String,
    private val pollIntervalDown: Long = 30,
    private val pollIntervalUp: Long = 60,
    private val diagnosticsFn: () -> String?,
    private val onFixed: () -> Unit = {},
    /** Called on the EDT whenever diagnostics returns a non-null value. */
    private val onDiagUpdate: AuthSetupBanner.(diag: String) -> Unit,
) : JBPanel<JBPanel<*>>(BorderLayout()) {

    // ── Palette — single source of truth for all three colour roles ──────────
    private companion object {
        val bannerBorder = JBColor(Color(0xE5, 0xA0, 0x00), Color(0x99, 0x75, 0x00))
        val bannerBg = JBColor(Color(0xFF, 0xF3, 0xCD), Color(0x3D, 0x36, 0x20))
        val bannerFg = JBColor(Color(0x5C, 0x45, 0x00), Color(0xE0, 0xC0, 0x60))
    }

    // ── Public widgets callers may configure ─────────────────────────────────
    val textLabel = JBLabel().apply { foreground = bannerFg }
    val installButton = bannerButton("Install…")
    val actionButton = bannerButton("Sign In")
    val retryButton = bannerButton("Retry").apply { toolTipText = retryTooltip }

    // ── Device code row (shown when inline auth parses a code + URL) ─────────
    private val deviceCodeRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
        isOpaque = false
        isVisible = false
        border = JBUI.Borders.emptyLeft(22) // indent to align with text (past icon)
    }
    private val codeLabel = JBLabel().apply {
        foreground = bannerFg
        font = font.deriveFont(Font.BOLD)
    }
    private val copyButton = bannerButton("\uD83D\uDCCB Copy Code")
    private val openBrowserButton = bannerButton("\uD83D\uDD17 Open GitHub")
    private var deviceUrl: String? = null

    private var scheduledFuture: ScheduledFuture<*>? = null
    private var wasDown = false

    init {
        isVisible = false
        isOpaque = true
        background = bannerBg
        border = JBUI.Borders.compound(
            com.intellij.ui.SideBorder(bannerBorder, com.intellij.ui.SideBorder.BOTTOM),
            JBUI.Borders.empty(4, 8),
        )

        installButton.isVisible = false
        actionButton.isVisible = false

        val icon = JBLabel(com.intellij.icons.AllIcons.General.Warning).apply {
            border = JBUI.Borders.emptyRight(6)
            accessibleContext.accessibleName = "Warning"
        }

        val buttons = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(installButton)
            add(actionButton)
            add(retryButton)
        }

        val topRow = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(icon, BorderLayout.WEST)
            add(textLabel, BorderLayout.CENTER)
            add(buttons, BorderLayout.EAST)
        }

        // Device code row: [Your code:] [CODE] [Copy] [Open Browser]
        deviceCodeRow.add(JBLabel("Your code:").apply { foreground = bannerFg })
        deviceCodeRow.add(codeLabel)
        deviceCodeRow.add(copyButton)
        deviceCodeRow.add(openBrowserButton)

        val rows = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(topRow)
            add(deviceCodeRow)
        }
        add(rows, BorderLayout.CENTER)

        retryButton.addActionListener { triggerCheck() }
        copyButton.addActionListener {
            val code = codeLabel.text.trim()
            if (code.isNotEmpty()) {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    .setContents(StringSelection(code), null)
                copyButton.text = "\u2713 Copied"
                AppExecutorUtil.getAppScheduledExecutorService().schedule(
                    { SwingUtilities.invokeLater { copyButton.text = "\uD83D\uDCCB Copy Code" } },
                    2L, TimeUnit.SECONDS,
                )
            }
        }
        openBrowserButton.addActionListener {
            deviceUrl?.let { com.intellij.ide.BrowserUtil.browse(it) }
        }

        addAncestorListener(object : javax.swing.event.AncestorListener {
            override fun ancestorAdded(e: javax.swing.event.AncestorEvent) {
                runCheck()
            }

            override fun ancestorRemoved(e: javax.swing.event.AncestorEvent) {
                cancelPoll()
            }

            override fun ancestorMoved(e: javax.swing.event.AncestorEvent) {}
        })
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Force an immediate re-check (e.g. after an auth error in a request). */
    fun triggerCheck() = runCheck()

    /**
     * Show a device code and verification URL in the banner.
     * Called by the inline auth flow when it parses the CLI output.
     */
    fun showDeviceCode(code: String, url: String) {
        codeLabel.text = " $code "
        deviceUrl = url
        textLabel.text = "<html><b>Sign in to Copilot</b> \u2014 copy your code, then open GitHub to enter it.</html>"
        actionButton.isVisible = false
        deviceCodeRow.isVisible = true
        revalidate()
        repaint()
    }

    /** Hide the device code row (e.g. after auth completes or on fallback to terminal). */
    fun hideDeviceCode() {
        deviceCodeRow.isVisible = false
        deviceUrl = null
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private fun scheduleNext(currentlyDown: Boolean) {
        val delay = if (currentlyDown) pollIntervalDown else pollIntervalUp
        scheduledFuture = AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                val diag = diagnosticsFn()
                SwingUtilities.invokeLater {
                    applyDiag(diag)
                    scheduleNext(diag != null)
                }
            },
            delay, TimeUnit.SECONDS,
        )
    }

    private fun cancelPoll() {
        scheduledFuture?.cancel(false)
        scheduledFuture = null
    }

    private fun runCheck() {
        cancelPoll()
        retryButton.isEnabled = false
        retryButton.text = "Checking\u2026"
        ApplicationManager.getApplication().executeOnPooledThread {
            val diag = diagnosticsFn()
            SwingUtilities.invokeLater {
                retryButton.text = "Retry"
                retryButton.isEnabled = true
                applyDiag(diag)
                scheduleNext(diag != null)
            }
        }
    }

    private fun applyDiag(diag: String?) {
        val nowDown = diag != null
        if (nowDown) {
            onDiagUpdate(this, diag)
        } else {
            hideDeviceCode()
        }
        isVisible = nowDown
        if (wasDown && !nowDown) onFixed()
        wasDown = nowDown
    }

    // ── Sign In feedback helpers ──────────────────────────────────────────────

    /** Call inside an [actionButton] ActionListener to give immediate "signing in" feedback. */
    fun showSignInPending() {
        actionButton.isEnabled = false
        textLabel.text = "<html><b>Signing in\u2026</b> waiting for device code from CLI.</html>"
        // Re-enable after a few seconds so the user can retry if nothing happened
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            { SwingUtilities.invokeLater { actionButton.isEnabled = true } },
            8L, TimeUnit.SECONDS,
        )
    }

    // ── Layout helper ─────────────────────────────────────────────────────────

    private fun bannerButton(label: String) = JButton(label).apply {
        foreground = bannerFg
        isOpaque = false
        isBorderPainted = false
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    }
}
