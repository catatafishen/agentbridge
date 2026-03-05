package com.github.catatafishen.idemcpserver.ui;

import com.github.catatafishen.idemcpserver.McpHttpServer;
import com.github.catatafishen.idemcpserver.McpToolFilter;
import com.github.catatafishen.idemcpserver.settings.McpServerSettings;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

/**
 * Tool window showing MCP server status with start/stop controls.
 */
public final class McpServerToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        Content content = ContentFactory.getInstance().createContent(
            new McpServerPanel(project), "", false);
        toolWindow.getContentManager().addContent(content);
    }

    private static final class McpServerPanel extends JPanel {
        private static final Logger LOG = Logger.getInstance(McpServerPanel.class);

        private final Project project;
        private final JBLabel statusLabel;
        private final JBLabel toolCountLabel;
        private final JButton startStopButton;
        private final JButton copyConfigButton;
        private final Timer refreshTimer;

        McpServerPanel(@NotNull Project project) {
            super(new BorderLayout());
            this.project = project;

            // Status area
            JPanel statusPanel = new JPanel();
            statusPanel.setLayout(new BoxLayout(statusPanel, BoxLayout.Y_AXIS));
            statusPanel.setBorder(JBUI.Borders.empty(12));

            statusLabel = new JBLabel("Server: stopped");
            statusLabel.setFont(statusLabel.getFont().deriveFont(14f));
            statusPanel.add(statusLabel);

            toolCountLabel = new JBLabel();
            toolCountLabel.setBorder(JBUI.Borders.emptyTop(4));
            updateToolCount();
            statusPanel.add(toolCountLabel);

            add(statusPanel, BorderLayout.NORTH);

            // Buttons
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            buttonPanel.setBorder(JBUI.Borders.empty(0, 12, 12, 12));

            startStopButton = new JButton("Start");
            startStopButton.addActionListener(e -> toggleServer());
            buttonPanel.add(startStopButton);

            copyConfigButton = new JButton("Copy MCP Config");
            copyConfigButton.setToolTipText("Copy JSON config for Claude Desktop, Cursor, etc.");
            copyConfigButton.addActionListener(e -> copyMcpConfig());
            copyConfigButton.setEnabled(false);
            buttonPanel.add(copyConfigButton);

            JButton settingsButton = new JButton("Settings…");
            settingsButton.addActionListener(e ->
                ShowSettingsUtil.getInstance().showSettingsDialog(
                    project, "IDE MCP Server"));
            buttonPanel.add(settingsButton);

            add(buttonPanel, BorderLayout.CENTER);

            // Refresh status periodically
            refreshTimer = new Timer(2000, e -> updateStatus());
            refreshTimer.start();
            updateStatus();
        }

        private void toggleServer() {
            McpHttpServer server = McpHttpServer.getInstance(project);
            if (server.isRunning()) {
                server.stop();
            } else {
                try {
                    server.start();
                } catch (Exception e) {
                    LOG.error("Failed to start MCP server", e);
                    statusLabel.setText("Error: " + e.getMessage());
                }
            }
            updateStatus();
        }

        private void updateStatus() {
            McpHttpServer server = McpHttpServer.getInstance(project);
            boolean running = server.isRunning();
            if (running) {
                int port = server.getPort();
                int conns = server.getActiveConnections();
                statusLabel.setText("Server: running on port " + port
                    + (conns > 0 ? " (" + conns + " active)" : ""));
            } else {
                McpServerSettings settings = McpServerSettings.getInstance(project);
                statusLabel.setText("Server: stopped (port " + settings.getPort() + ")");
            }
            startStopButton.setText(running ? "Stop" : "Start");
            copyConfigButton.setEnabled(running);
            updateToolCount();
        }

        private void updateToolCount() {
            McpServerSettings settings = McpServerSettings.getInstance(project);
            int enabled = McpToolFilter.getEnabledTools(settings).size();
            int total = McpToolFilter.getConfigurableTools().size();
            toolCountLabel.setText(enabled + " of " + total + " tools enabled");
        }

        private void copyMcpConfig() {
            McpServerSettings settings = McpServerSettings.getInstance(project);
            int port = settings.getPort();
            String config = "{\n"
                + "  \"mcpServers\": {\n"
                + "    \"ide-mcp-server\": {\n"
                + "      \"url\": \"http://127.0.0.1:" + port + "/mcp\"\n"
                + "    }\n"
                + "  }\n"
                + "}";
            Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(config), null);
            copyConfigButton.setText("Copied!");
            Timer reset = new Timer(2000, e -> copyConfigButton.setText("Copy MCP Config"));
            reset.setRepeats(false);
            reset.start();
        }
    }
}
