package com.github.catatafishen.agentbridge.settings

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.Timer

class McpGroupConfigurable(private val project: Project) :
    BoundConfigurable("MCP"),
    SearchableConfigurable {

    override fun getId(): String = ID

    private val settings get() = McpServerSettings.getInstance(project)

    override fun createPanel() = panel {
        row {
            comment(
                "Configure the <b>MCP server</b> that exposes IDE tools to agents, " +
                    "and manage which tools are available to them."
            )
        }
        separator()
        row("MCP server port:") {
            spinner(1024..65535, 1)
                .bindIntValue({ settings.port }, { settings.port = it })
        }
        row("Transport mode:") {
            comboBox(TransportMode.entries.toList())
                .applyToComponent {
                    renderer = SimpleListCellRenderer.create<TransportMode>("") { it.displayName }
                }
                .bindItem({ settings.transportMode }, {
                    settings.transportMode = it ?: TransportMode.STREAMABLE_HTTP
                })
        }
        row {
            checkBox("Start MCP server automatically when project opens")
                .bindSelected({ settings.isAutoStart }, { settings.isAutoStart = it })
        }
        separator()
        row {
            checkBox("Enable debug logging — log all ACP JSON-RPC messages at INFO level")
                .comment(
                    "When enabled, every ACP request and response is logged to the IDE log " +
                        "(Help → Show Log)."
                )
                .bindSelected(
                    { settings.isDebugLoggingEnabled },
                    { settings.isDebugLoggingEnabled = it }
                )
        }
        separator()
        row {
            button("Restart MCP Server") { e ->
                val btn = e.source as JButton
                btn.icon = AllIcons.Actions.Restart
                restartMcpServer(btn)
            }.applyToComponent {
                icon = AllIcons.Actions.Restart
                toolTipText = "Stop and restart the MCP server to pick up tool registration changes"
            }
            button("Copy MCP Config") { e ->
                copyMcpConfig(e.source as JButton)
            }.applyToComponent {
                icon = AllIcons.Actions.Copy
                toolTipText = "Copy JSON config for Claude Desktop, Cursor, etc."
            }
        }
    }

    private fun copyMcpConfig(button: JButton) {
        val port = settings.port
        val mode = settings.transportMode
        val url = if (mode == TransportMode.SSE)
            "http://127.0.0.1:$port/sse" else "http://127.0.0.1:$port/mcp"
        val config = """
            {
              "mcpServers": {
                "ide-mcp-server": {
                  "url": "$url"
                }
              }
            }
        """.trimIndent()
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(config), null)
        val original = button.text
        button.text = "Copied!"
        Timer(2000) { button.text = original }.apply { isRepeats = false }.start()
    }

    private fun restartMcpServer(button: JButton) {
        button.isEnabled = false
        button.text = "Restarting..."
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val serverClass = Class.forName(
                    "com.github.catatafishen.idemcpserver.McpHttpServer"
                )
                val server = PlatformApiCompat.getServiceByRawClass(project, serverClass)
                if (server == null) {
                    val msg =
                        "MCP HTTP Server service not found. Is the IDE MCP Server plugin installed?"
                    LOG.warn(msg); showRestartError(button, msg); return@executeOnPooledThread
                }
                serverClass.getMethod("stop").invoke(server)
                AppExecutorUtil.getAppScheduledExecutorService().schedule({
                    try {
                        serverClass.getMethod("start").invoke(server)
                        LOG.info("MCP server restarted via settings")
                    } catch (ex: Exception) {
                        LOG.error("Failed to start MCP server after restart", ex)
                        showRestartError(button, "Failed to start: ${ex.message}")
                        return@schedule
                    }
                    ApplicationManager.getApplication().invokeLater { resetRestartButton(button) }
                }, 500, TimeUnit.MILLISECONDS)
            } catch (_: ClassNotFoundException) {
                val msg = "MCP HTTP Server plugin is not installed. " +
                    "Install the 'IDE MCP Server' plugin to use the HTTP server."
                LOG.info(msg); showRestartError(button, msg)
            } catch (ex: Exception) {
                LOG.error("Failed to restart MCP server", ex)
                showRestartError(button, "Failed to restart: ${ex.message}")
            }
        }
    }

    private fun showRestartError(button: JButton, message: String) {
        ApplicationManager.getApplication().invokeLater {
            resetRestartButton(button)
            Messages.showErrorDialog(button, message, "MCP Server Restart Failed")
        }
    }

    private fun resetRestartButton(button: JButton) {
        button.text = "Restart MCP Server"
        button.isEnabled = true
        button.toolTipText =
            "Stop and restart the MCP server to pick up tool registration changes"
    }

    companion object {
        const val ID = "com.github.catatafishen.agentbridge.mcp"
        private val LOG = Logger.getInstance(McpGroupConfigurable::class.java)
    }
}
