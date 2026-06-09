package com.github.catatafishen.agentbridge.ui.graph;

import com.github.catatafishen.agentbridge.psi.graph.CodeGraphStore;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Title bar action that exports the Knowledge Graph to a JSON file (⤓).
 */
final class KnowledgeGraphExportAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(KnowledgeGraphExportAction.class);

    private final Project project;

    KnowledgeGraphExportAction(@NotNull Project project) {
        super("Export JSON", "Export knowledge graph as JSON", AllIcons.ToolbarDecorator.Export);
        this.project = project;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        FileSaverDescriptor descriptor = new FileSaverDescriptor(
            "Export Knowledge Graph", "Choose a destination file", "json");
        VirtualFileWrapper target = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save("code-graph.json");
        if (target == null) return;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                writeNodeLinkJson(target.getFile().toPath());
            } catch (Exception ex) {
                LOG.warn("Code graph export failed", ex);
            }
        });
    }

    private void writeNodeLinkJson(@NotNull Path path) throws IOException, SQLException {
        CodeGraphStore store = CodeGraphStore.getInstance(project);
        List<Map<String, Object>> nodes = store.queryRaw(
            "SELECT id, label, kind, fqn, source_file, source_line, language FROM graph_nodes");
        List<Map<String, Object>> edges = store.queryRaw(
            "SELECT source_id, target_id, relation, source_file, source_line FROM graph_edges");
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        com.google.gson.JsonArray nodesArr = new com.google.gson.JsonArray();
        for (Map<String, Object> n : nodes) nodesArr.add(toJson(n));
        com.google.gson.JsonArray linksArr = new com.google.gson.JsonArray();
        for (Map<String, Object> edge : edges) {
            com.google.gson.JsonObject link = toJson(edge);
            link.add("source", link.remove("source_id"));
            link.add("target", link.remove("target_id"));
            linksArr.add(link);
        }
        json.add("nodes", nodesArr);
        json.add("links", linksArr);
        try (OutputStream out = java.nio.file.Files.newOutputStream(path)) {
            out.write(new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                .toJson(json).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static @NotNull com.google.gson.JsonObject toJson(@NotNull Map<String, Object> row) {
        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            Object v = entry.getValue();
            if (v == null) obj.add(entry.getKey(), com.google.gson.JsonNull.INSTANCE);
            else if (v instanceof Number n) obj.addProperty(entry.getKey(), n);
            else if (v instanceof Boolean b) obj.addProperty(entry.getKey(), b);
            else obj.addProperty(entry.getKey(), v.toString());
        }
        return obj;
    }
}
