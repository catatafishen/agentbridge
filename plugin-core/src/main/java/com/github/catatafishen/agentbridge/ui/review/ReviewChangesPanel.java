package com.github.catatafishen.agentbridge.ui.review;

import com.github.catatafishen.agentbridge.psi.review.AgentEditSession;
import com.github.catatafishen.agentbridge.psi.review.ReviewItem;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
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
public final class ReviewChangesPanel extends JPanel {

    private final Project project;
    private final ReviewTableModel tableModel;
    private final JBTable table;
    private final JBLabel emptyLabel;

    public ReviewChangesPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        tableModel = new ReviewTableModel();
        table = new JBTable(tableModel);
        configureTable();

        JBScrollPane scrollPane = new JBScrollPane(table);

        emptyLabel = new JBLabel("No agent edits to review", SwingConstants.CENTER);
        emptyLabel.setForeground(JBColor.GRAY);

        ActionToolbar toolbar = createToolbar();
        toolbar.setTargetComponent(this);

        add(toolbar.getComponent(), BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        refresh();
    }

    public void refresh() {
        AgentEditSession session = AgentEditSession.getInstance(project);
        List<ReviewItem> items = session.isActive() ? session.getReviewItems() : List.of();
        tableModel.setItems(items);

        boolean hasItems = !items.isEmpty();
        table.setVisible(hasItems);
        if (hasItems) {
            remove(emptyLabel);
        } else if (emptyLabel.getParent() != this) {
            add(emptyLabel, BorderLayout.SOUTH);
        }
        revalidate();
        repaint();
    }

    private void configureTable() {
        table.setRowHeight(JBUI.scale(28));
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);

        // Column widths
        TableColumn statusCol = table.getColumnModel().getColumn(ReviewTableModel.COL_STATUS);
        statusCol.setPreferredWidth(JBUI.scale(70));
        statusCol.setMaxWidth(JBUI.scale(90));
        statusCol.setCellRenderer(new StatusCellRenderer());

        TableColumn fileCol = table.getColumnModel().getColumn(ReviewTableModel.COL_FILE);
        fileCol.setPreferredWidth(JBUI.scale(300));

        TableColumn actionsCol = table.getColumnModel().getColumn(ReviewTableModel.COL_ACTIONS);
        actionsCol.setPreferredWidth(JBUI.scale(140));
        actionsCol.setMaxWidth(JBUI.scale(160));
        actionsCol.setCellRenderer(new ActionsCellRenderer());

        // Click handler for file navigation and action buttons
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row < 0 || row >= tableModel.getRowCount()) return;

                ReviewItem item = tableModel.getItem(row);
                if (col == ReviewTableModel.COL_FILE) {
                    navigateToFile(item);
                } else if (col == ReviewTableModel.COL_ACTIONS) {
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
        // Determine which button was clicked based on x position within the cell
        Rectangle cellRect = table.getCellRect(row, ReviewTableModel.COL_ACTIONS, true);
        int relativeX = point.x - cellRect.x;
        int midpoint = cellRect.width / 2;

        if (relativeX < midpoint) {
            // Accept button (left half)
            AgentEditSession.getInstance(project).acceptFile(item.path());
        } else {
            // Reject button (right half)
            String reason = Messages.showInputDialog(
                project,
                "Reason for rejecting changes to " + item.relativePath() + " (optional):",
                "Reject Agent Edit",
                Messages.getQuestionIcon(),
                null, null
            );
            if (reason == null) return; // user cancelled
            AgentEditSession.getInstance(project).rejectFile(item.path(), reason.isBlank() ? null : reason);
        }
    }

    private @NotNull ActionToolbar createToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();

        group.add(new DumbAwareAction("Accept All", "Accept all agent edits", AllIcons.Actions.Commit) {
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
                AgentEditSession.getInstance(project).endSession();
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

        private static final String[] COLUMN_NAMES = {"Status", "File", "Actions"};

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

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }
    }

    private static final class StatusCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (value instanceof String status && c instanceof JLabel label) {
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

    private static final class ActionsCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            JPanel panel = new JPanel(new GridLayout(1, 2, JBUI.scale(4), 0));
            panel.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());

            JLabel acceptLabel = new JLabel("Accept", AllIcons.Actions.Commit, SwingConstants.CENTER);
            acceptLabel.setForeground(new JBColor(new Color(0, 128, 0), new Color(80, 200, 80)));
            acceptLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            JLabel rejectLabel = new JLabel("Reject", AllIcons.Actions.Rollback, SwingConstants.CENTER);
            rejectLabel.setForeground(new JBColor(new Color(200, 0, 0), new Color(255, 80, 80)));
            rejectLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            panel.add(acceptLabel);
            panel.add(rejectLabel);
            return panel;
        }
    }
}
