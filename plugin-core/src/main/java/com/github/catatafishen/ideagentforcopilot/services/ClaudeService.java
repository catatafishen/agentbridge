package com.github.catatafishen.ideagentforcopilot.services;

import com.github.catatafishen.ideagentforcopilot.bridge.AgentConfig;
import com.github.catatafishen.ideagentforcopilot.bridge.AgentSettings;
import com.github.catatafishen.ideagentforcopilot.bridge.ClaudeAgentConfig;
import com.github.catatafishen.ideagentforcopilot.bridge.ClaudeAgentSettings;
import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Claude Code agent service.
 * Provides {@link ClaudeAgentConfig} and {@link ClaudeAgentSettings} to the
 * generic {@link AgentService} lifecycle. Registered as an IntelliJ project service.
 */
@Service(Service.Level.PROJECT)
public final class ClaudeService extends AgentService {

    public ClaudeService(@NotNull Project project) {
        super(project);
    }

    @NotNull
    public static ClaudeService getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, ClaudeService.class);
    }

    @Override
    protected @NotNull AgentConfig createAgentConfig() {
        return new ClaudeAgentConfig();
    }

    @Override
    protected @NotNull AgentSettings createAgentSettings() {
        return new ClaudeAgentSettings();
    }

    @Override
    protected @NotNull String getDisplayName() {
        return "Claude";
    }
}
