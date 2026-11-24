package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.client.nested.NestedSessionJobs;
import com.github.catatafishen.agentbridge.client.nested.NestedSessionPolicy;
import com.github.catatafishen.agentbridge.client.nested.NestedSessionRunner;
import org.jetbrains.annotations.NotNull;

/**
 * What the nested-session tools answer. Kept apart from the tools so both say the same thing, and so the wording
 * can be tested without a project.
 */
final class NestedSessionReply {

    static final String READ_TOOL_ID = NestedSessionPolicy.READ_TOOL_ID;

    private NestedSessionReply() {
    }

    /**
     * The reply for a session that ended: its answer, or an error that says why there is none.
     */
    static @NotNull String finished(@NotNull NestedSessionRunner.Result result) {
        if (result.status() == NestedSessionRunner.Status.COMPLETED) {
            return result.text();
        }
        String partial = result.text().isEmpty() ? "" : "\n\nPartial answer:\n" + result.text();
        return "Error: " + result.error() + partial;
    }

    /**
     * The reply for a session that is still going when the caller stops waiting. It is not an error: the session
     * carries on, and the reply says how to get its result.
     */
    static @NotNull String stillRunning(@NotNull NestedSessionJobs.Job job, int waitedSeconds) {
        String sofar = job.text();
        return "The nested session is still running (waited " + waitedSeconds + "s). Session id: " + job.id()
            + ". Call " + READ_TOOL_ID + " with session_id \"" + job.id() + "\" and wait_seconds to wait for it "
            + "again, or with cancel: true to stop it."
            + (sofar.isEmpty() ? "" : "\n\nAnswer so far:\n" + sofar);
    }

    static @NotNull String unknownSession(@NotNull String id, @NotNull java.util.List<String> running) {
        return "Error: No nested session with id '" + id + "'. Its result may have been dropped after an hour. "
            + (running.isEmpty() ? "None is running." : "Running: " + String.join(", ", running) + ".");
    }
}
