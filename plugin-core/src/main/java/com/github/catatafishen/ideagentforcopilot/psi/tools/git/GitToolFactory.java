package com.github.catatafishen.ideagentforcopilot.psi.tools.git;

import com.github.catatafishen.ideagentforcopilot.psi.GitToolHandler;
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

    public static @NotNull List<Tool> create(@NotNull Project project, @NotNull GitToolHandler git) {
        return List.of(
            new GitStatusTool(project, git),
            new GitDiffTool(project, git),
            new GitLogTool(project, git),
            new GitBlameTool(project, git),
            new GitShowTool(project, git),
            new GetFileHistoryTool(project, git),
            new GitRemoteTool(project, git),
            new GitCommitTool(project, git),
            new GitStageTool(project, git),
            new GitUnstageTool(project, git),
            new GitBranchTool(project, git),
            new GitStashTool(project, git),
            new GitRevertTool(project, git),
            new GitTagTool(project, git),
            new GitPushTool(project, git),
            new GitResetTool(project, git),
            new GitRebaseTool(project, git),
            new GitFetchTool(project, git),
            new GitPullTool(project, git),
            new GitMergeTool(project, git),
            new GitCherryPickTool(project, git)
        );
    }
}
