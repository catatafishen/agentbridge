package com.github.catatafishen.agentbridge.ui.graph;

import com.github.catatafishen.agentbridge.psi.graph.CodeGraphStore;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.panels.VerticalLayout;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Explorer tab for the Knowledge Graph tool window.
 * Sortable table of files with dependency and commit counts,
 * plus a detail panel showing deps/dependents/commits for the selected file.
 */
public final class KnowledgeGraphExplorerPanel implements Disposable {

    private final Project project;
    private final JPanel root = new JPanel(new BorderLayout());
    private final ExplorerTableModel tableModel = new ExplorerTableModel();
    private final JBTable table = new JBTable(tableModel);
    private final JPanel detailPanel = new JPanel(new VerticalLayout(JBUI.scale(4)));
    private final SearchTextField searchField = new SearchTextField();

    private List<CodeGraphStore.ExplorerRow> allRows = List.of();

    public KnowledgeGraphExplorerPanel(@NotNull Project project) {
        this.project = project;
        build();
        loadData();

        root.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0
                && root.isShowing()) {
                loadData();
            }
        });
    }

    public @NotNull JComponent getComponent() {
        return root;
    }

    @Override
    public void dispose() {
        // no resources to release
    }

    private void build() {
        // Search bar
        JPanel topBar = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        topBar.setBorder(JBUI.Borders.empty(8));
        searchField.addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });
        topBar.add(new JBLabel("Search: "), BorderLayout.WEST);
        topBar.add(searchField, BorderLayout.CENTER);
        root.add(topBar, BorderLayout.NORTH);

        // Table — file column takes ~50% width, numeric columns share the rest
        table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        table.getColumnModel().getColumn(0).setPreferredWidth(500);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(80);
        table.getColumnModel().getColumn(3).setPreferredWidth(80);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onRowSelected();
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    navigateToSelectedFile();
                }
            }
        });

        // Split: table on top, detail below
        OnePixelSplitter splitPane = new OnePixelSplitter(true, 0.6f);
        splitPane.setFirstComponent(new JBScrollPane(table));

        JBScrollPane detailScroll = new JBScrollPane(detailPanel);
        detailPanel.setBorder(JBUI.Borders.empty(8));
        splitPane.setSecondComponent(detailScroll);

        root.add(splitPane, BorderLayout.CENTER);
    }

    private void loadData() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<CodeGraphStore.ExplorerRow> rows = CodeGraphStore.getInstance(project).getExplorerRows(500);
            ApplicationManager.getApplication().invokeLater(() -> {
                allRows = rows;
                applyFilter();
            }, ModalityState.nonModal());
        });
    }

    private void applyFilter() {
        String query = searchField.getText().trim().toLowerCase();
        List<CodeGraphStore.ExplorerRow> filtered;
        if (query.isEmpty()) {
            filtered = allRows;
        } else {
            filtered = new ArrayList<>();
            for (CodeGraphStore.ExplorerRow row : allRows) {
                if (row.path().toLowerCase().contains(query)) {
                    filtered.add(row);
                }
            }
        }
        tableModel.setData(filtered);
    }

    private void onRowSelected() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            detailPanel.removeAll();
            detailPanel.revalidate();
            detailPanel.repaint();
            return;
        }
        int modelRow = table.convertRowIndexToModel(viewRow);
        CodeGraphStore.ExplorerRow row = tableModel.getRow(modelRow);
        if (row == null) return;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            CodeGraphStore.FileDetail detail = CodeGraphStore.getInstance(project).getFileDetail(row.path());
            ApplicationManager.getApplication().invokeLater(() -> showDetail(detail), ModalityState.nonModal());
        });
    }

    private void showDetail(@NotNull CodeGraphStore.FileDetail detail) {
        detailPanel.removeAll();

        JBLabel titleLabel = new JBLabel(detail.path());
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        detailPanel.add(titleLabel);

        // Dependencies
        detailPanel.add(buildSectionLabel("Dependencies (" + detail.dependencies().size() + ")"));
        for (String dep : detail.dependencies()) {
            JBLabel label = new JBLabel("  → " + shortPath(dep));
            label.setToolTipText(dep);
            detailPanel.add(label);
        }

        // Dependents
        detailPanel.add(buildSectionLabel("Dependents (" + detail.dependents().size() + ")"));
        for (String dep : detail.dependents()) {
            JBLabel label = new JBLabel("  ← " + shortPath(dep));
            label.setToolTipText(dep);
            detailPanel.add(label);
        }

        // Recent commits
        if (!detail.commits().isEmpty()) {
            detailPanel.add(buildSectionLabel("Recent commits"));
            for (CodeGraphStore.CommitSummary c : detail.commits()) {
                String text = "  " + c.shortHash() + "  " + truncate(c.message(), 50) + " (" + c.author() + ")";
                detailPanel.add(new JBLabel(text));
            }
        }

        detailPanel.revalidate();
        detailPanel.repaint();
    }

    private void navigateToSelectedFile() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) return;
        int modelRow = table.convertRowIndexToModel(viewRow);
        CodeGraphStore.ExplorerRow row = tableModel.getRow(modelRow);
        if (row == null) return;

        String basePath = project.getBasePath();
        if (basePath == null) return;
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(basePath + "/" + row.path());
        if (vf != null) {
            new OpenFileDescriptor(project, vf).navigate(true);
        }
    }

    private static @NotNull JBLabel buildSectionLabel(@NotNull String text) {
        JBLabel label = new JBLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize2D() - 1));
        label.setForeground(JBColor.GRAY);
        label.setBorder(JBUI.Borders.emptyTop(8));
        return label;
    }

    private static @NotNull String shortPath(@NotNull String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private static @NotNull String truncate(@NotNull String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ── Table model ──────────────────────────────────────────────────────────

    static final class ExplorerTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"File", "Deps", "Dependents", "Commits"};
        private List<CodeGraphStore.ExplorerRow> data = List.of();

        void setData(@NotNull List<CodeGraphStore.ExplorerRow> rows) {
            this.data = rows;
            fireTableDataChanged();
        }

        CodeGraphStore.ExplorerRow getRow(int index) {
            if (index < 0 || index >= data.size()) return null;
            return data.get(index);
        }

        @Override
        public int getRowCount() {
            return data.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? String.class : Integer.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            CodeGraphStore.ExplorerRow row = data.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.path();
                case 1 -> row.depCount();
                case 2 -> row.dependentCount();
                case 3 -> row.commitCount();
                default -> "";
            };
        }
    }
}
