package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.github.catatafishen.ideagentforcopilot.services.ActiveAgentManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.concurrent.TimeUnit;

/**
 * Settings page: Settings → Tools → IDE Agent for Copilot → MCP → Server.
 * Configures MCP server port, transport mode, and auto-start.
 */
public final class McpServerConfigurable implements Configurable {

    private static final Logger LOG = Logger.getInstance(McpServerConfigurable.class);

    private final Project project;
    private JSpinner portSpinner;
    private ComboBox<TransportMode> transportModeCombo;
    private JBCheckBox autoStartCheckbox;
    private JBCheckBox followModeCheckbox;
    private JPanel mainPanel;

    public McpServerConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Nls
    @Override
    public String getDisplayName() {
        return "Server";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        McpServerSettings settings = McpServerSettings.getInstance(project);

        portSpinner = new JSpinner(new SpinnerNumberModel(settings.getPort(), 1024, 65535, 1));

        transportModeCombo = new ComboBox<>(TransportMode.values());
        transportModeCombo.setSelectedItem(settings.getTransportMode());
        transportModeCombo.setRenderer(SimpleListCellRenderer.create(
            (label, value, index) -> label.setText(value == null ? "" : value.getDisplayName())));

        autoStartCheckbox = new JBCheckBox("Start MCP server automatically when project opens",
            settings.isAutoStart());

        followModeCheckbox = new JBCheckBox(
            "Follow Agent — open files and highlight regions as the agent reads or edits them",
            ActiveAgentManager.getFollowAgentFiles(project));
        followModeCheckbox.setToolTipText(
            "Works independently of the connected agent — any external agent accessing "
                + "the MCP server will trigger follow-mode when this is enabled.");

        JButton restartButton = new JButton("Restart MCP Server");
        restartButton.setToolTipText("Stop and restart the MCP server to pick up tool registration changes");
        restartButton.addActionListener(e -> restartMcpServer(restartButton));

        JButton copyConfigButton = new JButton("Copy MCP Config");
        copyConfigButton.setToolTipText("Copy JSON config for Claude Desktop, Cursor, etc.");
        copyConfigButton.addActionListener(e -> copyMcpConfig(copyConfigButton));

        JPanel buttonRow = new JBPanel<>();
        buttonRow.setLayout(new BoxLayout(buttonRow, BoxLayout.X_AXIS));
        buttonRow.add(restartButton);
        buttonRow.add(Box.createHorizontalStrut(JBUI.scale(8)));
        buttonRow.add(copyConfigButton);
        buttonRow.add(Box.createHorizontalGlue());

        mainPanel = FormBuilder.createFormBuilder()
            .addComponent(new JBLabel(
                "<html>Configure the MCP (Model Context Protocol) server "
                    + "that exposes IDE tools to external agents.</html>"))
            .addSeparator()
            .addLabeledComponent("MCP server port:", portSpinner)
            .addLabeledComponent("Transport mode:", transportModeCombo)
            .addComponent(autoStartCheckbox)
            .addSeparator()
            .addComponent(followModeCheckbox)
            .addSeparator()
            .addComponent(buttonRow)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        mainPanel.setBorder(JBUI.Borders.empty(8));

        reset();
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        if ((Integer) portSpinner.getValue() != settings.getPort()) return true;
        if (transportModeCombo.getSelectedItem() != settings.getTransportMode()) return true;
        if (autoStartCheckbox.isSelected() != settings.isAutoStart()) return true;
        return followModeCheckbox.isSelected() != ActiveAgentManager.getFollowAgentFiles(project);
    }

    @Override
    public void apply() {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        settings.setPort((Integer) portSpinner.getValue());
        settings.setTransportMode((TransportMode) transportModeCombo.getSelectedItem());
        settings.setAutoStart(autoStartCheckbox.isSelected());
        ActiveAgentManager.setFollowAgentFiles(project, followModeCheckbox.isSelected());
    }

    @Override
    public void reset() {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        portSpinner.setValue(settings.getPort());
        transportModeCombo.setSelectedItem(settings.getTransportMode());
        autoStartCheckbox.setSelected(settings.isAutoStart());
        followModeCheckbox.setSelected(ActiveAgentManager.getFollowAgentFiles(project));
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        portSpinner = null;
        transportModeCombo = null;
        autoStartCheckbox = null;
        followModeCheckbox = null;
    }

    private void copyMcpConfig(JButton button) {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        int port = settings.getPort();
        TransportMode mode = settings.getTransportMode();
        String url = (mode == TransportMode.SSE)
            ? "http://127.0.0.1:" + port + "/sse"
            : "http://127.0.0.1:" + port + "/mcp";

        String config = "{\n"
            + "  \"mcpServers\": {\n"
            + "    \"ide-mcp-server\": {\n"
            + "      \"url\": \"" + url + "\"\n"
            + "    }\n"
            + "  }\n"
            + "}";
        Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new StringSelection(config), null);
        String original = button.getText();
        button.setText("Copied!");
        Timer reset = new Timer(2000, e -> button.setText(original));
        reset.setRepeats(false);
        reset.start();
    }

    /**
     * Restarts the MCP HTTP server by reflectively calling stop/start on McpHttpServer.
     * Uses reflection because McpHttpServer lives in the standalone-mcp module which
     * plugin-core cannot depend on directly.
     * <p>
     * Stop is called immediately, then start is scheduled 500 ms later on the same
     * executor (no raw Thread.sleep).
     */
    private void restartMcpServer(JButton button) {
        button.setEnabled(false);
        button.setText("Restarting...");

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Class<?> serverClass = Class.forName(
                    "com.github.catatafishen.idemcpserver.McpHttpServer");
                Object server = PlatformApiCompat.getServiceByRawClass(project, serverClass);
                if (server == null) {
                    String msg = "MCP HTTP Server service not found. Is the IDE MCP Server plugin installed?";
                    LOG.warn(msg);
                    showRestartError(button, msg);
                    return;
                }
                serverClass.getMethod("stop").invoke(server);

                // Schedule start 500 ms later without blocking a pooled thread
                AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
                    try {
                        serverClass.getMethod("start").invoke(server);
                        LOG.info("MCP server restarted via settings");
                    } catch (Exception ex) {
                        LOG.error("Failed to start MCP server after restart", ex);
                        showRestartError(button, "Failed to start: " + ex.getMessage());
                        return;
                    }
                    ApplicationManager.getApplication().invokeLater(() -> resetRestartButton(button));
                }, 500, TimeUnit.MILLISECONDS);

            } catch (ClassNotFoundException ex) {
                String msg = "MCP HTTP Server plugin is not installed. "
                    + "Install the 'IDE MCP Server' plugin to use the HTTP server.";
                LOG.info(msg);
                showRestartError(button, msg);
            } catch (Exception ex) {
                LOG.error("Failed to restart MCP server", ex);
                showRestartError(button, "Failed to restart: " + ex.getMessage());
            }
        });
    }

    private void showRestartError(JButton button, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            resetRestartButton(button);
            Messages.showErrorDialog(mainPanel, message, "MCP Server Restart Failed");
        });
    }

    private void resetRestartButton(JButton button) {
        button.setText("Restart MCP Server");
        button.setEnabled(true);
        button.setToolTipText("Stop and restart the MCP server to pick up tool registration changes");
    }
}
