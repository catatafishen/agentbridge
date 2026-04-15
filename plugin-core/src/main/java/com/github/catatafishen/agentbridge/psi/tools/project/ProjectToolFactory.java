package com.github.catatafishen.agentbridge.psi.tools.project;

import com.github.catatafishen.agentbridge.psi.RunConfigurationService;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory producing all individual project tool instances.
 * Conditionally includes Java-specific tools (e.g., {@link BuildProjectTool}) based on plugin availability.
 */
public final class ProjectToolFactory {

    private ProjectToolFactory() {
    }

    public static @NotNull List<Tool> create(
        @NotNull Project project,
        @NotNull RunConfigurationService runConfigService,
        boolean hasJava) {
        var tools = new ArrayList<Tool>();
        tools.add(new GetProjectInfoTool(project));
        if (hasJava) {
            tools.add(new BuildProjectTool(project));
        }
        tools.add(new GetIndexingStatusTool(project));
        tools.add(new DownloadSourcesTool(project));
        tools.add(new MarkDirectoryTool(project));
        tools.add(new EditProjectStructureTool(project));
        tools.add(new ListRunConfigurationsTool(project, runConfigService));
        tools.add(new RunConfigurationTool(project, runConfigService));
        tools.add(new CreateRunConfigurationTool(project, runConfigService));
        tools.add(new EditRunConfigurationTool(project, runConfigService));
        tools.add(new DeleteRunConfigurationTool(project, runConfigService));
        tools.add(new GetProjectModulesTool(project));
        tools.add(new GetProjectDependenciesTool(project));
        return List.copyOf(tools);
    }
}
