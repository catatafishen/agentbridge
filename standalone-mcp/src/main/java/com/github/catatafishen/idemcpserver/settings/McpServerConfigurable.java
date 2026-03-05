package com.github.catatafishen.idemcpserver.settings;

import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.github.catatafishen.idemcpserver.McpToolFilter;
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

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings page: Settings → Tools → IDE MCP Server.
 * Port number, auto-start toggle, and tool enable/disable checkboxes.
 */
public final class McpServerConfigurable implements Configurable {

    private final Project project;
    private JSpinner portSpinner;
    private JBCheckBox autoStartCheckbox;
    private final Map<String, JBCheckBox> toolCheckboxes = new LinkedHashMap<>();

    public McpServerConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Nls
    @Override
    public String getDisplayName() {
        return "IDE MCP Server";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        McpServerSettings settings = McpServerSettings.getInstance(project);

        portSpinner = new JSpinner(new SpinnerNumberModel(
            settings.getPort(), 1024, 65535, 1));
        autoStartCheckbox = new JBCheckBox("Start server automatically when project opens",
            settings.isAutoStart());

        // Tool list grouped by category
        JPanel toolsPanel = new JPanel();
        toolsPanel.setLayout(new BoxLayout(toolsPanel, BoxLayout.Y_AXIS));

        List<ToolRegistry.ToolEntry> tools = McpToolFilter.getConfigurableTools();
        ToolRegistry.Category currentCategory = null;

        for (ToolRegistry.ToolEntry tool : tools) {
            if (tool.category != currentCategory) {
                currentCategory = tool.category;
                JBLabel categoryLabel = new JBLabel(currentCategory.displayName);
                categoryLabel.setFont(categoryLabel.getFont().deriveFont(java.awt.Font.BOLD));
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
        mainPanel.add(FormBuilder.createFormBuilder()
            .addLabeledComponent("Port:", portSpinner)
            .addComponent(autoStartCheckbox)
            .getPanel(), BorderLayout.NORTH);
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
}
