package com.github.catatafishen.agentbridge.psi.tools.memory;

import com.github.catatafishen.agentbridge.memory.MemoryService;
import com.github.catatafishen.agentbridge.memory.embedding.EmbeddingService;
import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import com.github.catatafishen.agentbridge.memory.store.MemoryQuery;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * L2 on-demand recall: filtered search within a specific wing and room.
 * More targeted than memory_search — use when you know the topic area.
 *
 * <p><b>Attribution:</b> L2 layer adapted from MemPalace's layers.py (MIT License).
 */
public final class MemoryRecallTool extends Tool {

    private static final String PARAM_QUERY = "query";
    private static final String PARAM_LIMIT = "limit";

    MemoryRecallTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "memory_recall";
    }

    @Override
    public @NotNull String displayName() {
        return "Memory Recall";
    }

    @Override
    public @NotNull String description() {
        return "Targeted recall from a specific room in memory. More focused than memory_search "
            + "— use when you know the topic area (e.g. 'codebase', 'debugging', 'decisions'). "
            + "Returns drawers filtered by wing and room with optional text query.";
    }

    @Override
    public @NotNull ToolDefinition.Kind kind() {
        return ToolDefinition.Kind.SEARCH;
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.MEMORY;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("room", TYPE_STRING, "Room to recall from (e.g. 'codebase', 'debugging', 'workflow', 'decisions', 'preferences')"),
            Param.optional(PARAM_QUERY, TYPE_STRING, "Optional search query within the room"),
            Param.optional(PARAM_LIMIT, TYPE_INTEGER, "Max results (default: 5)")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String room = args.get("room").getAsString();
        String queryText = args.has(PARAM_QUERY) ? args.get(PARAM_QUERY).getAsString() : null;
        int limit = args.has(PARAM_LIMIT) ? args.get(PARAM_LIMIT).getAsInt() : 5;

        MemoryService memoryService = MemoryService.getInstance(project);
        MemoryStore store = memoryService.getStore();
        EmbeddingService embedding = memoryService.getEmbeddingService();
        if (store == null || embedding == null) {
            return "Error: Memory is not initialized. Enable it in Settings > AgentBridge > Memory.";
        }

        String wing = memoryService.getEffectiveWing();
        float[] queryEmbedding = queryText != null ? embedding.embed(queryText) : null;
        MemoryQuery query = MemoryQuery.filter()
            .queryText(queryText)
            .queryEmbedding(queryEmbedding)
            .wing(wing)
            .room(room)
            .limit(limit)
            .build();

        List<DrawerDocument.SearchResult> results = store.search(query, queryEmbedding);

        if (results.isEmpty()) {
            return "No memories found in room '" + room + "' (wing: " + wing + ").";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(results.size()).append(" memory(s) from room '").append(room).append("':\n\n");
        for (DrawerDocument.SearchResult result : results) {
            DrawerDocument d = result.drawer();
            sb.append("[").append(d.memoryType()).append("] ").append(d.filedAt()).append('\n');
            MemorySearchTool.appendSourceReference(sb, d);
            sb.append(d.content()).append("\n\n");
        }
        return sb.toString().trim();
    }
}
