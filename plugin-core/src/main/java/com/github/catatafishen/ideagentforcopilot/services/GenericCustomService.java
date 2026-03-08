package com.github.catatafishen.ideagentforcopilot.services;

import com.github.catatafishen.ideagentforcopilot.bridge.AgentConfig;
import com.github.catatafishen.ideagentforcopilot.bridge.AgentSettings;
import com.github.catatafishen.ideagentforcopilot.bridge.GenericAgentSettings;
import com.github.catatafishen.ideagentforcopilot.bridge.GenericCustomAgentConfig;
import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Service for user-provided generic ACP commands.
 * The start command is read from {@link ActiveAgentManager#getCustomAcpCommand()}.
 */
@Service(Service.Level.PROJECT)
public final class GenericCustomService extends AgentService {

    private static final GenericSettings SETTINGS = new GenericSettings("generic");
    private final AgentUiSettings uiSettings = new GenericAgentUiSettings(SETTINGS);

    public GenericCustomService(@NotNull Project project) {
        super(project);
    }

    @NotNull
    public static GenericCustomService getInstance(@NotNull Project project) {
        return PlatformApiCompat.getService(project, GenericCustomService.class);
    }

    public static GenericSettings settings() {
        return SETTINGS;
    }

    @Override
    public @NotNull AgentUiSettings getUiSettings() {
        return uiSettings;
    }

    @Override
    protected @NotNull AgentConfig createAgentConfig() {
        String command = ActiveAgentManager.getInstance(project).getCustomAcpCommand();
        return new GenericCustomAgentConfig(SETTINGS, command);
    }

    @Override
    protected @NotNull AgentSettings createAgentSettings() {
        return new GenericAgentSettings(SETTINGS);
    }

    @Override
    protected @NotNull String getDisplayName() {
        return "Generic ACP";
    }
}
