package com.github.catatafishen.agentbridge.psi.tools.memory;

import com.github.catatafishen.agentbridge.memory.MemorySettings;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Creates memory tools when semantic memory is enabled.
 * Tools are only registered when {@link MemorySettings#isEnabled()} is true.
 *
 * <p>P0 tools (always registered when memory is on):
 * - memory_search, memory_store, memory_status
 *
 * <p>P1 tools (registered when memory is on):
 * - memory_wake_up, memory_recall
 */
public final class MemoryToolFactory {

    private MemoryToolFactory() {
    }

    public static @NotNull List<Tool> create(@NotNull Project project) {
        if (!MemorySettings.getInstance(project).isEnabled()) {
            return List.of();
        }
        return List.of(
            // P0 — core tools
            new MemorySearchTool(project),
            new MemoryStoreTool(project),
            new MemoryStatusTool(project),
            // P1 — layer tools
            new MemoryWakeUpTool(project),
            new MemoryRecallTool(project)
        );
    }
}
