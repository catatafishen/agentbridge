package com.github.catatafishen.agentbridge.client.codex;

import com.github.catatafishen.agentbridge.bridge.AgentConfig;
import com.github.catatafishen.agentbridge.bridge.ProfileBasedAgentConfig;
import com.github.catatafishen.agentbridge.model.SessionUpdate;
import com.github.catatafishen.agentbridge.services.AgentProfile;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Protocol-level tests for {@link CodexClient} using a mocked transport.
 * Tests notification handling, message dispatch, and event processing
 * without launching a real Codex process.
 */
class CodexClientProtocolTest {

    private JsonRpcTransport mockTransport;
    private CodexApprovalHandler mockApprovalHandler;
    private CodexClient client;

    @BeforeEach
    void setUp() {
        mockTransport = mock(JsonRpcTransport.class);
        mockApprovalHandler = mock(CodexApprovalHandler.class);
        AgentProfile profile = CodexClient.createDefaultProfile();
        AgentConfig config = new ProfileBasedAgentConfig(profile, null);

        client = new CodexClient(profile, config, null, null, 0, mockTransport, mockApprovalHandler);
    }

    @Nested
    class OnNotification {

        @Test
        void textDeltaDeliversAgentMessageChunk() {
            setActiveTurn();
            List<SessionUpdate> updates = captureUpdates();

            JsonObject msg = JsonParser.parseString("""
                {"method": "item/agentMessage/delta", "params": {"delta": "Hello world"}}
                """).getAsJsonObject();

            client.onNotification(msg);

            assertEquals(1, updates.size());
            assertInstanceOf(SessionUpdate.AgentMessageChunk.class, updates.get(0));
        }

        @Test
        void textDeltaWithObjectFormat() {
            setActiveTurn();
            List<SessionUpdate> updates = captureUpdates();

            JsonObject msg = JsonParser.parseString("""
                {"method": "item/agentMessage/delta", "params": {"delta": {"text": "structured"}}}
                """).getAsJsonObject();

            client.onNotification(msg);

            assertEquals(1, updates.size());
            assertInstanceOf(SessionUpdate.AgentMessageChunk.class, updates.get(0));
        }

        @Test
        void emptyDeltaIsIgnored() {
            setActiveTurn();
            List<SessionUpdate> updates = captureUpdates();

            JsonObject msg = JsonParser.parseString("""
                {"method": "item/agentMessage/delta", "params": {"delta": ""}}
                """).getAsJsonObject();

            client.onNotification(msg);

            assertTrue(updates.isEmpty());
        }

        @Test
        void itemStartedWithMcpToolCallEmitsToolCall() {
            setActiveTurn();
            List<SessionUpdate> updates = captureUpdates();

            JsonObject msg = JsonParser.parseString("""
                {"method": "item/started", "params": {
                  "item": {
                    "id": "item-1",
                    "type": "mcpToolCall",
                    "tool": "agentbridge_read_file",
                    "arguments": {"path": "test.java"}
                  }
                }}""").getAsJsonObject();

            client.onNotification(msg);

            assertEquals(1, updates.size());
            assertInstanceOf(SessionUpdate.ToolCall.class, updates.get(0));
            SessionUpdate.ToolCall tc = (SessionUpdate.ToolCall) updates.get(0);
            assertEquals("read_file", tc.title());
        }

        @Test
        void itemStartedTracksPendingMcpTool() {
            setActiveTurn();

            JsonObject msg = JsonParser.parseString("""
                {"method": "item/started", "params": {
                  "item": {"id": "tc-123", "type": "mcpToolCall", "tool": "agentbridge_search_text"}
                }}""").getAsJsonObject();

            client.onNotification(msg);

            verify(mockApprovalHandler).trackPendingMcpTool("tc-123", "search_text");
        }

        @Test
        void itemCompletedRemovesPendingMcpTool() {
            setActiveTurn();

            JsonObject msg = JsonParser.parseString("""
                {"method": "item/completed", "params": {
                  "item": {"id": "tc-123", "type": "mcpToolCall", "status": "completed"}
                }}""").getAsJsonObject();

            client.onNotification(msg);

            verify(mockApprovalHandler).removePendingMcpTool("tc-123");
        }

        @Test
        void itemCompletedWithOutputEmitsToolCallUpdate() {
            setActiveTurn();
            List<SessionUpdate> updates = captureUpdates();

            JsonObject msg = JsonParser.parseString("""
                {"method": "item/completed", "params": {
                  "item": {"id": "tc-1", "type": "mcpToolCall", "status": "completed", "output": "file contents here"}
                }}""").getAsJsonObject();

            client.onNotification(msg);

            assertEquals(1, updates.size());
            assertInstanceOf(SessionUpdate.ToolCallUpdate.class, updates.get(0));
            SessionUpdate.ToolCallUpdate u = (SessionUpdate.ToolCallUpdate) updates.get(0);
            assertEquals("tc-1", u.toolCallId());
            assertEquals(SessionUpdate.ToolCallStatus.COMPLETED, u.status());
        }

        @Test
        void turnCompletedCompletesActiveTurnFuture() {
            CompletableFuture<String> future = setActiveTurn();

            JsonObject msg = JsonParser.parseString("""
                {"method": "turn/completed", "params": {"turn": {"status": "completed"}}}
                """).getAsJsonObject();

            client.onNotification(msg);

            assertTrue(future.isDone());
            assertEquals("end_turn", future.getNow(null));
        }

        @Test
        void turnCompletedWithInterruptedStatusReturnsCancelled() {
            CompletableFuture<String> future = setActiveTurn();

            JsonObject msg = JsonParser.parseString("""
                {"method": "turn/completed", "params": {"turn": {"status": "interrupted"}}}
                """).getAsJsonObject();

            client.onNotification(msg);

            assertTrue(future.isDone());
            assertEquals("cancelled", future.getNow(null));
        }

        @Test
        void turnCompletedWithUsageEmitsTurnUsage() {
            setActiveTurn();
            List<SessionUpdate> updates = captureUpdates();

            JsonObject msg = JsonParser.parseString("""
                {"method": "turn/completed", "params": {"turn": {
                  "status": "completed",
                  "usage": {"input_tokens": 200, "output_tokens": 100}
                }}}""").getAsJsonObject();

            client.onNotification(msg);

            assertEquals(1, updates.size());
            assertInstanceOf(SessionUpdate.TurnUsage.class, updates.get(0));
            SessionUpdate.TurnUsage usage = (SessionUpdate.TurnUsage) updates.get(0);
            assertEquals(200, usage.inputTokens());
            assertEquals(100, usage.outputTokens());
        }

        @Test
        void turnFailedEmitsBannerAndCompletesWithError() {
            CompletableFuture<String> future = setActiveTurn();
            List<SessionUpdate> updates = captureUpdates();

            JsonObject msg = JsonParser.parseString("""
                {"method": "turn/failed", "params": {"turn": {"status": "failed", "error": {"message": "crash"}}}}
                """).getAsJsonObject();

            client.onNotification(msg);

            assertTrue(future.isDone());
            assertEquals("error", future.getNow(null));
            assertFalse(updates.isEmpty());
            assertInstanceOf(SessionUpdate.Banner.class, updates.get(0));
        }

        @Test
        void reasoningDeltaEmitsThoughtChunk() {
            setActiveTurn();
            List<SessionUpdate> updates = captureUpdates();

            JsonObject msg = JsonParser.parseString("""
                {"method": "item/reasoning/summaryTextDelta", "params": {"delta": "thinking about it"}}
                """).getAsJsonObject();

            client.onNotification(msg);

            assertEquals(1, updates.size());
            assertInstanceOf(SessionUpdate.AgentThoughtChunk.class, updates.get(0));
        }

        @Test
        void itemStartedReasoningEmitsInitialThoughtChip() {
            setActiveTurn();
            List<SessionUpdate> updates = captureUpdates();

            JsonObject msg = JsonParser.parseString("""
                {"method": "item/started", "params": {"item": {"type": "reasoning"}}}
                """).getAsJsonObject();

            client.onNotification(msg);

            assertFalse(updates.isEmpty());
            assertInstanceOf(SessionUpdate.AgentThoughtChunk.class, updates.get(0));
        }
    }

    @Nested
    class OnServerRequest {

        @Test
        void unknownMethodRespondsWithError() {
            JsonObject msg = JsonParser.parseString("""
                {"id": 1, "method": "unknown/method", "params": {}}
                """).getAsJsonObject();

            client.onServerRequest(msg);

            verify(mockTransport).sendErrorResponse(any(), any(JsonObject.class));
        }

        @Test
        void approvalRequestDelegatesToHandler() {
            setActiveTurn();

            JsonObject msg = JsonParser.parseString("""
                {"id": 2, "method": "item/commandExecution/requestApproval", "params": {"command": "ls"}}
                """).getAsJsonObject();

            client.onServerRequest(msg);

            verify(mockApprovalHandler).handleNativeApprovalRequest(any(), eq("item/commandExecution/requestApproval"), any(), any(), any());
        }

        @Test
        void userInputRequestDelegatesToHandler() {
            JsonObject msg = JsonParser.parseString("""
                {"id": 3, "method": "item/tool/requestUserInput", "params": {"question": "Enter API key"}}
                """).getAsJsonObject();

            client.onServerRequest(msg);

            verify(mockApprovalHandler).handleUserInputRequest(any(), any());
        }
    }

    @Nested
    class MessageClassification {

        @Test
        void responseMessage() {
            JsonObject msg = JsonParser.parseString("""
                {"id": 1, "result": {}}
                """).getAsJsonObject();
            assertEquals(CodexClient.MessageType.RESPONSE, CodexClient.classifyMessageType(msg));
        }

        @Test
        void serverRequestMessage() {
            JsonObject msg = JsonParser.parseString("""
                {"id": 2, "method": "some/method"}
                """).getAsJsonObject();
            assertEquals(CodexClient.MessageType.SERVER_REQUEST, CodexClient.classifyMessageType(msg));
        }

        @Test
        void notificationMessage() {
            JsonObject msg = JsonParser.parseString("""
                {"method": "item/started"}
                """).getAsJsonObject();
            assertEquals(CodexClient.MessageType.NOTIFICATION, CodexClient.classifyMessageType(msg));
        }
    }

    @Nested
    class IsConnected {

        @Test
        void falseWhenTransportNotConnected() {
            when(mockTransport.isConnected()).thenReturn(false);
            assertFalse(client.isConnected());
        }
    }

    @Nested
    class StaticHelpers {

        @Test
        void buildCommandArgsJson() {
            String result = CodexClient.buildCommandArgsJson("echo \"hello\"");
            assertEquals("{\"command\":\"echo \\\"hello\\\"\"}", result);
        }

        @Test
        void isCodexAuthErrorDetectsAuthFailure() {
            assertTrue(CodexClient.isCodexAuthError("Authentication required"));
            assertFalse(CodexClient.isCodexAuthError("Connection timeout"));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private CompletableFuture<String> setActiveTurn() {
        CompletableFuture<String> future = new CompletableFuture<>();
        try {
            var resultField = CodexClient.class.getDeclaredField("activeTurnResult");
            resultField.setAccessible(true);
            ((AtomicReference<CompletableFuture<String>>) resultField.get(client)).set(future);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return future;
    }

    private List<SessionUpdate> captureUpdates() {
        List<SessionUpdate> updates = new ArrayList<>();
        try {
            var callbackField = CodexClient.class.getDeclaredField("activeTurnCallback");
            callbackField.setAccessible(true);
            ((AtomicReference<Consumer<SessionUpdate>>) callbackField.get(client)).set(updates::add);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return updates;
    }
}
