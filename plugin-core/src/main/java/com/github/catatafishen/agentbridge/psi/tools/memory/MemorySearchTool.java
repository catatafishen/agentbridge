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
 * Semantic search across the memory store. Supports text queries with optional
 * wing/room/type filters.
 *
 * <p><b>Attribution:</b> search API adapted from MemPalace's mempalace_search (MIT License).
 */
public final class MemorySearchTool extends Tool {

    private static final String PARAM_MEMORY_TYPE = "memory_type";
    private static final String PARAM_LIMIT = "limit";

    MemorySearchTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "memory_search";
    }

    @Override
    public @NotNull String displayName() {
        return "Memory Search";
    }

    @Override
    public @NotNull String description() {
        return "Semantic search across the memory store. Returns drawers ranked by relevance. "
            + "Supports optional wing, room, and memory_type filters. "
            + "Use for recalling past decisions, solutions, preferences, and technical context.";
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
            Param.required("query", TYPE_STRING, "Search query text"),
            Param.optional("wing", TYPE_STRING, "Filter by palace wing (project name)"),
            Param.optional("room", TYPE_STRING, "Filter by room (topic category)"),
            Param.optional(PARAM_MEMORY_TYPE, TYPE_STRING, "Filter by type: decision, preference, milestone, problem, technical"),
            Param.optional(PARAM_LIMIT, TYPE_INTEGER, "Max results to return (default: 10)")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String queryText = args.get("query").getAsString();
        String wing = args.has("wing") ? args.get("wing").getAsString() : null;
        String room = args.has("room") ? args.get("room").getAsString() : null;
        String memoryType = args.has(PARAM_MEMORY_TYPE) ? args.get(PARAM_MEMORY_TYPE).getAsString() : null;
        int limit = args.has(PARAM_LIMIT) ? args.get(PARAM_LIMIT).getAsInt() : 10;

        MemoryService memoryService = MemoryService.getInstance(project);
        MemoryStore store = memoryService.getStore();
        EmbeddingService embedding = memoryService.getEmbeddingService();
        if (store == null || embedding == null) {
            return "Error: Memory is not initialized. Enable it in Settings > AgentBridge > Memory.";
        }

        float[] queryEmbedding = embedding.embed(queryText);
        MemoryQuery query = MemoryQuery.semantic(queryText)
            .queryEmbedding(queryEmbedding)
            .wing(wing)
            .room(room)
            .memoryType(memoryType)
            .limit(limit)
            .build();
        List<DrawerDocument.SearchResult> results = store.search(query, queryEmbedding);

        if (results.isEmpty()) {
            return "No matching memories found for: " + queryText;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(results.size()).append(" result(s):\n\n");
        for (int i = 0; i < results.size(); i++) {
            DrawerDocument.SearchResult result = results.get(i);
            DrawerDocument d = result.drawer();
            sb.append("--- Result ").append(i + 1).append(" (score: ")
                .append(String.format("%.3f", result.score())).append(") ---\n");
            sb.append("ID: ").append(d.id()).append('\n');
            sb.append("Wing: ").append(d.wing()).append(" | Room: ").append(d.room())
                .append(" | Type: ").append(d.memoryType()).append('\n');
            sb.append("Filed: ").append(d.filedAt()).append(" | By: ").append(d.addedBy()).append('\n');
            sb.append("Content:\n").append(d.content()).append("\n\n");
        }
        return sb.toString().trim();
    }
}
