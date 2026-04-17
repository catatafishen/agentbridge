package com.github.catatafishen.agentbridge.ui.side;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
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
import java.util.regex.Pattern;

/**
 * Tree view of project agent-definition / instruction files.
 * <p>
 * Mirrors the former title-bar "Project Files" dropdown. Sections:
 * <ul>
 *   <li>Shared — TODO.md, AGENTS.md (created on first click if missing)</li>
 *   <li>Copilot CLI — .agent-work/copilot/{agents,skills,instructions}</li>
 *   <li>OpenCode — .agent-work/opencode/agent/*.md</li>
 *   <li>Junie — .agent-work/junie/{guidelines.md, agents/*.md}</li>
 *   <li>Kiro — .agent-work/kiro/{agents/*.json, skills/* /SKILL.md}</li>
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

        add(new JBScrollPane(tree), BorderLayout.CENTER);
        refresh();
    }

    void refresh() {
        root.removeAllChildren();
        String base = project.getBasePath();
        if (base == null) {
            treeModel.reload();
            return;
        }

        addSection("Shared",
            new FileNode(base, "TODO.md", "TODO", true),
            new FileNode(base, "AGENTS.md", "AGENTS", true)
        );

        addGlobSection("Copilot CLI", base,
            glob(base, ".agent-work/copilot/agents", "*.md"),
            glob(base, ".agent-work/copilot/skills", "*/SKILL.md"),
            glob(base, ".agent-work/copilot/instructions", "*.instructions.md")
        );

        addGlobSection("OpenCode", base,
            glob(base, ".agent-work/opencode/agent", "*.md")
        );

        List<FileNode> junieFiles = new ArrayList<>();
        File junieGuidelines = new File(base, ".agent-work/junie/guidelines.md");
        if (junieGuidelines.exists()) {
            junieFiles.add(new FileNode(base, ".agent-work/junie/guidelines.md", "guidelines.md", false));
        }
        junieFiles.addAll(glob(base, ".agent-work/junie/agents", "*.md"));
        addGlobSection("Junie", base, junieFiles);

        addGlobSection("Kiro", base,
            glob(base, ".agent-work/kiro/agents", "*.json"),
            glob(base, ".agent-work/kiro/skills", "*/SKILL.md")
        );

        treeModel.reload();
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }

    private void addSection(String title, FileNode... nodes) {
        if (nodes.length == 0) return;
        DefaultMutableTreeNode section = new DefaultMutableTreeNode(title);
        for (FileNode fn : nodes) {
            section.add(new DefaultMutableTreeNode(fn));
        }
        root.add(section);
    }

    @SafeVarargs
    private void addGlobSection(String title, String base, List<FileNode>... buckets) {
        List<FileNode> combined = new ArrayList<>();
        for (List<FileNode> bucket : buckets) combined.addAll(bucket);
        if (combined.isEmpty()) return;
        DefaultMutableTreeNode section = new DefaultMutableTreeNode(title);
        for (FileNode fn : combined) {
            section.add(new DefaultMutableTreeNode(fn));
        }
        root.add(section);
    }

    private void addGlobSection(String title, String base, List<FileNode> bucket) {
        addGlobSection(title, base, new List[]{bucket});
    }

    private List<FileNode> glob(String base, String dirPath, String pattern) {
        File dir = new File(base, dirPath);
        if (!dir.exists()) return List.of();
        List<FileNode> results = new ArrayList<>();
        if (pattern.contains("/")) {
            String fileName = pattern.substring(pattern.indexOf('/') + 1);
            File[] subs = dir.listFiles(File::isDirectory);
            if (subs != null) {
                for (File sub : subs) {
                    File target = new File(sub, fileName);
                    if (target.isFile()) {
                        String rel = relativize(base, target);
                        results.add(new FileNode(base, rel, sub.getName() + "/" + fileName, false));
                    }
                }
            }
        } else {
            Pattern rx = Pattern.compile("^" + Pattern.quote(pattern).replace("*", "\\E.*\\Q") + "$");
            File[] files = dir.listFiles((f, name) -> new File(f, name).isFile() && rx.matcher(name).matches());
            if (files != null) {
                for (File f : files) {
                    String rel = relativize(base, f);
                    results.add(new FileNode(base, rel, f.getName(), false));
                }
            }
        }
        results.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return results;
    }

    private static String relativize(String base, File file) {
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

    /** One leaf entry in the tree. */
    private static final class FileNode {
        final String base;
        final String relativePath;
        final String label;
        final boolean createIfMissing;

        FileNode(String base, String relativePath, String label, boolean createIfMissing) {
            this.base = base;
            this.relativePath = relativePath;
            this.label = label;
            this.createIfMissing = createIfMissing;
        }

        @Override
        public String toString() {
            return label;
        }

        boolean exists() {
            return new File(base, relativePath).exists();
        }

        Icon icon() {
            if (!exists()) return AllIcons.Actions.IntentionBulbGrey;
            String ext = relativePath.contains(".")
                ? relativePath.substring(relativePath.lastIndexOf('.') + 1)
                : "";
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
