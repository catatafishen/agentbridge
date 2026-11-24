package com.github.catatafishen.agentbridge.client.nested;

import com.github.catatafishen.agentbridge.acp.protocol.PromptRequest;
import com.github.catatafishen.agentbridge.client.AbstractClient;
import com.github.catatafishen.agentbridge.model.ContentBlock;
import com.github.catatafishen.agentbridge.model.Model;
import com.github.catatafishen.agentbridge.model.PromptResponse;
import com.github.catatafishen.agentbridge.model.SessionUpdate;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NestedSessionRunnerTest {

    private ExecutorService executor;
    private NestedSessionRunner runner;

    @BeforeEach
    void setUp() {
        executor = Executors.newCachedThreadPool();
        runner = new NestedSessionRunner(executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static NestedSessionRunner.Request request(String prompt, int timeoutSeconds) {
        return new NestedSessionRunner.Request(prompt, "/tmp", null, timeoutSeconds);
    }

    private static SessionUpdate.AgentMessageChunk chunk(String text) {
        return new SessionUpdate.AgentMessageChunk(List.of(new ContentBlock.Text(text)));
    }

    @Test
    void collectsTheAgentsAnswerAndClosesTheClient() {
        FakeClient client = new FakeClient((req, out) -> {
            out.accept(chunk("Hello "));
            out.accept(chunk("world"));
            return new PromptResponse("end_turn", null);
        });

        var result = runner.run(client, request("do it", 5), null);

        assertEquals(NestedSessionRunner.Status.COMPLETED, result.status());
        assertEquals("Hello world", result.text());
        assertTrue(client.closed, "the runner must always close the client");
    }

    @Test
    void sendsThePromptBehindTheNoQuestionsInstruction() {
        FakeClient client = new FakeClient((req, out) -> {
            out.accept(chunk("ok"));
            return new PromptResponse("end_turn", null);
        });

        runner.run(client, request("find the bug", 5), null);

        PromptRequest sent = client.lastRequest.get();
        String text = ((ContentBlock.Text) sent.prompt().getFirst()).text();
        assertTrue(text.startsWith(NestedSessionRunner.NO_QUESTIONS_INSTRUCTION));
        assertTrue(text.endsWith("find the bug"));
    }

    @Test
    void deniesPermissionRequestsTheAgentRaisesItself() {
        FakeClient client = new FakeClient((req, out) -> {
            out.accept(chunk("ok"));
            return new PromptResponse("end_turn", null);
        });
        runner.run(client, request("x", 5), null);

        AtomicReference<String> denial = new AtomicReference<>();
        client.permissionListener.accept(new AbstractClient.PermissionPrompt() {
            @Override
            public String toolCallId() {
                return "t1";
            }

            @Override
            public String toolName() {
                return "shell";
            }

            @Override
            public String arguments() {
                return "{}";
            }

            @Override
            public List<String> options() {
                return List.of();
            }

            @Override
            public void allow(String optionKind) {
                denial.set("allowed");
            }

            @Override
            public void deny(String reason) {
                denial.set(reason);
            }
        });

        assertEquals(NestedSessionRunner.PERMISSION_DENIED_REASON, denial.get());
    }

    @Test
    void anEmptyAnswerIsAFailureNotASuccess() {
        FakeClient client = new FakeClient((req, out) -> new PromptResponse("end_turn", null));

        var result = runner.run(client, request("x", 5), null);

        assertEquals(NestedSessionRunner.Status.FAILED, result.status());
        assertNotNull(result.error());
        assertTrue(client.closed);
    }

    @Test
    void aFailingPromptIsReportedWithItsMessage() {
        FakeClient client = new FakeClient((req, out) -> {
            throw new IllegalStateException("rate limited");
        });

        var result = runner.run(client, request("x", 5), null);

        assertEquals(NestedSessionRunner.Status.FAILED, result.status());
        assertEquals("rate limited", result.error());
        assertTrue(client.closed);
    }

    @Test
    void aClientThatCannotStartFailsWithoutSendingAPrompt() {
        FakeClient client = new FakeClient((req, out) -> new PromptResponse("end_turn", null));
        client.startFailure = new IllegalStateException("not authenticated");

        var result = runner.run(client, request("x", 5), null);

        assertEquals(NestedSessionRunner.Status.FAILED, result.status());
        assertEquals("not authenticated", result.error());
        assertNull(client.lastRequest.get());
        assertTrue(client.closed);
    }

    @Test
    void aTurnThatOverrunsIsCancelledAndKeepsThePartialAnswer() {
        CountDownLatch cancelled = new CountDownLatch(1);
        FakeClient client = new FakeClient((req, out) -> {
            out.accept(chunk("partial"));
            try {
                cancelled.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new PromptResponse("cancelled", null);
        });
        client.onCancel = cancelled::countDown;

        var result = runner.run(client, request("x", 1), null);

        assertEquals(NestedSessionRunner.Status.TIMED_OUT, result.status());
        assertEquals("partial", result.text());
        assertTrue(client.cancelCalled, "a timeout must stop the agent's turn");
        assertTrue(client.closed);
    }

    @Test
    void reportsTextAndToolCallsToTheListener() {
        FakeClient client = new FakeClient((req, out) -> {
            out.accept(new SessionUpdate.ToolCall("c1", "read_file", "read_file", null, null, null,
                null, null, null, null));
            out.accept(chunk("done"));
            return new PromptResponse("end_turn", null);
        });
        List<String> events = new ArrayList<>();

        runner.run(client, request("x", 5), new NestedSessionRunner.Listener() {
            @Override
            public void onText(@NotNull String text) {
                events.add("text:" + text);
            }

            @Override
            public void onToolCall(@NotNull SessionUpdate.ToolCall call) {
                events.add("tool:" + call.toolCallId());
            }

            @Override
            public void onToolCallUpdate(@NotNull SessionUpdate.ToolCallUpdate update) {
                events.add("update:" + update.toolCallId());
            }
        });

        assertEquals(List.of("tool:c1", "text:done"), events);
    }

    @Test
    void aCancelledTurnIsNotReportedAsCompleted() {
        FakeClient client = new FakeClient((req, out) -> {
            out.accept(chunk("some"));
            return new PromptResponse("cancelled", null);
        });

        var result = runner.run(client, request("x", 5), null);

        assertEquals(NestedSessionRunner.Status.CANCELLED, result.status());
        assertFalse(result.text().isEmpty());
    }

    private interface Turn {
        PromptResponse run(PromptRequest request, Consumer<SessionUpdate> out) throws Exception;
    }

    private static final class FakeClient extends AbstractClient {
        private final Turn turn;
        final AtomicReference<PromptRequest> lastRequest = new AtomicReference<>();
        volatile Consumer<PermissionPrompt> permissionListener;
        volatile RuntimeException startFailure;
        volatile Runnable onCancel = () -> {
        };
        volatile boolean closed;
        volatile boolean cancelCalled;

        FakeClient(Turn turn) {
            this.turn = turn;
        }

        @Override
        public String agentId() {
            return "fake";
        }

        @Override
        public String displayName() {
            return "Fake";
        }

        @Override
        public void start() {
            if (startFailure != null) throw startFailure;
        }

        @Override
        public void stop() {
            closed = true;
        }

        @Override
        public boolean isConnected() {
            return !closed;
        }

        @Override
        public void setPermissionRequestListener(Consumer<PermissionPrompt> listener) {
            this.permissionListener = listener;
        }

        @Override
        public String createSession(String cwd) {
            return "s1";
        }

        @Override
        public void cancelSession(String sessionId) {
            cancelCalled = true;
            onCancel.run();
        }

        @Override
        public PromptResponse sendPrompt(PromptRequest request, Consumer<SessionUpdate> onUpdate) throws Exception {
            lastRequest.set(request);
            return turn.run(request, onUpdate);
        }

        @Override
        public List<Model> getAvailableModels() {
            return List.of();
        }

        @Override
        public void setModel(String sessionId, String modelId) {
            // The fake has a single model.
        }
    }
}
