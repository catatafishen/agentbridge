package com.github.catatafishen.agentbridge.ui.review;

import com.github.catatafishen.agentbridge.psi.review.AgentEditSession;
import com.github.catatafishen.agentbridge.psi.review.ApprovalState;
import com.github.catatafishen.agentbridge.psi.review.ReviewItem;
import com.github.catatafishen.agentbridge.psi.review.ReviewSessionTopic;
import com.github.catatafishen.agentbridge.psi.review.RevertReasonDialog;
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
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Side panel listing every file the agent has touched in the current
 * {@link AgentEditSession}. The session is always running; entries appear here as soon
 * as the agent edits a file and stay visible (visually muted) after the user accepts
 * them, until they are removed via:
 * <ul>
 *   <li>the per-row <kbd>Delete</kbd> shortcut (approved rows only),</li>
 *   <li>the toolbar <b>Clean Approved</b> button,</li>
 *   <li>the post-commit prune in {@code git_commit},</li>
 *   <li>the optional <b>Auto-clean on new prompt</b> toggle, or</li>
 *   <li>a worktree-changing git operation that wipes the whole session.</li>
 * </ul>
 *
 * <p>The toolbar exposes <b>Auto-Approve</b> (new edits and existing pending rows are
 * approved on toggle-on) and <b>Auto-Clean on new prompt</b> (sweep approved rows when
 * the next user message starts a fresh turn). There is no longer an on/off master
 * switch — diff review is always on.</p>
 */
public final class ReviewChangesPanel extends JPanel implements Disposable {

    private static final String CARD_TABLE = "table";
    private static final String CARD_EMPTY = "empty";

    /** Local action key for the DEL/Backspace shortcut binding. */
    private static final String ACTION_REMOVE_APPROVED = "removeApprovedRow";

    private static final int BUTTON_AREA_WIDTH = 82;
    private static final int META_AREA_WIDTH = 130;

    private final transient Project project;
    private final ReviewTableModel tableModel;
    private final JBTable table;
    private final CardLayout cardLayout;
    private final JPanel cardPanel;
    private final JBLabel emptyLabel;

    public ReviewChangesPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        tableModel = new ReviewTableModel();
        table = new JBTable(tableModel);
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
        // Message-bus connection auto-disposes via the Disposer parent link above.
    }

    public void refresh() {
        AgentEditSession session = AgentEditSession.getInstance(project);
        List<ReviewItem> items = session.getReviewItems();
        tableModel.setItems(items);

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

    private void configureTable() {
        table.setRowHeight(JBUI.scale(32));
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setTableHeader(null);
        table.setExpandableItemsEnabled(false);

        TableColumn statusCol = table.getColumnModel().getColumn(ReviewTableModel.COL_STATUS);
        statusCol.setPreferredWidth(JBUI.scale(28));
        statusCol.setMaxWidth(JBUI.scale(32));
        statusCol.setCellRenderer(new StatusCellRenderer());

        TableColumn fileCol = table.getColumnModel().getColumn(ReviewTableModel.COL_FILE);
        fileCol.setPreferredWidth(JBUI.scale(280));
        fileCol.setCellRenderer(new FileCellRenderer());

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row < 0 || row >= tableModel.getRowCount()) return;

                ReviewItem item = tableModel.getItem(row);
                if (col == ReviewTableModel.COL_FILE) {
                    java.awt.Rectangle cellRect = table.getCellRect(row, ReviewTableModel.COL_FILE, true);
                    int relX = e.getX() - cellRect.x;
                    int buttonStart = cellRect.width - JBUI.scale(BUTTON_AREA_WIDTH);
                    if (relX >= buttonStart) {
                        handleFileActionClick(item, relX - buttonStart);
                    }
                }
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
                if (item.approvalState() == ApprovalState.APPROVED) {
                    AgentEditSession.getInstance(project).removeApproved(item.path());
                }
            }
        });
    }

    private void handleFileActionClick(@NotNull ReviewItem item, int relXInButtonArea) {
        int avail = JBUI.scale(BUTTON_AREA_WIDTH) - JBUI.scale(8);
        int gap = JBUI.scale(2);
        int btnW = (avail - 2 * gap) / 3;
        if (relXInButtonArea < JBUI.scale(4) + btnW + gap) {
            navigateToFile(item);
        } else if (relXInButtonArea < JBUI.scale(4) + 2 * btnW + 2 * gap) {
            AgentEditSession.getInstance(project).acceptFile(item.path());
        } else {
            showRevertDialog(item);
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
                    if (it.approvalState() == ApprovalState.APPROVED) { anyApproved = true; break; }
                }
                e.getPresentation().setEnabled(anyApproved);
            }
        });

        return ActionManager.getInstance().createActionToolbar("ReviewChangesToolbar", group, true);
    }

    // ── Toolbar actions ───────────────────────────────────────────────────────

    /**
     * Toggles auto-approve. When turned on, also sweeps every existing PENDING row to
     * APPROVED via {@link AgentEditSession#onAutoApproveTurnedOn()} so the user doesn't
     * have to click Accept on backlog items.
     */
    private static final class AutoApproveToggleAction extends ToggleAction {
        private final Project project;

        AutoApproveToggleAction(@NotNull Project project) {
            super("Auto-Approve", "Apply agent edits without per-file approval", AllIcons.Actions.Lightning);
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

    /**
     * Toggles "Auto-clean approved on new prompt": when ON, every fresh user turn sweeps
     * approved rows out of the list before the message is sent.
     */
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
        static final int COL_STATUS = 0;
        static final int COL_FILE = 1;
        private static final String[] COLUMN_NAMES = {"", "File"};

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
            ReviewItem item = items.get(rowIndex);
            return switch (columnIndex) {
                case COL_STATUS, COL_FILE -> item;
                default -> null;
            };
        }
    }

    // ── Cell renderers ────────────────────────────────────────────────────────

    private static final class StatusCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            Component c = super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column);
            if (value instanceof ReviewItem item && c instanceof JLabel label) {
                label.setText("");
                label.setHorizontalAlignment(CENTER);
                applyStatusIcon(item, table, isSelected, label);
            }
            return c;
        }

        private static void applyStatusIcon(@NotNull ReviewItem item, @NotNull JTable table,
                                            boolean isSelected, @NotNull JLabel label) {
            switch (item.status()) {
                case ADDED -> {
                    label.setForeground(isSelected ? table.getSelectionForeground()
                        : new JBColor(new Color(0, 128, 0), new Color(80, 200, 80)));
                    label.setIcon(AllIcons.General.Add);
                    label.setToolTipText(approvedTip("Added", item));
                }
                case MODIFIED -> {
                    label.setForeground(isSelected ? table.getSelectionForeground()
                        : new JBColor(new Color(0, 100, 200), new Color(80, 160, 255)));
                    label.setIcon(AllIcons.Actions.Edit);
                    label.setToolTipText(approvedTip("Modified", item));
                }
                case DELETED -> {
                    label.setForeground(isSelected ? table.getSelectionForeground()
                        : new JBColor(new Color(200, 0, 0), new Color(255, 80, 80)));
                    label.setIcon(AllIcons.General.Remove);
                    label.setToolTipText(approvedTip("Deleted", item));
                }
            }
        }

        private static @NotNull String approvedTip(@NotNull String base, @NotNull ReviewItem item) {
            return item.approvalState() == ApprovalState.APPROVED ? base + " · Approved" : base + " · Pending";
        }
    }

    /**
     * Renders a row as: [filename · meta(+N −N · 5m ago)] [view | accept | revert].
     * Approved rows render with muted foreground/background. Pending rows look normal.
     */
    private static final class FileCellRenderer extends DefaultTableCellRenderer {
        private final JPanel cellPanel = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        private final JLabel nameLabel = new JLabel();
        private final JLabel metaLabel = new JLabel();
        private final JPanel centerPanel = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        private final JPanel buttonsPanel = new JPanel(new GridLayout(1, 3, JBUI.scale(2), 0));

        FileCellRenderer() {
            cellPanel.setOpaque(true);
            nameLabel.setOpaque(false);
            metaLabel.setOpaque(false);
            metaLabel.setFont(UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().getSize() - 1f));
            metaLabel.setForeground(JBColor.GRAY);
            metaLabel.setHorizontalAlignment(SwingConstants.RIGHT);
            metaLabel.setPreferredSize(new Dimension(JBUI.scale(META_AREA_WIDTH), 0));

            JButton viewBtn = createButton(AllIcons.General.InspectionsEye, "View file");
            JButton acceptBtn = createButton(AllIcons.Actions.Checked, "Approve");
            JButton revertBtn = createButton(AllIcons.Actions.Rollback, "Revert…");
            buttonsPanel.add(viewBtn);
            buttonsPanel.add(acceptBtn);
            buttonsPanel.add(revertBtn);
            buttonsPanel.setOpaque(false);
            buttonsPanel.setPreferredSize(new Dimension(JBUI.scale(BUTTON_AREA_WIDTH), 0));

            centerPanel.setOpaque(false);
            centerPanel.add(nameLabel, BorderLayout.CENTER);
            centerPanel.add(metaLabel, BorderLayout.EAST);

            cellPanel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            cellPanel.add(centerPanel, BorderLayout.CENTER);
            cellPanel.add(buttonsPanel, BorderLayout.EAST);
        }

        private static JButton createButton(Icon icon, String tooltip) {
            JButton btn = new JButton(icon);
            btn.setToolTipText(tooltip);
            btn.putClientProperty("JButton.buttonType", "borderless");
            btn.setFocusPainted(false);
            btn.setContentAreaFilled(false);
            btn.setBorderPainted(false);
            btn.setOpaque(false);
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            return btn;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            ReviewItem item = value instanceof ReviewItem ri ? ri : null;
            String path = item != null ? item.path() : "";
            String relativePath = item != null ? item.relativePath() : path;
            java.nio.file.Path p = java.nio.file.Path.of(path);
            String fileName = p.getFileName() != null ? p.getFileName().toString() : path;

            boolean approved = item != null && item.approvalState() == ApprovalState.APPROVED;
            String prefix = approved ? "✓ " : "";
            nameLabel.setText(prefix + fileName);
            nameLabel.setToolTipText(relativePath
                + (approved ? " · Approved (DEL to remove)" : " · Pending review"));

            metaLabel.setText(item != null ? buildMeta(item) : "");
            metaLabel.setToolTipText(item != null && item.lastEditedMillis() > 0
                ? "Last edited " + TimestampDisplayFormatter.formatEpochMillis(item.lastEditedMillis())
                : null);

            Color bg = isSelected ? table.getSelectionBackground() : table.getBackground();
            Color fg = isSelected ? table.getSelectionForeground() : table.getForeground();
            if (approved && !isSelected) {
                bg = mute(bg);
                fg = JBColor.GRAY;
            }
            cellPanel.setBackground(bg);
            buttonsPanel.setBackground(bg);
            centerPanel.setBackground(bg);
            nameLabel.setForeground(fg);

            return cellPanel;
        }

        private static @NotNull Color mute(@NotNull Color base) {
            // Slight tint towards the panel background to visually de-emphasise approved rows.
            int r = (base.getRed() + UIUtil.getPanelBackground().getRed()) / 2;
            int g = (base.getGreen() + UIUtil.getPanelBackground().getGreen()) / 2;
            int b = (base.getBlue() + UIUtil.getPanelBackground().getBlue()) / 2;
            return new Color(r, g, b);
        }

        private static @NotNull String buildMeta(@NotNull ReviewItem item) {
            StringBuilder sb = new StringBuilder();
            if (item.linesAdded() > 0 || item.linesRemoved() > 0) {
                sb.append("+").append(item.linesAdded()).append(" −").append(item.linesRemoved());
            }
            if (item.lastEditedMillis() > 0) {
                if (!sb.isEmpty()) sb.append(" · ");
                sb.append(TimestampDisplayFormatter.formatEpochMillis(item.lastEditedMillis()));
            }
            return sb.toString();
        }
    }
}
