package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class KiroAcpClient extends AcpClient {

    public KiroAcpClient(@NotNull AgentConfig config,
                         @NotNull AgentSettings settings,
                         @Nullable ToolRegistry registry,
                         @Nullable String projectBasePath,
                         int mcpPort) {
        super(config, settings, registry, projectBasePath, mcpPort);
    }
}
