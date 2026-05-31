package com.github.catatafishen.agentbridge.ui.side;

import com.github.catatafishen.agentbridge.services.PromptDbService;
import com.github.catatafishen.agentbridge.ui.BroadcastChatPanel;
import com.github.catatafishen.agentbridge.ui.review.DiffPanel;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Container for the left-hand tool-window pane.
 * <p>
 * Uses a plain {@link CardLayout} to switch between tabs. When the side panel is open,
 * a compact tab strip ({@link #tabStrip}) is displayed at the top of this panel, driven
 * by {@link #setCustomTabStripVisible(boolean)}. This keeps {@code rootSplitter} stationary
 * in the component tree — no re-parenting on tab switch — avoiding the expensive
 * {@code addNotify}/{@code removeNotify} cascade on Windows (GDI+ font-metrics recalculation).
 * <p>
 * Hosts five tabs:
 * <ol>
 *   <li><b>Diff</b> — pending agent edits ({@link DiffPanel}).</li>
 *   <li><b>Plan</b> — rendered view of the active agent's {@code plan.md} with a
 *       {@code (done/total)} badge when task items exist.</li>
 *   <li><b>MCP</b> — live list of MCP tool calls with timestamps and expandable I/O.</li>
 *   <li><b>Prompts</b> — searchable conversation history, click to scroll.</li>
 *   <li><b>Stats</b> — session statistics and billing info.</li>
 * </ol>
 * Tab order is deliberate: review is the most time-sensitive and sits first.
 */
public final class SidePanel extends JPanel implements Disposable {

    public static final int TAB_REVIEW = 0;
    public static final int TAB_TODOS = 1;
    public static final int TAB_MCP = 2;
    public static final int TAB_PROMPT_DB = 3;
    public static final int TAB_STATS = 4;

    /**
     * Display names for each tab, in index order. Unmodifiable.
     */
    public static final java.util.List<String> TAB_NAMES =
        java.util.List.of("Diff", "Plan", "MCP", "Prompts", "Stats");

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentContainer = new JPanel(cardLayout);
    private int selectedTab = TAB_REVIEW;
    private String planBadge = "";
    private transient @Nullable Consumer<String> onPlanTitleChanged;

    private JPanel tabStrip;
    private TabLabel[] tabLabels;
    private boolean customTabStripVisible = false;

    private final transient Project project;
    private final TodoPanel todoPanel;

    public SidePanel(@NotNull Project project, @NotNull BroadcastChatPanel chatConsole,
                     @NotNull SessionStatsPanel sessionStatsPanel) {
        super(new BorderLayout());
        this.project = project;

        DiffPanel reviewPanel = new DiffPanel(project);
        Disposer.register(this, reviewPanel);
        Disposer.register(this, sessionStatsPanel);

        todoPanel = new TodoPanel(project);
        Disposer.register(this, todoPanel);
        PromptsPanel promptsPanel = new PromptsPanel(project, chatConsole);
        Disposer.register(this, promptsPanel);

        JPanel mcpTab = buildMcpTab(project);

        contentContainer.add(reviewPanel, String.valueOf(TAB_REVIEW));
        contentContainer.add(todoPanel, String.valueOf(TAB_TODOS));
        contentContainer.add(mcpTab, String.valueOf(TAB_MCP));
        contentContainer.add(promptsPanel, String.valueOf(TAB_PROMPT_DB));
        contentContainer.add(sessionStatsPanel, String.valueOf(TAB_STATS));
        cardLayout.show(contentContainer, String.valueOf(TAB_REVIEW));

        todoPanel.setOnProgressChanged(() -> {
            int total = todoPanel.getTotal();
            int done = todoPanel.getDone();
            planBadge = total > 0 ? " (" + done + "/" + total + ")" : "";
            if (onPlanTitleChanged != null) onPlanTitleChanged.accept(getPlanTitle());
        });

        PromptDbService.getInstance(project).registerNavigateCallback(params -> {
            selectTab(TAB_PROMPT_DB);
            promptsPanel.applySearchParams(params);
        });

        tabLabels = new TabLabel[TAB_NAMES.size()];
        tabStrip = buildTabStrip();

        add(tabStrip, BorderLayout.NORTH);
        add(contentContainer, BorderLayout.CENTER);
    }

    /**
     * Switches to the given tab index and refreshes it if needed.
     */
    public void selectTab(int index) {
        if (index == selectedTab) return;
        if (tabLabels != null) {
            tabLabels[selectedTab].setSelected(false);
            tabLabels[index].setSelected(true);
        }
        selectedTab = index;
        cardLayout.show(contentContainer, String.valueOf(index));
        if (index == TAB_TODOS) todoPanel.refresh();
    }

    /**
     * Returns the currently selected tab index.
     */
    public int getSelectedTab() {
        return selectedTab;
    }

    /**
     * Returns the Plan tab title, including the {@code (done/total)} badge if tasks exist.
     */
    public @NotNull String getPlanTitle() {
        return "Plan" + planBadge;
    }

    /**
     * Registers a callback that fires whenever the Plan tab title changes (badge update).
     * The callback receives the new title string.
     */
    public void setOnPlanTitleChanged(@Nullable Consumer<String> callback) {
        this.onPlanTitleChanged = callback;
    }

    /**
     * Switches to the Diff tab. Safe to call from the EDT.
     */
    public void selectReviewTab() {
        selectTab(TAB_REVIEW);
    }

    /**
     * Shows or hides the in-panel tab strip header. Call with {@code true} when the side panel
     * is expanded, {@code false} when collapsed, so tab labels track the open/closed state.
     * Also syncs the Plan tab badge text to the current value on show.
     */
    public void setCustomTabStripVisible(boolean visible) {
        customTabStripVisible = visible;
        if (visible) {
            tabLabels[TAB_TODOS].setText(getPlanTitle());
        }
        tabStrip.setVisible(visible);
        revalidate();
        repaint();
    }

    /**
     * Returns {@code true} if the in-panel tab strip is currently visible.
     */
    public boolean isCustomTabStripVisible() {
        return customTabStripVisible;
    }

    /**
     * Updates the Plan tab label text (including badge). Called whenever the plan badge changes.
     * No-op if the tab strip is not yet built.
     */
    public void updatePlanTabText(String title) {
        if (tabLabels != null) {
            tabLabels[TAB_TODOS].setText(title);
        }
    }

    /**
     * Builds the MCP tab with a resizable vertical split: JCEF tool calls on top,
     * hooks file browser on the bottom.
     */
    private @NotNull JPanel buildMcpTab(@NotNull Project project) {
        ToolCallsWebPanel toolCallsPanel = new ToolCallsWebPanel(project);
        Disposer.register(this, toolCallsPanel);

        Path hooksDir = project.getBasePath() != null
            ? Path.of(project.getBasePath(), ".agentbridge", "hooks")
            : null;

        JPanel bottomPanel = new JPanel(new BorderLayout());
        JBLabel hooksLabel = new JBLabel("Hooks");
        hooksLabel.setFont(UIUtil.getLabelFont().deriveFont(Font.BOLD, 11f));
        hooksLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        bottomPanel.add(hooksLabel, BorderLayout.NORTH);

        if (hooksDir != null) {
            ProjectFilesPanel hooksPanel = new ProjectFilesPanel(project, hooksDir);
            bottomPanel.add(hooksPanel, BorderLayout.CENTER);
        }

        OnePixelSplitter splitter = new OnePixelSplitter(true, 0.65f);
        splitter.setFirstComponent(toolCallsPanel);
        splitter.setSecondComponent(bottomPanel);

        JPanel tab = new JPanel(new BorderLayout());
        tab.add(splitter, BorderLayout.CENTER);
        return tab;
    }

    @Override
    public void dispose() {
        PromptDbService.getInstance(project).registerNavigateCallback(null);
    }

    private @NotNull JPanel buildTabStrip() {
        JPanel strip = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.setColor(JBColor.border());
                g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
            }
        };
        strip.setLayout(new BoxLayout(strip, BoxLayout.X_AXIS));
        strip.setOpaque(false);
        strip.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
        strip.setVisible(false);

        for (int i = 0; i < TAB_NAMES.size(); i++) {
            final int idx = i;
            TabLabel label = new TabLabel(TAB_NAMES.get(i), i == selectedTab);
            tabLabels[i] = label;
            label.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    selectTab(idx);
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    label.setHovered(true);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    label.setHovered(false);
                }
            });
            strip.add(label);
        }
        return strip;
    }

    private static final class TabLabel extends JComponent {
        private String text;
        private boolean selected;
        private boolean hovered;

        private static final int H_PAD = JBUI.scale(10);
        private static final int V_PAD = JBUI.scale(4);
        private static final int INDICATOR_H = JBUI.scale(2);

        TabLabel(String text, boolean selected) {
            this.text = text;
            this.selected = selected;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setOpaque(false);
            setFocusable(true);
            getAccessibleContext().setAccessibleName(text);

            // Enter and Space activate the tab (keyboard navigation support).
            InputMap im = getInputMap(WHEN_FOCUSED);
            ActionMap am = getActionMap();
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "activate");
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "activate");
            am.put("activate", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    doClick();
                }
            });

            // Repaint when focus changes so the focus ring updates.
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    repaint();
                }

                @Override
                public void focusLost(FocusEvent e) {
                    repaint();
                }
            });
        }

        /**
         * Programmatically fire all registered MouseListeners (click behaviour).
         */
        void doClick() {
            MouseEvent evt = new MouseEvent(this, MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, 0, 0, 1, false);
            for (MouseListener ml : getMouseListeners()) {
                ml.mouseClicked(evt);
            }
        }

        void setText(String text) {
            if (this.text.equals(text)) return;
            this.text = text;
            getAccessibleContext().setAccessibleName(text);
            revalidate();
            repaint();
        }

        void setSelected(boolean selected) {
            if (this.selected == selected) return;
            this.selected = selected;
            repaint();
        }

        void setHovered(boolean hovered) {
            if (this.hovered == hovered) return;
            this.hovered = hovered;
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            Font boldFont = UIUtil.getLabelFont().deriveFont(Font.BOLD);
            FontMetrics fm = getFontMetrics(boldFont);
            return new Dimension(
                fm.stringWidth(text) + H_PAD * 2,
                fm.getHeight() + V_PAD * 2 + INDICATOR_H
            );
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                Font font = selected
                    ? UIUtil.getLabelFont().deriveFont(Font.BOLD)
                    : UIUtil.getLabelFont();
                g2.setFont(font);
                FontMetrics fm = g2.getFontMetrics();
                Color textColor = (selected || hovered)
                    ? UIUtil.getLabelForeground()
                    : UIUtil.getLabelDisabledForeground();
                g2.setColor(textColor);
                g2.drawString(text, H_PAD, V_PAD + fm.getAscent());
                if (selected) {
                    g2.setColor(JBColor.namedColor("TabbedPane.underlineColor",
                        new JBColor(new Color(0x4883C8), new Color(0x5897C8))));
                    g2.fillRect(0, getHeight() - INDICATOR_H, getWidth(), INDICATOR_H);
                }
                if (isFocusOwner()) {
                    g2.setColor(UIUtil.getFocusedBorderColor());
                    g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f,
                        new float[]{2f, 2f}, 0f));
                    g2.drawRect(1, 1, getWidth() - 3, getHeight() - INDICATOR_H - 2);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
