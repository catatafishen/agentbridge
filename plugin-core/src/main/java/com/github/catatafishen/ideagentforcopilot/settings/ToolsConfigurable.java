package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.services.ToolDefinition;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBPanel;
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
 * Settings page: Settings → Tools → IDE Agent for Copilot → MCP → Tools.
 * Enable or disable individual MCP tools exposed to agents.
 */
public final class ToolsConfigurable implements Configurable {

    private final Project project;
    private final Map<String, JBCheckBox> toolCheckboxes = new LinkedHashMap<>();

    public ToolsConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Nls
    @Override
    public String getDisplayName() {
        return "Tools";
    }

    @NotNull
    @Override
    public JComponent createComponent() {
        McpServerSettings settings = McpServerSettings.getInstance(project);

        JBPanel<?> toolsPanel = new JBPanel<>();
        toolsPanel.setLayout(new BoxLayout(toolsPanel, BoxLayout.Y_AXIS));

        // Enable / Disable All buttons
        JButton enableAllBtn = new JButton("Enable All");
        JButton disableAllBtn = new JButton("Disable All");
        enableAllBtn.addActionListener(e -> toolCheckboxes.values().forEach(cb -> cb.setSelected(true)));
        disableAllBtn.addActionListener(e -> toolCheckboxes.values().forEach(cb -> cb.setSelected(false)));

        JBPanel<?> topRow = new JBPanel<>();
        topRow.setLayout(new BoxLayout(topRow, BoxLayout.X_AXIS));
        topRow.setBorder(JBUI.Borders.empty(0, 0, 4, 0));
        topRow.add(enableAllBtn);
        topRow.add(Box.createHorizontalStrut(JBUI.scale(8)));
        topRow.add(disableAllBtn);
        topRow.add(Box.createHorizontalGlue());
        topRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        toolsPanel.add(topRow);

        List<ToolDefinition> tools = McpToolFilter.getConfigurableTools(project);
        ToolRegistry.Category currentCategory = null;

        for (ToolDefinition tool : tools) {
            if (tool.category() != currentCategory) {
                currentCategory = tool.category();
                TitledSeparator sep = new TitledSeparator(currentCategory.displayName);
                sep.setBorder(JBUI.Borders.empty(8, 0, 4, 0));
                sep.setAlignmentX(Component.LEFT_ALIGNMENT);
                toolsPanel.add(sep);
            }

            JBCheckBox cb = new JBCheckBox(tool.displayName(), settings.isToolEnabled(tool.id()));
            cb.setToolTipText(tool.description());
            cb.setBorder(JBUI.Borders.empty(1, 16, 1, 0));
            cb.setAlignmentX(Component.LEFT_ALIGNMENT);
            toolCheckboxes.put(tool.id(), cb);
            toolsPanel.add(cb);
        }

        toolsPanel.add(Box.createVerticalGlue());

        JBScrollPane scrollPane = new JBScrollPane(toolsPanel);

        JBPanel<?> wrapper = new JBPanel<>(new BorderLayout());
        wrapper.setBorder(JBUI.Borders.empty(8));
        wrapper.add(scrollPane, BorderLayout.CENTER);
        return wrapper;
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
