package com.github.catatafishen.idemcpserver.settings;

import com.github.catatafishen.idemcpserver.McpHttpServer;
import com.github.catatafishen.ideagentforcopilot.services.CopilotSettings;
import com.github.catatafishen.ideagentforcopilot.settings.McpServerSettings;
import com.github.catatafishen.ideagentforcopilot.settings.TransportMode;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/**
 * Settings page: Settings → Tools → IDE Agent for Copilot → MCP Server → General.
 * Port, transport mode, auto-start, follow agent, restart and copy config buttons.
 */
public final class McpServerGeneralConfigurable implements Configurable {

    private static final Logger LOG = Logger.getInstance(McpServerGeneralConfigurable.class);

    private final Project project;
    private JSpinner portSpinner;
    private JComboBox<TransportMode> transportModeCombo;
    private JBCheckBox autoStartCheckbox;
    private JBCheckBox followModeCheckbox;

    public McpServerGeneralConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "General";
    }

    @Override
    public @Nullable JComponent createComponent() {
        McpServerSettings settings = McpServerSettings.getInstance(project);

        portSpinner = new JSpinner(new SpinnerNumberModel(
            settings.getPort(), 1024, 65535, 1));

        transportModeCombo = new JComboBox<>(TransportMode.values());
        transportModeCombo.setSelectedItem(settings.getTransportMode());
        transportModeCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof TransportMode mode) {
                    setText(mode.getDisplayName());
                }
                return this;
            }
        });

        autoStartCheckbox = new JBCheckBox("Start MCP server automatically when project opens",
            settings.isAutoStart());
        followModeCheckbox = new JBCheckBox("Follow Agent — open files and highlight regions as the agent reads or edits them",
            CopilotSettings.getFollowAgentFiles(project));

        JButton restartButton = new JButton("Restart Server");
        restartButton.setToolTipText("Stop and restart the MCP server with current settings");
        restartButton.addActionListener(e -> restartMcpServer(restartButton));

        JButton copyConfigButton = new JButton("Copy MCP Config");
        copyConfigButton.setToolTipText("Copy JSON config for Claude Desktop, Cursor, etc.");
        copyConfigButton.addActionListener(e -> copyMcpConfig(copyConfigButton));

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttonPanel.add(restartButton);
        buttonPanel.add(copyConfigButton);

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("MCP server port:", portSpinner)
            .addLabeledComponent("Transport mode:", transportModeCombo)
            .addComponent(autoStartCheckbox)
            .addComponent(followModeCheckbox)
            .addComponent(buttonPanel)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
    }

    @Override
    public boolean isModified() {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        if ((Integer) portSpinner.getValue() != settings.getPort()) return true;
        if (transportModeCombo.getSelectedItem() != settings.getTransportMode()) return true;
        if (autoStartCheckbox.isSelected() != settings.isAutoStart()) return true;
        return followModeCheckbox.isSelected() != CopilotSettings.getFollowAgentFiles(project);
    }

    @Override
    public void apply() {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        settings.setPort((Integer) portSpinner.getValue());
        settings.setTransportMode((TransportMode) transportModeCombo.getSelectedItem());
        settings.setAutoStart(autoStartCheckbox.isSelected());
        CopilotSettings.setFollowAgentFiles(project, followModeCheckbox.isSelected());
    }

    @Override
    public void reset() {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        portSpinner.setValue(settings.getPort());
        transportModeCombo.setSelectedItem(settings.getTransportMode());
        autoStartCheckbox.setSelected(settings.isAutoStart());
        followModeCheckbox.setSelected(CopilotSettings.getFollowAgentFiles(project));
    }

    private void restartMcpServer(JButton button) {
        button.setEnabled(false);
        button.setText("Restarting…");

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                McpHttpServer server = McpHttpServer.getInstance(project);
                server.stop();
                server.start();
                LOG.info("MCP server restarted via settings");
            } catch (Exception ex) {
                LOG.error("Failed to restart MCP server", ex);
            } finally {
                ApplicationManager.getApplication().invokeLater(() -> {
                    button.setText("Restart Server");
                    button.setEnabled(true);
                });
            }
        });
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
        button.setText("Copied!");
        Timer reset = new Timer(2000, e -> button.setText("Copy MCP Config"));
        reset.setRepeats(false);
        reset.start();
    }
}
