package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.psi.tools.Tool;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Factory producing all individual git tool instances.
 * Called from {@link com.github.catatafishen.ideagentforcopilot.psi.PsiBridgeService}
 * during initialization.
 */
public final class GitToolFactory {

    private GitToolFactory() {
    }

    public static @NotNull List<Tool> create(@NotNull Project project) {
        return List.of(
            new GitStatusTool(project),
            new GitDiffTool(project),
            new GitLogTool(project),
            new GitBlameTool(project),
            new GitShowTool(project),
            new GetFileHistoryTool(project),
            new GitRemoteTool(project),
            new GitCommitTool(project),
            new GitStageTool(project),
            new GitUnstageTool(project),
            new GitBranchTool(project),
            new GitStashTool(project),
            new GitRevertTool(project),
            new GitTagTool(project),
            new GitPushTool(project),
            new GitResetTool(project),
            new GitRebaseTool(project),
            new GitFetchTool(project),
            new GitPullTool(project),
            new GitMergeTool(project),
            new GitCherryPickTool(project)
        );
    }
}
