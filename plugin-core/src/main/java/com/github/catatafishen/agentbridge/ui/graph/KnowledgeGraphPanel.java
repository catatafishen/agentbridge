package com.github.catatafishen.agentbridge.ui.graph;

import com.github.catatafishen.agentbridge.psi.graph.CodeGraphIndexer;
import com.github.catatafishen.agentbridge.psi.graph.CodeGraphSettings;
import com.github.catatafishen.agentbridge.psi.graph.CodeGraphStore;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.panels.VerticalLayout;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Sidebar panel for the Knowledge Graph feature.
 *
 * <ul>
 *   <li><b>Enable toggle</b> — turns the feature on/off. Enabling triggers the initial
 *       index build. The tool is always registered via {@link com.github.catatafishen.agentbridge.psi.tools.graph.GraphToolFactory}
 *       but only advertised to agents when enabled (via {@code McpToolFilter}).</li>
 *   <li><b>Stats panel</b> — current node/edge/file counts and last-indexed timestamp.</li>
 *   <li><b>Rebuild button</b> — full background re-index.</li>
 *   <li><b>Export JSON button</b> — writes a node-link JSON graph to a chosen path.</li>
 * </ul>
 */
public final class KnowledgeGraphPanel implements Disposable {

    private static final Logger LOG = Logger.getInstance(KnowledgeGraphPanel.class);
    private static final String TOOL_ID = "query_knowledge_graph";

    private final Project project;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JCheckBox enableCheck = new JCheckBox("Enable Knowledge Graph");
    private final JCheckBox autoRefreshCheck = new JCheckBox("Refresh after agent edits");
    private final JTextArea statsArea = new JTextArea(6, 40);
    private final JButton rebuildButton = new JButton("Rebuild");
    private final JButton exportButton = new JButton("Export JSON…");
    private final JBLabel statusLabel = new JBLabel(" ");
    private Runnable toolChangeDisconnect;

    public KnowledgeGraphPanel(@NotNull Project project) {
        this.project = project;
        build();
        refreshFromSettings();
        refreshStats();

        // Auto-refresh when the panel becomes visible (handles case where
        // tool window is restored before PsiBridgeStartup finishes).
        root.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0
                && root.isShowing()) {
                refreshStats();
            }
        });

        // Deferred refresh: tool registration happens asynchronously during startup.
        // If the panel is already visible when constructed (restored tool window), the
        // hierarchy listener won't fire again. Schedule a retry so the panel picks up
        // the tool registration once PsiBridgeService finishes initializing.
        ApplicationManager.getApplication().invokeLater(this::refreshStats,
            com.intellij.openapi.application.ModalityState.nonModal());

        // Subscribe to tool registry changes so the panel updates from "<pending>"
        // to "yes" as soon as the tool is actually registered.
        toolChangeDisconnect = com.github.catatafishen.agentbridge.psi.PlatformApiCompat
            .subscribeToolsChanged(project, () ->
                ApplicationManager.getApplication().invokeLater(this::refreshStats,
                    com.intellij.openapi.application.ModalityState.nonModal()));
    }

    public @NotNull JComponent getComponent() {
        return root;
    }

    @Override
    public void dispose() {
        if (toolChangeDisconnect != null) {
            toolChangeDisconnect.run();
            toolChangeDisconnect = null;
        }
    }

    private void build() {
        JPanel top = new JPanel(new VerticalLayout(JBUI.scale(6)));
        top.setBorder(JBUI.Borders.empty(10));

        JBLabel description = new JBLabel(
            "<html><body style='width:280px'>"
                + "Persistent index of code structure, git history, and agent activity. "
                + "Powers the <code>query_knowledge_graph</code> MCP tool."
                + "</body></html>");
        top.add(description);

        CodeGraphSettings settings = CodeGraphSettings.getInstance(project);

        enableCheck.addActionListener(e -> {
            boolean on = enableCheck.isSelected();
            settings.setEnabled(on);
            if (on) {
                setStatus("Building graph…");
                CodeGraphIndexer.getInstance(project).rebuildAll(this::onIndexFinished);
            } else {
                setStatus("Disabled — tool hidden from agents.");
                refreshStats();
            }
        });
        top.add(enableCheck);

        autoRefreshCheck.addActionListener(e ->
            settings.setAutoRefreshOnAgentEdit(autoRefreshCheck.isSelected()));
        top.add(autoRefreshCheck);

        statsArea.setEditable(false);
        statsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(11f)));
        statsArea.setBorder(BorderFactory.createLineBorder(JBColor.border(), 1));
        top.add(statsArea);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        rebuildButton.addActionListener(e -> {
            setStatus("Rebuilding…");
            CodeGraphIndexer.getInstance(project).rebuildAll(this::onIndexFinished);
        });
        exportButton.addActionListener(e -> exportJson());
        buttons.add(rebuildButton);
        buttons.add(exportButton);
        top.add(buttons);

        statusLabel.setForeground(JBColor.GRAY);
        top.add(statusLabel);

        root.add(new JBScrollPane(top), BorderLayout.CENTER);
        root.setPreferredSize(new Dimension(JBUI.scale(360), JBUI.scale(500)));
    }

    private void refreshFromSettings() {
        CodeGraphSettings settings = CodeGraphSettings.getInstance(project);
        enableCheck.setSelected(settings.isEnabled());
        autoRefreshCheck.setSelected(settings.isAutoRefreshOnAgentEdit());
    }

    private void refreshStats() {
        CodeGraphStore.GraphStats s = CodeGraphStore.getInstance(project).getStats();
        CodeGraphSettings settings = CodeGraphSettings.getInstance(project);
        boolean toolInRegistry = ToolRegistry.getInstance(project).findById(TOOL_ID) != null;
        boolean advertisedToAgents = toolInRegistry && settings.isEnabled();

        StringBuilder sb = new StringBuilder();
        sb.append("Nodes:         ").append(s.nodeCount()).append('\n');
        sb.append("Edges:         ").append(s.edgeCount()).append('\n');
        sb.append("Files indexed: ").append(s.fileCount()).append('\n');
        sb.append("Commits:       ").append(s.commitCount()).append('\n');
        sb.append("Last indexed:  ").append(formatTime(s.lastIndexedAt())).append('\n');
        sb.append("Tool advertised: ");
        if (!toolInRegistry) {
            sb.append("<pending>");
        } else if (advertisedToAgents) {
            sb.append("yes");
        } else {
            sb.append("no (feature disabled)");
        }
        statsArea.setText(sb.toString());
    }

    private void onIndexFinished() {
        refreshStats();
        setStatus("Build finished — query_knowledge_graph ready.");
    }

    private void exportJson() {
        FileSaverDescriptor descriptor = new FileSaverDescriptor(
            "Export Knowledge Graph", "Choose a destination file", "json");
        VirtualFileWrapper target = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save("code-graph.json");
        if (target == null) return;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                writeNodeLinkJson(target.getFile().toPath());
                ApplicationManager.getApplication().invokeLater(
                    () -> setStatus("Exported to " + target.getFile().getAbsolutePath()));
            } catch (Exception e) {
                LOG.warn("Code graph export failed", e);
                ApplicationManager.getApplication().invokeLater(
                    () -> setStatus("Export failed: " + e.getMessage()));
            }
        });
    }

    private void writeNodeLinkJson(@NotNull java.nio.file.Path path) throws Exception {
        CodeGraphStore store = CodeGraphStore.getInstance(project);
        List<Map<String, Object>> nodes = store.queryRaw(
            "SELECT id, label, kind, fqn, source_file, source_line, language FROM graph_nodes");
        List<Map<String, Object>> edges = store.queryRaw(
            "SELECT source_id, target_id, relation, source_file, source_line FROM graph_edges");
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        com.google.gson.JsonArray nodesArr = new com.google.gson.JsonArray();
        for (Map<String, Object> n : nodes) nodesArr.add(toJson(n));
        com.google.gson.JsonArray linksArr = new com.google.gson.JsonArray();
        for (Map<String, Object> e : edges) {
            com.google.gson.JsonObject link = toJson(e);
            // graphify/d3-style: rename source_id → source, target_id → target
            link.add("source", link.remove("source_id"));
            link.add("target", link.remove("target_id"));
            linksArr.add(link);
        }
        root.add("nodes", nodesArr);
        root.add("links", linksArr);
        try (OutputStream out = java.nio.file.Files.newOutputStream(path)) {
            out.write(new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                .toJson(root).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static @NotNull com.google.gson.JsonObject toJson(@NotNull Map<String, Object> row) {
        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            Object v = e.getValue();
            if (v == null) obj.add(e.getKey(), com.google.gson.JsonNull.INSTANCE);
            else if (v instanceof Number n) obj.addProperty(e.getKey(), n);
            else if (v instanceof Boolean b) obj.addProperty(e.getKey(), b);
            else obj.addProperty(e.getKey(), v.toString());
        }
        return obj;
    }

    private void setStatus(@NotNull String text) {
        ApplicationManager.getApplication().invokeLater(() -> statusLabel.setText(text));
    }

    private static @NotNull String formatTime(long epochMs) {
        if (epochMs <= 0) return "never";
        try {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(epochMs));
        } catch (Exception e) {
            return String.valueOf(epochMs);
        }
    }
}
