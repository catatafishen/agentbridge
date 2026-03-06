package com.github.catatafishen.ideagentforcopilot.settings;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings page: Settings → Tools → IDE Agent for Copilot → Tool Registration.
 * MCP server config (port, auto-start) and tool enable/disable checkboxes.
 */
public final class ToolRegistrationConfigurable implements Configurable {

    private static final Logger LOG = Logger.getInstance(ToolRegistrationConfigurable.class);

    private final Project project;
    private JSpinner portSpinner;
    private JBCheckBox autoStartCheckbox;
    private final Map<String, JBCheckBox> toolCheckboxes = new LinkedHashMap<>();

    public ToolRegistrationConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Nls
    @Override
    public String getDisplayName() {
        return "Tool Registration";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        McpServerSettings settings = McpServerSettings.getInstance(project);

        portSpinner = new JSpinner(new SpinnerNumberModel(
            settings.getPort(), 1024, 65535, 1));
        autoStartCheckbox = new JBCheckBox("Start MCP server automatically when project opens",
            settings.isAutoStart());

        JButton restartButton = new JButton("Restart MCP Server");
        restartButton.setToolTipText("Stop and restart the MCP server to pick up tool registration changes");
        restartButton.addActionListener(e -> restartMcpServer(restartButton));

        JPanel serverPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("MCP server port:", portSpinner)
            .addComponent(autoStartCheckbox)
            .addComponent(restartButton)
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
        if (autoStartCheckbox.isSelected() != settings.isAutoStart()) return true;
        for (Map.Entry<String, JBCheckBox> entry : toolCheckboxes.entrySet()) {
            if (entry.getValue().isSelected() != settings.isToolEnabled(entry.getKey())) return true;
        }
        return false;
    }

    @Override
    public void apply() {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        settings.setPort((Integer) portSpinner.getValue());
        settings.setAutoStart(autoStartCheckbox.isSelected());
        for (Map.Entry<String, JBCheckBox> entry : toolCheckboxes.entrySet()) {
            settings.setToolEnabled(entry.getKey(), entry.getValue().isSelected());
        }
    }

    @Override
    public void reset() {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        portSpinner.setValue(settings.getPort());
        autoStartCheckbox.setSelected(settings.isAutoStart());
        for (Map.Entry<String, JBCheckBox> entry : toolCheckboxes.entrySet()) {
            entry.getValue().setSelected(settings.isToolEnabled(entry.getKey()));
        }
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
            try {
                Class<?> serverClass = Class.forName(
                    "com.github.catatafishen.idemcpserver.McpHttpServer");
                // Cast needed: Class.forName returns Class<?> but getService requires Class<T>
                @SuppressWarnings("unchecked")
                Class<Object> castedClass = (Class<Object>) serverClass;
                Object server = project.getService(castedClass);
                if (server == null) {
                    LOG.warn("McpHttpServer service not found");
                    return;
                }
                serverClass.getMethod("stop").invoke(server);
                serverClass.getMethod("start").invoke(server);
                LOG.info("MCP server restarted via settings");
            } catch (Exception ex) {
                LOG.error("Failed to restart MCP server", ex);
            } finally {
                ApplicationManager.getApplication().invokeLater(() -> {
                    button.setText("Restart MCP Server");
                    button.setEnabled(true);
                });
            }
        });
    }
}
