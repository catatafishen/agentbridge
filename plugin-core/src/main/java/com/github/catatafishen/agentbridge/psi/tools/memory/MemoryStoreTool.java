package com.github.catatafishen.agentbridge.psi.tools.memory;

import com.github.catatafishen.agentbridge.memory.MemoryService;
import com.github.catatafishen.agentbridge.memory.embedding.EmbeddingService;
import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;

/**
 * Stores a new memory drawer in the semantic memory store.
 * Agents use this to explicitly save decisions, preferences, milestones, etc.
 *
 * <p><b>Attribution:</b> store API adapted from MemPalace's mempalace_add_drawer (MIT License).
 */
public final class MemoryStoreTool extends Tool {

    private static final String PARAM_MEMORY_TYPE = "memory_type";

    MemoryStoreTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "memory_store";
    }

    @Override
    public @NotNull String displayName() {
        return "Memory Store";
    }

    @Override
    public @NotNull String description() {
        return "Store a new memory drawer. Use to explicitly save decisions, preferences, "
            + "milestones, problems, or technical insights. Duplicate content is automatically "
            + "detected and skipped. Returns the drawer ID on success, or a message if skipped.";
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
    public boolean isIdempotent() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("content", TYPE_STRING, "The memory content to store"),
            Param.optional("room", TYPE_STRING, "Topic room: 'codebase', 'debugging', 'workflow', 'decisions', 'preferences'. Default: 'general'"),
            Param.optional(PARAM_MEMORY_TYPE, TYPE_STRING, "Type: context, decision, problem, solution. Default: general")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String content = args.get("content").getAsString();
        String room = args.has("room") ? args.get("room").getAsString() : "general";
        String memoryType = args.has(PARAM_MEMORY_TYPE) ? args.get(PARAM_MEMORY_TYPE).getAsString() : DrawerDocument.TYPE_GENERAL;

        if (content.length() > DrawerDocument.MAX_CONTENT_LENGTH) {
            return "Error: Content exceeds maximum length of " + DrawerDocument.MAX_CONTENT_LENGTH + " characters.";
        }

        MemoryService memoryService = MemoryService.getInstance(project);
        MemoryStore store = memoryService.getStore();
        EmbeddingService embedding = memoryService.getEmbeddingService();
        if (store == null || embedding == null) {
            return "Error: Memory is not initialized. Enable it in Settings > AgentBridge > Memory.";
        }

        String wing = memoryService.getEffectiveWing();
        float[] vector = embedding.embed(content);
        String drawerId = MemoryStore.generateDrawerId(wing, room, content);

        DrawerDocument drawer = DrawerDocument.builder()
            .id(drawerId)
            .wing(wing)
            .room(room)
            .content(content)
            .memoryType(memoryType)
            .filedAt(Instant.now())
            .addedBy(DrawerDocument.ADDED_BY_MCP)
            .build();

        String result = store.addDrawer(drawer, vector);
        if (result != null) {
            return "Stored drawer: " + result + " (wing=" + wing + ", room=" + room + ", type=" + memoryType + ")";
        }
        return "Skipped: duplicate content already exists in memory store.";
    }
}
