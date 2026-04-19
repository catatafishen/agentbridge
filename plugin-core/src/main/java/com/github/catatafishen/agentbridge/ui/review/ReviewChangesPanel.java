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
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Side panel listing every file the agent has touched in the current
 * {@link AgentEditSession}.
 * <p>
 * Columns: [file name (status-colored) + meta] [approve checkbox] [remove X].
 * File names are colored by status: green = added, blue = modified, grey = deleted.
 * Clicking a row opens the file. The approve checkbox toggles between PENDING and
 * APPROVED. The X button removes approved rows.
 */
public final class ReviewChangesPanel extends JPanel implements Disposable {

    private static final String CARD_TABLE = "table";
    private static final String CARD_EMPTY = "empty";
    private static final String ACTION_REMOVE_APPROVED = "removeApprovedRow";

    /**
     * Standard diff colors for added/removed line counts.
     */
    private static final JBColor DIFF_GREEN = new JBColor(new Color(0, 128, 0), new Color(80, 200, 80));
    private static final JBColor DIFF_RED = new JBColor(new Color(200, 0, 0), new Color(255, 80, 80));

    /**
     * File status colors — matches IntelliJ's VCS file coloring convention:
     * green = added, blue = modified, grey = deleted.
     */
    private static final JBColor STATUS_ADDED = new JBColor(new Color(0x00, 0x61, 0x00), new Color(0x57, 0xAB, 0x5A));
    private static final JBColor STATUS_MODIFIED = new JBColor(new Color(0x08, 0x69, 0xDA), new Color(0x58, 0xA6, 0xFF));
    private static final JBColor STATUS_DELETED = new JBColor(new Color(0x6E, 0x77, 0x81), new Color(0x8B, 0x94, 0x9E));

    private final transient Project project;
    private final ReviewTableModel tableModel;
    private final JBTable table;
    private final CardLayout cardLayout;
    private final JPanel cardPanel;
    private final JBLabel emptyLabel;
    private final JBLabel diffTotalsLabel;
    private final ReviewDiffCountAnimator diffCountAnimator;
    private final Timer diffAnimationTimer;

    public ReviewChangesPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        tableModel = new ReviewTableModel();
        table = new JBTable(tableModel);
        diffCountAnimator = new ReviewDiffCountAnimator();
        diffAnimationTimer = new Timer(33, e -> {
            long now = System.currentTimeMillis();
            table.repaint();
            if (!diffCountAnimator.hasActiveAnimations(now)) {
                ((Timer) e.getSource()).stop();
            }
        });
        diffAnimationTimer.setRepeats(true);
        configureTable();

        JBScrollPane scrollPane = new JBScrollPane(table);
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
        cardPanel.add(scrollPane, CARD_TABLE);
        cardPanel.add(emptyStatePanel, CARD_EMPTY);

        ActionToolbar toolbar = createToolbar();
        toolbar.setTargetComponent(this);
        toolbar.getComponent().setBorder(JBUI.Borders.empty());

        JPanel toolbarFooter = new JPanel(new BorderLayout());
        toolbarFooter.setBorder(JBUI.Borders.compound(
            new SideBorder(JBColor.border(), SideBorder.TOP),
            JBUI.Borders.empty(2, 0)
        ));
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
            () -> ApplicationManager.getApplication().invokeLater(this::refresh)
        );

        refresh();
    }

    @Override
    public void dispose() {
        diffAnimationTimer.stop();
        diffCountAnimator.clear();
    }

    public void refresh() {
        AgentEditSession session = AgentEditSession.getInstance(project);
        List<ReviewItem> items = session.getReviewItems();
        long now = System.currentTimeMillis();
        diffCountAnimator.sync(items, now);
        tableModel.setItems(items);
        updateDiffTotals(items);
        updateDiffAnimationTimer(now);

        if (!items.isEmpty()) {
            cardLayout.show(cardPanel, CARD_TABLE);
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
            if (!diffAnimationTimer.isRunning()) {
                diffAnimationTimer.start();
            }
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
            sb.append(FONT_CLOSE).append("</html>");
            diffTotalsLabel.setText(sb.toString());
        }
    }

    private static @NotNull String colorHex(@NotNull Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static final String FONT_CLOSE = "</font>";

    private static @NotNull String colorSpan(@NotNull Color c, @NotNull String text) {
        return "<font color='" + colorHex(c) + "'>" + text + FONT_CLOSE;
    }

    private void configureTable() {
        table.setRowHeight(JBUI.scale(32));
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setTableHeader(null);
        table.setExpandableItemsEnabled(false);

        // COL_FILE — file name + meta (takes remaining space)
        TableColumn fileCol = table.getColumnModel().getColumn(ReviewTableModel.COL_FILE);
        fileCol.setPreferredWidth(JBUI.scale(280));
        fileCol.setCellRenderer(new FileCellRenderer());

        // COL_APPROVE — toolbar-style toggle button: highlighted when approved, plain when pending
        TableColumn approveCol = table.getColumnModel().getColumn(ReviewTableModel.COL_APPROVE);
        approveCol.setPreferredWidth(JBUI.scale(32));
        approveCol.setMaxWidth(JBUI.scale(36));
        approveCol.setCellRenderer(new ApproveToggleRenderer());

        // COL_REMOVE — rollback icon for pending (reject), X icon for approved (remove)
        TableColumn removeCol = table.getColumnModel().getColumn(ReviewTableModel.COL_REMOVE);
        removeCol.setPreferredWidth(JBUI.scale(28));
        removeCol.setMaxWidth(JBUI.scale(32));
        removeCol.setCellRenderer(new RejectOrRemoveRenderer());

        table.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row < 0 || row >= tableModel.getRowCount()) return;
                ReviewItem item = tableModel.getItem(row);

                switch (col) {
                    case ReviewTableModel.COL_APPROVE -> toggleApproval(item);
                    case ReviewTableModel.COL_REMOVE -> {
                        if (item.approved()) {
                            AgentEditSession.getInstance(project).removeApproved(item.path());
                        } else {
                            showRevertDialog(item);
                        }
                    }
                    default -> navigateToFile(item);
                }
            }
        });

        // Right-click to revert
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) showRevertPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) showRevertPopup(e);
            }
        });

        // DEL removes the focused row when it's APPROVED.
        InputMap im = table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap am = table.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), ACTION_REMOVE_APPROVED);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), ACTION_REMOVE_APPROVED);
        am.put(ACTION_REMOVE_APPROVED, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int row = table.getSelectedRow();
                if (row < 0 || row >= tableModel.getRowCount()) return;
                ReviewItem item = tableModel.getItem(row);
                if (item.approved()) {
                    AgentEditSession.getInstance(project).removeApproved(item.path());
                }
            }
        });
    }

    private void showRevertPopup(MouseEvent e) {
        int row = table.rowAtPoint(e.getPoint());
        if (row < 0 || row >= tableModel.getRowCount()) return;
        ReviewItem item = tableModel.getItem(row);
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem revertItem = new javax.swing.JMenuItem("Revert…", AllIcons.Actions.Rollback);
        revertItem.addActionListener(ev -> showRevertDialog(item));
        menu.add(revertItem);
        menu.show(table, e.getX(), e.getY());
    }

    private void toggleApproval(@NotNull ReviewItem item) {
        AgentEditSession session = AgentEditSession.getInstance(project);
        if (item.approved()) {
            session.unapproveFile(item.path());
        } else {
            session.acceptFile(item.path());
        }
    }

    private void navigateToFile(@NotNull ReviewItem item) {
        if (item.status() == ReviewItem.Status.DELETED) return;
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(item.path());
        if (vf != null) {
            FileEditorManager.getInstance(project).openFile(vf, true);
        }
    }

    private void showRevertDialog(@NotNull ReviewItem item) {
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(item.path());
        if (vf == null) return;
        AgentEditSession session = AgentEditSession.getInstance(project);
        RevertReasonDialog dialog = new RevertReasonDialog(project, vf, item.relativePath(), session.isGateActive());
        if (!dialog.showAndGet()) return;
        AgentEditSession.RevertGateAction gateAction = switch (dialog.getResult()) {
            case CONTINUE_REVIEWING -> AgentEditSession.RevertGateAction.CONTINUE_REVIEWING;
            case SEND_NOW -> AgentEditSession.RevertGateAction.SEND_NOW;
            default -> AgentEditSession.RevertGateAction.DEFAULT;
        };
        session.revertFile(item.path(), dialog.getReason(), gateAction);
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

    // ── Toolbar actions ───────────────────────────────────────────────────────

    private static final class AutoApproveToggleAction extends ToggleAction {
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
            if (state) {
                AgentEditSession.getInstance(project).onAutoApproveTurnedOn();
            }
            project.getMessageBus().syncPublisher(ReviewSessionTopic.TOPIC).reviewStateChanged();
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

    // ── Table model ───────────────────────────────────────────────────────────

    private static final class ReviewTableModel extends AbstractTableModel {
        static final int COL_FILE = 0;
        static final int COL_APPROVE = 1;
        static final int COL_REMOVE = 2;
        private static final String[] COLUMN_NAMES = {"File", "", ""};

        private final List<ReviewItem> items = new ArrayList<>();

        void setItems(List<ReviewItem> newItems) {
            items.clear();
            items.addAll(newItems);
            fireTableDataChanged();
        }

        @NotNull ReviewItem getItem(int row) {
            return items.get(row);
        }

        @Override
        public int getRowCount() {
            return items.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMN_NAMES.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMN_NAMES[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return items.get(rowIndex);
        }
    }

    // ── Cell renderers ────────────────────────────────────────────────────────

    /**
     * File name column: shows file name, animated diff-colored line counts, and timestamp.
     */
    private final class FileCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            ReviewItem item = value instanceof ReviewItem ri ? ri : null;
            super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column);

            if (item == null) return this;

            java.nio.file.Path p = java.nio.file.Path.of(item.path());
            String fileName = p.getFileName() != null ? p.getFileName().toString() : item.path();

            boolean approved = item.approved();
            long now = System.currentTimeMillis();
            setText(buildHtml(fileName, item, isSelected, diffCountAnimator.displayCounts(item, now)));
            setToolTipText(item.relativePath() + (approved ? " · Approved" : " · Pending review"));
            return this;
        }

        private @NotNull String buildHtml(@NotNull String fileName, @NotNull ReviewItem item,
                                          boolean isSelected, @NotNull ReviewDiffCountAnimator.DiffCounts counts) {
            StringBuilder sb = new StringBuilder("<html>");
            if (!isSelected) {
                Color statusColor = switch (item.status()) {
                    case ADDED -> STATUS_ADDED;
                    case MODIFIED -> STATUS_MODIFIED;
                    case DELETED -> STATUS_DELETED;
                };
                sb.append(colorSpan(statusColor, escapeHtml(fileName)));
            } else {
                sb.append(escapeHtml(fileName));
            }

            // Diff-colored line counts
            if (counts.added() > 0 || counts.removed() > 0) {
                sb.append(" <font size='-2'>");
                if (counts.added() > 0) {
                    sb.append(colorSpan(DIFF_GREEN, "+" + counts.added()));
                }
                if (counts.removed() > 0) {
                    if (counts.added() > 0) sb.append(" ");
                    sb.append(colorSpan(DIFF_RED, "-" + counts.removed()));
                }
                sb.append(FONT_CLOSE);
            }

            // Timestamp
            if (item.lastEditedMillis() > 0) {
                sb.append(" <font size='-2' color='gray'>");
                sb.append(TimestampDisplayFormatter.formatEpochMillis(item.lastEditedMillis()));
                sb.append(FONT_CLOSE);
            }

            sb.append("</html>");
            return sb.toString();
        }

        private static @NotNull String escapeHtml(@NotNull String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    /**
     * Renders the approve column as a toolbar-style icon toggle button.
     * Uses {@link JBUI.CurrentTheme.ActionButton#pressedBackground()} for the highlighted
     * background when approved — the exact same tint IntelliJ uses for toggled toolbar buttons —
     * so the inline cell button and the Auto-Approve toolbar toggle share the same visual language.
     */
    private static final class ApproveToggleRenderer extends JLabel implements TableCellRenderer {
        private boolean approved;

        ApproveToggleRenderer() {
            setHorizontalAlignment(CENTER);
            setOpaque(false);
            setIcon(AllIcons.Actions.Checked);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            if (value instanceof ReviewItem item) {
                approved = item.approved();
                setToolTipText(approved ? "Approved — click to unapprove" : "Approve this change");
            }
            setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getBackground());
            g2.fillRect(0, 0, getWidth(), getHeight());
            if (approved) {
                int size = JBUI.scale(22);
                int x = (getWidth() - size) / 2;
                int y = (getHeight() - size) / 2;
                g2.setColor(JBUI.CurrentTheme.ActionButton.pressedBackground());
                g2.fillRoundRect(x, y, size, size, JBUI.scale(4), JBUI.scale(4));
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /**
     * Right-hand action column: rollback icon for pending rows (opens reject dialog),
     * X icon for approved rows (removes from list).
     */
    private static final class RejectOrRemoveRenderer extends JLabel implements TableCellRenderer {

        RejectOrRemoveRenderer() {
            setHorizontalAlignment(CENTER);
            setOpaque(false);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
            if (value instanceof ReviewItem item) {
                if (item.approved()) {
                    setIcon(AllIcons.Actions.Close);
                    setToolTipText("Remove from list");
                } else {
                    setIcon(AllIcons.Actions.Rollback);
                    setToolTipText("Reject this change…");
                }
            }
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(getBackground());
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }
}

