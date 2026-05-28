package com.github.catatafishen.agentbridge.ui.side;

import com.github.catatafishen.agentbridge.services.ActiveAgentManager;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.treeStructure.Tree;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tree view of project session / hook files.
 * <p>
 * Two modes:
 * <ul>
 *   <li><b>Session mode</b> — files from the active agent's session directory.
 *       Used by the Plan tab.</li>
 *   <li><b>Hooks mode</b> — {@code *.json} files from a hooks directory.
 *       Used by the Hooks settings panel.</li>
 * </ul>
 */
final class ProjectFilesPanel extends JPanel {

    private final transient Project project;
    private final boolean sessionOnly;
    @Nullable
    private final transient Path hooksDir;
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Project Files");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final Tree tree = new Tree(treeModel);

    ProjectFilesPanel(@NotNull Project project, boolean sessionOnly) {
        this(project, sessionOnly, null);
    }

    /** Creates a panel in hooks mode, listing {@code *.json} files from {@code hooksDir}. */
    ProjectFilesPanel(@NotNull Project project, @NotNull Path hooksDir) {
        this(project, false, hooksDir);
    }

    private ProjectFilesPanel(@NotNull Project project, boolean sessionOnly, @Nullable Path hooksDir) {
        super(new BorderLayout());
        this.project = project;
        this.sessionOnly = sessionOnly;
        this.hooksDir = hooksDir;
        setOpaque(false);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new FileNodeRenderer());
        tree.getSelectionModel().setSelectionMode(
            javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.putClientProperty("JTree.lineStyle", "None");
        tree.setOpaque(false);
        tree.setBackground(new JBColor(new Color(0, 0, 0, 0), new Color(0, 0, 0, 0)));
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path == null) return;
                Object last = path.getLastPathComponent();
                if (last instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof FileNode fn) {
                    activate(fn);
                }
            }
        });

        add(tree, BorderLayout.CENTER);
        refresh();
    }

    void refresh() {
        root.removeAllChildren();
        if (project.getBasePath() == null) {
            treeModel.reload();
            return;
        }

        if (hooksDir != null) {
            addHooksSection();
        } else if (sessionOnly) {
            addSessionSection();
        }

        treeModel.reload();
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }

    /**
     * In session-only mode, adds files directly to the root (no section header node)
     * since the outer panel label already serves as the section heading.
     */
    private void addSessionSection() {
        try {
            var manager = ActiveAgentManager.getInstance(project);
            var client = manager.getClientIfRunning();
            if (client == null) return;
            Path sessionDir = client.getSessionDirectory();
            if (sessionDir == null || !Files.isDirectory(sessionDir)) return;

            List<FileNode> sessionFiles = listSessionFiles(sessionDir.toFile(), sessionDir.toFile());
            sessionFiles.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
            for (FileNode fn : sessionFiles) {
                root.add(new DefaultMutableTreeNode(fn));
            }
        } catch (Exception ignored) {
            // agent may not be started yet
        }
    }

    /**
     * In hooks mode, lists all {@code *.json} files from the hooks directory
     * directly in the root (no collapsible section node — the outer panel already
     * provides the "Hooks" label as a heading).
     */
    private void addHooksSection() {
        if (hooksDir == null || !Files.isDirectory(hooksDir)) return;
        String hooksBase = hooksDir.getParent() != null ? hooksDir.getParent().toString()
            : hooksDir.toString();
        try {
            List<FileNode> nodes = new ArrayList<>();
            try (var stream = Files.newDirectoryStream(hooksDir, "*.json")) {
                for (Path p : stream) {
                    String rel = hooksDir.relativize(p).toString();
                    nodes.add(new FileNode(hooksBase, hooksDir.getFileName() + "/" + rel,
                        p.getFileName().toString()));
                }
            }
            nodes.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
            for (FileNode fn : nodes) {
                root.add(new DefaultMutableTreeNode(fn));
            }
        } catch (IOException ignored) {
            // hooks dir may not exist yet
        }
    }

    /**
     * Recursively lists files under the session directory, using relative paths as labels.
     * Files with unrecognized file types are skipped — they're almost always noise.
     */
    private static @NotNull List<FileNode> listSessionFiles(@NotNull File root, @NotNull File dir) {
        List<FileNode> results = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children == null) return results;
        for (File child : children) {
            if (child.isDirectory()) {
                results.addAll(listSessionFiles(root, child));
            } else if (isRecognizedFileType(child.getName())) {
                String rel = root.toURI().relativize(child.toURI()).getPath();
                results.add(new FileNode(root.getAbsolutePath(), rel, rel));
            }
        }
        return results;
    }

    private static boolean isRecognizedFileType(@NotNull String fileName) {
        return !(FileTypeManager.getInstance().getFileTypeByFileName(fileName) instanceof UnknownFileType);
    }

    private void activate(FileNode fn) {
        File file = new File(fn.base, fn.relativePath);
        if (!file.exists()) return;
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
        if (vf != null) {
            FileEditorManager.getInstance(project).openFile(vf, true);
        }
        refresh();
    }

    /**
     * One leaf entry in the tree. {@code exists} is captured at construction time so the
     * tree renderer does not stat the filesystem on every repaint (would run on the EDT).
     */
    static final class FileNode {
        final String base;
        final String relativePath;
        final String label;
        final boolean exists;

        FileNode(String base, String relativePath, String label) {
            this.base = base;
            this.relativePath = relativePath;
            this.label = label;
            this.exists = new File(base, relativePath).exists();
        }

        @Override
        public String toString() {
            return label;
        }

        Icon icon() {
            if (!exists) return AllIcons.Actions.IntentionBulbGrey;
            int dot = relativePath.lastIndexOf('.');
            String ext = dot >= 0 ? relativePath.substring(dot + 1) : "";
            Icon icon = FileTypeManager.getInstance().getFileTypeByExtension(ext).getIcon();
            return icon != null ? icon : AllIcons.FileTypes.Text;
        }
    }

    /**
     * Tree cell renderer that paints a selection background only on the actually-selected
     * row and stays fully transparent otherwise.
     */
    private static final class FileNodeRenderer extends DefaultTreeCellRenderer {
        private boolean rowSelected;

        FileNodeRenderer() {
            setBackgroundNonSelectionColor(null);
            setBorderSelectionColor(null);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            setBackgroundNonSelectionColor(null);
            setBorderSelectionColor(null);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                                                      boolean expanded, boolean leaf, int row,
                                                      boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            this.rowSelected = sel;
            setOpaque(sel);
            if (sel) {
                setBackground(com.intellij.util.ui.UIUtil.getTreeSelectionBackground(hasFocus));
                setForeground(com.intellij.util.ui.UIUtil.getTreeSelectionForeground(hasFocus));
            } else {
                setBackground(null);
                setForeground(com.intellij.util.ui.UIUtil.getTreeForeground());
            }
            setFont(tree.getFont());
            if (value instanceof DefaultMutableTreeNode node) {
                Object userObject = node.getUserObject();
                if (userObject instanceof FileNode fn) {
                    setIcon(fn.icon());
                    setText(fn.label);
                    setToolTipText(fn.relativePath);
                } else if (userObject instanceof String label) {
                    setIcon(null);
                    setText(label);
                    setToolTipText(null);
                    setFont(tree.getFont().deriveFont(Font.BOLD));
                    if (!sel) {
                        setForeground(com.intellij.util.ui.JBUI.CurrentTheme
                            .Label.disabledForeground());
                    }
                }
            }
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (!rowSelected) {
                setOpaque(false);
            }
            super.paintComponent(g);
        }
    }
}
