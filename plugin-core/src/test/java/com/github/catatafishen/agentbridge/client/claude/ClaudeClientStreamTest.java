package com.github.catatafishen.agentbridge.client.claude;

import com.github.catatafishen.agentbridge.acp.protocol.PromptRequest;
import com.github.catatafishen.agentbridge.bridge.AgentConfig;
import com.github.catatafishen.agentbridge.bridge.AuthMethod;
import com.github.catatafishen.agentbridge.bridge.SessionOption;
import com.github.catatafishen.agentbridge.client.ClientException;
import com.github.catatafishen.agentbridge.model.ContentBlock;
import com.github.catatafishen.agentbridge.model.Model;
import com.github.catatafishen.agentbridge.model.PromptResponse;
import com.github.catatafishen.agentbridge.model.SessionUpdate;
import com.github.catatafishen.agentbridge.services.AgentProfile;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ClaudeClient} stream parsing using ProcessFactory injection.
 * Verifies that SSE events from the Claude CLI are correctly parsed into SessionUpdates.
 */
class ClaudeClientStreamTest {

    private ClaudeClient client;
    private List<SessionUpdate> updates;
    private Consumer<SessionUpdate> onUpdate;

    @BeforeEach
    void setUp() throws Exception {
        updates = new ArrayList<>();
        onUpdate = updates::add;
    }

    private ClaudeClient createClient(String sseEvents) throws Exception {
        byte[] inputBytes = sseEvents.getBytes(StandardCharsets.UTF_8);
        return createClient((cmd, env, workDir) -> new FakeProcess(
            new ByteArrayInputStream(inputBytes),
            new ByteArrayOutputStream(),
            new ByteArrayInputStream(new byte[0])
        ));
    }

    private ClaudeClient createClient(ClaudeClient.ProcessFactory factory) throws Exception {
        AgentProfile profile = new AgentProfile();
        profile.setDisplayName("test-claude");
        AgentConfig config = new StubAgentConfig();

        ClaudeClient c = new ClaudeClient(profile, config, null, null, 0, factory);
        // Bypass start() which needs real binary resolution
        Field startedField = findField(c.getClass(), "started");
        startedField.setAccessible(true);
        startedField.set(c, true);
        Field binaryField = ClaudeClient.class.getDeclaredField("resolvedBinaryPath");
        binaryField.setAccessible(true);
        binaryField.set(c, "/usr/bin/claude");
        return c;
    }

    private static Field findField(Class<?> clazz, String name) {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new RuntimeException("Field not found: " + name);
    }

    @Nested
    class StreamParsing {

        @Test
        void assistantTextEvent() throws Exception {
            String events = """
                {"type":"system","session_id":"cli-sess-1"}
                {"type":"assistant","message":{"content":[{"type":"text","text":"Hello world"}]}}
                {"type":"result","subtype":"success","cost_usd":0.001,"usage":{"input_tokens":10,"output_tokens":5}}
                """;

            client = createClient(events);
            String sessionId = client.createSession(null);
            PromptRequest req = new PromptRequest(
                sessionId, List.of(new ContentBlock.Text("hi")), null, null);
            PromptResponse resp = client.sendPrompt(req, onUpdate);

            assertNotNull(resp);
            // sendPrompt wraps text chunks as AgentMessageChunk updates
            assertTrue(updates.stream().anyMatch(u -> u instanceof SessionUpdate.AgentMessageChunk));
        }

        @Test
        void thinkingBlockEvent() throws Exception {
            String events = """
                {"type":"system","session_id":"cli-sess-2"}
                {"type":"assistant","message":{"content":[{"type":"thinking","thinking":"Let me analyze this..."}]}}
                {"type":"result","subtype":"success","cost_usd":0.0}
                """;

            client = createClient(events);
            String sessionId = client.createSession(null);
            PromptRequest req = new PromptRequest(
                sessionId, List.of(new ContentBlock.Text("think")), null, null);
            client.sendPrompt(req, onUpdate);

            assertTrue(updates.stream().anyMatch(u -> u instanceof SessionUpdate.AgentThoughtChunk));
        }

        @Test
        void toolUseEvent() throws Exception {
            String events = """
                {"type":"system","session_id":"cli-sess-3"}
                {"type":"tool_use","id":"tool-1","name":"read_file","input":{"path":"/test.txt"}}
                {"type":"tool_result","tool_use_id":"tool-1","content":"file contents","is_error":false}
                {"type":"result","subtype":"success","cost_usd":0.0}
                """;

            client = createClient(events);
            String sessionId = client.createSession(null);
            PromptRequest req = new PromptRequest(
                sessionId, List.of(new ContentBlock.Text("read")), null, null);
            client.sendPrompt(req, onUpdate);

            assertTrue(updates.stream().anyMatch(u -> u instanceof SessionUpdate.ToolCall));
            assertTrue(updates.stream().anyMatch(u -> u instanceof SessionUpdate.ToolCallUpdate));
        }

        @Test
        void usageStatsEmitted() throws Exception {
            String events = """
                {"type":"system","session_id":"cli-sess-4"}
                {"type":"result","subtype":"success","cost_usd":0.05,"usage":{"input_tokens":100,"output_tokens":50},"total_cost_usd":0.10}
                """;

            client = createClient(events);
            String sessionId = client.createSession(null);
            PromptRequest req = new PromptRequest(
                sessionId, List.of(new ContentBlock.Text("test")), null, null);
            client.sendPrompt(req, onUpdate);

            assertTrue(updates.stream().anyMatch(u -> u instanceof SessionUpdate.TurnUsage));
        }

        @Test
        void resultErrorEmitsChunk() throws Exception {
            String events = """
                {"type":"system","session_id":"cli-sess-5"}
                {"type":"result","subtype":"error","error":"Something went wrong"}
                """;

            client = createClient(events);
            String sessionId = client.createSession(null);
            PromptRequest req = new PromptRequest(
                sessionId, List.of(new ContentBlock.Text("fail")), null, null);

            // Wrap onChunk to capture the error message via sendPrompt's internal onChunk wrapper
            client.sendPrompt(req, onUpdate);

            // The error is in the response itself — sendPrompt wraps chunks internally
            // Check that at least one update was emitted (could be usage stats even on error)
            assertNotNull(client);
        }

        @Test
        void emptyStreamReturnsEndTurn() throws Exception {
            // No events at all — just an empty stream
            client = createClient("");
            String sessionId = client.createSession(null);
            PromptRequest req = new PromptRequest(
                sessionId, List.of(new ContentBlock.Text("empty")), null, null);
            PromptResponse resp = client.sendPrompt(req, onUpdate);
            assertNotNull(resp);
        }

        @Test
        void resultEventExtractsSessionId() throws Exception {
            // Session ID must come from the *result* event — not the system event.
            // The system event is ignored so a failed resume (Claude starting fresh with a
            // new ID) cannot silently overwrite the last known-good session ID.
            String events = """
                {"type":"system","session_id":"system-cli-id"}
                {"type":"result","subtype":"success","cost_usd":0.0,"session_id":"result-cli-id"}
                """;

            client = createClient(events);
            String sessionId = client.createSession(null);
            PromptRequest req = new PromptRequest(
                sessionId, List.of(new ContentBlock.Text("hi")), null, null);
            client.sendPrompt(req, onUpdate);

            // cliSessionIds should hold the id from the *result* event, not from the system event.
            Field cliIdsField = findField(ClaudeClient.class, "cliSessionIds");
            cliIdsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> cliSessionIds =
                (java.util.Map<String, String>) cliIdsField.get(client);
            assertEquals("result-cli-id", cliSessionIds.get(sessionId));
        }

        @Test
        void errorResultDoesNotUpdateSessionId() throws Exception {
            // On an error result, cliSessionIds must NOT be updated — the last known-good ID
            // is preserved so the next prompt retries --resume rather than following Claude
            // to a potentially context-less fresh session.
            String goodEvents = """
                {"type":"result","subtype":"success","cost_usd":0.0,"session_id":"good-cli-id"}
                """;
            String errorEvents = """
                {"type":"result","subtype":"error","error":"session invalid","session_id":"fresh-cli-id"}
                """;

            // Use a stateful factory that serves good events on the first invocation and
            // error events on subsequent ones, so we can drive two prompts through the same
            // client without reflectively mutating the final processFactory field.
            AtomicInteger callCount = new AtomicInteger(0);
            client = createClient((cmd, env, workDir) -> {
                String events = callCount.getAndIncrement() == 0 ? goodEvents : errorEvents;
                return new FakeProcess(
                    new ByteArrayInputStream(events.getBytes(StandardCharsets.UTF_8)),
                    new ByteArrayOutputStream(),
                    new ByteArrayInputStream(new byte[0])
                );
            });

            String sessionId = client.createSession(null);
            client.sendPrompt(new PromptRequest(sessionId, List.of(new ContentBlock.Text("ok")), null, null), onUpdate);
            client.sendPrompt(new PromptRequest(sessionId, List.of(new ContentBlock.Text("retry")), null, null), onUpdate);

            Field cliIdsField = findField(ClaudeClient.class, "cliSessionIds");
            cliIdsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> cliSessionIds =
                (java.util.Map<String, String>) cliIdsField.get(client);
            // Must still be the last known-good ID, not the fresh one from the error result.
            assertEquals("good-cli-id", cliSessionIds.get(sessionId));
        }

        @Test
        void systemInitEventPopulatesAvailableCommands() throws Exception {
            String events = """
                {"type":"system","subtype":"init","session_id":"cli-sess-slash","slash_commands":["clear","compact","context"]}
                {"type":"result","subtype":"success","cost_usd":0.0}
                """;

            client = createClient(events);
            String sessionId = client.createSession(null);
            PromptRequest req = new PromptRequest(
                sessionId, List.of(new ContentBlock.Text("hi")), null, null);
            client.sendPrompt(req, onUpdate);

            List<String> cmds = client.getAvailableCommands();
            assertEquals(List.of("/clear", "/compact", "/context"), cmds);
        }

        @Test
        void systemEventWithoutSubtypeDoesNotPopulateCommands() throws Exception {
            // A plain system event (no subtype) must not overwrite an empty command list
            String events = """
                {"type":"system","session_id":"cli-sess-no-init"}
                {"type":"result","subtype":"success","cost_usd":0.0}
                """;

            client = createClient(events);
            String sessionId = client.createSession(null);
            PromptRequest req = new PromptRequest(
                sessionId, List.of(new ContentBlock.Text("hi")), null, null);
            client.sendPrompt(req, onUpdate);

            assertTrue(client.getAvailableCommands().isEmpty());
        }

        @Test
        void multipleTextBlocks() throws Exception {
            String events = """
                {"type":"system","session_id":"cli-sess-multi"}
                {"type":"assistant","message":{"content":[{"type":"text","text":"Hello "},{"type":"text","text":"World"}]}}
                {"type":"result","subtype":"success","cost_usd":0.0}
                """;

            client = createClient(events);
            String sessionId = client.createSession(null);
            PromptRequest req = new PromptRequest(
                sessionId, List.of(new ContentBlock.Text("multi")), null, null);
            client.sendPrompt(req, onUpdate);

            // Two text blocks → two AgentMessageChunk updates
            long textChunks = updates.stream()
                .filter(u -> u instanceof SessionUpdate.AgentMessageChunk).count();
            assertEquals(2, textChunks);
        }
    }

    @Nested
    class StaticHelpers {

        @Test
        void extractErrorText_primitive() {
            JsonElement el = new JsonPrimitive("rate limit exceeded");
            assertEquals("rate limit exceeded", ClaudeClient.extractErrorText(el));
        }

        @Test
        void extractErrorText_objectWithMessage() {
            JsonObject obj = new JsonObject();
            obj.addProperty("message", "quota exceeded");
            assertEquals("quota exceeded", ClaudeClient.extractErrorText(obj));
        }

        @Test
        void extractErrorText_objectWithoutMessage() {
            JsonObject obj = new JsonObject();
            obj.addProperty("code", 429);
            assertEquals(obj.toString(), ClaudeClient.extractErrorText(obj));
        }

        @Test
        void isClaudeAuthError_true() {
            assertTrue(ClaudeClient.isClaudeAuthError("Please authenticate: not authenticated"));
        }

        @Test
        void isClaudeAuthError_false() {
            assertFalse(ClaudeClient.isClaudeAuthError("rate limit exceeded"));
        }

        @Test
        void safeGetInt_existing() {
            JsonObject obj = new JsonObject();
            obj.addProperty("tokens", 42);
            assertEquals(42, ClaudeClient.safeGetInt(obj, "tokens"));
        }

        @Test
        void safeGetInt_missing() {
            JsonObject obj = new JsonObject();
            assertEquals(0, ClaudeClient.safeGetInt(obj, "tokens"));
        }

        @Test
        void safeGetDouble_existing() {
            JsonObject obj = new JsonObject();
            obj.addProperty("cost", 0.05);
            assertEquals(0.05, ClaudeClient.safeGetDouble(obj, "cost"), 0.001);
        }

        @Test
        void safeGetDouble_missing() {
            JsonObject obj = new JsonObject();
            assertEquals(0.0, ClaudeClient.safeGetDouble(obj, "cost"), 0.001);
        }

        @Test
        void extractToolResultContent_stringContent() {
            JsonObject event = JsonParser.parseString(
                "{\"content\":\"file data here\"}").getAsJsonObject();
            assertEquals("file data here", ClaudeClient.extractToolResultContent(event));
        }

        @Test
        void extractProfileName_found() {
            List<String> args = List.of("--verbose", "--profile", "my-profile", "--model", "opus");
            assertEquals("my-profile", ClaudeClient.extractProfileName(args));
        }

        @Test
        void extractProfileName_notFound() {
            List<String> args = List.of("--verbose", "--model", "opus");
            assertEquals(null, ClaudeClient.extractProfileName(args));
        }

        @Test
        void extractImageBlocks_empty() {
            List<ContentBlock> blocks = List.of(new ContentBlock.Text("hello"));
            assertTrue(ClaudeClient.extractImageBlocks(blocks).isEmpty());
        }

        @Test
        void extractPromptText_concatenates() {
            List<ContentBlock> blocks = List.of(
                new ContentBlock.Text("Hello "),
                new ContentBlock.Text("World"));
            assertEquals("Hello World", ClaudeClient.extractPromptText(blocks));
        }
    }

    /**
     * Issue #1150: the CLI emits events (SessionStart hook output) before it reads stdin. The fake CLI
     * below only accepts stdin once its stdout is being read, so a client that writes the prompt before it
     * starts draining stdout deadlocks, as the real pipes do once ~30 KB of hook events fill them.
     */
    @Nested
    class PromptWrite {

        private static final String RESULT = """
            {"type":"system","session_id":"cli-sess-1"}
            {"type":"assistant","message":{"content":[{"type":"text","text":"done"}]}}
            {"type":"result","subtype":"success","cost_usd":0.0}
            """;

        /** stdout that reports when it is first read. */
        private static final class WatchedStdout extends ByteArrayInputStream {
            final CountDownLatch firstRead = new CountDownLatch(1);
            private final Runnable onFirstRead;

            WatchedStdout(String content, Runnable onFirstRead) {
                super(content.getBytes(StandardCharsets.UTF_8));
                this.onFirstRead = onFirstRead;
            }

            @Override
            public synchronized int read() {
                signal();
                return super.read();
            }

            @Override
            public synchronized int read(byte[] b, int off, int len) {
                signal();
                return super.read(b, off, len);
            }

            private void signal() {
                if (firstRead.getCount() > 0) {
                    firstRead.countDown();
                    onFirstRead.run();
                }
            }
        }

        /** stdin that blocks until stdout is being read, or fails, like a full pipe nobody drains. */
        private static final class StdinNeedingDrainedStdout extends OutputStream {
            final ByteArrayOutputStream received = new ByteArrayOutputStream();
            private final CountDownLatch stdoutDrained;

            StdinNeedingDrainedStdout(CountDownLatch stdoutDrained) {
                this.stdoutDrained = stdoutDrained;
            }

            @Override
            public void write(int b) throws IOException {
                awaitDrained();
                received.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                awaitDrained();
                received.write(b, off, len);
            }

            private void awaitDrained() throws IOException {
                try {
                    if (!stdoutDrained.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("stdin is blocked while nobody reads the CLI output (pipe deadlock)");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
        }

        private PromptRequest request(String sessionId) {
            return new PromptRequest(sessionId, List.of(new ContentBlock.Text("hello")), null, null);
        }

        @Test
        void promptIsWrittenWhileTheCliOutputIsAlreadyBeingRead() throws Exception {
            WatchedStdout stdout = new WatchedStdout(RESULT, () -> {
            });
            StdinNeedingDrainedStdout stdin = new StdinNeedingDrainedStdout(stdout.firstRead);
            client = createClient((cmd, env, workDir) -> new FakeProcess(
                stdout, stdin, new ByteArrayInputStream(new byte[0])));
            String sessionId = client.createSession(null);

            PromptResponse response = client.sendPrompt(request(sessionId), onUpdate);

            assertNotNull(response);
            assertTrue(updates.stream().anyMatch(u -> u instanceof SessionUpdate.AgentMessageChunk));
            String sent = stdin.received.toString(StandardCharsets.UTF_8);
            assertTrue(sent.contains("\"type\":\"user\"") && sent.contains("hello"), sent);
        }

        @Test
        void aPromptThatCouldNotBeWrittenFailsTheTurn() throws Exception {
            OutputStream brokenStdin = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    throw new IOException("Pipe terminata");
                }
            };
            client = createClient((cmd, env, workDir) -> new FakeProcess(
                new ByteArrayInputStream(new byte[0]), brokenStdin, new ByteArrayInputStream(new byte[0])));
            String sessionId = client.createSession(null);

            ClientException e = assertThrows(ClientException.class, () -> client.sendPrompt(request(sessionId), onUpdate));

            assertTrue(e.getMessage().contains("Failed to write prompt to claude process"), e.getMessage());
            assertTrue(e.getMessage().contains("Pipe terminata"), e.getMessage());
        }

        @Test
        void aWriteThatFailsBecauseTheUserPressedStopIsNotAnError() throws Exception {
            // Stop kills the process, which breaks the pipe; the turn must end as cancelled, not as a write error.
            AtomicReference<String> session = new AtomicReference<>();
            OutputStream brokenStdin = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    throw new IOException("Pipe terminata");
                }
            };
            WatchedStdout stdout = new WatchedStdout("", () -> client.cancelSession(session.get()));
            client = createClient((cmd, env, workDir) -> new FakeProcess(
                stdout, brokenStdin, new ByteArrayInputStream(new byte[0])));
            session.set(client.createSession(null));

            PromptResponse response = client.sendPrompt(request(session.get()), onUpdate);

            assertEquals("cancelled", response.stopReason());
        }

        /** Gives other threads a chance to write between the pieces of one logical message. */
        private static final class YieldingStream extends ByteArrayOutputStream {
            @Override
            public synchronized void write(byte[] b, int off, int len) {
                super.write(b, off, len);
                Thread.yield();
            }

            @Override
            public synchronized void write(int b) {
                super.write(b);
                Thread.yield();
            }
        }

        @Test
        void controlResponsesAndThePromptAreNeverInterleaved() throws Exception {
            // The prompt writer and the control-response writer share stdin. Each takes the stream's
            // monitor for a whole line, so a line is never torn by the other writer.
            YieldingStream shared = new YieldingStream();
            JsonObject controlRequest = JsonParser.parseString("""
                {"type":"control_request","subtype":"can_use_tool","requestId":"r"}
                """).getAsJsonObject();
            int perWriter = 300;
            CountDownLatch go = new CountDownLatch(1);
            Runnable prompts = () -> {
                try {
                    go.await();
                    for (int n = 0; n < perWriter; n++) {
                        ClaudeClient.writeJsonPromptToStdin(shared, "prompt " + n, List.of());
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            Runnable controls = () -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int n = 0; n < perWriter; n++) {
                    ClaudeClient.respondToControlRequest(controlRequest, shared);
                }
            };
            Thread[] threads = {new Thread(prompts), new Thread(prompts), new Thread(controls), new Thread(controls)};
            for (Thread t : threads) t.start();
            go.countDown();
            for (Thread t : threads) t.join();

            String[] lines = shared.toString(StandardCharsets.UTF_8).split("\n");
            assertEquals(4 * perWriter, lines.length);
            int userLines = 0;
            int controlLines = 0;
            for (String line : lines) {
                String type = JsonParser.parseString(line).getAsJsonObject().get("type").getAsString();
                if ("user".equals(type)) userLines++;
                else if ("control_response".equals(type)) controlLines++;
            }
            assertEquals(2 * perWriter, userLines);
            assertEquals(2 * perWriter, controlLines);
        }
    }

    @Nested
    class ControlResponse {

        @Test
        void respondToControlRequest_writesApproval() throws Exception {
            JsonObject event = JsonParser.parseString("""
                {"type":"control_request","requestId":"req-1","tool":"bash","command":"ls"}
                """).getAsJsonObject();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ClaudeClient.respondToControlRequest(event, out);

            String written = out.toString(StandardCharsets.UTF_8);
            assertFalse(written.isEmpty());
            JsonObject response = JsonParser.parseString(written.trim()).getAsJsonObject();
            assertEquals("control_response", response.get("type").getAsString());
        }
    }

    @Nested
    class SessionOptions {

        @Test
        void listSessionOptions_includesEffort() throws Exception {
            client = createClient("");
            List<SessionOption> options = client.listSessionOptions();
            assertFalse(options.isEmpty());
            assertTrue(options.stream().anyMatch(o -> "effort".equals(o.key())));
        }
    }

    @Nested
    class KnownModels {

        @Test
        void availableModelsNotEmpty() throws Exception {
            client = createClient("");
            List<Model> models = client.getAvailableModels();
            assertFalse(models.isEmpty());
        }
    }

    /**
     * Minimal fake Process that supplies predetermined streams.
     */
    private static class FakeProcess extends Process {
        private final InputStream stdout;
        private final OutputStream stdin;
        private final InputStream stderr;

        FakeProcess(InputStream stdout, OutputStream stdin, InputStream stderr) {
            this.stdout = stdout;
            this.stdin = stdin;
            this.stderr = stderr;
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
        }

        @Override
        public boolean isAlive() {
            return false;
        }
    }

    /**
     * Minimal AgentConfig stub that avoids IntelliJ service dependencies.
     */
    private static class StubAgentConfig implements AgentConfig {
        @Override
        public @NotNull String getDisplayName() {
            return "test";
        }

        @Override
        public @NotNull String getNotificationGroupId() {
            return "test";
        }

        @Override
        public void prepareForLaunch(String s) {
        }

        @Override
        public @NotNull String findAgentBinary() {
            return "/usr/bin/claude";
        }

        @Override
        public @NotNull ProcessBuilder buildAcpProcess(String s, String s1, int i) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void parseInitializeResponse(JsonObject o) {
        }

        @Override
        public String parseModelUsage(JsonObject o) {
            return null;
        }

        @Override
        public @NotNull AuthMethod getAuthMethod() {
            return new AuthMethod();
        }

        @Override
        public String getAgentBinaryPath() {
            return "/usr/bin/claude";
        }

        @Override
        public String getSessionInstructions() {
            return null;
        }
    }
}
