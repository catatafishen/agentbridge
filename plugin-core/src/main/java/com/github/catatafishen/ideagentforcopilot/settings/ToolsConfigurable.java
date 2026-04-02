package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.services.ToolDefinition;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.github.catatafishen.ideagentforcopilot.ui.ThemeColor;
import com.github.catatafishen.ideagentforcopilot.ui.ToolKindColors;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Settings page: Settings → Tools → AgentBridge → MCP → Tools.
 * Enable or disable individual MCP tools exposed to agents, and customize per-kind colors.
 */
public final class ToolsConfigurable implements Configurable {

    private final Project project;
    private final Map<String, JBCheckBox> toolCheckboxes = new LinkedHashMap<>();
    /**
     * Per-category lists of checkboxes, used for section-level enable/disable.
     */
    private final Map<ToolRegistry.Category, List<JBCheckBox>> categoryCheckboxes = new LinkedHashMap<>();

    private @Nullable ThemeColorComboBox readColorCombo;
    private @Nullable ThemeColorComboBox editColorCombo;
    private @Nullable ThemeColorComboBox executeColorCombo;

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
        toolsPanel.setBorder(JBUI.Borders.empty(8));

        // Global enable/disable row at top
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

        // Tool list grouped by category
        List<ToolDefinition> tools = McpToolFilter.getConfigurableTools(project);
        ToolRegistry.Category currentCategory = null;

        for (ToolDefinition tool : tools) {
            if (tool.category() != currentCategory) {
                currentCategory = tool.category();
                final ToolRegistry.Category cat = currentCategory;
                toolsPanel.add(buildCategoryHeader(cat));
            }

            Color kindColor = kindColorFor(tool, settings);
            JBPanel<?> toolRow = new JBPanel<>();
            toolRow.setLayout(new BoxLayout(toolRow, BoxLayout.X_AXIS));
            toolRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            toolRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, toolRow.getPreferredSize().height));

            // Colored kind dot
            JBLabel dot = new JBLabel("● ");
            dot.setForeground(kindColor);
            dot.setBorder(JBUI.Borders.empty(1, 16, 0, 0));
            toolRow.add(dot);

            JBCheckBox cb = new JBCheckBox(tool.displayName(), settings.isToolEnabled(tool.id()));
            cb.setBorder(JBUI.Borders.empty(1, 0, 0, 0));
            toolCheckboxes.put(tool.id(), cb);
            categoryCheckboxes.computeIfAbsent(tool.category(), k -> new ArrayList<>()).add(cb);
            toolRow.add(cb);
            toolRow.add(Box.createHorizontalGlue());

            toolsPanel.add(toolRow);

            String desc = tool.description();
            if (!desc.isBlank()) {
                // Use HTML so the label wraps when the panel is width-constrained.
                // Preferred width is set to 1 so this label does not drive the panel's
                // preferred width — the wrapping container determines the actual width.
                JBLabel descLabel = new JBLabel("<html>" + desc + "</html>");
                descLabel.setFont(descLabel.getFont().deriveFont((float) (JBUI.Fonts.label().getSize() - 1)));
                descLabel.setForeground(UIUtil.getContextHelpForeground());
                descLabel.setBorder(JBUI.Borders.empty(0, 36, 3, 0));
                descLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                descLabel.setPreferredSize(new Dimension(1, descLabel.getPreferredSize().height));
                descLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, descLabel.getPreferredSize().height));
                toolsPanel.add(descLabel);
            }
        }

        // Color picker section
        toolsPanel.add(buildColorPickerSection(settings));
        toolsPanel.add(Box.createVerticalGlue());

        // Wrap in a BorderLayout panel so the scroll pane forces the content width.
        // toolsPanel in NORTH gets the full viewport width, preventing long labels from
        // widening the panel and pushing the Enable/Disable buttons off-screen.
        JBPanel<?> scrollContent = new JBPanel<>(new BorderLayout());
        scrollContent.add(toolsPanel, BorderLayout.NORTH);

        JBScrollPane scrollPane = new JBScrollPane(scrollContent);
        scrollPane.setBorder(JBUI.Borders.empty());
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        JBPanel<?> wrapper = new JBPanel<>(new BorderLayout());
        wrapper.add(scrollPane, BorderLayout.CENTER);
        return wrapper;
    }

    /**
     * Builds a category header row: separator on the left, Enable/Disable buttons on the right.
     */
    private JComponent buildCategoryHeader(ToolRegistry.Category category) {
        JBPanel<?> row = new JBPanel<>(new BorderLayout());
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(JBUI.Borders.empty(8, 0, 2, 0));

        TitledSeparator sep = new TitledSeparator(category.displayName);
        row.add(sep, BorderLayout.CENTER);

        JButton sectionEnableBtn = new JButton("Enable");
        JButton sectionDisableBtn = new JButton("Disable");
        sectionEnableBtn.setFont(JBUI.Fonts.smallFont());
        sectionDisableBtn.setFont(JBUI.Fonts.smallFont());

        sectionEnableBtn.addActionListener(e -> {
            List<JBCheckBox> cbs = categoryCheckboxes.get(category);
            if (cbs != null) cbs.forEach(cb -> cb.setSelected(true));
        });
        sectionDisableBtn.addActionListener(e -> {
            List<JBCheckBox> cbs = categoryCheckboxes.get(category);
            if (cbs != null) cbs.forEach(cb -> cb.setSelected(false));
        });

        JBPanel<?> btnRow = new JBPanel<>();
        btnRow.setLayout(new BoxLayout(btnRow, BoxLayout.X_AXIS));
        btnRow.add(sectionEnableBtn);
        btnRow.add(Box.createHorizontalStrut(JBUI.scale(4)));
        btnRow.add(sectionDisableBtn);
        row.add(btnRow, BorderLayout.EAST);

        return row;
    }

    /**
     * Builds the "Tool Kind Colors" section with a {@link ThemeColorComboBox} for each kind.
     */
    private JComponent buildColorPickerSection(McpServerSettings settings) {
        JBPanel<?> section = new JBPanel<>();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);

        TitledSeparator colorSep = new TitledSeparator("Tool Kind Colors");
        colorSep.setBorder(JBUI.Borders.empty(16, 0, 4, 0));
        colorSep.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(colorSep);

        JBLabel hint = new JBLabel(
            "<html>Customize the accent color used for each tool kind in this settings panel, "
                + "tool-chip labels in the chat view, and permission dropdowns. "
                + "Colors adapt automatically when the IDE theme changes.</html>");
        hint.setFont(JBUI.Fonts.smallFont());
        hint.setForeground(UIUtil.getContextHelpForeground());
        hint.setBorder(JBUI.Borders.empty(0, 0, 8, 0));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setPreferredSize(new Dimension(1, hint.getPreferredSize().height));
        hint.setMaximumSize(new Dimension(Integer.MAX_VALUE, hint.getPreferredSize().height));
        section.add(hint);

        readColorCombo = new ThemeColorComboBox();
        editColorCombo = new ThemeColorComboBox();
        executeColorCombo = new ThemeColorComboBox();

        readColorCombo.setSelectedThemeColor(ThemeColor.fromKey(settings.getKindReadColorKey()));
        editColorCombo.setSelectedThemeColor(ThemeColor.fromKey(settings.getKindEditColorKey()));
        executeColorCombo.setSelectedThemeColor(ThemeColor.fromKey(settings.getKindExecuteColorKey()));

        section.add(colorRow("Read & Navigate", readColorCombo));
        section.add(colorRow("Edit & Refactor", editColorCombo));
        section.add(colorRow("Run & Execute", executeColorCombo));

        return section;
    }

    private static JComponent colorRow(String label, ThemeColorComboBox combo) {
        JBPanel<?> row = new JBPanel<>();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(JBUI.Borders.empty(2, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));

        JBLabel lbl = new JBLabel(label);
        lbl.setPreferredSize(new Dimension(JBUI.scale(140), lbl.getPreferredSize().height));
        lbl.setMaximumSize(new Dimension(JBUI.scale(140), lbl.getPreferredSize().height));
        row.add(lbl);
        combo.setMaximumSize(new Dimension(JBUI.scale(180), combo.getPreferredSize().height));
        row.add(combo);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    /**
     * Returns the kind accent color for [tool], honoring settings overrides.
     */
    private static Color kindColorFor(ToolDefinition tool, McpServerSettings settings) {
        if (tool.isReadOnly()) return ToolKindColors.readColor(settings);
        return switch (tool.kind()) {
            case EDIT, WRITE -> ToolKindColors.editColor(settings);
            case EXECUTE -> ToolKindColors.executeColor(settings);
            default -> ToolKindColors.readColor(settings);
        };
    }

    @Override
    public boolean isModified() {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        for (Map.Entry<String, JBCheckBox> entry : toolCheckboxes.entrySet()) {
            if (entry.getValue().isSelected() != settings.isToolEnabled(entry.getKey())) return true;
        }
        if (readColorCombo != null
            && !Objects.equals(keyOf(readColorCombo), settings.getKindReadColorKey())) return true;
        if (editColorCombo != null
            && !Objects.equals(keyOf(editColorCombo), settings.getKindEditColorKey())) return true;
        return executeColorCombo != null
            && !Objects.equals(keyOf(executeColorCombo), settings.getKindExecuteColorKey());
    }

    @Override
    public void apply() {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        for (Map.Entry<String, JBCheckBox> entry : toolCheckboxes.entrySet()) {
            settings.setToolEnabled(entry.getKey(), entry.getValue().isSelected());
        }
        if (readColorCombo != null) settings.setKindReadColorKey(keyOf(readColorCombo));
        if (editColorCombo != null) settings.setKindEditColorKey(keyOf(editColorCombo));
        if (executeColorCombo != null) settings.setKindExecuteColorKey(keyOf(executeColorCombo));
    }

    @Override
    public void reset() {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        for (Map.Entry<String, JBCheckBox> entry : toolCheckboxes.entrySet()) {
            entry.getValue().setSelected(settings.isToolEnabled(entry.getKey()));
        }
        if (readColorCombo != null)
            readColorCombo.setSelectedThemeColor(ThemeColor.fromKey(settings.getKindReadColorKey()));
        if (editColorCombo != null)
            editColorCombo.setSelectedThemeColor(ThemeColor.fromKey(settings.getKindEditColorKey()));
        if (executeColorCombo != null)
            executeColorCombo.setSelectedThemeColor(ThemeColor.fromKey(settings.getKindExecuteColorKey()));
    }

    @Override
    public void disposeUIResources() {
        readColorCombo = null;
        editColorCombo = null;
        executeColorCombo = null;
        toolCheckboxes.clear();
        categoryCheckboxes.clear();
    }

    /**
     * Returns the ThemeColor name for persistence, or null if "Default" is selected.
     */
    private static @Nullable String keyOf(ThemeColorComboBox combo) {
        ThemeColor tc = combo.getSelectedThemeColor();
        return tc != null ? tc.name() : null;
    }
}
