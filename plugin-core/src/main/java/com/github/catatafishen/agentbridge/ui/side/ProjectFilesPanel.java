package com.github.catatafishen.agentbridge.ui.side;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

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
import java.util.ArrayList;
import java.util.List;

/**
 * Tree view of project agent-definition / instruction files.
 * <p>
 * Mirrors the former title-bar "Project Files" dropdown. Sections:
 * <ul>
 *   <li>Shared — {@code AGENTS.md} and the agent task-list file, created on first click if missing</li>
 *   <li>Copilot CLI — {@code .agent-work/copilot/{agents,skills,instructions}}</li>
 *   <li>OpenCode — {@code .agent-work/opencode/agent/*.md}</li>
 *   <li>Junie — {@code .agent-work/junie/{guidelines.md, agents/*.md}}</li>
 *   <li>Kiro — {@code .agent-work/kiro/{agents/*.json, skills/SKILL.md}}</li>
 * </ul>
 * Missing "shared" files render with a dim bulb icon and are created empty on click.
 */
final class ProjectFilesPanel extends JPanel {

    private final transient Project project;
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Project Files");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final Tree tree = new Tree(treeModel);

    ProjectFilesPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new FileNodeRenderer());
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

        JBScrollPane scrollPane = new JBScrollPane(tree);
        scrollPane.setBorder(JBUI.Borders.empty());
        add(scrollPane, BorderLayout.CENTER);
        refresh();
    }

    void refresh() {
        root.removeAllChildren();
        String base = project.getBasePath();
        if (base == null) {
            treeModel.reload();
            return;
        }

        addSection("Shared", List.of(
            new FileNode(base, "TODO.md", "TODO", true),
            new FileNode(base, "AGENTS.md", "AGENTS", true)
        ));

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
            try {
                File parent = file.getParentFile();
                if (parent != null) Files.createDirectories(parent.toPath());
                Files.writeString(file.toPath(), "");
            } catch (IOException ex) {
                return;
            }
        }
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

    private static final class FileNodeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                                                      boolean expanded, boolean leaf, int row,
                                                      boolean hasFocus) {
            Component c = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            if (value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof FileNode fn
                && c instanceof JLabel label) {
                label.setIcon(fn.icon());
                label.setText(fn.label);
                label.setToolTipText(fn.relativePath);
            }
            return c;
        }
    }
}
