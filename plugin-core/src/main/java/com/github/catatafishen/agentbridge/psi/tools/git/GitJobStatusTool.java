package com.github.catatafishen.agentbridge.psi.tools.git;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public final class GitJobStatusTool extends GitTool {

    private static final String PARAM_JOB_ID = "job_id";

    public GitJobStatusTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "git_job_status";
    }

    @Override
    public @NotNull String displayName() {
        return "Git Job Status";
    }

    @Override
    public @NotNull String description() {
        return "Check a background git_commit or git_push job started with async: true. "
            + "If job_id is omitted, returns the latest background Git job.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_JOB_ID, TYPE_STRING,
                "Background job id returned by git_commit or git_push async mode")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        String jobId = args.has(PARAM_JOB_ID) ? args.get(PARAM_JOB_ID).getAsString() : null;
        return GitJobRegistry.getInstance(project).describe(jobId);
    }
}
