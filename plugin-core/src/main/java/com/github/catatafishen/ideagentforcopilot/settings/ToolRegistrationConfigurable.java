package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings page: Settings → Tools → IDE Agent for Copilot → Tool Registration.
 * MCP server config (port, transport mode, auto-start) and tool enable/disable checkboxes.
 */
public final class ToolRegistrationConfigurable implements Configurable {

    private static final Logger LOG = Logger.getInstance(ToolRegistrationConfigurable.class);

    private final Project project;
    private JSpinner portSpinner;
    private JComboBox<TransportMode> transportModeCombo;
    private JBCheckBox autoStartCheckbox;
    private JSpinner bridgePortSpinner;
    private JBCheckBox bridgeAutoStartCheckbox;
    private final Map<String, JBCheckBox> toolCheckboxes = new LinkedHashMap<>();

    public ToolRegistrationConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Nls
    @Override
    public String getDisplayName() {
        return "MCP";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
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

        bridgePortSpinner = new JSpinner(new SpinnerNumberModel(
            settings.getBridgePort(), 0, 65535, 1));
        bridgePortSpinner.setToolTipText("0 = auto-assign random port");
        bridgeAutoStartCheckbox = new JBCheckBox("Start PSI Bridge automatically when project opens",
            settings.isBridgeAutoStart());

        JButton restartButton = new JButton("Restart MCP Server");
        restartButton.setToolTipText("Stop and restart the MCP server to pick up tool registration changes");
        restartButton.addActionListener(e -> restartMcpServer(restartButton));

        JButton copyConfigButton = new JButton("Copy MCP Config");
        copyConfigButton.setToolTipText("Copy JSON config for Claude Desktop, Cursor, etc.");
        copyConfigButton.addActionListener(e -> copyMcpConfig(copyConfigButton));

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttonRow.add(restartButton);
        buttonRow.add(copyConfigButton);

        JPanel serverPanel = FormBuilder.createFormBuilder()
            .addComponent(new JBLabel(
                "<html>Configure the MCP (Model Context Protocol) server and PSI Bridge "
                    + "that expose IDE tools to external agents.</html>"))
            .addSeparator()
            .addLabeledComponent("MCP server port:", portSpinner)
            .addLabeledComponent("Transport mode:", transportModeCombo)
            .addComponent(autoStartCheckbox)
            .addSeparator()
            .addLabeledComponent("PSI Bridge port (0 = auto):", bridgePortSpinner)
            .addComponent(bridgeAutoStartCheckbox)
            .addSeparator()
            .addComponent(buttonRow)
            .getPanel();

        JPanel toolsPanel = new JPanel();
        toolsPanel.setLayout(new BoxLayout(toolsPanel, BoxLayout.Y_AXIS));

        List<ToolRegistry.ToolEntry> tools = McpToolFilter.getConfigurableTools();
        ToolRegistry.Category currentCategory = null;

        for (ToolRegistry.ToolEntry tool : tools) {
            if (tool.category != currentCategory) {
                currentCategory = tool.category;
                JBLabel categoryLabel = new JBLabel(currentCategory.displayName);
                categoryLabel.setFont(categoryLabel.getFont().deriveFont(Font.BOLD));
                categoryLabel.setBorder(JBUI.Borders.empty(8, 0, 4, 0));
                toolsPanel.add(categoryLabel);
            }

            JBCheckBox cb = new JBCheckBox(tool.displayName, settings.isToolEnabled(tool.id));
            cb.setToolTipText(tool.description);
            cb.setBorder(JBUI.Borders.empty(1, 16, 1, 0));
            toolCheckboxes.put(tool.id, cb);
            toolsPanel.add(cb);
        }

        toolsPanel.add(Box.createVerticalGlue());

        JBScrollPane scrollPane = new JBScrollPane(toolsPanel);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Enabled Tools"));
        scrollPane.setPreferredSize(JBUI.size(400, 300));

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.add(serverPanel, BorderLayout.NORTH);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        return mainPanel;
    }

    @Override
    public boolean isModified() {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        if ((Integer) portSpinner.getValue() != settings.getPort()) return true;
        if (transportModeCombo.getSelectedItem() != settings.getTransportMode()) return true;
        if (autoStartCheckbox.isSelected() != settings.isAutoStart()) return true;
        if ((Integer) bridgePortSpinner.getValue() != settings.getBridgePort()) return true;
        if (bridgeAutoStartCheckbox.isSelected() != settings.isBridgeAutoStart()) return true;
        for (Map.Entry<String, JBCheckBox> entry : toolCheckboxes.entrySet()) {
            if (entry.getValue().isSelected() != settings.isToolEnabled(entry.getKey())) return true;
        }
        return false;
    }

    @Override
    public void apply() {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        settings.setPort((Integer) portSpinner.getValue());
        settings.setTransportMode((TransportMode) transportModeCombo.getSelectedItem());
        settings.setAutoStart(autoStartCheckbox.isSelected());
        settings.setBridgePort((Integer) bridgePortSpinner.getValue());
        settings.setBridgeAutoStart(bridgeAutoStartCheckbox.isSelected());
        for (Map.Entry<String, JBCheckBox> entry : toolCheckboxes.entrySet()) {
            settings.setToolEnabled(entry.getKey(), entry.getValue().isSelected());
        }
    }

    @Override
    public void reset() {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        portSpinner.setValue(settings.getPort());
        transportModeCombo.setSelectedItem(settings.getTransportMode());
        autoStartCheckbox.setSelected(settings.isAutoStart());
        bridgePortSpinner.setValue(settings.getBridgePort());
        bridgeAutoStartCheckbox.setSelected(settings.isBridgeAutoStart());
        for (Map.Entry<String, JBCheckBox> entry : toolCheckboxes.entrySet()) {
            entry.getValue().setSelected(settings.isToolEnabled(entry.getKey()));
        }
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
     */
    private void restartMcpServer(JButton button) {
        button.setEnabled(false);
        button.setText("Restarting...");

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String errorMessage = null;
            try {
                Class<?> serverClass = Class.forName(
                    "com.github.catatafishen.idemcpserver.McpHttpServer");
                Object server = PlatformApiCompat.getServiceByRawClass(project, serverClass);
                if (server == null) {
                    errorMessage = "MCP HTTP Server service not found. Is the IDE MCP Server plugin installed?";
                    LOG.warn(errorMessage);
                    return;
                }
                serverClass.getMethod("stop").invoke(server);
                Thread.sleep(500);
                serverClass.getMethod("start").invoke(server);
                LOG.info("MCP server restarted via settings");
            } catch (ClassNotFoundException ex) {
                errorMessage = "MCP HTTP Server plugin is not installed. "
                    + "Install the 'IDE MCP Server' plugin to use the HTTP server.";
                LOG.info(errorMessage);
            } catch (Exception ex) {
                errorMessage = "Failed to restart: " + ex.getMessage();
                LOG.error("Failed to restart MCP server", ex);
            } finally {
                String finalError = errorMessage;
                ApplicationManager.getApplication().invokeLater(() -> {
                    button.setText("Restart MCP Server");
                    button.setEnabled(true);
                    if (finalError != null) {
                        button.setToolTipText(finalError);
                    } else {
                        button.setToolTipText("Stop and restart the MCP server to pick up tool registration changes");
                    }
                });
            }
        });
    }
}
