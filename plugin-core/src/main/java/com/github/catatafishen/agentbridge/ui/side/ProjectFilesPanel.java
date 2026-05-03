package com.github.catatafishen.agentbridge.ui.side;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
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
 * Tree view of project agent-definition / instruction files.
 * <p>
 * In normal mode, shows sections for each supported agent client (Copilot CLI, OpenCode,
 * Junie, Kiro). In session-only mode (used by the Plan tab), shows session files from the
 * active agent's session directory directly in the root (no extra section header node, since
 * the outer panel already provides a label). In hooks mode, lists {@code *.json} files from
 * the hooks directory.
 * <ul>
 *   <li>Copilot CLI — {@code .agent-work/copilot/{agents,skills,instructions}}</li>
 *   <li>OpenCode — {@code .agent-work/opencode/agent/*.md}</li>
 *   <li>Junie — {@code .agent-work/junie/{guidelines.md, agents/*.md}}</li>
 *   <li>Kiro — {@code .agent-work/kiro/{agents/*.json, skills/SKILL.md}}</li>
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

    ProjectFilesPanel(@NotNull Project project) {
        this(project, false);
    }

    ProjectFilesPanel(@NotNull Project project, boolean sessionOnly) {
        this(project, sessionOnly, null);
    }

    /**
     * Creates a panel in hooks mode, listing {@code *.json} files from {@code hooksDir}.
     */
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
        // Single-selection only — discontiguous selection (the JTree default) was
        // letting the selection model report multiple paths after expand/collapse,
        // which lit every file row blue as if all were selected.
        tree.getSelectionModel().setSelectionMode(
            javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
        // Disable the connector/branch guide line: in some L&Fs it paints a thin
        // selection-tinted band down through the selected node's descendants,
        // which read as "child rows are selected too".
        tree.putClientProperty("JTree.lineStyle", "None");
        // Let the parent's background show through so dark mode doesn't
        // paint a gray rectangle behind the file rows.
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

        // No internal scroll pane: the tree expands to its preferred height so the
        // outer SessionStatsPanel scroll pane scrolls the whole side panel as one.
        add(tree, BorderLayout.CENTER);
        refresh();
    }

    void refresh() {
        root.removeAllChildren();
        String base = project.getBasePath();
        if (base == null) {
            treeModel.reload();
            return;
        }

        if (hooksDir != null) {
            addHooksSection();
        } else if (sessionOnly) {
            addSessionSection();
        } else {
            List<FileNode> copilot = new ArrayList<>();
            copilot.addAll(glob(base, ".agent-work/copilot/agents", "*.md"));
            copilot.addAll(glob(base, ".agent-work/copilot/skills", "*/SKILL.md"));
            copilot.addAll(glob(base, ".agent-work/copilot/instructions", "*.instructions.md"));
            addSection("Copilot CLI", copilot);

            addSection("OpenCode", glob(base, ".agent-work/opencode/agent", "*.md"));

            List<FileNode> junie = new ArrayList<>();
            File junieGuidelines = new File(base, ".agent-work/junie/guidelines.md");
            if (junieGuidelines.exists()) {
                junie.add(new FileNode(base, ".agent-work/junie/guidelines.md", "guidelines.md", false));
            }
            junie.addAll(glob(base, ".agent-work/junie/agents", "*.md"));
            addSection("Junie", junie);

            List<FileNode> kiro = new ArrayList<>();
            kiro.addAll(glob(base, ".agent-work/kiro/agents", "*.json"));
            kiro.addAll(glob(base, ".agent-work/kiro/skills", "*/SKILL.md"));
            addSection("Kiro", kiro);
        }

        treeModel.reload();
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }

    private void addSection(@NotNull String title, @NotNull List<FileNode> nodes) {
        if (nodes.isEmpty()) return;
        DefaultMutableTreeNode section = new DefaultMutableTreeNode(title);
        for (FileNode fn : nodes) {
            section.add(new DefaultMutableTreeNode(fn));
        }
        root.add(section);
    }

    /**
     * In session-only mode, adds files directly to the root (no section header node)
     * since the outer panel label already serves as the section heading.
     */
    private void addSessionSection() {
        try {
            var manager = ActiveAgentManager.getInstance(project);
            var client = manager.getClient();
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
     * In hooks mode, lists all {@code *.json} files from the hooks directory,
     * grouped under a "Hook Files" section node.
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
                        p.getFileName().toString(), false));
                }
            }
            nodes.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
            addSection("Hook Files", nodes);
        } catch (IOException ignored) {
            // hooks dir may not exist yet
        }
    }

    /**
     * Recursively lists files under the session directory, using relative paths as labels.
     * Files with unrecognized file types (rendered with the generic "?" icon) are skipped
     * — they're almost always noise (temp files, internal state) rather than content the
     * user wants to open.
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
                results.add(new FileNode(root.getAbsolutePath(), rel, rel, false));
            }
        }
        return results;
    }

    private static boolean isRecognizedFileType(@NotNull String fileName) {
        return !(FileTypeManager.getInstance().getFileTypeByFileName(fileName) instanceof UnknownFileType);
    }

    /**
     * Lists files matching a simple glob below {@code base/dirPath}.
     * <p>
     * Patterns support a single {@code *} wildcard, and a {@code (star)/fileName}
     * form to look one level deep (e.g. {@code "(star)/SKILL.md"}).
     */
    static @NotNull List<FileNode> glob(@NotNull String base, @NotNull String dirPath, @NotNull String pattern) {
        File dir = new File(base, dirPath);
        if (!dir.exists()) return List.of();
        List<FileNode> results = pattern.contains("/")
            ? globNestedFileName(base, dir, pattern.substring(pattern.indexOf('/') + 1))
            : globFlatPattern(base, dir, pattern);
        results.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return results;
    }

    private static @NotNull List<FileNode> globNestedFileName(String base, File dir, String fileName) {
        List<FileNode> results = new ArrayList<>();
        File[] subs = dir.listFiles(File::isDirectory);
        if (subs == null) return results;
        for (File sub : subs) {
            File target = new File(sub, fileName);
            if (target.isFile()) {
                results.add(new FileNode(base, relativize(base, target), sub.getName() + "/" + fileName, false));
            }
        }
        return results;
    }

    private static @NotNull List<FileNode> globFlatPattern(String base, File dir, String pattern) {
        List<FileNode> results = new ArrayList<>();
        String prefix;
        String suffix;
        int star = pattern.indexOf('*');
        if (star < 0) {
            prefix = pattern;
            suffix = "";
        } else {
            prefix = pattern.substring(0, star);
            suffix = pattern.substring(star + 1);
        }
        File[] files = dir.listFiles((f, name) -> {
            File candidate = new File(f, name);
            return candidate.isFile() && name.startsWith(prefix) && name.endsWith(suffix)
                && name.length() >= prefix.length() + suffix.length();
        });
        if (files == null) return results;
        for (File f : files) {
            results.add(new FileNode(base, relativize(base, f), f.getName(), false));
        }
        return results;
    }

    static @NotNull String relativize(@NotNull String base, @NotNull File file) {
        return new File(base).toURI().relativize(file.toURI()).getPath();
    }

    private void activate(FileNode fn) {
        File file = new File(fn.base, fn.relativePath);
        if (!file.exists()) {
            if (!fn.createIfMissing) return;
            VirtualFile created = createEmptyFile(file);
            if (created == null) return;
            FileEditorManager.getInstance(project).openFile(created, true);
            refresh();
            return;
        }
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
        if (vf != null) {
            FileEditorManager.getInstance(project).openFile(vf, true);
        }
        refresh();
    }

    /**
     * Creates an empty file via VFS inside a {@link com.intellij.openapi.application.WriteAction},
     * so VFS events (and the file-type/index pipeline) stay consistent. Surfaces an error notification
     * if creation fails — silently swallowing here would make the click appear to do nothing.
     */
    private @org.jetbrains.annotations.Nullable VirtualFile createEmptyFile(@NotNull File file) {
        File parent = file.getParentFile();
        try {
            if (parent != null) Files.createDirectories(parent.toPath());
        } catch (IOException ex) {
            notifyCreateFailure(file, ex);
            return null;
        }
        VirtualFile parentVf = parent == null ? null
            : LocalFileSystem.getInstance().refreshAndFindFileByIoFile(parent);
        if (parentVf == null) {
            notifyCreateFailure(file, new IOException("parent directory not visible to VFS"));
            return null;
        }
        final VirtualFile[] created = new VirtualFile[1];
        com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction(() -> {
            try {
                created[0] = parentVf.createChildData(this, file.getName());
            } catch (IOException ex) {
                notifyCreateFailure(file, ex);
            }
        });
        return created[0];
    }

    private void notifyCreateFailure(@NotNull File file, @NotNull Throwable cause) {
        String detail = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        PlatformApiCompat.showNotification(
            project,
            "Could not create " + file.getName(),
            detail,
            com.intellij.notification.NotificationType.ERROR);
    }

    /**
     * One leaf entry in the tree. {@code exists} is captured at construction time so the
     * tree renderer does not stat the filesystem on every repaint (would run on the EDT).
     */
    static final class FileNode {
        final String base;
        final String relativePath;
        final String label;
        final boolean createIfMissing;
        final boolean exists;

        FileNode(String base, String relativePath, String label, boolean createIfMissing) {
            this.base = base;
            this.relativePath = relativePath;
            this.label = label;
            this.createIfMissing = createIfMissing;
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
     * row and stays fully transparent otherwise. {@link DefaultTreeCellRenderer}'s default
     * {@code paint()} fills with {@code backgroundNonSelectionColor} (which inherits a
     * panel-grey from the L&F) before drawing icon and text, leaving a visible grey rect
     * behind every row even with {@code setOpaque(false)}. We override {@code paintComponent}
     * to skip the fill entirely when not selected.
     */
    private static final class FileNodeRenderer extends DefaultTreeCellRenderer {
        private boolean rowSelected;

        FileNodeRenderer() {
            // Belt-and-braces: also clear the field DefaultTreeCellRenderer.paint reads from.
            setBackgroundNonSelectionColor(null);
            setBorderSelectionColor(null);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            // L&F change can reset both colors; re-clear so the transparent background
            // and missing focus rectangle survive theme switches.
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
            // Reset font + icon each call — JTree reuses a single renderer instance, so
            // a section node painted right after a file node must not inherit its bold/dim
            // styling, and vice versa.
            setFont(tree.getFont());
            if (value instanceof DefaultMutableTreeNode node) {
                Object userObject = node.getUserObject();
                if (userObject instanceof FileNode fn) {
                    setIcon(fn.icon());
                    setText(fn.label);
                    setToolTipText(fn.relativePath);
                } else if (userObject instanceof String label) {
                    // Section header node (e.g. "Shared", "Copilot CLI"): render dim + bold
                    // with no icon to match the SessionStatsPanel section-header style above.
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
            // When unselected, skip the background fill that DefaultTreeCellRenderer (via
            // JLabel) would otherwise paint. When selected, fall through to the default
            // opaque paint so the row gets the proper selection band.
            if (!rowSelected) {
                setOpaque(false);
            }
            super.paintComponent(g);
        }
    }
}
