package com.github.catatafishen.agentbridge.psi.tools.memory;

import com.github.catatafishen.agentbridge.memory.MemoryService;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Shows the current status of the memory store: drawer counts grouped by wing and room.
 *
 * <p><b>Attribution:</b> status API adapted from MemPalace's mempalace_status (MIT License).
 */
public final class MemoryStatusTool extends Tool {

    MemoryStatusTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "memory_status";
    }

    @Override
    public @NotNull String displayName() {
        return "Memory Status";
    }

    @Override
    public @NotNull String description() {
        return "Show the current status of the memory store: total drawer count, "
            + "drawer counts grouped by wing and room, and the current palace wing. "
            + "Use to understand what the agent has remembered so far.";
    }

    @Override
    public @NotNull ToolDefinition.Kind kind() {
        return ToolDefinition.Kind.READ;
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
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        MemoryService memoryService = MemoryService.getInstance(project);
        MemoryStore store = memoryService.getStore();
        if (store == null) {
            return "Error: Memory is not initialized. Enable it in Settings > AgentBridge > Memory.";
        }

        String wing = memoryService.getEffectiveWing();
        int totalCount = store.getDrawerCount();
        Map<String, Map<String, Integer>> taxonomy = store.getTaxonomy();

        StringBuilder sb = new StringBuilder();
        sb.append("Memory Store Status\n");
        sb.append("===================\n");
        sb.append("Current wing: ").append(wing).append('\n');
        sb.append("Total drawers: ").append(totalCount).append('\n');

        if (taxonomy.isEmpty()) {
            sb.append("\nNo drawers stored yet.");
        } else {
            sb.append("\nBreakdown by wing/room:\n");
            for (var wingEntry : taxonomy.entrySet()) {
                sb.append("\n  Wing: ").append(wingEntry.getKey()).append('\n');
                for (var roomEntry : wingEntry.getValue().entrySet()) {
                    sb.append("    ").append(roomEntry.getKey()).append(": ")
                        .append(roomEntry.getValue()).append(" drawer(s)\n");
                }
            }
        }
        return sb.toString().trim();
    }
}
