package com.github.catatafishen.agentbridge.agent.codex;

import com.github.catatafishen.agentbridge.settings.McpServerSettings;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JsonRpcTransportTest {

    @Nested
    class ClassifyMessageType {

        @Test
        void response_hasIdAndResult_noMethod() {
            JsonObject msg = new JsonObject();
            msg.addProperty("id", 1);
            msg.add("result", new JsonObject());
            assertEquals(JsonRpcTransport.MessageType.RESPONSE, JsonRpcTransport.classifyMessageType(msg));
        }

        @Test
        void response_hasIdAndError_noMethod() {
            JsonObject msg = new JsonObject();
            msg.addProperty("id", 2);
            msg.add("error", new JsonObject());
            assertEquals(JsonRpcTransport.MessageType.RESPONSE, JsonRpcTransport.classifyMessageType(msg));
        }

        @Test
        void serverRequest_hasIdAndMethod() {
            JsonObject msg = new JsonObject();
            msg.addProperty("id", 3);
            msg.addProperty("method", "item/tool/call");
            assertEquals(JsonRpcTransport.MessageType.SERVER_REQUEST, JsonRpcTransport.classifyMessageType(msg));
        }

        @Test
        void notification_hasMethod_noId() {
            JsonObject msg = new JsonObject();
            msg.addProperty("method", "turn/updated");
            assertEquals(JsonRpcTransport.MessageType.NOTIFICATION, JsonRpcTransport.classifyMessageType(msg));
        }

        @Test
        void notification_hasMethod_nullId() {
            JsonObject msg = new JsonObject();
            msg.add("id", JsonNull.INSTANCE);
            msg.addProperty("method", "turn/updated");
            assertEquals(JsonRpcTransport.MessageType.NOTIFICATION, JsonRpcTransport.classifyMessageType(msg));
        }

        @Test
        void unknown_emptyObject() {
            assertEquals(JsonRpcTransport.MessageType.UNKNOWN, JsonRpcTransport.classifyMessageType(new JsonObject()));
        }
    }

    @Nested
    class ExtractJsonRpcErrorMessage {

        @Test
        void hasMessageField_returnsIt() {
            JsonObject err = new JsonObject();
            err.addProperty("message", "rate limit exceeded");
            assertEquals("rate limit exceeded", JsonRpcTransport.extractJsonRpcErrorMessage(err));
        }

        @Test
        void noMessageField_returnsToString() {
            JsonObject err = new JsonObject();
            err.addProperty("code", -32600);
            assertEquals(err.toString(), JsonRpcTransport.extractJsonRpcErrorMessage(err));
        }
    }

    @Nested
    class IsCodexAuthError {

        @Test
        void nullInput_returnsFalse() {
            assertFalse(JsonRpcTransport.isCodexAuthError(null));
        }

        @Test
        void notAuthenticated_returnsTrue() {
            assertTrue(JsonRpcTransport.isCodexAuthError("Not authenticated with Codex"));
        }

        @Test
        void unauthorized_returnsTrue() {
            assertTrue(JsonRpcTransport.isCodexAuthError("Unauthorized request"));
        }

        @Test
        void unrelatedError_returnsFalse() {
            assertFalse(JsonRpcTransport.isCodexAuthError("Tool 'apply_patch' failed"));
        }

        @Test
        void code401_returnsTrue() {
            assertTrue(JsonRpcTransport.isCodexAuthError("HTTP 401 error from API"));
        }

        @Test
        void invalidApiKey_returnsTrue() {
            assertTrue(JsonRpcTransport.isCodexAuthError("Invalid API key provided"));
        }

        @Test
        void pleaseLogIn_returnsTrue() {
            assertTrue(JsonRpcTransport.isCodexAuthError("Please log in to continue"));
        }
    }

    /**
     * Tests for sendRequest, sendNotification, sendResponse using a mock process.
     */
    @Nested
    class SendMessages {

        private Project mockProject;
        private MockedStatic<McpServerSettings> mockedSettings;
        private JsonRpcTransport transport;
        private ByteArrayOutputStream captured;

        @BeforeEach
        void setUp() {
            mockProject = mock(Project.class);
            mockedSettings = mockStatic(McpServerSettings.class);
            McpServerSettings settings = mock(McpServerSettings.class);
            when(settings.isDebugLoggingEnabled()).thenReturn(false);
            mockedSettings.when(() -> McpServerSettings.getInstance(any())).thenReturn(settings);

            transport = new JsonRpcTransport(mockProject);
            captured = new ByteArrayOutputStream();

            // Use reflection to set stdin and connected directly
            try {
                var stdinField = JsonRpcTransport.class.getDeclaredField("stdin");
                stdinField.setAccessible(true);
                @SuppressWarnings("unchecked")
                var ref = (java.util.concurrent.atomic.AtomicReference<OutputStream>) stdinField.get(transport);
                ref.set(captured);

                var connectedField = JsonRpcTransport.class.getDeclaredField("connected");
                connectedField.setAccessible(true);
                connectedField.set(transport, true);
            } catch (Exception e) {
                fail("Reflection setup failed: " + e.getMessage());
            }
        }

        @AfterEach
        void tearDown() {
            mockedSettings.close();
        }

        @Test
        void sendRequest_writesJsonWithIdMethodParams() {
            JsonObject params = new JsonObject();
            params.addProperty("model", "gpt-4o");

            CompletableFuture<JsonObject> future = transport.sendRequest("setModel", params);

            assertNotNull(future);
            assertFalse(future.isDone());

            String written = captured.toString(StandardCharsets.UTF_8);
            JsonObject msg = JsonParser.parseString(written.trim()).getAsJsonObject();
            assertTrue(msg.has("id"));
            assertEquals("setModel", msg.get("method").getAsString());
            assertEquals("gpt-4o", msg.getAsJsonObject("params").get("model").getAsString());
        }

        @Test
        void sendRequest_returnsPendingFuture() {
            JsonObject params = new JsonObject();
            CompletableFuture<JsonObject> future = transport.sendRequest("test", params);

            assertNotNull(future);
            assertFalse(future.isDone());
        }

        @Test
        void sendRequest_incrementsId() {
            transport.sendRequest("m1", new JsonObject());
            captured.reset();
            transport.sendRequest("m2", new JsonObject());

            String written = captured.toString(StandardCharsets.UTF_8);
            JsonObject msg = JsonParser.parseString(written.trim()).getAsJsonObject();
            // Second request should have id > 1
            assertTrue(msg.get("id").getAsInt() > 1);
        }

        @Test
        void sendNotification_writesJsonWithoutId() {
            JsonObject params = new JsonObject();
            params.addProperty("status", "ready");

            transport.sendNotification("initialized", params);

            String written = captured.toString(StandardCharsets.UTF_8);
            JsonObject msg = JsonParser.parseString(written.trim()).getAsJsonObject();
            assertFalse(msg.has("id"));
            assertEquals("initialized", msg.get("method").getAsString());
            assertEquals("ready", msg.getAsJsonObject("params").get("status").getAsString());
        }

        @Test
        void sendResponse_writesJsonWithIdAndResult() {
            JsonObject result = new JsonObject();
            result.addProperty("success", true);

            transport.sendResponse(new JsonPrimitive(42), result);

            String written = captured.toString(StandardCharsets.UTF_8);
            JsonObject msg = JsonParser.parseString(written.trim()).getAsJsonObject();
            assertEquals(42, msg.get("id").getAsInt());
            assertTrue(msg.getAsJsonObject("result").get("success").getAsBoolean());
            assertFalse(msg.has("method"));
        }
    }

    @Nested
    class Shutdown {

        private Project mockProject;
        private MockedStatic<McpServerSettings> mockedSettings;
        private JsonRpcTransport transport;

        @BeforeEach
        void setUp() {
            mockProject = mock(Project.class);
            mockedSettings = mockStatic(McpServerSettings.class);
            McpServerSettings settings = mock(McpServerSettings.class);
            when(settings.isDebugLoggingEnabled()).thenReturn(false);
            mockedSettings.when(() -> McpServerSettings.getInstance(any())).thenReturn(settings);

            transport = new JsonRpcTransport(mockProject);

            // Set up writable state via reflection
            try {
                var stdinField = JsonRpcTransport.class.getDeclaredField("stdin");
                stdinField.setAccessible(true);
                @SuppressWarnings("unchecked")
                var ref = (java.util.concurrent.atomic.AtomicReference<OutputStream>) stdinField.get(transport);
                ref.set(new ByteArrayOutputStream());

                var connectedField = JsonRpcTransport.class.getDeclaredField("connected");
                connectedField.setAccessible(true);
                connectedField.set(transport, true);
            } catch (Exception e) {
                fail("Reflection setup failed: " + e.getMessage());
            }
        }

        @AfterEach
        void tearDown() {
            mockedSettings.close();
        }

        @Test
        void shutdown_completsPendingFuturesExceptionally() {
            CompletableFuture<JsonObject> f1 = transport.sendRequest("method1", new JsonObject());
            CompletableFuture<JsonObject> f2 = transport.sendRequest("method2", new JsonObject());

            transport.shutdown();

            assertTrue(f1.isCompletedExceptionally());
            assertTrue(f2.isCompletedExceptionally());

            ExecutionException ex = assertThrows(ExecutionException.class, f1::get);
            assertTrue(ex.getCause().getMessage().contains("transport disconnected"));
        }

        @Test
        void shutdown_setsConnectedFalse() {
            assertTrue(transport.isConnected());

            transport.shutdown();

            assertFalse(transport.isConnected());
        }

        @Test
        void shutdown_closesOutputStream() {
            // Use a tracking stream to verify close was called
            var closed = new boolean[]{false};
            OutputStream trackingStream = new ByteArrayOutputStream() {
                @Override
                public void close() throws IOException {
                    closed[0] = true;
                    super.close();
                }
            };

            try {
                var stdinField = JsonRpcTransport.class.getDeclaredField("stdin");
                stdinField.setAccessible(true);
                @SuppressWarnings("unchecked")
                var ref = (java.util.concurrent.atomic.AtomicReference<OutputStream>) stdinField.get(transport);
                ref.set(trackingStream);
            } catch (Exception e) {
                fail("Reflection failed: " + e.getMessage());
            }

            transport.shutdown();

            assertTrue(closed[0]);
        }
    }

    /**
     * Tests for message dispatch using reflection to call processLine directly
     * (avoids thread-local MockedStatic issues with the reader thread).
     */
    @Nested
    class DispatchToHandler {

        private Project mockProject;
        private MockedStatic<McpServerSettings> mockedSettings;
        private JsonRpcTransport transport;
        private JsonRpcTransport.MessageHandler handler;

        @BeforeEach
        void setUp() {
            mockProject = mock(Project.class);
            mockedSettings = mockStatic(McpServerSettings.class);
            McpServerSettings settings = mock(McpServerSettings.class);
            when(settings.isDebugLoggingEnabled()).thenReturn(false);
            mockedSettings.when(() -> McpServerSettings.getInstance(any())).thenReturn(settings);

            transport = new JsonRpcTransport(mockProject);
            handler = mock(JsonRpcTransport.MessageHandler.class);
            transport.setMessageHandler(handler);
        }

        @AfterEach
        void tearDown() {
            mockedSettings.close();
        }

        /** Invoke the private processLine method via reflection on the test thread. */
        private void invokeProcessLine(String line) throws Exception {
            var method = JsonRpcTransport.class.getDeclaredMethod("processLine", String.class);
            method.setAccessible(true);
            method.invoke(transport, line);
        }

        @Test
        void serverRequest_dispatchesToHandler() throws Exception {
            String line = "{\"id\":1,\"method\":\"item/tool/call\",\"params\":{\"tool\":\"read_file\"}}";

            invokeProcessLine(line);

            verify(handler).onServerRequest(argThat(msg ->
                    msg.has("method") && msg.get("method").getAsString().equals("item/tool/call")
            ));
        }

        @Test
        void notification_dispatchesToHandler() throws Exception {
            String line = "{\"method\":\"turn/updated\",\"params\":{\"status\":\"running\"}}";

            invokeProcessLine(line);

            verify(handler).onNotification(argThat(msg ->
                    msg.has("method") && msg.get("method").getAsString().equals("turn/updated")
            ));
        }

        @Test
        void response_resolvesPendingFuture() throws Exception {
            // Set up writable state
            ByteArrayOutputStream capturedStdin = new ByteArrayOutputStream();
            try {
                var stdinField = JsonRpcTransport.class.getDeclaredField("stdin");
                stdinField.setAccessible(true);
                @SuppressWarnings("unchecked")
                var ref = (java.util.concurrent.atomic.AtomicReference<OutputStream>) stdinField.get(transport);
                ref.set(capturedStdin);

                var connectedField = JsonRpcTransport.class.getDeclaredField("connected");
                connectedField.setAccessible(true);
                connectedField.set(transport, true);
            } catch (Exception e) {
                fail("Reflection failed: " + e.getMessage());
            }

            // Send a request to get the assigned ID
            CompletableFuture<JsonObject> future = transport.sendRequest("test/method", new JsonObject());
            String written = capturedStdin.toString(StandardCharsets.UTF_8);
            JsonObject sentMsg = JsonParser.parseString(written.trim()).getAsJsonObject();
            int assignedId = sentMsg.get("id").getAsInt();

            // Simulate receiving a response via processLine on this thread
            String responseLine = "{\"id\":" + assignedId + ",\"result\":{\"answer\":\"hello\"}}";
            invokeProcessLine(responseLine);

            assertTrue(future.isDone());
            assertFalse(future.isCompletedExceptionally());
            JsonObject result = future.get(1, TimeUnit.SECONDS);
            assertEquals("hello", result.get("answer").getAsString());
        }

        @Test
        void errorResponse_completesFutureExceptionally() throws Exception {
            ByteArrayOutputStream capturedStdin = new ByteArrayOutputStream();
            try {
                var stdinField = JsonRpcTransport.class.getDeclaredField("stdin");
                stdinField.setAccessible(true);
                @SuppressWarnings("unchecked")
                var ref = (java.util.concurrent.atomic.AtomicReference<OutputStream>) stdinField.get(transport);
                ref.set(capturedStdin);

                var connectedField = JsonRpcTransport.class.getDeclaredField("connected");
                connectedField.setAccessible(true);
                connectedField.set(transport, true);
            } catch (Exception e) {
                fail("Reflection failed: " + e.getMessage());
            }

            CompletableFuture<JsonObject> future = transport.sendRequest("test/method", new JsonObject());
            String written = capturedStdin.toString(StandardCharsets.UTF_8);
            JsonObject sentMsg = JsonParser.parseString(written.trim()).getAsJsonObject();
            int assignedId = sentMsg.get("id").getAsInt();

            // Simulate error response via processLine
            String errorLine = "{\"id\":" + assignedId + ",\"error\":{\"message\":\"something broke\"}}";
            invokeProcessLine(errorLine);

            assertTrue(future.isCompletedExceptionally());
            ExecutionException ex = assertThrows(ExecutionException.class, future::get);
            assertTrue(ex.getCause().getMessage().contains("something broke"));
        }

        @Test
        void unknownMessage_noHandlerCalled() throws Exception {
            String line = "{\"data\":\"some random json\"}";

            invokeProcessLine(line);

            org.mockito.Mockito.verify(handler, org.mockito.Mockito.never()).onServerRequest(org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.verify(handler, org.mockito.Mockito.never()).onNotification(org.mockito.ArgumentMatchers.any());
        }

        @Test
        void malformedJson_noException() throws Exception {
            // processLine should catch parse errors gracefully
            String line = "this is not json";

            invokeProcessLine(line);

            org.mockito.Mockito.verify(handler, org.mockito.Mockito.never()).onServerRequest(org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.verify(handler, org.mockito.Mockito.never()).onNotification(org.mockito.ArgumentMatchers.any());
        }
    }
}
