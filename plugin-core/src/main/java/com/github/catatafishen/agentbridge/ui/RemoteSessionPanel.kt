package com.github.catatafishen.agentbridge.ui

import com.github.catatafishen.agentbridge.settings.QrCodePanel
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Non-modal dialog that displays the Copilot remote session QR code and URL once the CLI
 * emits the remote control link to stderr. The user can scan the QR code with GitHub mobile
 * or copy/open the URL to control the session from GitHub web.
 *
 * Show via [show] — non-blocking; the user can keep working while this is open.
 */
class RemoteSessionPanel(project: Project, private val url: String) : DialogWrapper(project, false) {

    init {
        title = "Copilot Remote Session"
        isModal = false
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(12, 16)
        }

        val qrCode = QrCodePanel().apply {
            setUrl(url)
            alignmentX = java.awt.Component.CENTER_ALIGNMENT
            preferredSize = Dimension(JBUI.scale(200), JBUI.scale(200))
            minimumSize = Dimension(JBUI.scale(200), JBUI.scale(200))
        }
        panel.add(qrCode)
        panel.add(Box.createVerticalStrut(JBUI.scale(12)))

        val urlLabel = JBLabel(url).apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            font = JBUI.Fonts.smallFont()
            alignmentX = java.awt.Component.CENTER_ALIGNMENT
        }
        panel.add(urlLabel)
        panel.add(Box.createVerticalStrut(JBUI.scale(12)))

        val buttonRow = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(8), 0)).apply {
            isOpaque = false
            alignmentX = java.awt.Component.CENTER_ALIGNMENT
        }

        val copyButton = JButton("Copy link").apply {
            addActionListener {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
            }
        }

        val openButton = JButton("Open in browser").apply {
            isEnabled = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
            addActionListener {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(URI(url))
                }
            }
        }

        buttonRow.add(copyButton)
        buttonRow.add(openButton)
        panel.add(buttonRow)

        val wrapper = JPanel(BorderLayout()).apply { isOpaque = false }
        wrapper.add(panel, BorderLayout.NORTH)
        return wrapper
    }

    override fun createActions() = arrayOf(okAction)
}
