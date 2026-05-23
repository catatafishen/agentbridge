package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.client.AbstractClient;
import com.github.catatafishen.agentbridge.client.ClientPromptException;
import com.github.catatafishen.agentbridge.client.acp.transport.JsonRpcTransport;
import com.github.catatafishen.agentbridge.model.SessionUpdate;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Protocol-level tests for {@link AcpClient} using a mocked transport.
 * Tests session update handling, permission flow, and protocol logic
 * without launching a real ACP process or requiring IntelliJ platform services.
 */
class AcpClientProtocolTest {

    private JsonRpcTransport mockTransport;
    private TestAcpClient client;

    @BeforeEach
    void setUp() {
        mockTransport = mock(JsonRpcTransport.class);
        client = new TestAcpClient(mockTransport);
    }

    @Nested
    class HandleSessionUpdate {

        @Test
        void nullParamsIsIgnored() {
            client.handleSessionUpdate(null);
            assertTrue(client.getAvailableCommands().isEmpty());
        }

        @Test
        void emptyParamsProducesNullUpdate() {
            List<SessionUpdate> updates = new ArrayList<>();
            client.setUpdateConsumer(updates::add);
            client.handleSessionUpdate(new JsonObject());
            assertTrue(updates.isEmpty());
        }

        @Test
        void agentMessageChunkIsDeliveredToConsumer() {
            List<SessionUpdate> updates = new ArrayList<>();
            client.setUpdateConsumer(updates::add);

            JsonObject params = JsonParser.parseString("""
                {
                  "update": {
                    "sessionUpdate": "agent_message_chunk",
                    "content": [{"type": "text", "text": "Hello world"}]
                  }
                }""").getAsJsonObject();

            client.handleSessionUpdate(params);

            assertEquals(1, updates.size());
            assertInstanceOf(SessionUpdate.AgentMessageChunk.class, updates.get(0));
        }

        @Test
        void toolCallUpdateIsDeliveredToConsumer() {
            List<SessionUpdate> updates = new ArrayList<>();
            client.setUpdateConsumer(updates::add);

            JsonObject params = JsonParser.parseString("""
                {
                  "update": {
                    "sessionUpdate": "tool_call_update",
                    "toolCallId": "tc-1",
                    "title": "read_file"
                  }
                }""").getAsJsonObject();

            client.handleSessionUpdate(params);

            assertEquals(1, updates.size());
            assertInstanceOf(SessionUpdate.ToolCallUpdate.class, updates.get(0));
        }

        @Test
        void noConsumerRegistered_doesNotThrow() {
            JsonObject params = JsonParser.parseString("""
                {
                  "update": {
                    "sessionUpdate": "agent_message_chunk",
                    "content": [{"type": "text", "text": "No consumer"}]
                  }
                }""").getAsJsonObject();

            assertDoesNotThrow(() -> client.handleSessionUpdate(params));
        }

        @Test
        void configOptionUpdateUpdatesInternalState() {
            // config_option_update with "configOptions" array format
            JsonObject params = JsonParser.parseString("""
                {
                  "update": {
                    "sessionUpdate": "config_option_update",
                    "configOptions": [
                      {"id": "model", "label": "Model", "values": [
                        {"id": "gpt-4", "label": "GPT-4"}
                      ]}
                    ]
                  }
                }""").getAsJsonObject();

            List<SessionUpdate> updates = new ArrayList<>();
            client.setUpdateConsumer(updates::add);
            client.handleSessionUpdate(params);

            assertEquals(1, updates.size());
            assertInstanceOf(SessionUpdate.ConfigOptionsChanged.class, updates.get(0));
        }

        @Test
        void availableCommandsChangedUpdatesInternalState() {
            JsonObject params = JsonParser.parseString("""
                {
                  "update": {
                    "sessionUpdate": "available_commands_update",
                    "availableCommands": [
                      {"name": "help", "description": "Show help"},
                      {"name": "clear", "description": "Clear chat"}
                    ]
                  }
                }""").getAsJsonObject();

            client.handleSessionUpdate(params);

            List<String> commands = client.getAvailableCommands();
            assertEquals(2, commands.size());
            assertTrue(commands.contains("help"));
            assertTrue(commands.contains("clear"));
        }

        @Test
        void turnUsageIsDeliveredToConsumer() {
            List<SessionUpdate> updates = new ArrayList<>();
            client.setUpdateConsumer(updates::add);

            // turn_usage puts inputTokens/outputTokens at the top level of the update
            JsonObject params = JsonParser.parseString("""
                {
                  "update": {
                    "sessionUpdate": "turn_usage",
                    "inputTokens": 100,
                    "outputTokens": 50
                  }
                }""").getAsJsonObject();

            client.handleSessionUpdate(params);

            assertEquals(1, updates.size());
            assertInstanceOf(SessionUpdate.TurnUsage.class, updates.get(0));
            SessionUpdate.TurnUsage usage = (SessionUpdate.TurnUsage) updates.get(0);
            assertEquals(100, usage.inputTokens());
            assertEquals(50, usage.outputTokens());
        }

        @Test
        void bannerUpdateIsDeliveredToConsumer() {
            List<SessionUpdate> updates = new ArrayList<>();
            client.setUpdateConsumer(updates::add);

            JsonObject params = JsonParser.parseString("""
                {
                  "update": {
                    "sessionUpdate": "banner",
                    "content": [{"type": "text", "text": "Welcome!"}],
                    "contentType": "info"
                  }
                }""").getAsJsonObject();

            client.handleSessionUpdate(params);

            assertEquals(1, updates.size());
            assertInstanceOf(SessionUpdate.Banner.class, updates.get(0));
        }

        @Test
        void usageUpdateIsDeliveredToConsumer() {
            List<SessionUpdate> updates = new ArrayList<>();
            client.setUpdateConsumer(updates::add);

            JsonObject params = JsonParser.parseString("""
                {
                  "update": {
                    "sessionUpdate": "usage_update",
                    "used": 50
                  }
                }""").getAsJsonObject();

            client.handleSessionUpdate(params);

            assertEquals(1, updates.size());
            assertInstanceOf(SessionUpdate.TurnUsage.class, updates.get(0));
            SessionUpdate.TurnUsage u = (SessionUpdate.TurnUsage) updates.get(0);
            assertEquals(50, u.inputTokens());
        }

        @Test
        void unwrapsNestedUpdateObject() {
            List<SessionUpdate> updates = new ArrayList<>();
            client.setUpdateConsumer(updates::add);

            // Copilot/Junie wrap the payload in a nested "update" key
            JsonObject params = JsonParser.parseString("""
                {
                  "sessionId": "sess-123",
                  "update": {
                    "sessionUpdate": "agent_thought_chunk",
                    "content": [{"type": "text", "text": "thinking..."}]
                  }
                }""").getAsJsonObject();

            client.handleSessionUpdate(params);

            assertEquals(1, updates.size());
            assertInstanceOf(SessionUpdate.AgentThoughtChunk.class, updates.get(0));
        }

        @Test
        void withoutUpdateKey_parsesDirectly() {
            List<SessionUpdate> updates = new ArrayList<>();
            client.setUpdateConsumer(updates::add);

            // Direct format (no "update" wrapper)
            JsonObject params = JsonParser.parseString("""
                {
                  "sessionUpdate": "user_message_chunk",
                  "content": [{"type": "text", "text": "hello"}]
                }""").getAsJsonObject();

            client.handleSessionUpdate(params);

            assertEquals(1, updates.size());
            assertInstanceOf(SessionUpdate.UserMessageChunk.class, updates.get(0));
        }
    }

    @Nested
    class CreateSession {

        @Test
        void createsSessionAndStoresId() throws Exception {
            // Use the ACP wire format: models wrapped in container with availableModels
            String responseJson = """
                {
                  "sessionId": "session-abc-123",
                  "models": {"availableModels": [{"modelId": "gpt-4o", "name": "GPT-4o"}]},
                  "modes": {"availableModes": [{"id": "agent", "name": "Agent"}]}
                }""";
            CompletableFuture<JsonElement> future =
                CompletableFuture.completedFuture(JsonParser.parseString(responseJson));
            when(mockTransport.sendRequest(eq("session/new"), any(JsonObject.class))).thenReturn(future);

            String sessionId = client.createSession("/tmp/test");

            assertEquals("session-abc-123", sessionId);
            assertEquals("session-abc-123", client.getActiveSessionId());
        }

        @Test
        void populatesModelsFromResponse() throws Exception {
            String responseJson = """
                {
                  "sessionId": "s1",
                  "models": {
                    "availableModels": [
                      {"modelId": "gpt-4o", "name": "GPT-4o"},
                      {"modelId": "claude-3-5", "name": "Claude 3.5"}
                    ],
                    "currentModelId": "gpt-4o"
                  }
                }""";
            CompletableFuture<JsonElement> future =
                CompletableFuture.completedFuture(JsonParser.parseString(responseJson));
            when(mockTransport.sendRequest(eq("session/new"), any())).thenReturn(future);

            client.createSession("/tmp/test");

            assertEquals(2, client.getAvailableModels().size());
            assertEquals("gpt-4o", client.getCurrentModelId());
        }

        @Test
        void populatesModesFromResponse() throws Exception {
            String responseJson = """
                {
                  "sessionId": "s1",
                  "modes": {
                    "availableModes": [
                      {"id": "agent", "name": "Agent"},
                      {"id": "edit", "name": "Edit"}
                    ]
                  }
                }""";
            CompletableFuture<JsonElement> future =
                CompletableFuture.completedFuture(JsonParser.parseString(responseJson));
            when(mockTransport.sendRequest(eq("session/new"), any())).thenReturn(future);

            client.createSession("/tmp/test");

            assertEquals(2, client.getAvailableModes().size());
            assertEquals("agent", client.getAvailableModes().get(0).slug());
        }

        @Test
        void populatesCommandsFromResponse() throws Exception {
            String responseJson = """
                {
                  "sessionId": "s1",
                  "commands": [
                    {"name": "help", "description": "Show help"},
                    {"name": "clear", "description": "Clear history"}
                  ]
                }""";
            CompletableFuture<JsonElement> future =
                CompletableFuture.completedFuture(JsonParser.parseString(responseJson));
            when(mockTransport.sendRequest(eq("session/new"), any())).thenReturn(future);

            client.createSession("/tmp/test");

            assertEquals(2, client.getAvailableCommands().size());
            assertTrue(client.getAvailableCommands().contains("help"));
            assertTrue(client.getAvailableCommands().contains("clear"));
        }

        @Test
        void populatesConfigOptionsFromResponse() throws Exception {
            String responseJson = """
                {
                  "sessionId": "s1",
                  "configOptions": [
                    {"id": "model", "label": "Model", "values": [
                      {"id": "gpt-4o", "label": "GPT-4o"}
                    ]}
                  ]
                }""";
            CompletableFuture<JsonElement> future =
                CompletableFuture.completedFuture(JsonParser.parseString(responseJson));
            when(mockTransport.sendRequest(eq("session/new"), any())).thenReturn(future);

            client.createSession("/tmp/test");

            assertEquals(1, client.getAvailableConfigOptions().size());
            assertEquals("model", client.getAvailableConfigOptions().get(0).id());
        }
    }

    @Nested
    class SendPrompt {

        @Test
        void failedPromptThrowsClientPromptException() throws Exception {
            // First create a session
            String responseJson = """
                {"sessionId": "s1", "models": {"availableModels": []}}""";
            CompletableFuture<JsonElement> future =
                CompletableFuture.completedFuture(JsonParser.parseString(responseJson));
            when(mockTransport.sendRequest(eq("session/new"), any())).thenReturn(future);
            client.createSession("/tmp/test");

            // Now make the prompt fail
            CompletableFuture<JsonElement> promptFuture = new CompletableFuture<>();
            promptFuture.completeExceptionally(new RuntimeException("Connection lost"));
            when(mockTransport.sendRequest(eq("session/prompt"), any())).thenReturn(promptFuture);

            var request = new com.github.catatafishen.agentbridge.acp.protocol.PromptRequest(
                "s1", List.of(new com.github.catatafishen.agentbridge.model.ContentBlock.Text("Hello!")), null, null);

            assertThrows(ClientPromptException.class,
                () -> client.sendPrompt(request, update -> {}));
        }
    }

    @Nested
    class CancelSession {

        @Test
        void sendsCancelNotification() throws Exception {
            // Create session first
            String responseJson = """
                {"sessionId": "s1", "models": {"availableModels": []}}""";
            CompletableFuture<JsonElement> future =
                CompletableFuture.completedFuture(JsonParser.parseString(responseJson));
            when(mockTransport.sendRequest(eq("session/new"), any())).thenReturn(future);
            client.createSession("/tmp/test");

            client.cancelSession("s1");
            verify(mockTransport).sendNotification(eq("session/cancel"), any(JsonObject.class));
        }

        @Test
        void cancelClearsCurrentSessionId() throws Exception {
            String responseJson = """
                {"sessionId": "s1", "models": {"availableModels": []}}""";
            CompletableFuture<JsonElement> future =
                CompletableFuture.completedFuture(JsonParser.parseString(responseJson));
            when(mockTransport.sendRequest(eq("session/new"), any())).thenReturn(future);
            client.createSession("/tmp/test");

            assertNotNull(client.getActiveSessionId());
            client.cancelSession("s1");
            assertNull(client.getActiveSessionId());
        }
    }

    @Nested
    class SetSessionOption {

        @BeforeEach
        void createSession() throws Exception {
            String responseJson = """
                {"sessionId": "s1", "models": {"availableModels": []}}""";
            CompletableFuture<JsonElement> future =
                CompletableFuture.completedFuture(JsonParser.parseString(responseJson));
            when(mockTransport.sendRequest(eq("session/new"), any())).thenReturn(future);
            client.createSession("/tmp/test");
        }

        @Test
        void sendsSetModelRequest() {
            // setModel uses sendRequest("session/set_model", ...)
            when(mockTransport.sendRequest(eq("session/set_model"), any()))
                .thenReturn(CompletableFuture.completedFuture(new JsonObject()));
            client.setModel("s1", "gpt-4o");
            verify(mockTransport).sendRequest(eq("session/set_model"), any(JsonObject.class));
        }

        @Test
        void sendsConfigOptionRequest() {
            // setConfigOption uses sendRequest("session/set_config_option", ...)
            when(mockTransport.sendRequest(eq("session/set_config_option"), any()))
                .thenReturn(CompletableFuture.completedFuture(new JsonObject()));
            client.setConfigOption("s1", "model", "gpt-4o");
            verify(mockTransport).sendRequest(eq("session/set_config_option"), any(JsonObject.class));
        }
    }

    @Nested
    class DropCurrentSession {

        @Test
        void clearsSessionId() throws Exception {
            String responseJson = """
                {"sessionId": "s1", "models": {"availableModels": []}}""";
            CompletableFuture<JsonElement> future =
                CompletableFuture.completedFuture(JsonParser.parseString(responseJson));
            when(mockTransport.sendRequest(eq("session/new"), any())).thenReturn(future);
            client.createSession("/tmp/test");

            assertNotNull(client.getActiveSessionId());
            client.dropCurrentSession();
            assertNull(client.getActiveSessionId());
        }
    }

    @Nested
    class UpdateCommandNames {

        @Test
        void updatesAvailableCommands() {
            client.updateCommandNames(List.of("help", "clear", "review"));
            assertEquals(3, client.getAvailableCommands().size());
            assertTrue(client.getAvailableCommands().contains("review"));
        }

        @Test
        void replacePreviousCommands() {
            client.updateCommandNames(List.of("old-cmd"));
            client.updateCommandNames(List.of("new-cmd"));
            assertEquals(1, client.getAvailableCommands().size());
            assertTrue(client.getAvailableCommands().contains("new-cmd"));
            assertFalse(client.getAvailableCommands().contains("old-cmd"));
        }

        @Test
        void emptyListClearsCommands() {
            client.updateCommandNames(List.of("cmd1", "cmd2"));
            client.updateCommandNames(List.of());
            assertTrue(client.getAvailableCommands().isEmpty());
        }
    }

    @Nested
    class PermissionHandling {

        @Test
        void autoDeniesBlockedBuiltInTool() {
            JsonObject params = JsonParser.parseString("""
                {
                  "toolCall": {
                    "title": "bash",
                    "toolCallId": "tc-1"
                  },
                  "options": [
                    {"id": "allow_once", "label": "Allow"},
                    {"id": "deny_once", "label": "Deny"}
                  ]
                }""").getAsJsonObject();

            JsonElement requestId = JsonParser.parseString("\"req-1\"");
            client.handleAgentRequest(requestId,
                new JsonRpcTransport.IncomingRequest("session/request_permission", params));

            verify(mockTransport).sendResponse(eq(requestId), any(JsonObject.class));
        }

        @Test
        void allowsMcpToolsWithoutBlocking() {
            JsonObject params = JsonParser.parseString("""
                {
                  "toolCall": {
                    "title": "agentbridge-read_file",
                    "toolCallId": "tc-2"
                  },
                  "options": [
                    {"id": "allow_once", "label": "Allow"},
                    {"id": "deny_once", "label": "Deny"}
                  ]
                }""").getAsJsonObject();

            JsonElement requestId = JsonParser.parseString("\"req-2\"");
            client.handleAgentRequest(requestId,
                new JsonRpcTransport.IncomingRequest("session/request_permission", params));

            verify(mockTransport).sendResponse(eq(requestId), any(JsonObject.class));
        }
    }

    @Nested
    class IsConnected {

        @Test
        void falseWhenTransportNotAlive() {
            when(mockTransport.isAlive()).thenReturn(false);
            assertFalse(client.isConnected());
        }

        @Test
        void falseWhenNoProcess() {
            when(mockTransport.isAlive()).thenReturn(true);
            // No process was ever launched in test mode
            assertFalse(client.isConnected());
        }
    }

    @Nested
    class NormalizeSessionUpdateParams {

        @Test
        void unwrapsNestedUpdateObject() {
            JsonObject params = JsonParser.parseString("""
                {"sessionId": "s1", "update": {"sessionUpdate": "agent_message_chunk"}}
                """).getAsJsonObject();

            JsonObject normalized = client.normalizeSessionUpdateParams(params);
            assertTrue(normalized.has("sessionUpdate"));
            assertEquals("agent_message_chunk", normalized.get("sessionUpdate").getAsString());
        }

        @Test
        void returnsAsIsWhenNoUpdateKey() {
            JsonObject params = JsonParser.parseString("""
                {"sessionUpdate": "agent_message_chunk"}
                """).getAsJsonObject();

            JsonObject normalized = client.normalizeSessionUpdateParams(params);
            assertSame(params, normalized);
        }

        @Test
        void returnsAsIsWhenUpdateNotObject() {
            JsonObject params = JsonParser.parseString("""
                {"update": "not_an_object"}
                """).getAsJsonObject();

            JsonObject normalized = client.normalizeSessionUpdateParams(params);
            assertSame(params, normalized);
        }
    }

    /**
     * Testable subclass that uses a mocked transport and stubs out all
     * platform-dependent operations (process launch, file I/O, IntelliJ APIs).
     */
    private static class TestAcpClient extends AcpClient {

        TestAcpClient(JsonRpcTransport transport) {
            super(null, transport);
        }

        @Override
        public @NotNull String agentId() {
            return "test-agent";
        }

        @Override
        public @NotNull String displayName() {
            return "Test Agent";
        }

        @Override
        public boolean isHealthy() {
            return true;
        }

        @Override
        protected boolean isMcpToolTitle(@NotNull String protocolTitle) {
            return protocolTitle.startsWith("agentbridge");
        }

        @Override
        protected @NotNull List<String> buildCommand(String cwd, int mcpPort) {
            return List.of("echo", "test");
        }

        @Override
        String loadResumeSessionId() {
            return null;
        }

        @Override
        protected int resolveMcpPort() {
            return 0;
        }

        @Override
        protected void onSessionCreated(String sessionId) {
            // no-op for tests
        }

        @Override
        protected void beforeCreateSession(String cwd) {
            // no-op for tests
        }

        @Override
        protected void tryBranchSessionAtStartup() {
            // no-op for tests
        }

        // Expose protected methods for test access
        void setUpdateConsumer(Consumer<SessionUpdate> consumer) {
            try {
                var field = AcpClient.class.getDeclaredField("updateConsumer");
                field.setAccessible(true);
                ((AtomicReference<Consumer<SessionUpdate>>) field.get(this)).set(consumer);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        Consumer<SessionUpdate> getUpdateConsumerForTest() {
            try {
                var field = AcpClient.class.getDeclaredField("updateConsumer");
                field.setAccessible(true);
                return ((AtomicReference<Consumer<SessionUpdate>>) field.get(this)).get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
