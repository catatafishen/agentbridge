package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.services.ChatWebServer
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.Timer

class ChatWebServerConfigurable(private val project: Project) :
    BoundConfigurable("Web Access"),
    SearchableConfigurable {

    override fun getId(): String = "com.github.catatafishen.agentbridge.web-access"

    private val settings get() = ChatWebServerSettings.getInstance(project)
    private val urlLabel = JBLabel("").apply { cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) }
    private val appQrPanel = QrCodePanel()
    private val certQrPanel = QrCodePanel()
    private val certQrRow = buildQrColumn(certQrPanel, "Install CA cert")
    private val appQrRow = buildQrColumn(appQrPanel, "Open on phone")
    private var startStopButton: JButton? = null
    private val copyUrlButton = JButton("Copy URL").also { btn ->
        btn.addActionListener {
            val url = getServerUrl()
            if (url.isNotEmpty()) copyToClipboard(url, btn)
        }
    }

    override fun createPanel() = panel {
        row {
            comment(
                "Serve the chat panel as a local web app accessible from any device on the same network " +
                    "(phone, tablet, etc.). Supports prompt sending, nudging, quick replies, and PWA push notifications."
            )
        }
        separator()
        row {
            checkBox("Start web server automatically when project opens")
                .bindSelected({ settings.isEnabled }, { settings.isEnabled = it })
        }
        row("Port:") {
            spinner(1024..65535, 1)
                .bindIntValue({ settings.port }, { settings.port = it })
        }
        row {
            checkBox("Use HTTPS (generates self-signed CA cert for device trust)")
                .bindSelected({ settings.isHttpsEnabled }, { settings.isHttpsEnabled = it })
        }
        separator()
        row {
            startStopButton = button(getStartStopLabel()) { toggleServer() }.component
        }
        row("URL:") {
            cell(urlLabel).align(AlignX.FILL)
            cell(copyUrlButton)
        }
        row {
            cell(appQrRow)
            cell(certQrRow)
        }
        onReset { updateUrlAndQr(); startStopButton?.text = getStartStopLabel() }
    }

    private fun buildQrColumn(qr: QrCodePanel, label: String): JBPanel<*> {
        val col = JBPanel<JBPanel<*>>(BorderLayout())
        val header = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 0))
        header.add(JBLabel(label))
        col.add(header, BorderLayout.NORTH)
        col.add(qr, BorderLayout.CENTER)
        return col
    }

    private fun toggleServer() {
        val ws = ChatWebServer.getInstance(project)
        if (ws.isRunning) {
            ws.stop(); refresh()
        } else {
            apply()
            startStopButton?.let { it.isEnabled = false; it.text = "Starting…" }
            Thread({
                try {
                    ws.start()
                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        JOptionPane.showMessageDialog(
                            null,
                            "Failed to start web server: ${e.message}",
                            "Chat Web Server Error", JOptionPane.ERROR_MESSAGE
                        )
                        refresh()
                    }
                    return@Thread
                }
                SwingUtilities.invokeLater(::refresh)
            }, "ChatWebServer-start").start()
        }
    }

    private fun refresh() {
        startStopButton?.let { it.isEnabled = true; it.text = getStartStopLabel() }
        updateUrlAndQr()
    }

    private fun getStartStopLabel(): String {
        val ws = ChatWebServer.getInstance(project)
        return if (ws.isRunning) "Stop Web Server" else "Start Web Server"
    }

    private fun updateUrlAndQr() {
        val ws = ChatWebServer.getInstance(project)
        val url = getServerUrl()
        if (url.isEmpty()) {
            urlLabel.text = "<html><i style='color:gray'>Not running</i></html>"
            appQrPanel.setUrl(null); certQrPanel.setUrl(null)
        } else {
            urlLabel.text = "<html><a href='$url'>$url</a></html>"
            appQrPanel.setUrl(url)
            if (isRunningHttps()) {
                val host = ChatWebServer.getLanIp() ?: "localhost"
                certQrPanel.setUrl("http://$host:${ws.httpCertPort}/cert.crt")
            } else {
                certQrPanel.setUrl(null)
            }
        }
        certQrRow.isVisible = isRunningHttps()
    }

    private fun getServerUrl(): String {
        val ws = ChatWebServer.getInstance(project)
        if (!ws.isRunning) return ""
        val protocol = if (ws.isHttps) "https" else "http"
        val host = ChatWebServer.getLanIp() ?: "localhost"
        return "$protocol://$host:${ws.port}"
    }

    private fun isRunningHttps(): Boolean {
        val ws = ChatWebServer.getInstance(project)
        return ws.isRunning && ws.isHttps
    }

    private fun copyToClipboard(text: String, feedbackButton: JButton) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        val orig = feedbackButton.text
        feedbackButton.text = "Copied!"
        Timer(2000) { feedbackButton.text = orig }.apply { isRepeats = false }.start()
    }
}
