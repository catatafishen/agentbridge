package com.github.catatafishen.agentbridge.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.InlineBanner
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.Box
import javax.swing.BoxLayout

/**
 * Reusable warning banner for auth / setup prerequisite checks.
 *
 * Uses [InlineBanner] for native JetBrains look and feel, with an optional device-code row
 * shown during inline auth flows.
 *
 * The banner self-shows/hides by polling [diagnosticsFn]. When the diagnostic clears, [onFixed]
 * is called and the banner hides. Polling uses [AppExecutorUtil] so no raw executor threads are
 * ever leaked.
 *
 * Callers configure the display state in [onDiagUpdate] via [updateState], which fires on the EDT
 * whenever a new (non-null) diagnostic value arrives.
 */
class AuthSetupBanner(
    private val pollIntervalDown: Long = 30,
    private val pollIntervalUp: Long = 60,
    private val diagnosticsFn: () -> String?,
    private val onFixed: () -> Unit = {},
    /** Called on the EDT whenever diagnostics returns a non-null value. */
    private val onDiagUpdate: AuthSetupBanner.(diag: String) -> Unit,
) : JBPanel<JBPanel<*>>(BorderLayout()) {

    private val banner = InlineBanner("", EditorNotificationPanel.Status.Warning)

    // Action links created once in init; visibility toggled as needed
    private var installAction: javax.swing.JComponent? = null
    private var signInAction: javax.swing.JComponent? = null
    private var retryAction: javax.swing.JComponent? = null

    /** Set by callers to handle the "Install…" action click. */
    var installHandler: (() -> Unit)? = null

    /** Set by callers to handle the "Sign In" action click. */
    var signInHandler: (() -> Unit)? = null

    /** Set by callers for extra cleanup on retry (runs before [triggerCheck]). */
    var retryHandler: (() -> Unit)? = null

    // ── Device code row (shown when inline auth parses a code + URL) ─────────
    private val deviceCodeRow = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        isVisible = false
        border = JBUI.Borders.emptyLeft(22)
    }
    private val codeLabel = JBLabel().apply {
        font = JBUI.Fonts.label().asBold()
    }
    private val copyLink = HyperlinkLabel("Copy code").apply {
        icon = AllIcons.Actions.Copy
    }
    private val openBrowserLink = HyperlinkLabel("Open GitHub").apply {
        icon = AllIcons.Ide.External_link_arrow
    }
    private var deviceUrl: String? = null

    private var scheduledFuture: ScheduledFuture<*>? = null

    // Tracks the delayed "re-show sign in" future from showSignInPending()
    private var pendingSignInFuture: ScheduledFuture<*>? = null
    private var wasDown = false

    init {
        isVisible = false
        isOpaque = false

        deviceCodeRow.add(JBLabel("Your code:"))
        deviceCodeRow.add(Box.createHorizontalStrut(JBUI.scale(6)))
        deviceCodeRow.add(codeLabel)
        deviceCodeRow.add(Box.createHorizontalStrut(JBUI.scale(8)))
        deviceCodeRow.add(copyLink)
        deviceCodeRow.add(Box.createHorizontalStrut(JBUI.scale(8)))
        deviceCodeRow.add(openBrowserLink)

        // Use BorderLayout so banner fills width and deviceCodeRow sits below without alignment issues
        val stack = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(banner, BorderLayout.CENTER)
            add(deviceCodeRow, BorderLayout.SOUTH)
        }
        add(stack, BorderLayout.CENTER)

        // Create action links once — toggle visibility instead of remove/add
        installAction = banner.addAction("Install\u2026", AllIcons.Actions.Download) { installHandler?.invoke() }
        signInAction = banner.addAction("Sign In", AllIcons.Actions.Execute) { signInHandler?.invoke() }
        retryAction = banner.addAction("Retry", AllIcons.Actions.Refresh) {
            retryHandler?.invoke()
            triggerCheck()
        }
        installAction?.isVisible = false
        signInAction?.isVisible = false

        copyLink.addHyperlinkListener {
            val code = codeLabel.text.trim()
            if (code.isNotEmpty()) {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    .setContents(StringSelection(code), null)
                copyLink.setHyperlinkText("Copied!")
                AppExecutorUtil.getAppScheduledExecutorService().schedule(
                    { ApplicationManager.getApplication().invokeLater { copyLink.setHyperlinkText("Copy code") } },
                    2L, TimeUnit.SECONDS,
                )
            }
        }
        openBrowserLink.addHyperlinkListener {
            deviceUrl?.let { com.intellij.ide.BrowserUtil.browse(it) }
        }

        addAncestorListener(object : javax.swing.event.AncestorListener {
            override fun ancestorAdded(e: javax.swing.event.AncestorEvent) {
                runCheck()
            }

            override fun ancestorRemoved(e: javax.swing.event.AncestorEvent) {
                cancelPoll()
            }

            override fun ancestorMoved(e: javax.swing.event.AncestorEvent) { /* no-op */
            }
        })
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Update the banner display state. Called from [onDiagUpdate] callback.
     *
     * @param message     Plain-text message to display.
     * @param showInstall Whether to show the "Install…" action (triggers [installHandler]).
     * @param showSignIn  Whether to show the "Sign In" action (triggers [signInHandler]).
     */
    fun updateState(message: String, showInstall: Boolean = false, showSignIn: Boolean = false) {
        banner.setMessage(message)
        rebuildActions(showInstall, showSignIn)
    }

    /** Force an immediate re-check (e.g. after an auth error in a request). */
    fun triggerCheck() = runCheck()

    /**
     * Show a device code and verification URL in the banner.
     * Called by the inline auth flow when it parses the CLI output.
     */
    fun showDeviceCode(code: String, url: String) {
        codeLabel.text = " $code "
        deviceUrl = url
        banner.setMessage("Sign in to Copilot \u2014 copy your code, then open GitHub to enter it.")
        rebuildActions(showInstall = false, showSignIn = false)
        deviceCodeRow.isVisible = true
        revalidate()
        repaint()
    }

    /** Hide the device code row (e.g. after auth completes or on fallback to terminal). */
    fun hideDeviceCode() {
        deviceCodeRow.isVisible = false
        deviceUrl = null
    }

    /** Give immediate "signing in" feedback. */
    fun showSignInPending() {
        banner.setMessage("Signing in\u2026 waiting for device code from CLI.")
        rebuildActions(showInstall = false, showSignIn = false)
        // Re-enable Sign In after a few seconds so the user can retry if nothing happened
        pendingSignInFuture = AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                ApplicationManager.getApplication()
                    .invokeLater { rebuildActions(showInstall = false, showSignIn = true) }
            },
            8L, TimeUnit.SECONDS,
        )
    }

    // ── Action management ─────────────────────────────────────────────────────

    private fun rebuildActions(showInstall: Boolean, showSignIn: Boolean) {
        installAction?.isVisible = showInstall
        signInAction?.isVisible = showSignIn
        retryAction?.isVisible = true
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private fun scheduleNext(currentlyDown: Boolean) {
        val delay = if (currentlyDown) pollIntervalDown else pollIntervalUp
        scheduledFuture = AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                val diag = diagnosticsFn()
                ApplicationManager.getApplication().invokeLater {
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
        pendingSignInFuture?.cancel(false)
        pendingSignInFuture = null
    }

    private fun runCheck() {
        cancelPoll()
        banner.setMessage("Checking\u2026")
        rebuildActions(showInstall = false, showSignIn = false)
        ApplicationManager.getApplication().executeOnPooledThread {
            val diag = diagnosticsFn()
            ApplicationManager.getApplication().invokeLater {
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
            banner.setMessage("")
        }
        isVisible = nowDown
        if (wasDown && !nowDown) onFixed()
        wasDown = nowDown
    }
}
