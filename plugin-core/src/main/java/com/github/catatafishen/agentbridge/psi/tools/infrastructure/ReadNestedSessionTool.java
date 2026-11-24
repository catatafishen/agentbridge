package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.client.nested.NestedSessionJobs;
import com.github.catatafishen.agentbridge.client.nested.NestedSessionPolicy;
import com.github.catatafishen.agentbridge.client.nested.NestedSessionRunner;
import com.github.catatafishen.agentbridge.psi.tools.McpRequestDeadline;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Waits for, reads or cancels a nested session that {@code run_nested_session} started in the background. It is
 * how a session can take longer than a single MCP call may wait: the caller polls, each call bounded by
 * {@link McpRequestDeadline#MAX_TIMEOUT_SECONDS}.
 */
public final class ReadNestedSessionTool extends InfrastructureTool {

    private static final String PARAM_SESSION_ID = "session_id";
    private static final String PARAM_WAIT = "wait_seconds";
    private static final String PARAM_CANCEL = "cancel";
    private static final int DEFAULT_WAIT_SECONDS = 60;

    private final NestedSessionJobs jobs;

    public ReadNestedSessionTool(Project project, @NotNull NestedSessionJobs jobs) {
        super(project);
        this.jobs = jobs;
    }

    @Override
    public @NotNull String id() {
        return NestedSessionPolicy.READ_TOOL_ID;
    }

    @Override
    public @NotNull String displayName() {
        return "Read Nested Session";
    }

    @Override
    public @NotNull String description() {
        return "Wait for a nested session that " + NestedSessionPolicy.TOOL_ID + " left running, and return its "
            + "answer. Takes the `session_id` from that tool's reply. Blocks until the session ends or "
            + "`wait_seconds` (default " + DEFAULT_WAIT_SECONDS + ", at most "
            + McpRequestDeadline.MAX_TIMEOUT_SECONDS + ") passes; if it is still running, returns the answer so "
            + "far and the same id, so call it again. With `cancel: true` it stops the session instead and "
            + "returns what it had. A finished session can be read again for an hour. Returns the answer text, "
            + "or an error that says why there is none.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.OTHER;
    }

    @Override
    public boolean managesOwnTimeout() {
        return true;
    }

    @Override
    public boolean needsWriteLock() {
        return false;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Read nested session {session_id}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_SESSION_ID, TYPE_STRING, "The session id " + NestedSessionPolicy.TOOL_ID
                + " returned."),
            Param.optional(PARAM_WAIT, TYPE_INTEGER, "Seconds to wait for the session to end (default "
                + DEFAULT_WAIT_SECONDS + ", at most " + McpRequestDeadline.MAX_TIMEOUT_SECONDS + ")."),
            Param.optional(PARAM_CANCEL, TYPE_BOOLEAN, "Stop the session instead of waiting for it.")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws InterruptedException {
        JsonElement idElement = args.get(PARAM_SESSION_ID);
        String id = idElement != null && idElement.isJsonPrimitive() ? idElement.getAsString().trim() : "";
        if (id.isEmpty()) {
            return err(PARAM_SESSION_ID + " is required");
        }
        int requested = args.has(PARAM_WAIT) && !args.get(PARAM_WAIT).isJsonNull()
            ? args.get(PARAM_WAIT).getAsInt() : DEFAULT_WAIT_SECONDS;
        String rejected = McpRequestDeadline.rejectNonPositive(requested);
        if (rejected != null) {
            return rejected;
        }
        NestedSessionJobs.Job job = jobs.get(id);
        if (job == null) {
            return NestedSessionReply.unknownSession(id, jobs.runningIds());
        }
        boolean cancel = args.has(PARAM_CANCEL) && !args.get(PARAM_CANCEL).isJsonNull()
            && args.get(PARAM_CANCEL).getAsBoolean();
        int wait = McpRequestDeadline.clamp(requested);
        if (cancel) {
            job.cancel();
            // The runner stops the agent's turn and closes its client; give it a moment to report.
            wait = Math.min(wait, CANCEL_GRACE_SECONDS);
        }
        String body = awaitReply(job, wait);
        return McpRequestDeadline.prependNotice(McpRequestDeadline.clampNotice(requested), body);
    }

    private static final int CANCEL_GRACE_SECONDS = 15;

    /**
     * Waits up to {@code waitSeconds} for {@code job} and words the outcome.
     */
    static @NotNull String awaitReply(@NotNull NestedSessionJobs.Job job, int waitSeconds)
        throws InterruptedException {
        NestedSessionRunner.Result result = job.await(waitSeconds);
        return result == null
            ? NestedSessionReply.stillRunning(job, waitSeconds)
            : NestedSessionReply.finished(result);
    }
}
