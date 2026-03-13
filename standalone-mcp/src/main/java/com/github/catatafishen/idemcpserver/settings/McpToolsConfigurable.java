package com.github.catatafishen.idemcpserver.settings;

import com.github.catatafishen.ideagentforcopilot.services.ToolDefinition;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.github.catatafishen.ideagentforcopilot.settings.McpServerSettings;
import com.github.catatafishen.ideagentforcopilot.settings.McpToolFilter;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings page: Settings → Tools → IDE Agent for Copilot → MCP Server → Tools.
 * Enable/disable individual MCP tools by category.
 */
public final class McpToolsConfigurable implements Configurable {

    private final Project project;
    private final Map<String, JBCheckBox> toolCheckboxes = new LinkedHashMap<>();

    public McpToolsConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Tools";
    }

    @Override
    public @NotNull JComponent createComponent() {
        McpServerSettings settings = McpServerSettings.getInstance(project);

        JPanel toolsPanel = new JPanel();
        toolsPanel.setLayout(new BoxLayout(toolsPanel, BoxLayout.Y_AXIS));

        List<ToolDefinition> tools = McpToolFilter.getConfigurableTools(project);
        ToolRegistry.Category currentCategory = null;

        for (ToolDefinition tool : tools) {
            if (tool.category() != currentCategory) {
                currentCategory = tool.category();
                JBLabel categoryLabel = new JBLabel(currentCategory.displayName);
                categoryLabel.setFont(categoryLabel.getFont().deriveFont(Font.BOLD));
                categoryLabel.setBorder(JBUI.Borders.empty(8, 0, 4, 0));
                toolsPanel.add(categoryLabel);
            }

            JBCheckBox cb = new JBCheckBox(tool.displayName(), settings.isToolEnabled(tool.id()));
            cb.setToolTipText(tool.description());
            cb.setBorder(JBUI.Borders.empty(1, 16, 1, 0));
            toolCheckboxes.put(tool.id(), cb);
            toolsPanel.add(cb);
        }

        toolsPanel.add(Box.createVerticalGlue());

        JBScrollPane scrollPane = new JBScrollPane(toolsPanel);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Enabled Tools"));

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.add(scrollPane, BorderLayout.CENTER);
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        for (Map.Entry<String, JBCheckBox> entry : toolCheckboxes.entrySet()) {
            if (entry.getValue().isSelected() != settings.isToolEnabled(entry.getKey())) return true;
        }
        return false;
    }

    @Override
    public void apply() {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        for (Map.Entry<String, JBCheckBox> entry : toolCheckboxes.entrySet()) {
            settings.setToolEnabled(entry.getKey(), entry.getValue().isSelected());
        }
    }

    @Override
    public void reset() {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        for (Map.Entry<String, JBCheckBox> entry : toolCheckboxes.entrySet()) {
            entry.getValue().setSelected(settings.isToolEnabled(entry.getKey()));
        }
    }
}
