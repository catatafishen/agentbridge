package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.ui.StatusBanner.Companion.INFO_DISMISS_MS
import com.github.catatafishen.agentbridge.ui.StatusBanner.Companion.WARNING_DISMISS_MS
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.InlineBanner
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.TimeUnit
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.event.AncestorEvent
import javax.swing.event.AncestorListener

/**
 * Returns the theme-aware border color for the given [EditorNotificationPanel.Status].
 *
 * The `border` field on [EditorNotificationPanel.Status] is package-private,
 * so we map each status to its corresponding [com.intellij.util.ui.JBUI.CurrentTheme.Banner] color.
 */
internal fun statusBorderColor(status: EditorNotificationPanel.Status): java.awt.Color = when (status) {
    EditorNotificationPanel.Status.Info -> com.intellij.util.ui.JBUI.CurrentTheme.Banner.INFO_BORDER_COLOR
    EditorNotificationPanel.Status.Success -> com.intellij.util.ui.JBUI.CurrentTheme.Banner.SUCCESS_BORDER_COLOR
    EditorNotificationPanel.Status.Warning -> com.intellij.util.ui.JBUI.CurrentTheme.Banner.WARNING_BORDER_COLOR
    EditorNotificationPanel.Status.Error -> com.intellij.util.ui.JBUI.CurrentTheme.Banner.ERROR_BORDER_COLOR
    EditorNotificationPanel.Status.Promo -> com.intellij.util.ui.JBUI.CurrentTheme.Banner.INFO_BORDER_COLOR
}

/**
 * Transient status banner that shows error/warning/info messages above the chat panel.
 *
 * Uses [InlineBanner] for native JetBrains look and feel. Info messages auto-dismiss
 * after [INFO_DISMISS_MS] (paused while hovered); warning messages after [WARNING_DISMISS_MS];
 * error messages stay until the user clicks the close button.
 *
 * Uses [Alarm] for dismiss scheduling (lifecycle-aware, EDT-native).
 */
class StatusBanner(parentDisposable: Disposable) :
    com.intellij.ui.components.JBPanel<StatusBanner>(BorderLayout()) {

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    private companion object {
        const val INFO_DISMISS_MS = 10_000
        const val WARNING_DISMISS_MS = 15_000
    }

    private val dismissAlarm = Alarm(parentDisposable)
    private var currentBanner: InlineBanner? = null
    private var hovered = false

    init {
        isOpaque = false
        isVisible = false
        addAncestorListener(object : AncestorListener {
            override fun ancestorAdded(e: AncestorEvent) { /* no-op */
            }

            override fun ancestorMoved(e: AncestorEvent) { /* no-op */
            }

            override fun ancestorRemoved(e: AncestorEvent) {
                dismissAlarm.cancelAllRequests()
            }
        })
    }

    fun showError(message: String) = show(message, EditorNotificationPanel.Status.Error)

    fun showError(message: String, actionText: String, action: () -> Unit) =
        show(message, EditorNotificationPanel.Status.Error, actionText, action)

    fun showWarning(message: String) = show(message, EditorNotificationPanel.Status.Warning)

    fun showInfo(message: String) = show(message, EditorNotificationPanel.Status.Info)

    /**
     * Dismisses the current banner if one is showing.
     * Safe to call from any thread — dispatches to EDT if needed.
     */
    fun dismissCurrent() {
        if (currentBanner == null) return
        if (ApplicationManager.getApplication().isDispatchThread) {
            dismiss()
        } else {
            ApplicationManager.getApplication().invokeLater { dismiss() }
        }
    }

    private fun show(
        message: String,
        status: EditorNotificationPanel.Status,
        actionText: String? = null,
        action: (() -> Unit)? = null
    ) {
        ApplicationManager.getApplication().invokeLater {
            dismiss()
            val borderColor = statusBorderColor(status)
            val banner = object : InlineBanner(message, status) {
                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as java.awt.Graphics2D
                    try {
                        g2.color = background
                        g2.fillRect(0, 0, width, height)
                        g2.color = borderColor
                        g2.fillRect(0, 0, width, 1)
                        g2.fillRect(0, height - 1, width, 1)
                    } finally {
                        g2.dispose()
                    }
                    paintChildren(g)
                }
            }
            banner.showCloseButton(true)
            banner.setCloseAction { dismiss() }
            if (actionText != null && action != null) {
                banner.addAction(actionText) { dismiss(); action() }
            }
            banner.accessibleContext.accessibleName = when (status) {
                EditorNotificationPanel.Status.Error -> "Error: $message"
                EditorNotificationPanel.Status.Warning -> "Warning: $message"
                else -> "Info: $message"
            }

            val dismissMs = when (status) {
                EditorNotificationPanel.Status.Error -> 0
                EditorNotificationPanel.Status.Warning -> WARNING_DISMISS_MS
                else -> INFO_DISMISS_MS
            }

            if (dismissMs > 0) {
                banner.addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) {
                        hovered = true
                        dismissAlarm.cancelAllRequests()
                    }

                    override fun mouseExited(e: MouseEvent) {
                        hovered = false
                        scheduleDismiss(dismissMs)
                    }
                })
                scheduleDismiss(dismissMs)
            }

            currentBanner = banner
            add(banner, BorderLayout.CENTER)
            isVisible = true
            revalidate()
            repaint()
        }
    }

    private fun scheduleDismiss(delayMs: Int) {
        dismissAlarm.cancelAllRequests()
        dismissAlarm.addRequest(::dismiss, delayMs.toLong())
    }

    private fun dismiss() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        dismissAlarm.cancelAllRequests()
        hovered = false
        currentBanner?.let { banner ->
            currentBanner = null
            banner.close()
            isVisible = false
            // Ensure parent relayouts after InlineBanner's close animation
            ApplicationManager.getApplication().invokeLater {
                revalidate()
                repaint()
            }
        }
    }

    // ── Auth flow support (for AcpConnectPanel) ──────────────────────────────

    private var deviceCodeRow: JBPanel<JBPanel<*>>? = null
    private var codeLabel: JBLabel? = null
    private var copyLink: HyperlinkLabel? = null
    private var openBrowserLink: HyperlinkLabel? = null
    private var deviceUrl: String? = null

    /**
     * Shows an auth-specific error with Sign In and Retry actions.
     */
    fun showAuthError(message: String, onSignIn: () -> Unit, onRetry: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            dismiss()
            val status = EditorNotificationPanel.Status.Warning
            val borderColor = statusBorderColor(status)
            val banner = object : InlineBanner(message, status) {
                override fun paintComponent(g: Graphics) {
                    val g2 = g.create() as java.awt.Graphics2D
                    try {
                        g2.color = background
                        g2.fillRect(0, 0, width, height)
                        g2.color = borderColor
                        g2.fillRect(0, 0, width, 1)
                        g2.fillRect(0, height - 1, width, 1)
                    } finally {
                        g2.dispose()
                    }
                    paintChildren(g)
                }
            }
            banner.showCloseButton(true)
            banner.setCloseAction { dismiss() }
            banner.addAction("Sign In", AllIcons.Actions.Execute) { onSignIn() }
            banner.addAction("Retry", AllIcons.Actions.Refresh) { dismiss(); onRetry() }

            currentBanner = banner
            add(banner, BorderLayout.CENTER)
            isVisible = true
            revalidate()
            repaint()
        }
    }

    /**
     * Shows a device code and verification URL for inline auth flow.
     */
    fun showDeviceCode(code: String, url: String) {
        ApplicationManager.getApplication().invokeLater {
            dismiss()
            val status = EditorNotificationPanel.Status.Info
            val borderColor = statusBorderColor(status)

            // Create device code row if it doesn't exist
            if (deviceCodeRow == null) {
                deviceCodeRow = JBPanel<JBPanel<*>>().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    isOpaque = false
                    border = JBUI.Borders.empty(4, 8)
                }
                codeLabel = JBLabel().apply {
                    font = JBUI.Fonts.label().asBold()
                }
                copyLink = HyperlinkLabel("Copy code").apply {
                    icon = AllIcons.Actions.Copy
                }
                openBrowserLink = HyperlinkLabel("Open GitHub").apply {
                    icon = AllIcons.Ide.External_link_arrow
                }
                deviceCodeRow!!.add(JBLabel("Your code:"))
                deviceCodeRow!!.add(Box.createHorizontalStrut(JBUI.scale(6)))
                deviceCodeRow!!.add(codeLabel!!)
                deviceCodeRow!!.add(Box.createHorizontalStrut(JBUI.scale(8)))
                deviceCodeRow!!.add(copyLink!!)
                deviceCodeRow!!.add(Box.createHorizontalStrut(JBUI.scale(8)))
                deviceCodeRow!!.add(openBrowserLink!!)

                copyLink!!.addHyperlinkListener {
                    val c = codeLabel!!.text.trim()
                    if (c.isNotEmpty()) {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(StringSelection(c), null)
                        copyLink!!.setHyperlinkText("Copied!")
                        AppExecutorUtil.getAppScheduledExecutorService().schedule(
                            {
                                ApplicationManager.getApplication()
                                    .invokeLater { copyLink!!.setHyperlinkText("Copy code") }
                            },
                            2L, TimeUnit.SECONDS,
                        )
                    }
                }
                openBrowserLink!!.addHyperlinkListener {
                    deviceUrl?.let { com.intellij.ide.BrowserUtil.browse(it) }
                }
            }

            codeLabel!!.text = " $code "
            deviceUrl = url

            val banner =
                object : InlineBanner("Sign in to Copilot — copy your code, then open GitHub to enter it.", status) {
                    override fun paintComponent(g: Graphics) {
                        val g2 = g.create() as java.awt.Graphics2D
                        try {
                            g2.color = background
                            g2.fillRect(0, 0, width, height)
                            g2.color = borderColor
                            g2.fillRect(0, 0, width, 1)
                            g2.fillRect(0, height - 1, width, 1)
                        } finally {
                            g2.dispose()
                        }
                        paintChildren(g)
                    }
                }
            banner.showCloseButton(false)

            // Wrap banner + device code row
            val wrapper = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                add(banner, BorderLayout.CENTER)
                add(deviceCodeRow!!, BorderLayout.SOUTH)
            }

            currentBanner = banner
            add(wrapper, BorderLayout.CENTER)
            isVisible = true
            revalidate()
            repaint()
        }
    }

    /**
     * Hides the device code row (after auth completes or on fallback).
     */
    fun hideDeviceCode() {
        ApplicationManager.getApplication().invokeLater {
            deviceUrl = null
            dismiss()
        }
    }
}
