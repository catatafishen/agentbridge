package com.github.catatafishen.agentbridge.psi.tools.memory;

import com.github.catatafishen.agentbridge.memory.MemorySettings;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

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
            new MemoryRecallTool(project),
            // P2 — knowledge graph tools
            new MemoryKgQueryTool(project),
            new MemoryKgAddTool(project),
            new MemoryKgInvalidateTool(project),
            new MemoryKgTimelineTool(project)
        );
    }
}
