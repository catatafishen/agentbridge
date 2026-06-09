package com.github.catatafishen.agentbridge.ui.graph;

import com.github.catatafishen.agentbridge.psi.graph.CodeGraphSettings;
import com.github.catatafishen.agentbridge.psi.graph.IndexableRootType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.panels.VerticalLayout;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Settings dialog for the Knowledge Graph tool window.
 * Contains directory type checkboxes with IDE-standard color indicators.
 */
public final class CodeGraphSettingsDialog extends DialogWrapper {

    private final Project project;
    private final Map<IndexableRootType, JCheckBox> checkBoxes = new EnumMap<>(IndexableRootType.class);

    public CodeGraphSettingsDialog(@NotNull Project project) {
        super(project, false);
        this.project = project;
        setTitle("Knowledge Graph Settings");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new VerticalLayout(JBUI.scale(12)));
        panel.setBorder(JBUI.Borders.empty(8, 12));
        panel.setPreferredSize(new Dimension(JBUI.scale(380), JBUI.scale(300)));

        // Section: Indexing Scope
        JBLabel scopeTitle = new JBLabel("Indexing Scope");
        scopeTitle.setFont(scopeTitle.getFont().deriveFont(Font.BOLD));
        panel.add(scopeTitle);

        JBLabel scopeDesc = new JBLabel(
            "<html><body style='width:320px'>"
                + "Select which directory types to include when building the knowledge graph. "
                + "Unchecked types are skipped during indexing."
                + "</body></html>");
        scopeDesc.setForeground(JBColor.GRAY);
        panel.add(scopeDesc);

        // Checkboxes for each root type
        CodeGraphSettings settings = CodeGraphSettings.getInstance(project);
        Set<IndexableRootType> current = settings.getIncludedRootTypes();

        JPanel checkBoxPanel = new JPanel(new VerticalLayout(JBUI.scale(6)));
        checkBoxPanel.setBorder(JBUI.Borders.emptyLeft(8));

        for (IndexableRootType type : IndexableRootType.values()) {
            JCheckBox cb = new JCheckBox(type.displayName());
            cb.setSelected(current.contains(type));
            cb.setIcon(new ColorDotIcon(type.color(), false));
            cb.setSelectedIcon(new ColorDotIcon(type.color(), true));
            checkBoxes.put(type, cb);
            checkBoxPanel.add(cb);
        }
        panel.add(checkBoxPanel);

        // Separator
        panel.add(Box.createVerticalStrut(JBUI.scale(8)));
        panel.add(new JSeparator());

        // Info
        JBLabel infoLabel = new JBLabel(
            "<html><body style='width:320px'>"
                + "<b>Note:</b> Changing the scope requires a rebuild to take effect. "
                + "By default, only <b>Sources</b> and <b>Test Sources</b> are indexed — "
                + "this excludes node_modules, build output, and other non-source directories."
                + "</body></html>");
        infoLabel.setForeground(JBColor.GRAY);
        panel.add(infoLabel);

        return panel;
    }

    @Override
    protected void doOKAction() {
        EnumSet<IndexableRootType> selected = EnumSet.noneOf(IndexableRootType.class);
        for (Map.Entry<IndexableRootType, JCheckBox> entry : checkBoxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                selected.add(entry.getKey());
            }
        }
        CodeGraphSettings.getInstance(project).setIncludedRootTypes(selected);
        super.doOKAction();
    }

    /**
     * Small colored circle icon to visually match the IDE's directory marking colors.
     */
    private static final class ColorDotIcon implements Icon {

        private static final int SIZE = JBUI.scale(12);
        private final Color color;
        private final boolean filled;

        ColorDotIcon(@NotNull Color color, boolean filled) {
            this.color = color;
            this.filled = filled;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int inset = JBUI.scale(2);
            int diameter = SIZE - inset * 2;
            if (filled) {
                g2.setColor(color);
                g2.fillOval(x + inset, y + inset, diameter, diameter);
            } else {
                g2.setColor(color);
                g2.setStroke(new BasicStroke(1.5f * JBUI.scale(1)));
                g2.drawOval(x + inset, y + inset, diameter, diameter);
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }
    }
}
