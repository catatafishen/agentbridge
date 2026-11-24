package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.client.nested.NestedSessionJobs;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Factory that creates all infrastructure tool instances.
 */
public final class InfrastructureToolFactory {

    private InfrastructureToolFactory() {
    }

    public static @NotNull List<Tool> create(@NotNull Project project) {
        // One registry for both nested-session tools: the second reads what the first starts.
        NestedSessionJobs nestedSessions = new NestedSessionJobs(AppExecutorUtil::getAppExecutorService);
        return List.of(
            new PromptUserTool(project),
            new SleepTool(project),
            new RunNestedSessionTool(project, nestedSessions),
            new ReadNestedSessionTool(project, nestedSessions),
            new HttpRequestTool(project),
            new RunCommandTool(project),
            new ReadIdeLogTool(project),
            new GetNotificationsTool(project),
            new ListRunTabsTool(project),
            new ReadRunOutputTool(project),
            new ReadBuildOutputTool(project),
            new InteractWithModalTool(project)
        );
    }
}
