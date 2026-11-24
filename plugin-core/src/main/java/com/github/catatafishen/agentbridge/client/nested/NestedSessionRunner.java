package com.github.catatafishen.agentbridge.client.nested;

import com.github.catatafishen.agentbridge.acp.protocol.PromptRequest;
import com.github.catatafishen.agentbridge.client.AbstractClient;
import com.github.catatafishen.agentbridge.model.ContentBlock;
import com.github.catatafishen.agentbridge.model.PromptResponse;
import com.github.catatafishen.agentbridge.model.SessionUpdate;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs one prompt to completion in a session of its own and returns what the agent answered.
 *
 * <p>The session is meant for unattended work: nobody can answer a question. The prompt therefore
 * carries {@link #NO_QUESTIONS_INSTRUCTION}, and every permission request the agent itself raises is
 * denied instead of being shown to the user. Tools that go through AgentBridge's MCP layer keep their
 * normal permission handling.
 *
 * <p>The runner owns the client's lifecycle: it starts it and always closes it. The client should be
 * {@linkplain AbstractClient#setEphemeral ephemeral}, so that it leaves the chat's own session alone.
 * No UI classes are used here; {@link Listener} is how a caller shows progress.
 */
public final class NestedSessionRunner {

    private static final Logger LOG = Logger.getInstance(NestedSessionRunner.class);

    /**
     * Prepended to the caller's prompt.
     */
    public static final String NO_QUESTIONS_INSTRUCTION = """
        You are running as a nested, unattended session started by another agent. Nobody can answer \
        questions: do not ask any, and do not call prompt_user, interact_with_modal or run_nested_session. \
        Where something is unclear, make the most reasonable assumption, say which one you made, and \
        carry on. Finish the task and end with a final answer that stands on its own: it is all the \
        caller will see.

        Task:
        """;

    static final String PERMISSION_DENIED_REASON =
        "Denied: this is an unattended nested session and nobody can approve the request.";

    /**
     * How a nested session ended.
     */
    public enum Status {
        COMPLETED, FAILED, TIMED_OUT, CANCELLED
    }

    /**
     * What the caller wants run.
     *
     * @param prompt         the task; the no-questions instruction is added in front of it
     * @param cwd            working directory of the session
     * @param modelId        model to use, or {@code null} for the agent's default
     * @param timeoutSeconds how long the whole turn may take
     */
    public record Request(@NotNull String prompt, @NotNull String cwd, @Nullable String modelId, int timeoutSeconds) {
    }

    /**
     * The outcome. {@code text} is everything the agent said; {@code error} is set unless the status is
     * {@link Status#COMPLETED}.
     */
    public record Result(@NotNull Status status, @NotNull String text, @Nullable String error) {
    }

    /**
     * Progress of a running session, for showing it. Called on the thread that streams the updates.
     */
    public interface Listener {
        void onText(@NotNull String text);

        void onToolCall(@NotNull SessionUpdate.ToolCall call);

        void onToolCallUpdate(@NotNull SessionUpdate.ToolCallUpdate update);
    }

    private final Executor executor;

    /**
     * @param executor runs the blocking prompt, so that the caller can stop waiting for it
     */
    public NestedSessionRunner(@NotNull Executor executor) {
        this.executor = executor;
    }

    /**
     * Runs {@code request} on {@code client}, which this method starts and closes.
     */
    public @NotNull Result run(@NotNull AbstractClient client, @NotNull Request request,
                               @Nullable Listener listener) {
        Answer text = new Answer();
        try {
            client.setPermissionRequestListener(prompt -> prompt.deny(PERMISSION_DENIED_REASON));
            client.start();
            String sessionId = client.createSession(request.cwd());
            if (request.modelId() != null && !request.modelId().isBlank()) {
                client.setModel(sessionId, request.modelId());
            }
            return runTurn(client, sessionId, request, listener, text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Status.CANCELLED, text.get(), "Interrupted");
        } catch (Exception e) {
            LOG.warn("Nested session failed", e);
            return new Result(Status.FAILED, text.get(), describe(e));
        } finally {
            closeQuietly(client);
        }
    }

    private @NotNull Result runTurn(@NotNull AbstractClient client, @NotNull String sessionId,
                                    @NotNull Request request, @Nullable Listener listener,
                                    @NotNull Answer text) throws InterruptedException {
        PromptRequest prompt = new PromptRequest(
            sessionId,
            List.of(new ContentBlock.Text(NO_QUESTIONS_INSTRUCTION + request.prompt())),
            request.modelId(),
            null);
        CompletableFuture<PromptResponse> turn = CompletableFuture.supplyAsync(() -> {
            try {
                return client.sendPrompt(prompt, update -> collect(update, text, listener));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Prompt interrupted", e);
            } catch (Exception e) {
                throw new IllegalStateException(describe(e), e);
            }
        }, executor);

        try {
            PromptResponse response = turn.get(request.timeoutSeconds(), TimeUnit.SECONDS);
            return finished(response, text);
        } catch (TimeoutException e) {
            stopTurn(client, sessionId, turn);
            return new Result(Status.TIMED_OUT, text.get(),
                "The nested session did not finish within " + request.timeoutSeconds() + " seconds.");
        } catch (InterruptedException e) {
            stopTurn(client, sessionId, turn);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new Result(Status.FAILED, text.get(), describe(cause));
        }
    }

    private static @NotNull Result finished(@NotNull PromptResponse response, @NotNull Answer text) {
        String reply = text.get();
        if ("cancelled".equals(response.stopReason())) {
            return new Result(Status.CANCELLED, reply, "The nested session was cancelled.");
        }
        if (reply.isBlank()) {
            return new Result(Status.FAILED, reply,
                "The nested session ended (" + response.stopReason() + ") without an answer.");
        }
        return new Result(Status.COMPLETED, reply, null);
    }

    private static void collect(@NotNull SessionUpdate update, @NotNull Answer text,
                                @Nullable Listener listener) {
        switch (update) {
            case SessionUpdate.AgentMessageChunk chunk -> {
                String part = chunk.text();
                text.append(part);
                if (listener != null && !part.isEmpty()) listener.onText(part);
            }
            case SessionUpdate.ToolCall call -> {
                if (listener != null) listener.onToolCall(call);
            }
            case SessionUpdate.ToolCallUpdate toolUpdate -> {
                if (listener != null) listener.onToolCallUpdate(toolUpdate);
            }
            default -> {
                // Reasoning, plans, usage and session settings are not part of the answer.
            }
        }
    }

    /**
     * The answer so far. The agent streams into it on its own thread while the caller may read it
     * on timeout, so every access goes through the lock.
     */
    private static final class Answer {
        private final StringBuilder text = new StringBuilder();

        synchronized void append(@NotNull String part) {
            text.append(part);
        }

        synchronized @NotNull String get() {
            return text.toString().trim();
        }
    }

    private static void stopTurn(@NotNull AbstractClient client, @NotNull String sessionId,
                                 @NotNull CompletableFuture<PromptResponse> turn) {
        try {
            client.cancelSession(sessionId);
        } catch (RuntimeException e) {
            LOG.warn("Could not cancel the nested session " + sessionId, e);
        }
        turn.cancel(true);
    }

    private static void closeQuietly(@NotNull AbstractClient client) {
        try {
            client.close();
        } catch (RuntimeException e) {
            LOG.warn("Could not close the nested session's client", e);
        }
    }

    private static @NotNull String describe(@NotNull Throwable e) {
        String message = e.getMessage();
        Throwable cause = e.getCause();
        if ((message == null || message.isBlank()) && cause != null) {
            message = cause.getMessage();
        }
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
