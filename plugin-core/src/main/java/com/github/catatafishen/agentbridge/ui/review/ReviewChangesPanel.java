package com.github.catatafishen.agentbridge.ui.review;

import com.github.catatafishen.agentbridge.psi.review.AgentEditSession;
import com.github.catatafishen.agentbridge.psi.review.ReviewItem;
import com.github.catatafishen.agentbridge.psi.review.ReviewSessionTopic;
import com.github.catatafishen.agentbridge.settings.McpServerSettings;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
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
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Panel displaying review items from the current {@link AgentEditSession}.
 * Shows a table of files with status, and per-file accept/reject actions.
 * Toolbar provides bulk accept-all/reject-all and end-session actions.
 */
public final class ReviewChangesPanel extends JPanel implements Disposable {

    private static final String CARD_TABLE = "table";
    private static final String CARD_EMPTY = "empty";

    private final Project project;
    private final ReviewTableModel tableModel;
    private final JBTable table;
    private final CardLayout cardLayout;
    private final JPanel cardPanel;
    private final JPanel emptyStatePanel;
    private final JBLabel emptyLabel;
    private final JButton enableButton;

    public ReviewChangesPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        tableModel = new ReviewTableModel();
        table = new JBTable(tableModel);
        configureTable();

        JBScrollPane scrollPane = new JBScrollPane(table);

        emptyLabel = new JBLabel("", SwingConstants.CENTER);
        emptyLabel.setForeground(JBColor.GRAY);
        enableButton = new JButton("Enable Diff Review");
        enableButton.addActionListener(e -> {
            McpServerSettings.getInstance(project).setReviewAgentEdits(true);
            refresh();
        });
        // Centered column: message on top, button below. Button is only visible when review is off.
        emptyStatePanel = new JPanel(new GridBagLayout());
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        emptyLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        enableButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        column.add(emptyLabel);
        column.add(Box.createVerticalStrut(JBUI.scale(8)));
        column.add(enableButton);
        emptyStatePanel.add(column);

        // CardLayout keeps both children alive — switching cards avoids the
        // bug where add/remove on BorderLayout.CENTER loses the scrollPane.
        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);
        cardPanel.add(scrollPane, CARD_TABLE);
        cardPanel.add(emptyStatePanel, CARD_EMPTY);

        ActionToolbar toolbar = createToolbar();
        toolbar.setTargetComponent(this);
        toolbar.getComponent().setBorder(JBUI.Borders.empty());

        // Toolbar footer styled like the chat input's bottom toolbar:
        // top gray divider line + symmetric padding + a minimum height so it lines
        // up with the chat footer (which is taller due to the ProcessingTimerPanel).
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

        // Own subscription so the panel refreshes whenever the session state changes.
        // Disposed with the panel by the tool-window content.
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
        List<ReviewItem> items = session.isActive() ? session.getReviewItems() : List.of();
        tableModel.setItems(items);

        if (!items.isEmpty()) {
            cardLayout.show(cardPanel, CARD_TABLE);
        } else {
            boolean reviewEnabled = McpServerSettings.getInstance(project).isReviewAgentEdits();
            if (reviewEnabled) {
                emptyLabel.setText("No agent edits to review");
                enableButton.setVisible(false);
            } else {
                emptyLabel.setText("<html><center>Diff Review is off.<br>Agent edits are applied directly without review.</center></html>");
                enableButton.setVisible(true);
            }
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
        table.getTableHeader().setReorderingAllowed(false);

        // Column widths
        TableColumn statusCol = table.getColumnModel().getColumn(ReviewTableModel.COL_STATUS);
        statusCol.setPreferredWidth(JBUI.scale(28));
        statusCol.setMaxWidth(JBUI.scale(32));
        statusCol.setCellRenderer(new StatusCellRenderer());

        TableColumn fileCol = table.getColumnModel().getColumn(ReviewTableModel.COL_FILE);
        fileCol.setPreferredWidth(JBUI.scale(300));
        fileCol.setCellRenderer(new FileCellRenderer());

        TableColumn actionsCol = table.getColumnModel().getColumn(ReviewTableModel.COL_ACTIONS);
        actionsCol.setPreferredWidth(JBUI.scale(210));
        actionsCol.setMaxWidth(JBUI.scale(240));
        actionsCol.setCellRenderer(new ActionsCellRenderer());

        // Click handler for file navigation and action buttons
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row < 0 || row >= tableModel.getRowCount()) return;

                ReviewItem item = tableModel.getItem(row);
                if (col == ReviewTableModel.COL_ACTIONS) {
                    handleActionClick(item, e.getPoint(), row);
                }
            }
        });
    }

    private void navigateToFile(@NotNull ReviewItem item) {
        if (item.status() == ReviewItem.Status.DELETED) return;
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(item.path());
        if (vf != null) {
            FileEditorManager.getInstance(project).openFile(vf, true);
        }
    }

    private void handleActionClick(@NotNull ReviewItem item, @NotNull Point point, int row) {
        Rectangle cellRect = table.getCellRect(row, ReviewTableModel.COL_ACTIONS, true);
        int relX = point.x - cellRect.x - JBUI.scale(4);
        int avail = cellRect.width - JBUI.scale(8);
        int gap = JBUI.scale(2);
        int btnW = (avail - 2 * gap) / 3;

        if (relX < btnW + gap) {
            navigateToFile(item);
        } else if (relX < 2 * btnW + 2 * gap) {
            AgentEditSession.getInstance(project).acceptFile(item.path());
        } else {
            showRejectDialog(item);
        }
    }

    private void showRejectDialog(@NotNull ReviewItem item) {
        String reason = Messages.showInputDialog(
            project,
            "Reason for rejecting changes to " + item.relativePath() + " (optional):",
            "Reject Agent Edit",
            Messages.getQuestionIcon(),
            null, null
        );
        if (reason == null) return;
        AgentEditSession.getInstance(project).rejectFile(item.path(), reason.isBlank() ? null : reason);
    }

    private @NotNull ActionToolbar createToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();

        group.add(new DumbAwareAction("Accept All", "Accept all agent edits", AllIcons.Actions.Checked) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                AgentEditSession.getInstance(project).acceptAll();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(
                    AgentEditSession.getInstance(project).isActive()
                        && AgentEditSession.getInstance(project).hasChanges()
                );
            }
        });

        group.add(new DumbAwareAction("Reject All", "Reject all agent edits and revert", AllIcons.Actions.Rollback) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                String reason = Messages.showInputDialog(
                    project,
                    "Reason for rejecting all changes (optional):",
                    "Reject All Agent Edits",
                    Messages.getWarningIcon(),
                    null, null
                );
                if (reason == null) return;
                AgentEditSession.getInstance(project).rejectAll(reason.isBlank() ? null : reason);
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(
                    AgentEditSession.getInstance(project).isActive()
                        && AgentEditSession.getInstance(project).hasChanges()
                );
            }
        });

        group.addSeparator();

        group.add(new DumbAwareAction("End Review Session", "End the review session without accepting or rejecting",
            AllIcons.Actions.Cancel) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                AgentEditSession session = AgentEditSession.getInstance(project);
                if (session.hasChanges()) {
                    int result = Messages.showOkCancelDialog(
                        project,
                        "You have " + session.getReviewItems().size()
                            + " unreviewed file(s). End the review session?",
                        "End Review Session?",
                        "End Session",
                        "Cancel",
                        Messages.getWarningIcon()
                    );
                    if (result != Messages.OK) return;
                }
                session.endSession();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(AgentEditSession.getInstance(project).isActive());
            }
        });

        return ActionManager.getInstance().createActionToolbar("ReviewChangesPanel", group, true);
    }

    private static final class ReviewTableModel extends AbstractTableModel {

        static final int COL_STATUS = 0;
        static final int COL_FILE = 1;
        static final int COL_ACTIONS = 2;

        private static final String[] COLUMN_NAMES = {"", "File", "Actions"};

        private List<ReviewItem> items = new ArrayList<>();

        void setItems(@NotNull List<ReviewItem> newItems) {
            items = new ArrayList<>(newItems);
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
                case COL_STATUS -> item.status().name();
                case COL_FILE -> item.relativePath();
                case COL_ACTIONS -> "Accept | Reject";
                default -> null;
            };
        }

    }

    private static final class StatusCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (value instanceof String status && c instanceof JLabel label) {
                label.setText("");
                label.setHorizontalAlignment(CENTER);
                switch (status) {
                    case "ADDED" -> {
                        label.setForeground(isSelected ? table.getSelectionForeground()
                            : new JBColor(new Color(0, 128, 0), new Color(80, 200, 80)));
                        label.setIcon(AllIcons.General.Add);
                    }
                    case "MODIFIED" -> {
                        label.setForeground(isSelected ? table.getSelectionForeground()
                            : new JBColor(new Color(0, 100, 200), new Color(80, 160, 255)));
                        label.setIcon(AllIcons.Actions.Edit);
                    }
                    case "DELETED" -> {
                        label.setForeground(isSelected ? table.getSelectionForeground()
                            : new JBColor(new Color(200, 0, 0), new Color(255, 80, 80)));
                        label.setIcon(AllIcons.General.Remove);
                    }
                    default -> label.setIcon(null);
                }
            }
            return c;
        }
    }

    private static final class FileCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (value instanceof String path && c instanceof JLabel label) {
                java.nio.file.Path p = java.nio.file.Path.of(path);
                String filename = p.getFileName() != null ? p.getFileName().toString() : path;
                String parent = p.getParent() != null ? p.getParent().toString() : "";
                String parentDisplay = parent.isEmpty() ? "" : parent + "/";
                String gray = isSelected ? "" : "gray";
                label.setText("<html><b>" + filename + "</b>"
                    + (parentDisplay.isEmpty() ? "" : " <font color='" + gray + "'>" + parentDisplay + "</font>")
                    + "</html>");
                label.setToolTipText(path);
            }
            return c;
        }
    }

    private static final class ActionsCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            JPanel panel = new JPanel(new GridLayout(1, 3, JBUI.scale(2), 0));
            panel.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
            panel.setBorder(JBUI.Borders.empty(2, 4));

            JButton viewBtn = new JButton(AllIcons.General.InspectionsEye);
            viewBtn.setToolTipText("View file");
            styleButton(viewBtn);

            JButton acceptBtn = new JButton(AllIcons.Actions.Checked);
            acceptBtn.setToolTipText("Accept");
            styleButton(acceptBtn);

            JButton rejectBtn = new JButton(AllIcons.Actions.Rollback);
            rejectBtn.setToolTipText("Reject");
            styleButton(rejectBtn);

            panel.add(viewBtn);
            panel.add(acceptBtn);
            panel.add(rejectBtn);
            return panel;
        }

        private static void styleButton(JButton btn) {
            btn.putClientProperty("JButton.buttonType", "borderless");
            btn.setFocusPainted(false);
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
    }
}
