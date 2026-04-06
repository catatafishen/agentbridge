package com.github.catatafishen.agentbridge.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the ACP protocol using MockAcpServer.
 * These tests exercise the full JSON-RPC protocol flow including streaming,
 * tool calls, permissions, and error handling ? all without API costs.
 */
@SuppressWarnings({"UnusedReturnValue", "java:S2925"})
class AcpEndToEndTest {

    private MockAcpServer server;
    private BufferedWriter writer;
    private BufferedReader reader;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockAcpServer();
        server.start();
        writer = new BufferedWriter(new OutputStreamWriter(server.getProcessStdin()));
        reader = new BufferedReader(new InputStreamReader(server.getProcessStdout()));
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
    }

    // ========================
    // Helper methods
    // ========================

    private JsonObject sendRequest(long id, String method, JsonObject params) throws Exception {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", id);
        request.addProperty("method", method);
        request.add("params", params);
        writer.write(gson.toJson(request));
        writer.newLine();
        writer.flush();
        return request;
    }

    private JsonObject readResponse() throws Exception {
        String line = reader.readLine();
        assertNotNull(line, "Expected a response but got null");
        return JsonParser.parseString(line).getAsJsonObject();
    }

    private String doInitialize() throws Exception {
        sendRequest(1, "initialize", new JsonObject());
        JsonObject initResp = readResponse();
        assertTrue(initResp.has("result"), "Initialize should return result");

        JsonObject initialized = new JsonObject();
        initialized.addProperty("jsonrpc", "2.0");
        initialized.addProperty("method", "initialized");
        writer.write(gson.toJson(initialized));
        writer.newLine();
        writer.flush();

        return initResp.getAsJsonObject("result").toString();
    }

    private String doCreateSession() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", "/test/project");
        params.add("mcpServers", new JsonArray());
        sendRequest(2, "session/new", params);
        JsonObject sessionResp = readResponse();
        return sessionResp.getAsJsonObject("result").get("sessionId").getAsString();
    }

    // ========================
    // Initialization Tests
    // ========================

    @Nested
    class InitializationTests {

        @Test
        void testInitializeReturnsProtocolVersion() throws Exception {
            sendRequest(1, "initialize", new JsonObject());
            JsonObject resp = readResponse();
            JsonObject result = resp.getAsJsonObject("result");

            assertEquals(1, result.get("protocolVersion").getAsInt());
        }

        @Test
        void testInitializeReturnsAgentInfo() throws Exception {
            sendRequest(1, "initialize", new JsonObject());
            JsonObject resp = readResponse();
            JsonObject result = resp.getAsJsonObject("result");

            assertTrue(result.has("agentInfo"), "Should have agentInfo");
            JsonObject agentInfo = result.getAsJsonObject("agentInfo");
            assertNotNull(agentInfo.get("name").getAsString());
            assertNotNull(agentInfo.get("version").getAsString());
        }

        @Test
        void testInitializeReturnsAuthMethods() throws Exception {
            sendRequest(1, "initialize", new JsonObject());
            JsonObject resp = readResponse();
            JsonObject result = resp.getAsJsonObject("result");

            assertTrue(result.has("authMethods"), "Should have authMethods");
            JsonArray authMethods = result.getAsJsonArray("authMethods");
            assertFalse(authMethods.isEmpty(), "Should have at least one auth method");

            JsonObject auth = authMethods.get(0).getAsJsonObject();
            assertNotNull(auth.get("id").getAsString());
            assertNotNull(auth.get("name").getAsString());
        }

        @Test
        void testInitializeReturnsAgentCapabilities() throws Exception {
            sendRequest(1, "initialize", new JsonObject());
            JsonObject resp = readResponse();
            JsonObject result = resp.getAsJsonObject("result");

            assertTrue(result.has("agentCapabilities"), "Should have agentCapabilities");
        }

        @Test
        void testInitializedNotificationNoResponse() throws Exception {
            doInitialize();
            // The initialized notification should NOT produce a response.
            // If we can still send and receive after it, the server handled it correctly.
            JsonObject params = new JsonObject();
            params.addProperty("cwd", "/tmp");
            params.add("mcpServers", new JsonArray());
            sendRequest(2, "session/new", params);
            JsonObject resp = readResponse();
            assertEquals(2, resp.get("id").getAsLong(),
                "Response should be for session/new, not initialized");
        }
    }

    // ========================
    // Session Management Tests
    // ========================

    @Nested
    class SessionTests {

        @Test
        void testCreateSessionReturnsId() throws Exception {
            doInitialize();
            String sessionId = doCreateSession();
            assertNotNull(sessionId);
            assertFalse(sessionId.isEmpty());
            assertTrue(sessionId.startsWith("mock-session-"));
        }

        @Test
        void testCreateSessionReturnsModels() throws Exception {
            doInitialize();
            JsonObject params = new JsonObject();
            params.addProperty("cwd", "/test");
            params.add("mcpServers", new JsonArray());
            sendRequest(2, "session/new", params);
            JsonObject resp = readResponse();
            JsonObject result = resp.getAsJsonObject("result");

            assertTrue(result.has("models"), "Session result should include models");
            JsonObject models = result.getAsJsonObject("models");
            assertTrue(models.has("availableModels"));
            assertTrue(models.has("currentModelId"));

            JsonArray available = models.getAsJsonArray("availableModels");
            assertTrue(available.size() >= 2, "Should have at least 2 models");

            // Verify model structure
            JsonObject firstModel = available.get(0).getAsJsonObject();
            assertTrue(firstModel.has("modelId"));
            assertTrue(firstModel.has("name"));
            assertTrue(firstModel.has("_meta"));
        }

        @Test
        void testMultipleSessionsGetUniqueIds() throws Exception {
            doInitialize();
            String session1 = doCreateSession();

            JsonObject params = new JsonObject();
            params.addProperty("cwd", "/other");
            params.add("mcpServers", new JsonArray());
            sendRequest(3, "session/new", params);
            JsonObject resp = readResponse();
            String session2 = resp.getAsJsonObject("result").get("sessionId").getAsString();

            assertNotEquals(session1, session2, "Each session should have a unique ID");
        }

        @Test
        void testSessionCwdIsPassedThrough() throws Exception {
            doInitialize();
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> capturedCwd = new AtomicReference<>();

            server.registerHandler("session/new", params -> {
                capturedCwd.set(params.has("cwd") ? params.get("cwd").getAsString() : null);
                latch.countDown();
                JsonObject result = new JsonObject();
                result.addProperty("sessionId", "test-id");
                result.add("models", new JsonObject());
                return result;
            });

            JsonObject params = new JsonObject();
            params.addProperty("cwd", "/my/project/dir");
            params.add("mcpServers", new JsonArray());
            sendRequest(2, "session/new", params);
            readResponse();

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals("/my/project/dir", capturedCwd.get());
        }

        @Test
        void testSessionWithMcpServers() throws Exception {
            doInitialize();
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<JsonArray> capturedServers = new AtomicReference<>();

            server.registerHandler("session/new", params -> {
                capturedServers.set(params.has("mcpServers") ? params.getAsJsonArray("mcpServers") : null);
                latch.countDown();
                JsonObject result = new JsonObject();
                result.addProperty("sessionId", "test-id");
                result.add("models", new JsonObject());
                return result;
            });

            JsonObject params = new JsonObject();
            params.addProperty("cwd", "/test");
            JsonArray mcpServers = new JsonArray();
            JsonObject mcpServer = new JsonObject();
            mcpServer.addProperty("name", "intellij-tools");
            mcpServer.addProperty("command", "java");
            mcpServers.add(mcpServer);
            params.add("mcpServers", mcpServers);

            sendRequest(2, "session/new", params);
            readResponse();

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertNotNull(capturedServers.get());
            assertEquals(1, capturedServers.get().size());
            assertEquals("intellij-tools", capturedServers.get().get(0).getAsJsonObject().get("name").getAsString());
        }
    }

    // ========================
    // Streaming Prompt Tests
    // ========================

    @Nested
    class StreamingTests {

        @Test
        void testPromptWithStreamingChunks() throws Exception {
            doInitialize();
            String sessionId = doCreateSession();

            // Register handler that streams chunks before returning result
            server.registerHandler("session/prompt", params -> {
                String sid = params.get("sessionId").getAsString();
                try {
                    // Stream text in chunks
                    server.sendNotification("session/update",
                        MockAcpServer.buildMessageChunk(sid, "Hello "));
                    server.sendNotification("session/update",
                        MockAcpServer.buildMessageChunk(sid, "world"));
                    server.sendNotification("session/update",
                        MockAcpServer.buildMessageChunk(sid, "!"));
                    Thread.sleep(50);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                JsonObject result = new JsonObject();
                result.addProperty("stopReason", "end_turn");
                return result;
            });

            // Send prompt
            JsonObject promptParams = new JsonObject();
            promptParams.addProperty("sessionId", sessionId);
            JsonArray prompt = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", "Say hello");
            prompt.add(text);
            promptParams.add("prompt", prompt);
            sendRequest(3, "session/prompt", promptParams);

            // Read notifications and final response
            List<String> chunks = new ArrayList<>();
            String stopReason = null;

            // Read all messages until we get the prompt result
            for (int i = 0; i < 10; i++) {
                String line = reader.readLine();
                if (line == null) break;
                JsonObject msg = JsonParser.parseString(line).getAsJsonObject();

                if (msg.has("result")) {
                    stopReason = msg.getAsJsonObject("result").get("stopReason").getAsString();
                    break;
                } else if (msg.has("method") && "session/update".equals(msg.get("method").getAsString())) {
                    JsonObject update = msg.getAsJsonObject("params").getAsJsonObject("update");
                    if ("agent_message_chunk".equals(update.get("sessionUpdate").getAsString())) {
                        chunks.add(update.getAsJsonObject("content").get("text").getAsString());
                    }
                }
            }

            assertEquals(3, chunks.size(), "Should receive 3 chunks");
            assertEquals("Hello ", chunks.get(0));
            assertEquals("world", chunks.get(1));
            assertEquals("!", chunks.get(2));
            assertEquals("end_turn", stopReason);
        }

        @Test
        void testPromptWithToolCallNotifications() throws Exception {
            doInitialize();
            String sessionId = doCreateSession();

            server.registerHandler("session/prompt", params -> {
                String sid = params.get("sessionId").getAsString();
                try {
                    // Tool call lifecycle: pending → in_progress → completed
                    server.sendNotification("session/update",
                        MockAcpServer.buildToolCall(sid, "tc_001", "Reading file", "read", "pending"));
                    server.sendNotification("session/update",
                        MockAcpServer.buildToolCall(sid, "tc_001", "Reading file", "read", "in_progress"));
                    server.sendNotification("session/update",
                        MockAcpServer.buildToolCall(sid, "tc_001", "Reading file", "read", "completed"));

                    // Then stream response
                    server.sendNotification("session/update",
                        MockAcpServer.buildMessageChunk(sid, "File contents: OK"));
                    Thread.sleep(50);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                JsonObject result = new JsonObject();
                result.addProperty("stopReason", "end_turn");
                return result;
            });

            JsonObject promptParams = new JsonObject();
            promptParams.addProperty("sessionId", sessionId);
            JsonArray prompt = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", "Read a file");
            prompt.add(text);
            promptParams.add("prompt", prompt);
            sendRequest(3, "session/prompt", promptParams);

            List<String> toolStatuses = new ArrayList<>();
            List<String> chunks = new ArrayList<>();

            for (int i = 0; i < 10; i++) {
                String line = reader.readLine();
                if (line == null) break;
                JsonObject msg = JsonParser.parseString(line).getAsJsonObject();

                if (msg.has("result")) break;
                if (msg.has("method") && "session/update".equals(msg.get("method").getAsString())) {
                    JsonObject update = msg.getAsJsonObject("params").getAsJsonObject("update");
                    String updateType = update.get("sessionUpdate").getAsString();
                    if ("tool_call".equals(updateType)) {
                        toolStatuses.add(update.get("status").getAsString());
                    } else if ("agent_message_chunk".equals(updateType)) {
                        chunks.add(update.getAsJsonObject("content").get("text").getAsString());
                    }
                }
            }

            assertEquals(List.of("pending", "in_progress", "completed"), toolStatuses);
            assertEquals(1, chunks.size());
            assertEquals("File contents: OK", chunks.getFirst());
        }

        @Test
        void testPromptWithModelSelection() throws Exception {
            doInitialize();
            String sessionId = doCreateSession();

            AtomicReference<String> capturedModel = new AtomicReference<>();
            server.registerHandler("session/prompt", params -> {
                capturedModel.set(params.has("model") ? params.get("model").getAsString() : null);
                JsonObject result = new JsonObject();
                result.addProperty("stopReason", "end_turn");
                return result;
            });

            JsonObject promptParams = new JsonObject();
            promptParams.addProperty("sessionId", sessionId);
            promptParams.addProperty("model", "gpt-4.1");
            JsonArray prompt = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", "test");
            prompt.add(text);
            promptParams.add("prompt", prompt);
            sendRequest(3, "session/prompt", promptParams);
            readResponse();

            assertEquals("gpt-4.1", capturedModel.get(), "Model should be passed through");
        }

        @Test
        void testPromptWithResourceReferences() throws Exception {
            doInitialize();
            String sessionId = doCreateSession();

            AtomicReference<JsonArray> capturedPrompt = new AtomicReference<>();
            server.registerHandler("session/prompt", params -> {
                capturedPrompt.set(params.has("prompt") ? params.getAsJsonArray("prompt") : null);
                JsonObject result = new JsonObject();
                result.addProperty("stopReason", "end_turn");
                return result;
            });

            JsonObject promptParams = buildResourcePromptParams(sessionId);
            sendRequest(3, "session/prompt", promptParams);
            readResponse();

            assertNotNull(capturedPrompt.get());
            assertEquals(2, capturedPrompt.get().size(), "Should have resource + text");
            assertEquals("resource", capturedPrompt.get().get(0).getAsJsonObject().get("type").getAsString());
            assertEquals("text", capturedPrompt.get().get(1).getAsJsonObject().get("type").getAsString());
        }

        private static JsonObject buildResourcePromptParams(String sessionId) {
            JsonObject promptParams = new JsonObject();
            promptParams.addProperty("sessionId", sessionId);
            JsonArray prompt = new JsonArray();

            JsonObject resource = new JsonObject();
            resource.addProperty("type", "resource");
            JsonObject resourceData = new JsonObject();
            resourceData.addProperty("uri", "file:///test/Main.java");
            resourceData.addProperty("mimeType", "text/x-java");
            resourceData.addProperty("text", "public class Main {}");
            resource.add("resource", resourceData);
            prompt.add(resource);

            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", "Analyze this file");
            prompt.add(text);

            promptParams.add("prompt", prompt);
            return promptParams;
        }
    }

    // ========================
    // Permission Handling Tests
    // ========================

    @Nested
    class PermissionTests {

        @Test
        void testPermissionRequestContainsAllFields() throws Exception {
            doInitialize();
            String sessionId = doCreateSession();

            server.registerHandler("session/prompt", params -> {
                String sid = params.get("sessionId").getAsString();
                try {
                    server.sendAgentRequest(100, "session/request_permission",
                        MockAcpServer.buildRequestPermission(sid, "call_001", "edit", "Edit Main.java"));
                    Thread.sleep(300);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                JsonObject result = new JsonObject();
                result.addProperty("stopReason", "end_turn");
                return result;
            });

            JsonObject promptParams = new JsonObject();
            promptParams.addProperty("sessionId", sessionId);
            JsonArray prompt = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", "edit file");
            prompt.add(text);
            promptParams.add("prompt", prompt);
            sendRequest(3, "session/prompt", promptParams);

            // Read permission request from agent
            Thread.sleep(500);
            // The permission request has both id and method
            // Read all available messages
            List<JsonObject> messages = new ArrayList<>();
            while (reader.ready()) {
                String line = reader.readLine();
                if (line != null) messages.add(JsonParser.parseString(line).getAsJsonObject());
            }

            // Find the permission request
            JsonObject permReq = messages.stream()
                .filter(m -> m.has("method") && "session/request_permission".equals(m.get("method").getAsString()))
                .findFirst().orElse(null);

            assertNotNull(permReq, "Should receive permission request");
            assertTrue(permReq.has("id"), "Permission request should have id");
            assertEquals(100, permReq.get("id").getAsLong());

            JsonObject permParams = permReq.getAsJsonObject("params");
            assertTrue(permParams.has("toolCall"));
            assertTrue(permParams.has("options"));

            JsonObject toolCall = permParams.getAsJsonObject("toolCall");
            assertEquals("edit", toolCall.get("kind").getAsString());
            assertEquals("Edit Main.java", toolCall.get("title").getAsString());
        }

        @Test
        void testMultiplePermissionKinds() {
            // Verify all permission kinds produce valid structures
            String[] kinds = {"edit", "create", "delete", "other", "read"};
            for (String kind : kinds) {
                JsonObject params = MockAcpServer.buildRequestPermission("s1", "call_001", kind, "Test " + kind);
                JsonObject toolCall = params.getAsJsonObject("toolCall");
                assertEquals(kind, toolCall.get("kind").getAsString());
                assertEquals("Test " + kind, toolCall.get("title").getAsString());
                assertEquals(2, params.getAsJsonArray("options").size(), "Should have 2 options for kind: " + kind);
            }
        }

        @Test
        void testPermissionOptionsHaveRequiredFields() {
            JsonObject params = MockAcpServer.buildRequestPermission("s1", "call_001");
            JsonArray options = params.getAsJsonArray("options");

            for (int i = 0; i < options.size(); i++) {
                JsonObject option = options.get(i).getAsJsonObject();
                assertTrue(option.has("optionId"), "Option " + i + " should have optionId");
                assertTrue(option.has("name"), "Option " + i + " should have name");
                assertTrue(option.has("kind"), "Option " + i + " should have kind");
            }
        }
    }

    // ========================
    // Error Handling Tests
    // ========================

    @Nested
    class ErrorHandlingTests {

        @Test
        void testRapidFireRequests() throws Exception {
            doInitialize();

            // Send multiple requests rapidly
            for (int i = 10; i < 20; i++) {
                JsonObject params = new JsonObject();
                params.addProperty("cwd", "/test/" + i);
                params.add("mcpServers", new JsonArray());
                sendRequest(i, "session/new", params);
            }

            // Read all responses
            List<Long> receivedIds = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                JsonObject resp = readResponse();
                receivedIds.add(resp.get("id").getAsLong());
            }

            // All should get responses (order may vary but all ids should be present)
            for (long id = 10; id < 20; id++) {
                assertTrue(receivedIds.contains(id), "Should receive response for id " + id);
            }
        }

        @Test
        void testConcurrentReadWrite() throws Exception {
            doInitialize();

            // Send request in background thread while reading
            CompletableFuture<Void> sendDone = CompletableFuture.runAsync(() -> {
                try {
                    for (int i = 10; i < 15; i++) {
                        JsonObject params = new JsonObject();
                        params.addProperty("cwd", "/test");
                        params.add("mcpServers", new JsonArray());
                        sendRequest(i, "session/new", params);
                        Thread.sleep(10);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // Read all responses
            int responseCount = 0;
            for (int i = 0; i < 5; i++) {
                JsonObject resp = readResponse();
                assertNotNull(resp);
                responseCount++;
            }

            sendDone.get(5, TimeUnit.SECONDS);
            assertEquals(5, responseCount);
        }

        @Test
        void testHandlerException() throws Exception {
            doInitialize();

            server.registerHandler("session/prompt", params -> {
                throw new RuntimeException("Handler error");
            });

            String sessionId = doCreateSession();
            JsonObject promptParams = new JsonObject();
            promptParams.addProperty("sessionId", sessionId);
            JsonArray prompt = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", "test");
            prompt.add(text);
            promptParams.add("prompt", prompt);
            sendRequest(3, "session/prompt", promptParams);

            // Server should survive the handler exception
            Thread.sleep(200);

            // Send another request to verify server is still alive
            JsonObject params = new JsonObject();
            params.addProperty("cwd", "/test");
            params.add("mcpServers", new JsonArray());
            sendRequest(4, "session/new", params);
            JsonObject resp = readResponse();
            assertEquals(4, resp.get("id").getAsLong(), "Server should still respond after handler error");
        }

        @Test
        void testNotificationDoesNotGetResponse() throws Exception {
            doInitialize();

            // Send notification (no id)
            JsonObject notification = new JsonObject();
            notification.addProperty("jsonrpc", "2.0");
            notification.addProperty("method", "initialized");
            writer.write(gson.toJson(notification));
            writer.newLine();
            writer.flush();

            // Then send a real request
            sendRequest(5, "initialize", new JsonObject());
            JsonObject resp = readResponse();

            // Response should be for request 5, not the notification
            assertEquals(5, resp.get("id").getAsLong());
        }

        @Test
        void testLargePayload() throws Exception {
            doInitialize();

            // Build a large CWD string
            StringBuilder largeCwd = new StringBuilder("/");
            for (int i = 0; i < 1000; i++) {
                largeCwd.append("deep/nested/path/segment/").append(i).append("/");
            }

            JsonObject params = new JsonObject();
            params.addProperty("cwd", largeCwd.toString());
            params.add("mcpServers", new JsonArray());
            sendRequest(2, "session/new", params);
            JsonObject resp = readResponse();

            assertNotNull(resp.getAsJsonObject("result").get("sessionId").getAsString());
        }
    }

    // ========================
    // Protocol Flow Tests
    // ========================

    @Nested
    class ProtocolFlowTests {

        @Test
        void testFullConversationFlow() throws Exception {
            doInitialize();
            String sessionId = doCreateSession();

            // Register handler for multi-turn conversation
            final int[] turnCount = {0};
            server.registerHandler("session/prompt", params -> {
                String sid = params.get("sessionId").getAsString();
                turnCount[0]++;
                try {
                    server.sendNotification("session/update",
                        MockAcpServer.buildMessageChunk(sid, "Response " + turnCount[0]));
                    Thread.sleep(20);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                JsonObject result = new JsonObject();
                result.addProperty("stopReason", "end_turn");
                return result;
            });

            // Send 3 prompts in the same session
            for (int i = 0; i < 3; i++) {
                JsonObject promptParams = new JsonObject();
                promptParams.addProperty("sessionId", sessionId);
                JsonArray prompt = new JsonArray();
                JsonObject text = new JsonObject();
                text.addProperty("type", "text");
                text.addProperty("text", "Turn " + (i + 1));
                prompt.add(text);
                promptParams.add("prompt", prompt);
                sendRequest(10 + i, "session/prompt", promptParams);

                // Read chunk notification + result
                for (int j = 0; j < 5; j++) {
                    String line = reader.readLine();
                    if (line == null) break;
                    JsonObject msg = JsonParser.parseString(line).getAsJsonObject();
                    if (msg.has("result")) break;
                }
            }

            assertEquals(3, turnCount[0], "Should have processed 3 turns");

            // Verify all prompts were received
            List<JsonObject> promptRequests = server.getReceivedRequests("session/prompt");
            assertEquals(3, promptRequests.size());
        }

        @Test
        void testRequestIdTracking() throws Exception {
            doInitialize();

            // Send requests with specific IDs and verify responses match
            long[] ids = {42, 100, 7, 999};
            for (long id : ids) {
                JsonObject params = new JsonObject();
                params.addProperty("cwd", "/test");
                params.add("mcpServers", new JsonArray());
                sendRequest(id, "session/new", params);
                JsonObject resp = readResponse();
                assertEquals(id, resp.get("id").getAsLong(),
                    "Response id should match request id");
            }
        }

        @Test
        void testServerRecordsAllRequests() throws Exception {
            doInitialize();
            doCreateSession();

            List<JsonObject> received = server.getReceivedRequests();
            assertTrue(received.size() >= 3,
                "Should have recorded initialize, initialized, and session/new");

            // Check specific methods
            assertFalse(server.getReceivedRequests("initialize").isEmpty());
            assertFalse(server.getReceivedRequests("session/new").isEmpty());
        }

        @Test
        void testAgentNotificationToClient() throws Exception {
            doInitialize();
            String sessionId = doCreateSession();

            // Server sends unsolicited notification
            JsonObject updateParams = MockAcpServer.buildMessageChunk(sessionId, "Unsolicited update");
            server.sendNotification("session/update", updateParams);

            // Client should receive it
            String line = reader.readLine();
            assertNotNull(line);
            JsonObject msg = JsonParser.parseString(line).getAsJsonObject();
            assertEquals("session/update", msg.get("method").getAsString());
            assertFalse(msg.has("id"), "Notification should not have id");
        }

        @Test
        void testAgentRequestResponseRoundtrip() throws Exception {
            doInitialize();

            // Send an agent-to-client request (has both id and method)
            server.sendAgentRequest(500, "session/request_permission",
                MockAcpServer.buildRequestPermission("s1", "call_001"));

            // Read the request from the agent
            String line = reader.readLine();
            assertNotNull(line);
            JsonObject msg = JsonParser.parseString(line).getAsJsonObject();

            assertTrue(msg.has("id"), "Agent request should have id");
            assertTrue(msg.has("method"), "Agent request should have method");
            assertEquals(500, msg.get("id").getAsLong());
            assertEquals("session/request_permission", msg.get("method").getAsString());
        }
    }

    // ========================
    // Model Configuration Tests
    // ========================

    @Nested
    class ModelTests {

        @Test
        void testModelsIncludeUsageMetadata() throws Exception {
            doInitialize();
            JsonObject params = new JsonObject();
            params.addProperty("cwd", "/test");
            params.add("mcpServers", new JsonArray());
            sendRequest(2, "session/new", params);
            JsonObject resp = readResponse();

            JsonArray models = resp.getAsJsonObject("result")
                .getAsJsonObject("models")
                .getAsJsonArray("availableModels");

            for (int i = 0; i < models.size(); i++) {
                JsonObject model = models.get(i).getAsJsonObject();
                assertTrue(model.has("_meta"), "Model should have _meta: " + model.get("modelId"));
                JsonObject meta = model.getAsJsonObject("_meta");
                assertTrue(meta.has("copilotUsage"), "Model should have copilotUsage");
            }
        }

        @Test
        void testFreeModelAvailable() throws Exception {
            // Add a free model to the mock
            server.registerHandler("session/new", params -> {
                JsonObject result = new JsonObject();
                result.addProperty("sessionId", "test-session");
                JsonObject models = new JsonObject();
                JsonArray available = new JsonArray();

                // Free model
                JsonObject freeModel = new JsonObject();
                freeModel.addProperty("modelId", "gpt-4.1-mini");
                freeModel.addProperty("name", "GPT-4.1 Mini");
                JsonObject freeMeta = new JsonObject();
                freeMeta.addProperty("copilotUsage", "0x");
                freeModel.add("_meta", freeMeta);
                available.add(freeModel);

                // Paid model
                JsonObject paidModel = new JsonObject();
                paidModel.addProperty("modelId", "claude-opus-4");
                paidModel.addProperty("name", "Claude Opus 4");
                JsonObject paidMeta = new JsonObject();
                paidMeta.addProperty("copilotUsage", "3x");
                paidModel.add("_meta", paidMeta);
                available.add(paidModel);

                models.add("availableModels", available);
                models.addProperty("currentModelId", "gpt-4.1-mini");
                result.add("models", models);
                return result;
            });

            doInitialize();
            JsonObject params = new JsonObject();
            params.addProperty("cwd", "/test");
            params.add("mcpServers", new JsonArray());
            sendRequest(2, "session/new", params);
            JsonObject resp = readResponse();

            JsonArray models = resp.getAsJsonObject("result")
                .getAsJsonObject("models")
                .getAsJsonArray("availableModels");

            // Find the free model
            boolean hasFreeModel = false;
            for (int i = 0; i < models.size(); i++) {
                JsonObject model = models.get(i).getAsJsonObject();
                String usage = model.getAsJsonObject("_meta").get("copilotUsage").getAsString();
                if ("0x".equals(usage)) {
                    hasFreeModel = true;
                    break;
                }
            }
            assertTrue(hasFreeModel, "Should have a free (0x) model available");
        }

        @Test
        void testCurrentModelIdSet() throws Exception {
            doInitialize();
            JsonObject params = new JsonObject();
            params.addProperty("cwd", "/test");
            params.add("mcpServers", new JsonArray());
            sendRequest(2, "session/new", params);
            JsonObject resp = readResponse();

            String currentModel = resp.getAsJsonObject("result")
                .getAsJsonObject("models")
                .get("currentModelId").getAsString();

            assertNotNull(currentModel);
            assertEquals("gpt-4.1", currentModel);
        }
    }

    // ========================
    // Edge Case Tests
    // ========================

    @Nested
    class EdgeCaseTests {

        @Test
        void testEmptyPromptArray() throws Exception {
            doInitialize();
            String sessionId = doCreateSession();

            AtomicReference<JsonArray> capturedPrompt = new AtomicReference<>();
            server.registerHandler("session/prompt", params -> {
                capturedPrompt.set(params.has("prompt") ? params.getAsJsonArray("prompt") : null);
                JsonObject result = new JsonObject();
                result.addProperty("stopReason", "end_turn");
                return result;
            });

            JsonObject promptParams = new JsonObject();
            promptParams.addProperty("sessionId", sessionId);
            promptParams.add("prompt", new JsonArray());
            sendRequest(3, "session/prompt", promptParams);
            readResponse();

            assertNotNull(capturedPrompt.get());
            assertEquals(0, capturedPrompt.get().size());
        }

        @Test
        void testSpecialCharactersInPrompt() throws Exception {
            doInitialize();
            String sessionId = doCreateSession();

            AtomicReference<String> capturedText = new AtomicReference<>();
            server.registerHandler("session/prompt", params -> {
                JsonArray prompt = params.getAsJsonArray("prompt");
                if (!prompt.isEmpty()) {
                    capturedText.set(prompt.get(0).getAsJsonObject().get("text").getAsString());
                }
                JsonObject result = new JsonObject();
                result.addProperty("stopReason", "end_turn");
                return result;
            });

            String specialText = "Hello \"world\" with 'quotes' and \\backslashes\\ and\nnewlines\tand\ttabs";
            JsonObject promptParams = new JsonObject();
            promptParams.addProperty("sessionId", sessionId);
            JsonArray prompt = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", specialText);
            prompt.add(text);
            promptParams.add("prompt", prompt);
            sendRequest(3, "session/prompt", promptParams);
            readResponse();

            assertEquals(specialText, capturedText.get(), "Special characters should survive round-trip");
        }

        @Test
        void testUnicodeInMessages() throws Exception {
            doInitialize();
            String sessionId = doCreateSession();

            server.registerHandler("session/prompt", params -> {
                String sid = params.get("sessionId").getAsString();
                try {
                    server.sendNotification("session/update",
                        MockAcpServer.buildMessageChunk(sid, "Unicode: 日本語 émojis 🎉 Ñ ü ö"));
                    Thread.sleep(20);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                JsonObject result = new JsonObject();
                result.addProperty("stopReason", "end_turn");
                return result;
            });

            JsonObject promptParams = new JsonObject();
            promptParams.addProperty("sessionId", sessionId);
            JsonArray prompt = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", "test unicode");
            prompt.add(text);
            promptParams.add("prompt", prompt);
            sendRequest(3, "session/prompt", promptParams);

            // Read chunk
            String line = reader.readLine();
            assertNotNull(line);
            JsonObject msg = JsonParser.parseString(line).getAsJsonObject();
            if (msg.has("method")) {
                JsonObject content = msg.getAsJsonObject("params")
                    .getAsJsonObject("update")
                    .getAsJsonObject("content");
                assertTrue(content.get("text").getAsString().contains("日本語"));
                assertTrue(content.get("text").getAsString().contains("🎉"));
            }
        }

        @Test
        void testStopReasonVariants() throws Exception {
            doInitialize();
            String sessionId = doCreateSession();

            String[] stopReasons = {"end_turn", "max_tokens", "tool_use", "content_filter"};
            for (int i = 0; i < stopReasons.length; i++) {
                String reason = stopReasons[i];
                server.registerHandler("session/prompt", params -> {
                    JsonObject result = new JsonObject();
                    result.addProperty("stopReason", reason);
                    return result;
                });

                JsonObject promptParams = new JsonObject();
                promptParams.addProperty("sessionId", sessionId);
                JsonArray prompt = new JsonArray();
                JsonObject text = new JsonObject();
                text.addProperty("type", "text");
                text.addProperty("text", "test " + reason);
                prompt.add(text);
                promptParams.add("prompt", prompt);
                sendRequest(10 + i, "session/prompt", promptParams);

                JsonObject resp = readResponse();
                assertEquals(reason, resp.getAsJsonObject("result").get("stopReason").getAsString(),
                    "Stop reason should be: " + reason);
            }
        }
    }
}
