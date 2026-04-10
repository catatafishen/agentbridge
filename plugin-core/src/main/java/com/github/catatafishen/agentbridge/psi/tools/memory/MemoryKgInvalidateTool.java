package com.github.catatafishen.agentbridge.psi.tools.memory;

import com.github.catatafishen.agentbridge.memory.MemoryService;
import com.github.catatafishen.agentbridge.memory.kg.KnowledgeGraph;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Invalidate (soft-delete) a knowledge graph triple by ID. Sets valid_until to now
 * without physically deleting the triple, preserving history.
 *
 * <p><b>Attribution:</b> adapted from MemPalace's mempalace_kg_invalidate (MIT License).
 */
public final class MemoryKgInvalidateTool extends Tool {

    private static final String PARAM_TRIPLE_ID = "triple_id";

    MemoryKgInvalidateTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "memory_kg_invalidate";
    }

    @Override
    public @NotNull String displayName() {
        return "Memory KG Invalidate";
    }

    @Override
    public @NotNull String description() {
        return "Invalidate a knowledge graph triple by ID. Sets valid_until to now — "
            + "the triple remains in history but is excluded from queries. "
            + "Use when a fact is no longer true (e.g., project migrated from Maven to Gradle).";
    }

    @Override
    public @NotNull ToolDefinition.Kind kind() {
        return ToolDefinition.Kind.WRITE;
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.MEMORY;
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_TRIPLE_ID, TYPE_INTEGER, "ID of the triple to invalidate (from memory_kg_query results)")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        long tripleId = args.get(PARAM_TRIPLE_ID).getAsLong();

        MemoryService memoryService = MemoryService.getInstance(project);
        KnowledgeGraph kg = memoryService.getKnowledgeGraph();
        if (kg == null) {
            return "Error: Memory is not initialized. Enable it in Settings > AgentBridge > Memory.";
        }

        boolean invalidated = kg.invalidateTriple(tripleId);
        if (invalidated) {
            return "Triple " + tripleId + " invalidated (marked as no longer valid).";
        } else {
            return "Error: Triple " + tripleId + " not found or already invalidated.";
        }
    }
}
