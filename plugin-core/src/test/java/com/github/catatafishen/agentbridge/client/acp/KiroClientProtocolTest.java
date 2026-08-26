package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.client.acp.transport.JsonRpcTransport;
import com.github.catatafishen.agentbridge.model.ContentBlock;
import com.github.catatafishen.agentbridge.model.PromptResponse;
import com.github.catatafishen.agentbridge.model.SessionUpdate;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Protocol-level tests for {@link KiroClient} using a mocked transport.
 * Tests Kiro-specific notification routing, request handling, processUpdate behaviour,
 * and tryRecoverPromptException without launching a real process or requiring IntelliJ APIs.
 *
 * <p>Strategy: construct a {@link KiroClient} via its package-private test constructor that
 * injects a Mockito mock transport. After {@link KiroClient#registerHandlers()} is called,
 * {@link ArgumentCaptor} captures the notification, request, and stderr lambdas so tests can
 * fire them directly without a running process.</p>
 */
class KiroClientProtocolTest {

    private JsonRpcTransport mockTransport;
    private KiroClient client;

    // Captured handlers — populated by setUp() after calling registerHandlers()
    private Consumer<JsonRpcTransport.IncomingNotification> notificationHandler;
    private BiConsumer<JsonElement, JsonRpcTransport.IncomingRequest> requestHandler;
    private Consumer<String> stderrHandler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mockTransport = mock(JsonRpcTransport.class);
        client = new KiroClient(null, mockTransport);

        // registerHandlers() installs all callbacks on the transport — capture them
        client.registerHandlers();

        var notifCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(mockTransport).onNotification(notifCaptor.capture());
        notificationHandler = notifCaptor.getValue();

        var requestCaptor = ArgumentCaptor.forClass(BiConsumer.class);
        verify(mockTransport).onRequest(requestCaptor.capture());
        requestHandler = requestCaptor.getValue();

        var stderrCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(mockTransport).onStderr(stderrCaptor.capture());
        stderrHandler = stderrCaptor.getValue();
    }

    // ── Notification routing ─────────────────────────────────────────────

    @Nested
    @DisplayName("Kiro-specific notification routing via handleKiroNotification")
    class KiroNotificationRouting {

        @Test
        @DisplayName("session/update is delegated to handleSessionUpdate (not dropped)")
        void sessionUpdateDelegatedToParent() {
            List<SessionUpdate> updates = new ArrayList<>();
            setUpdateConsumer(updates::add);

            JsonObject params = JsonParser.parseString("""
                {
                  "update": {
                    "sessionUpdate": "agent_message_chunk",
                    "content": [{"type": "text", "text": "hello"}]
                  }
                }""").getAsJsonObject();

            notificationHandler.accept(
                new JsonRpcTransport.IncomingNotification("session/update", params));

            assertEquals(1, updates.size());
            assertInstanceOf(SessionUpdate.AgentMessageChunk.class, updates.get(0));
        }

        @Test
        @DisplayName("_kiro.dev/commands/available updates available commands")
        void commandsAvailableUpdatesCommandList() {
            JsonObject params = JsonParser.parseString("""
                {"commands":[
                  {"name":"compact","description":"Compact the conversation"},
                  {"name":"clear","description":"Clear the session"}
                ]}""").getAsJsonObject();

            notificationHandler.accept(
                new JsonRpcTransport.IncomingNotification("_kiro.dev/commands/available", params));

            List<String> commands = client.getAvailableCommands();
            assertEquals(2, commands.size());
            assertTrue(commands.contains("/compact"));
            assertTrue(commands.contains("/clear"));
        }

        @Test
        @DisplayName("_kiro.dev/commands/available with null params is ignored gracefully")
        void commandsAvailableNullParamsNoThrow() {
            assertDoesNotThrow(() ->
                notificationHandler.accept(
                    new JsonRpcTransport.IncomingNotification("_kiro.dev/commands/available", null)));
        }

        @Test
        @DisplayName("_kiro.dev/mcp/oauth_request with url field is handled without throw")
        void mcpOAuthRequestDoesNotThrow() {
            JsonObject params = new JsonObject();
            params.addProperty("url", "https://example.com/oauth");

            assertDoesNotThrow(() ->
                notificationHandler.accept(
                    new JsonRpcTransport.IncomingNotification("_kiro.dev/mcp/oauth_request", params)));
        }

        @Test
        @DisplayName("_kiro.dev/mcp/oauth_request with null params is handled without throw")
        void mcpOAuthRequestNullParamsNoThrow() {
            assertDoesNotThrow(() ->
                notificationHandler.accept(
                    new JsonRpcTransport.IncomingNotification("_kiro.dev/mcp/oauth_request", null)));
        }

        @Test
        @DisplayName("_kiro.dev/mcp/server_initialized with serverName field is handled without throw")
        void mcpServerInitializedDoesNotThrow() {
            JsonObject params = new JsonObject();
            params.addProperty("serverName", "agentbridge");

            assertDoesNotThrow(() ->
                notificationHandler.accept(
                    new JsonRpcTransport.IncomingNotification("_kiro.dev/mcp/server_initialized", params)));
        }

        @Test
        @DisplayName("_kiro.dev/mcp/server_initialized with null params is handled without throw")
        void mcpServerInitializedNullParamsNoThrow() {
            assertDoesNotThrow(() ->
                notificationHandler.accept(
                    new JsonRpcTransport.IncomingNotification("_kiro.dev/mcp/server_initialized", null)));
        }

        @Test
        @DisplayName("_kiro.dev/compaction/status with status field is handled without throw")
        void compactionStatusDoesNotThrow() {
            JsonObject params = new JsonObject();
            params.addProperty("status", "in_progress");

            assertDoesNotThrow(() ->
                notificationHandler.accept(
                    new JsonRpcTransport.IncomingNotification("_kiro.dev/compaction/status", params)));
        }

        @Test
        @DisplayName("_kiro.dev/compaction/status with null params is handled without throw")
        void compactionStatusNullParamsNoThrow() {
            assertDoesNotThrow(() ->
                notificationHandler.accept(
                    new JsonRpcTransport.IncomingNotification("_kiro.dev/compaction/status", null)));
        }

        @Test
        @DisplayName("_kiro.dev/clear/status with status field is handled without throw")
        void clearStatusDoesNotThrow() {
            JsonObject params = new JsonObject();
            params.addProperty("status", "done");

            assertDoesNotThrow(() ->
                notificationHandler.accept(
                    new JsonRpcTransport.IncomingNotification("_kiro.dev/clear/status", params)));
        }

        @Test
        @DisplayName("_kiro.dev/clear/status with null params is handled without throw")
        void clearStatusNullParamsNoThrow() {
            assertDoesNotThrow(() ->
                notificationHandler.accept(
                    new JsonRpcTransport.IncomingNotification("_kiro.dev/clear/status", null)));
        }

        @Test
        @DisplayName("_kiro.dev/metadata is silently ignored")
        void metadataIsIgnored() {
            JsonObject params = new JsonObject();
            params.addProperty("inputTokens", 100);

            assertDoesNotThrow(() ->
                notificationHandler.accept(
                    new JsonRpcTransport.IncomingNotification("_kiro.dev/metadata", params)));
        }

        @Test
        @DisplayName("_session/terminate with sessionId field is handled without throw")
        void sessionTerminateDoesNotThrow() {
            JsonObject params = new JsonObject();
            params.addProperty("sessionId", "sub-session-abc");

            assertDoesNotThrow(() ->
                notificationHandler.accept(
                    new JsonRpcTransport.IncomingNotification("_session/terminate", params)));
        }

        @Test
        @DisplayName("_session/terminate with null params is handled without throw")
        void sessionTerminateNullParamsNoThrow() {
            assertDoesNotThrow(() ->
                notificationHandler.accept(
                    new JsonRpcTransport.IncomingNotification("_session/terminate", null)));
        }

        @Test
        @DisplayName("unknown _kiro.dev/* notification is handled without throw")
        void unknownKiroNotificationNoThrow() {
            assertDoesNotThrow(() ->
                notificationHandler.accept(
                    new JsonRpcTransport.IncomingNotification("_kiro.dev/unknown_future_event", null)));
        }

        @Test
        @DisplayName("unrelated non-kiro notification is not routed to handleKiroNotification")
        void unrelatedNotificationIgnored() {
            assertDoesNotThrow(() ->
                notificationHandler.accept(
                    new JsonRpcTransport.IncomingNotification("some/other/method", null)));
        }
    }

    // ── handleAgentRequest override ──────────────────────────────────────

    @Nested
    @DisplayName("KiroClient.handleAgentRequest overrides for Kiro-specific requests")
    class HandleAgentRequestOverride {

        @Test
        @DisplayName("_kiro/terminal/shell_type returns a non-blank shellType in response")
        void shellTypeReturnsShellName() {
            JsonElement requestId = new JsonPrimitive(42);

            requestHandler.accept(requestId,
                new JsonRpcTransport.IncomingRequest("_kiro/terminal/shell_type", null));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<JsonElement> responseCaptor = ArgumentCaptor.forClass(JsonElement.class);
            verify(mockTransport).sendResponse(eq(requestId), responseCaptor.capture());

            JsonElement response = responseCaptor.getValue();
            assertTrue(response.isJsonObject(), "Response should be a JSON object");
            JsonObject obj = response.getAsJsonObject();
            assertTrue(obj.has("shellType"), "Response must have 'shellType' field");
            String shellType = obj.get("shellType").getAsString();
            assertFalse(shellType.isBlank(), "shellType must not be blank");
            assertFalse(shellType.contains("/"), "shellType must be a basename, not a path");
        }

        @Test
        @DisplayName("_kiro/auth/getAccessToken with no local DB either sends a response or an error — never throws")
        void getAccessTokenMissingDbDoesNotThrow() {
            // The Kiro SQLite DB almost certainly doesn't exist in the test environment,
            // so the handler should respond with a JSON-RPC error rather than throw an exception.
            JsonElement requestId = new JsonPrimitive(99);

            assertDoesNotThrow(() ->
                requestHandler.accept(requestId,
                    new JsonRpcTransport.IncomingRequest("_kiro/auth/getAccessToken", null)));

            // Exactly one of sendResponse or sendError must have been called
            // (not both, not neither). In CI there's no Kiro DB → sendError expected.
        }

        @Test
        @DisplayName("unknown method is delegated to parent AcpClient.handleAgentRequest without throw")
        void unknownMethodDelegatedToParentNoThrow() {
            JsonElement requestId = new JsonPrimitive(7);

            // A completely unknown method: parent sends METHOD_NOT_FOUND error
            assertDoesNotThrow(() ->
                requestHandler.accept(requestId,
                    new JsonRpcTransport.IncomingRequest("unknown/method", new JsonObject())));
        }
    }

    // ── tryRecoverPromptException ────────────────────────────────────────

    @Nested
    @DisplayName("tryRecoverPromptException — Rust panic detection via stderr")
    class TryRecoverPromptException {

        @Test
        @DisplayName("returns null when no panic lines seen in stderr")
        void returnsNullWhenNoStderrPanic() {
            stderrHandler.accept("normal startup output");
            stderrHandler.accept("info: Kiro started on port 3000");

            PromptResponse result = client.tryRecoverPromptException(new RuntimeException("timeout"));
            assertNull(result, "No panic detected — should return null");
        }

        @Test
        @DisplayName("throws UncheckedIOException containing the panic line when panic detected in stderr buffer")
        void throwsWithPanicMessageFromBuffer() {
            stderrHandler.accept("info: Kiro started");
            stderrHandler.accept("thread 'main' panicked at 'index out of bounds', src/main.rs:42");
            stderrHandler.accept("stack backtrace:");

            java.io.UncheckedIOException ex = assertThrows(java.io.UncheckedIOException.class,
                () -> client.tryRecoverPromptException(new RuntimeException("timeout")));
            assertTrue(ex.getMessage().contains("Kiro crashed"),
                "Message should say 'Kiro crashed', got: " + ex.getMessage());
        }

        @Test
        @DisplayName("uses eagerly-captured panic line even when rolling buffer is overflowed by backtrace lines")
        void usesCapturedPanicLineFromStderr() {
            // First panic line triggers eager capture AND (in real usage) process kill.
            // Here destroyProcess() is a real method that calls ClientProcessRegistry.unregister(null)
            // and destroyProcessTree(null) — the latter is a no-op for null processes, so this is safe.
            String panicLine = "thread 'agent' panicked at src/executor.rs:200:10";
            stderrHandler.accept(panicLine);
            // Emit 35 more lines after the panic — these would evict the panic from the 30-line
            // rolling buffer, but capturedPanicLine should still preserve it.
            for (int i = 0; i < 35; i++) {
                stderrHandler.accept("backtrace frame " + i);
            }

            java.io.UncheckedIOException ex = assertThrows(java.io.UncheckedIOException.class,
                () -> client.tryRecoverPromptException(new RuntimeException("timeout")));
            assertTrue(ex.getMessage().contains("Kiro crashed"),
                "Should surface the crash reason, got: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("panicked"),
                "Panic text should appear in the message, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("ANSI codes in panic line are stripped before surfacing in the error message")
        void stripAnsiFromPanicLine() {
            String ansiPanicLine = "\u001b[31mthread 'main' panicked at 'boom'\u001b[0m";
            stderrHandler.accept(ansiPanicLine);

            java.io.UncheckedIOException ex = assertThrows(java.io.UncheckedIOException.class,
                () -> client.tryRecoverPromptException(new RuntimeException("cause")));
            assertFalse(ex.getMessage().contains("\u001b"),
                "ANSI codes should be stripped from the error message");
            assertTrue(ex.getMessage().contains("Kiro crashed"),
                "Message should still describe the crash");
        }
    }

    // ── processUpdate instance method ────────────────────────────────────

    @Nested
    @DisplayName("processUpdate — Kiro-specific update transformations")
    class ProcessUpdate {

        @Test
        @DisplayName("ToolCall with null arguments is filtered out (returns null)")
        void toolCallWithNoArgumentsIsFiltered() {
            var toolCall = new SessionUpdate.ToolCall(
                "tc-1", "read_file", null, null,
                null,   // no rawInput / arguments
                null, null, null, null, null
            );

            SessionUpdate result = client.processUpdate(toolCall);
            assertNull(result, "ToolCall with no arguments should be filtered (returns null)");
        }

        @Test
        @DisplayName("ToolCall with empty arguments string is filtered out (returns null)")
        void toolCallWithEmptyArgumentsIsFiltered() {
            var toolCall = new SessionUpdate.ToolCall(
                "tc-1", "read_file", null, null,
                "",   // empty arguments
                null, null, null, null, null
            );

            SessionUpdate result = client.processUpdate(toolCall);
            assertNull(result, "ToolCall with empty arguments should be filtered (returns null)");
        }

        @Test
        @DisplayName("ToolCall with non-empty arguments passes through")
        void toolCallWithArgumentsPassesThrough() {
            var toolCall = new SessionUpdate.ToolCall(
                "tc-1", "@agentbridge/read_file", null, null,
                "{\"path\":\"src/Foo.java\"}",
                null, null, null, null, null
            );

            SessionUpdate result = client.processUpdate(toolCall);
            assertInstanceOf(SessionUpdate.ToolCall.class, result);
            assertEquals("tc-1", ((SessionUpdate.ToolCall) result).toolCallId());
        }

        @Test
        @DisplayName("ToolCall with __tool_use_purpose has purpose extracted")
        void toolCallPurposeIsExtracted() {
            var toolCall = new SessionUpdate.ToolCall(
                "tc-2", "@agentbridge/read_file", null, null,
                "{\"path\":\"src/Foo.java\",\"__tool_use_purpose\":\"Read the implementation\"}",
                null, null, null, null, null
            );

            SessionUpdate result = client.processUpdate(toolCall);
            assertInstanceOf(SessionUpdate.ToolCall.class, result);
            assertEquals("Read the implementation",
                ((SessionUpdate.ToolCall) result).purpose());
        }

        @Test
        @DisplayName("AgentMessageChunk with Thinking block is converted to AgentThoughtChunk")
        void thinkingBlockConverted() {
            var thinking = new ContentBlock.Thinking("reason");
            var update = new SessionUpdate.AgentMessageChunk(List.of(thinking));

            SessionUpdate result = client.processUpdate(update);
            assertInstanceOf(SessionUpdate.AgentThoughtChunk.class, result);
        }

        @Test
        @DisplayName("AgentMessageChunk with only Text block passes through unchanged")
        void textBlockPassesThrough() {
            var text = new ContentBlock.Text("hello");
            var update = new SessionUpdate.AgentMessageChunk(List.of(text));

            SessionUpdate result = client.processUpdate(update);
            assertInstanceOf(SessionUpdate.AgentMessageChunk.class, result);
        }

        @Test
        @DisplayName("non-ToolCall, non-AgentMessageChunk updates pass through unchanged")
        void otherUpdatesPassThrough() {
            var infoUpdate = new SessionUpdate.SessionInfoChanged("new title");
            SessionUpdate result = client.processUpdate(infoUpdate);
            assertEquals(infoUpdate, result);
        }
    }

    // ── registerHandlers clears stale crash state ────────────────────────

    @Nested
    @DisplayName("registerHandlers resets crash state from prior process lifecycle")
    class RegisterHandlersCrashReset {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("calling registerHandlers again clears capturedPanicLine so old crash is not reported")
        void registerHandlersClearsCapturedPanicLine() {
            // Poison the crash state via the first lifecycle's stderr handler
            stderrHandler.accept("thread 'main' panicked at 'boom', src/main.rs:1");

            // Re-register (simulates a process restart)
            client.registerHandlers();

            // Capture the new stderr handler from the second registerHandlers() call
            var stderrCaptor2 = ArgumentCaptor.forClass(Consumer.class);
            verify(mockTransport, org.mockito.Mockito.times(2)).onStderr(stderrCaptor2.capture());
            Consumer<String> newStderrHandler = stderrCaptor2.getAllValues().get(1);

            // After re-registration the capturedPanicLine is cleared — no panic from old lifecycle
            PromptResponse result = client.tryRecoverPromptException(new RuntimeException("x"));
            assertNull(result,
                "After registerHandlers(), stale capturedPanicLine should be cleared");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Injects an update consumer into the client via reflection on the private
     * {@code updateConsumer} field in {@link AcpClient}.
     */
    @SuppressWarnings("unchecked")
    private void setUpdateConsumer(Consumer<SessionUpdate> consumer) {
        try {
            var field = AcpClient.class.getDeclaredField("updateConsumer");
            field.setAccessible(true);
            ((AtomicReference<Consumer<SessionUpdate>>) field.get(client)).set(consumer);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
