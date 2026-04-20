package com.github.catatafishen.agentbridge.ui.review;

import com.github.catatafishen.agentbridge.psi.review.AgentEditSession;
import com.github.catatafishen.agentbridge.psi.review.RevertReasonDialog;
import com.github.catatafishen.agentbridge.psi.review.ReviewItem;
import com.github.catatafishen.agentbridge.psi.review.ReviewSessionTopic;
import com.github.catatafishen.agentbridge.settings.McpServerSettings;
import com.github.catatafishen.agentbridge.ui.util.TimestampDisplayFormatter;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.SideBorder;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;

/**
 * Side panel listing files the agent has touched in the current {@link AgentEditSession}.
 * <p>
 * Uses a {@link JBList} with a single row renderer instead of a multi-column table.
 * Each row shows: timestamp + status-colored filename + diff counts on the left,
 * approve toggle + remove/reject icon on the right. Click zones are determined by
 * x-position relative to cell bounds.
 */
public final class DiffPanel extends JPanel implements Disposable {

    private static final String CARD_LIST = "list";
    private static final String CARD_EMPTY = "empty";
    private static final String ACTION_REMOVE_APPROVED = "removeApprovedRow";

    /**
     * Unscaled button zone width (each action icon gets this much horizontal space).
     */
    private static final int BUTTON_SIZE = 28;

    private static final JBColor DIFF_GREEN = new JBColor(new Color(0, 128, 0), new Color(80, 200, 80));
    private static final JBColor DIFF_RED = new JBColor(new Color(200, 0, 0), new Color(255, 80, 80));

    private static final JBColor STATUS_ADDED = new JBColor(new Color(0x00, 0x61, 0x00), new Color(0x57, 0xAB, 0x5A));
    private static final JBColor STATUS_MODIFIED = new JBColor(new Color(0x08, 0x69, 0xDA), new Color(0x58, 0xA6, 0xFF));
    private static final JBColor STATUS_DELETED = new JBColor(new Color(0x6E, 0x77, 0x81), new Color(0x8B, 0x94, 0x9E));

    private static final JBColor APPROVED_BG = new JBColor(
        new Color(0, 120, 0, 90), new Color(80, 200, 80, 90));

    private final transient Project project;
    private final DefaultListModel<ReviewItem> listModel = new DefaultListModel<>();
    private final JBList<ReviewItem> list;
    private final CardLayout cardLayout;
    private final JPanel cardPanel;
    private final JBLabel emptyLabel;
    private final JBLabel diffTotalsLabel;
    private final ReviewDiffCountAnimator diffCountAnimator;
    private final Timer diffAnimationTimer;

    public DiffPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        diffCountAnimator = new ReviewDiffCountAnimator();

        list = new JBList<>(listModel) {
            @Override
            public String getToolTipText(MouseEvent e) {
                return getZoneTooltip(e);
            }
        };
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        list.setCellRenderer(new ReviewRowRenderer());
        list.setExpandableItemsEnabled(false);
        ToolTipManager.sharedInstance().registerComponent(list);
        configureListActions();

        diffAnimationTimer = new Timer(33, e -> {
            long now = System.currentTimeMillis();
            list.repaint();
            if (!diffCountAnimator.hasActiveAnimations(now)) {
                ((Timer) e.getSource()).stop();
            }
        });
        diffAnimationTimer.setRepeats(true);

        JBScrollPane scrollPane = new JBScrollPane(list);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        emptyLabel = new JBLabel("", SwingConstants.CENTER);
        emptyLabel.setForeground(JBColor.GRAY);
        JPanel emptyStatePanel = new JPanel(new GridBagLayout());
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        emptyLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        column.add(emptyLabel);
        emptyStatePanel.add(column);

        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);
        cardPanel.add(scrollPane, CARD_LIST);
        cardPanel.add(emptyStatePanel, CARD_EMPTY);

        ActionToolbar toolbar = createToolbar();
        toolbar.setTargetComponent(this);
        toolbar.getComponent().setBorder(JBUI.Borders.empty());

        JPanel toolbarFooter = new JPanel(new BorderLayout());
        toolbarFooter.setBorder(JBUI.Borders.compound(
            new SideBorder(JBColor.border(), SideBorder.TOP),
            JBUI.Borders.empty(2, 0)));
        JComponent toolbarComponent = toolbar.getComponent();
        int footerHeight = JBUI.scale(32);
        toolbarComponent.setPreferredSize(new Dimension(0, footerHeight));
        toolbarComponent.setMinimumSize(new Dimension(0, footerHeight));

        diffTotalsLabel = new JBLabel();
        diffTotalsLabel.setBorder(JBUI.Borders.emptyRight(8));
        toolbarFooter.add(diffTotalsLabel, BorderLayout.EAST);
        toolbarFooter.add(toolbarComponent, BorderLayout.CENTER);

        add(cardPanel, BorderLayout.CENTER);
        add(toolbarFooter, BorderLayout.SOUTH);

        project.getMessageBus().connect(this).subscribe(
            ReviewSessionTopic.TOPIC,
            () -> ApplicationManager.getApplication().invokeLater(this::refresh));

        refresh();
    }

    @Override
    public void dispose() {
        diffAnimationTimer.stop();
        diffCountAnimator.clear();
    }

    public void refresh() {
        ReviewItem selected = list.getSelectedValue();
        String selectedPath = selected != null ? selected.path() : null;

        AgentEditSession session = AgentEditSession.getInstance(project);
        List<ReviewItem> items = session.getReviewItems();
        long now = System.currentTimeMillis();
        diffCountAnimator.sync(items, now);

        listModel.clear();
        for (ReviewItem item : items) {
            listModel.addElement(item);
        }

        if (selectedPath != null) {
            for (int i = 0; i < listModel.size(); i++) {
                if (listModel.get(i).path().equals(selectedPath)) {
                    list.setSelectedIndex(i);
                    break;
                }
            }
        }

        updateDiffTotals(items);
        updateDiffAnimationTimer(now);

        if (!items.isEmpty()) {
            cardLayout.show(cardPanel, CARD_LIST);
        } else {
            emptyLabel.setText("<html><center>No agent edits to review.<br>"
                + "Edits will appear here as soon as the agent touches a file.</center></html>");
            cardLayout.show(cardPanel, CARD_EMPTY);
        }
        revalidate();
        repaint();
    }

    private void updateDiffAnimationTimer(long now) {
        if (diffCountAnimator.hasActiveAnimations(now)) {
            if (!diffAnimationTimer.isRunning()) diffAnimationTimer.start();
        } else {
            diffAnimationTimer.stop();
        }
    }

    private void updateDiffTotals(@NotNull List<ReviewItem> items) {
        int added = 0;
        int removed = 0;
        for (ReviewItem item : items) {
            added += item.linesAdded();
            removed += item.linesRemoved();
        }
        if (added == 0 && removed == 0) {
            diffTotalsLabel.setText("");
        } else {
            StringBuilder sb = new StringBuilder("<html><font size='-2'>");
            if (added > 0) sb.append(colorSpan(DIFF_GREEN, "+" + added));
            if (removed > 0) {
                if (added > 0) sb.append(" ");
                sb.append(colorSpan(DIFF_RED, "-" + removed));
            }
            sb.append("</font></html>");
            diffTotalsLabel.setText(sb.toString());
        }
    }

    private void configureListActions() {
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                ReviewItem item = itemAtPoint(e);
                if (item != null) handleListClick(item, e);
            }
        });

        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) showRevertPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) showRevertPopup(e);
            }
        });

        InputMap im = list.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap am = list.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), ACTION_REMOVE_APPROVED);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), ACTION_REMOVE_APPROVED);
        am.put(ACTION_REMOVE_APPROVED, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                ReviewItem item = list.getSelectedValue();
                if (item != null && item.approved()) {
                    AgentEditSession.getInstance(project).removeApproved(item.path());
                }
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openFile");
        am.put("openFile", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                ReviewItem item = list.getSelectedValue();
                if (item != null) navigateToFile(item);
            }
        });
    }

    private static final int ZONE_FILE = 0;
    private static final int ZONE_APPROVE = 1;
    private static final int ZONE_REMOVE = 2;

    /**
     * Resolves the {@link ReviewItem} at a mouse event's position,
     * or {@code null} if the click is outside any list cell.
     */
    private @Nullable ReviewItem itemAtPoint(MouseEvent e) {
        int index = list.locationToIndex(e.getPoint());
        if (index < 0) return null;
        Rectangle bounds = list.getCellBounds(index, index);
        if (bounds == null || !bounds.contains(e.getPoint())) return null;
        return listModel.get(index);
    }

    private void handleListClick(@NotNull ReviewItem item, MouseEvent e) {
        int index = list.locationToIndex(e.getPoint());
        Rectangle bounds = list.getCellBounds(index, index);
        if (bounds == null) return;
        int zone = hitTestZone(e.getX() - bounds.x, bounds.width);
        switch (zone) {
            case ZONE_REMOVE -> {
                if (item.approved()) {
                    AgentEditSession.getInstance(project).removeApproved(item.path());
                } else {
                    showRevertDialog(item);
                }
            }
            case ZONE_APPROVE -> toggleApproval(item);
            default -> navigateToFile(item);
        }
    }

    private static int hitTestZone(int relativeX, int cellWidth) {
        int btn = JBUI.scale(BUTTON_SIZE);
        int leftPad = JBUI.scale(8);   // matches empty(6, 8, 6, 4) left inset
        int rightPad = JBUI.scale(4);  // matches empty(6, 8, 6, 4) right inset
        if (relativeX < leftPad + btn) return ZONE_APPROVE;
        if (relativeX >= cellWidth - rightPad - btn) return ZONE_REMOVE;
        return ZONE_FILE;
    }

    private String getZoneTooltip(MouseEvent e) {
        ReviewItem item = itemAtPoint(e);
        if (item == null) return null;

        int index = list.locationToIndex(e.getPoint());
        Rectangle bounds = list.getCellBounds(index, index);
        if (bounds == null) return null;
        int zone = hitTestZone(e.getX() - bounds.x, bounds.width);
        return switch (zone) {
            case ZONE_REMOVE -> item.approved() ? "Remove from list" : "Reject this change…";
            case ZONE_APPROVE -> item.approved() ? "Approved — click to unapprove" : "Approve this change";
            default -> item.relativePath() + (item.approved() ? " · Approved" : " · Pending review");
        };
    }

    private void toggleApproval(@NotNull ReviewItem item) {
        AgentEditSession session = AgentEditSession.getInstance(project);
        if (item.approved()) session.unapproveFile(item.path());
        else session.acceptFile(item.path());
    }

    private void navigateToFile(@NotNull ReviewItem item) {
        if (item.status() == ReviewItem.Status.DELETED) return;
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(item.path());
        if (vf != null) FileEditorManager.getInstance(project).openFile(vf, true);
    }

    private void showRevertDialog(@NotNull ReviewItem item) {
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(item.path());
        if (vf == null) return;
        AgentEditSession session = AgentEditSession.getInstance(project);
        RevertReasonDialog dialog = new RevertReasonDialog(
            project, vf, item.relativePath(), session.isGateActive());
        if (!dialog.showAndGet()) return;
        AgentEditSession.RevertGateAction gateAction = switch (dialog.getResult()) {
            case CONTINUE_REVIEWING -> AgentEditSession.RevertGateAction.CONTINUE_REVIEWING;
            case SEND_NOW -> AgentEditSession.RevertGateAction.SEND_NOW;
            default -> AgentEditSession.RevertGateAction.DEFAULT;
        };
        session.revertFile(item.path(), dialog.getReason(), gateAction);
    }

    private void showRevertPopup(MouseEvent e) {
        ReviewItem item = itemAtPoint(e);
        if (item == null) return;
        JPopupMenu menu = new JPopupMenu();
        JMenuItem revertItem = new JMenuItem("Revert…", AllIcons.Actions.Rollback);
        revertItem.addActionListener(ev -> showRevertDialog(item));
        menu.add(revertItem);
        menu.show(list, e.getX(), e.getY());
    }

    private @NotNull ActionToolbar createToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new AutoApproveToggleAction(project));
        group.add(new AutoCleanOnNewPromptToggleAction(project));
        group.addSeparator();
        group.add(new DumbAwareAction("Clean Approved",
            "Remove all approved rows from the list", AllIcons.Actions.GC) {
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                AgentEditSession.getInstance(project).removeAllApproved();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                boolean anyApproved = false;
                for (ReviewItem it : AgentEditSession.getInstance(project).getReviewItems()) {
                    if (it.approved()) {
                        anyApproved = true;
                        break;
                    }
                }
                e.getPresentation().setEnabled(anyApproved);
            }
        });
        return ActionManager.getInstance().createActionToolbar("ReviewChangesToolbar", group, true);
    }

    private static final class AutoApproveToggleAction extends ToggleAction
        implements com.intellij.openapi.actionSystem.ex.CustomComponentAction {
        private final Project project;

        AutoApproveToggleAction(@NotNull Project project) {
            super("Auto-Approve", "Apply agent edits without per-file approval", AllIcons.Actions.Checked);
            this.project = project;
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return McpServerSettings.getInstance(project).isAutoApproveAgentEdits();
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            McpServerSettings.getInstance(project).setAutoApproveAgentEdits(state);
            if (state) AgentEditSession.getInstance(project).onAutoApproveTurnedOn();
            project.getMessageBus().syncPublisher(ReviewSessionTopic.TOPIC).reviewStateChanged();
        }

        @Override
        public @NotNull JComponent createCustomComponent(
            @NotNull com.intellij.openapi.actionSystem.Presentation presentation,
            @NotNull String place) {
            return new com.intellij.openapi.actionSystem.impl.ActionButton(
                this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE) {
                @Override
                protected void paintButtonLook(Graphics g) {
                    if (isSelected()) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(APPROVED_BG);
                        int arc = JBUI.scale(4);
                        g2.fillRoundRect(2, 2, getWidth() - 4, getHeight() - 4, arc, arc);
                        g2.dispose();
                        Icon icon = presentation.getIcon();
                        if (icon != null) {
                            int x = (getWidth() - icon.getIconWidth()) / 2;
                            int y = (getHeight() - icon.getIconHeight()) / 2;
                            icon.paintIcon(this, g, x, y);
                        }
                    } else {
                        super.paintButtonLook(g);
                    }
                }
            };
        }
    }

    private static final class AutoCleanOnNewPromptToggleAction extends ToggleAction {
        private final Project project;

        AutoCleanOnNewPromptToggleAction(@NotNull Project project) {
            super("Auto-Clean on New Prompt",
                "Remove approved rows automatically when starting a new prompt",
                AllIcons.Actions.ClearCash);
            this.project = project;
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return McpServerSettings.getInstance(project).isAutoCleanReviewOnNewPrompt();
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            McpServerSettings.getInstance(project).setAutoCleanReviewOnNewPrompt(state);
        }
    }

    /**
     * Renders a single review row with a two-column layout:
     * <ul>
     *   <li>LEFT: approve badge</li>
     *   <li>CENTER: timestamp (top), status-coloured filename (middle), animated diff counts (bottom)</li>
     *   <li>RIGHT: remove/reject icon</li>
     * </ul>
     */
    private final class ReviewRowRenderer extends JPanel implements ListCellRenderer<ReviewItem> {
        private final BadgeLabel approveLabel = new BadgeLabel();
        private final SimpleColoredComponent timestampText = new SimpleColoredComponent();
        private final SimpleColoredComponent fileText = new SimpleColoredComponent();
        private final SimpleColoredComponent diffText = new SimpleColoredComponent();
        private final JLabel removeLabel = new JLabel();

        ReviewRowRenderer() {
            setLayout(new BorderLayout());
            setBorder(JBUI.Borders.empty(6, 8, 6, 4));

            Dimension btnDim = new Dimension(JBUI.scale(BUTTON_SIZE), JBUI.scale(BUTTON_SIZE));

            approveLabel.setHorizontalAlignment(SwingConstants.CENTER);
            approveLabel.setPreferredSize(btnDim);
            add(approveLabel, BorderLayout.WEST);

            JPanel textPanel = new JPanel();
            textPanel.setOpaque(false);
            textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
            textPanel.setBorder(JBUI.Borders.emptyLeft(6));
            timestampText.setOpaque(false);
            fileText.setOpaque(false);
            diffText.setOpaque(false);
            textPanel.add(timestampText);
            textPanel.add(fileText);
            textPanel.add(diffText);
            add(textPanel, BorderLayout.CENTER);

            removeLabel.setHorizontalAlignment(SwingConstants.CENTER);
            removeLabel.setVerticalAlignment(SwingConstants.CENTER);
            removeLabel.setPreferredSize(btnDim);
            add(removeLabel, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(
            JList<? extends ReviewItem> jList, ReviewItem item,
            int index, boolean isSelected, boolean cellHasFocus) {

            Color bg = isSelected ? jList.getSelectionBackground() : jList.getBackground();
            Color fg = isSelected ? jList.getSelectionForeground() : jList.getForeground();
            setBackground(bg);
            setOpaque(true);

            timestampText.clear();
            if (item.lastEditedMillis() > 0) {
                timestampText.append(
                    TimestampDisplayFormatter.formatEpochMillis(item.lastEditedMillis()),
                    new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, isSelected ? fg : JBColor.GRAY));
                timestampText.setVisible(true);
            } else {
                timestampText.setVisible(false);
            }

            fileText.clear();
            fileText.setFont(jList.getFont());
            Path p = Path.of(item.path());
            String fileName = p.getFileName() != null ? p.getFileName().toString() : item.path();
            Color fileColor = isSelected ? fg : switch (item.status()) {
                case ADDED -> STATUS_ADDED;
                case MODIFIED -> STATUS_MODIFIED;
                case DELETED -> STATUS_DELETED;
            };
            fileText.append(fileName, new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, fileColor));

            diffText.clear();
            long now = System.currentTimeMillis();
            ReviewDiffCountAnimator.DiffCounts counts = diffCountAnimator.displayCounts(item, now);
            if (counts.added() > 0 || counts.removed() > 0) {
                if (counts.added() > 0) {
                    Color c = isSelected ? fg : DIFF_GREEN;
                    diffText.append("+" + counts.added(),
                        new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, c));
                }
                if (counts.removed() > 0) {
                    if (counts.added() > 0) diffText.append(" ", SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    Color c = isSelected ? fg : DIFF_RED;
                    diffText.append("-" + counts.removed(),
                        new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, c));
                }
                diffText.setVisible(true);
            } else {
                diffText.setVisible(false);
            }

            approveLabel.setIcon(AllIcons.Actions.Checked);
            approveLabel.setHighlighted(item.approved());
            removeLabel.setIcon(item.approved() ? AllIcons.Actions.Close : AllIcons.Actions.Rollback);

            return this;
        }
    }

    /**
     * A {@link JLabel} that draws a semi-transparent green badge behind the icon
     * when {@link #setHighlighted(boolean) highlighted}, indicating approved state.
     */
    private static final class BadgeLabel extends JLabel {
        private boolean highlighted;

        void setHighlighted(boolean highlighted) {
            this.highlighted = highlighted;
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (highlighted) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int size = JBUI.scale(22);
                int x = (getWidth() - size) / 2;
                int y = (getHeight() - size) / 2;
                g2.setColor(APPROVED_BG);
                g2.fillRoundRect(x, y, size, size, JBUI.scale(4), JBUI.scale(4));
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    private static @NotNull String colorHex(@NotNull Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static @NotNull String colorSpan(@NotNull Color c, @NotNull String text) {
        return "<font color='" + colorHex(c) + "'>" + text + "</font>";
    }
}
