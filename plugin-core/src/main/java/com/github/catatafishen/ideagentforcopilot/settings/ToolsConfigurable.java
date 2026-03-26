package com.github.catatafishen.ideagentforcopilot.settings;

import com.github.catatafishen.ideagentforcopilot.services.ToolDefinition;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.github.catatafishen.ideagentforcopilot.ui.ToolKindColors;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ColorPanel;
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

    // Color picker panels for each tool kind
    private @Nullable ColorPanel readColorPanel;
    private @Nullable ColorPanel editColorPanel;
    private @Nullable ColorPanel executeColorPanel;

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
                JBLabel descLabel = new JBLabel(desc);
                descLabel.setFont(descLabel.getFont().deriveFont((float) (JBUI.Fonts.label().getSize() - 1)));
                descLabel.setForeground(UIUtil.getContextHelpForeground());
                descLabel.setBorder(JBUI.Borders.empty(0, 36, 3, 0));
                descLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                toolsPanel.add(descLabel);
            }
        }

        // Color picker section
        toolsPanel.add(buildColorPickerSection(settings));
        toolsPanel.add(Box.createVerticalGlue());

        JBScrollPane scrollPane = new JBScrollPane(toolsPanel);
        scrollPane.setBorder(JBUI.Borders.empty());

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
     * Builds the "Tool Kind Colors" section with a [ColorPanel] for each kind.
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
                + "tool-chip labels in the chat view, and permission dropdowns.</html>");
        hint.setFont(JBUI.Fonts.smallFont());
        hint.setForeground(UIUtil.getContextHelpForeground());
        hint.setBorder(JBUI.Borders.empty(0, 0, 8, 0));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(hint);

        readColorPanel = new ColorPanel();
        editColorPanel = new ColorPanel();
        executeColorPanel = new ColorPanel();

        readColorPanel.setSelectedColor(ToolKindColors.readColor(settings));
        editColorPanel.setSelectedColor(ToolKindColors.editColor(settings));
        executeColorPanel.setSelectedColor(ToolKindColors.executeColor(settings));

        section.add(colorRow("Read & Navigate", readColorPanel));
        section.add(colorRow("Edit & Refactor", editColorPanel));
        section.add(colorRow("Run & Execute", executeColorPanel));

        return section;
    }

    private static JComponent colorRow(String label, ColorPanel picker) {
        JBPanel<?> row = new JBPanel<>();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(JBUI.Borders.empty(2, 0));

        JBLabel lbl = new JBLabel(label);
        lbl.setPreferredSize(new Dimension(JBUI.scale(140), lbl.getPreferredSize().height));
        row.add(lbl);
        row.add(picker);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    /**
     * Returns the kind accent color for [tool], honoring settings overrides.
     */
    private static Color kindColorFor(ToolDefinition tool, McpServerSettings settings) {
        if (tool.isReadOnly()) return ToolKindColors.readColor(settings);
        return switch (tool.kind()) {
            case "edit" -> ToolKindColors.editColor(settings);
            case "execute" -> ToolKindColors.executeColor(settings);
            default -> ToolKindColors.readColor(settings);
        };
    }

    @Override
    public boolean isModified() {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        for (Map.Entry<String, JBCheckBox> entry : toolCheckboxes.entrySet()) {
            if (entry.getValue().isSelected() != settings.isToolEnabled(entry.getKey())) return true;
        }
        if (readColorPanel != null && colorDiffersFromSetting(readColorPanel, settings.getKindReadColorHex(),
            ToolKindColors.DEFAULT_READ)) return true;
        if (editColorPanel != null && colorDiffersFromSetting(editColorPanel, settings.getKindEditColorHex(),
            ToolKindColors.DEFAULT_EDIT)) return true;
        return executeColorPanel != null && colorDiffersFromSetting(executeColorPanel, settings.getKindExecuteColorHex(),
            ToolKindColors.DEFAULT_EXECUTE);
    }

    @Override
    public void apply() {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        for (Map.Entry<String, JBCheckBox> entry : toolCheckboxes.entrySet()) {
            settings.setToolEnabled(entry.getKey(), entry.getValue().isSelected());
        }
        if (readColorPanel != null)
            settings.setKindReadColorHex(encodeColor(readColorPanel, ToolKindColors.DEFAULT_READ));
        if (editColorPanel != null)
            settings.setKindEditColorHex(encodeColor(editColorPanel, ToolKindColors.DEFAULT_EDIT));
        if (executeColorPanel != null)
            settings.setKindExecuteColorHex(encodeColor(executeColorPanel, ToolKindColors.DEFAULT_EXECUTE));
    }

    @Override
    public void reset() {
        McpServerSettings settings = McpServerSettings.getInstance(project);
        for (Map.Entry<String, JBCheckBox> entry : toolCheckboxes.entrySet()) {
            entry.getValue().setSelected(settings.isToolEnabled(entry.getKey()));
        }
        if (readColorPanel != null)
            readColorPanel.setSelectedColor(ToolKindColors.readColor(settings));
        if (editColorPanel != null)
            editColorPanel.setSelectedColor(ToolKindColors.editColor(settings));
        if (executeColorPanel != null)
            executeColorPanel.setSelectedColor(ToolKindColors.executeColor(settings));
    }

    /**
     * Returns true if [panel]'s selected color differs from the given [savedHex] setting
     * (or the [defaultColor] if [savedHex] is null/blank).
     */
    private static boolean colorDiffersFromSetting(ColorPanel panel, @Nullable String savedHex, Color defaultColor) {
        Color panelColor = panel.getSelectedColor();
        if (panelColor == null) return false;
        Color expected = (savedHex != null && !savedHex.isBlank())
            ? ToolKindColors.parseHex(savedHex)
            : defaultColor;
        if (expected == null) expected = defaultColor;
        return !colorsEqual(panelColor, expected);
    }

    /**
     * Returns the hex encoding of [panel]'s selected color, or null if it matches [defaultColor]
     * (null signals "use default", avoiding unnecessary storage).
     */
    private static @Nullable String encodeColor(ColorPanel panel, Color defaultColor) {
        Color selected = panel.getSelectedColor();
        if (selected == null || colorsEqual(selected, defaultColor)) return null;
        return ToolKindColors.toHex(selected);
    }

    private static boolean colorsEqual(Color a, Color b) {
        return a.getRed() == b.getRed() && a.getGreen() == b.getGreen() && a.getBlue() == b.getBlue();
    }
}
