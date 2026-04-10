package com.github.catatafishen.agentbridge.acp.transport;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link JsonRpcTransport} — bidirectional JSON-RPC 2.0 transport over stdio.
 *
 * <p>Uses a mock {@link Process} backed by piped streams so that:
 * <ul>
 *   <li>{@code feedLine(json)} simulates agent stdout → transport reads it</li>
 *   <li>{@code readSentJson()} captures what the transport writes to agent stdin</li>
 *   <li>{@code stderrFeed} simulates agent stderr</li>
 * </ul>
 */
@DisplayName("JsonRpcTransport")
@Timeout(10)
class JsonRpcTransportTest {

    private JsonRpcTransport transport;

    // Agent stdout simulation: write here → transport reads via process.getInputStream()
    private PipedOutputStream feedToTransport;
    private PipedInputStream transportReadsFrom;

    // Capture transport output: transport writes via process.getOutputStream() → we read here
    private PipedOutputStream transportWritesTo;
    private PipedInputStream capturedOutput;
    private BufferedReader capturedReader;

    // Agent stderr simulation: write here → transport reads via process.getErrorStream()
    private PipedOutputStream stderrFeed;
    private PipedInputStream stderrStream;

    private Process mockProcess;

    @BeforeEach
    void setUp() throws Exception {
        transport = new JsonRpcTransport();

        feedToTransport = new PipedOutputStream();
        transportReadsFrom = new PipedInputStream(feedToTransport, 8192);

        capturedOutput = new PipedInputStream(8192);
        transportWritesTo = new PipedOutputStream(capturedOutput);
        capturedReader = new BufferedReader(new InputStreamReader(capturedOutput, StandardCharsets.UTF_8));

        stderrFeed = new PipedOutputStream();
        stderrStream = new PipedInputStream(stderrFeed, 4096);

        mockProcess = new MockProcess(transportWritesTo, transportReadsFrom, stderrStream);
    }

    @AfterEach
    void tearDown() {
        transport.stop();
        closeQuietly(feedToTransport);
        closeQuietly(stderrFeed);
        closeQuietly(transportWritesTo);
    }

    // ─── Helpers ──────────────────────────────────

    /** Write a JSON-RPC line to the transport (simulating agent stdout). */
    private void feedLine(String json) throws IOException {
        feedToTransport.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        feedToTransport.flush();
    }

    /** Read the next line written by the transport (what it sent to agent stdin). */
    private String readSentLine() throws IOException {
        return capturedReader.readLine();
    }

    /** Read the next line written by the transport and parse it as JSON. */
    private JsonObject readSentJson() throws IOException {
        String line = readSentLine();
        assertNotNull(line, "Expected a sent line but got null (pipe closed?)");
        return JsonParser.parseString(line).getAsJsonObject();
    }

    private static void closeQuietly(OutputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }

    // ─── Mock Process ─────────────────────────────

    /**
     * Minimal {@link Process} subclass backed by piped streams.
     * {@code isAlive()} returns {@code true} until {@code destroy()} is called.
     */
    private static class MockProcess extends Process {
        private final OutputStream out;   // process stdin — transport writes here
        private final InputStream in;     // process stdout — transport reads here
        private final InputStream err;    // process stderr — transport reads here
        private volatile boolean alive = true;

        MockProcess(OutputStream out, InputStream in, InputStream err) {
            this.out = out;
            this.in = in;
            this.err = err;
        }

        @Override
        public OutputStream getOutputStream() {
            return out;
        }

        @Override
        public InputStream getInputStream() {
            return in;
        }

        @Override
        public InputStream getErrorStream() {
            return err;
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            if (alive) throw new IllegalThreadStateException("Process still running");
            return 0;
        }

        @Override
        public void destroy() {
            alive = false;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }

    // ═══════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("lifecycle")
    class LifecycleTest {

        @Test
        @DisplayName("isAlive returns false before start")
        void isAliveBeforeStart() {
            assertFalse(transport.isAlive());
        }

        @Test
        @DisplayName("isAlive returns true after start")
        void isAliveAfterStart() {
            transport.start(mockProcess);
            assertTrue(transport.isAlive());
        }

        @Test
        @DisplayName("start twice throws IllegalStateException")
        void startTwiceThrows() {
            transport.start(mockProcess);
            assertThrows(IllegalStateException.class, () -> transport.start(mockProcess));
        }

        @Test
        @DisplayName("stop sets alive to false")
        void stopSetsAliveToFalse() {
            transport.start(mockProcess);
            assertTrue(transport.isAlive());

            transport.stop();
            assertFalse(transport.isAlive());
        }

        @Test
        @DisplayName("stop when not started is a no-op")
        void stopWhenNotStartedIsNoOp() {
            assertDoesNotThrow(() -> transport.stop());
            assertFalse(transport.isAlive());
        }

        @Test
        @DisplayName("stop completes pending futures with IOException('Transport stopped')")
        void stopCompletesPendingFutures() {
            transport.start(mockProcess);
            CompletableFuture<JsonElement> future = transport.sendRequest("test/method", null);

            transport.stop();

            ExecutionException ex = assertThrows(ExecutionException.class,
                () -> future.get(1, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, ex.getCause());
            assertTrue(ex.getCause().getMessage().contains("Transport stopped"));
        }

        @Test
        @DisplayName("stop completes multiple pending futures")
        void stopCompletesMultiplePendingFutures() {
            transport.start(mockProcess);
            CompletableFuture<JsonElement> f1 = transport.sendRequest("method1", null);
            CompletableFuture<JsonElement> f2 = transport.sendRequest("method2", null);
            CompletableFuture<JsonElement> f3 = transport.sendRequest("method3", null);

            transport.stop();

            assertTrue(f1.isCompletedExceptionally());
            assertTrue(f2.isCompletedExceptionally());
            assertTrue(f3.isCompletedExceptionally());
        }

        @Test
        @DisplayName("isAlive returns false when process is not alive")
        void isAliveReturnsFalseWhenProcessDead() {
            transport.start(mockProcess);
            assertTrue(transport.isAlive());

            mockProcess.destroy(); // kills the mock process
            assertFalse(transport.isAlive());
        }
    }

    // ═══════════════════════════════════════════════
    // Sending Requests
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("sendRequest")
    class SendRequestTest {

        @Test
        @DisplayName("writes valid JSON-RPC 2.0 request")
        void writesValidJsonRpcRequest() throws Exception {
            transport.start(mockProcess);

            JsonObject params = new JsonObject();
            params.addProperty("key", "value");
            transport.sendRequest("tools/call", params);

            JsonObject sent = readSentJson();
            assertEquals("2.0", sent.get("jsonrpc").getAsString());
            assertTrue(sent.has("id"), "Request must have an id");
            assertEquals("tools/call", sent.get("method").getAsString());
            assertEquals("value", sent.getAsJsonObject("params").get("key").getAsString());
        }

        @Test
        @DisplayName("null params omits the params field")
        void nullParamsOmitted() throws Exception {
            transport.start(mockProcess);

            transport.sendRequest("test/method", null);

            JsonObject sent = readSentJson();
            assertFalse(sent.has("params"), "params should be absent when null");
        }

        @Test
        @DisplayName("sequential requests have incrementing IDs")
        void incrementingIds() throws Exception {
            transport.start(mockProcess);

            transport.sendRequest("method1", null);
            transport.sendRequest("method2", null);

            JsonObject first = readSentJson();
            JsonObject second = readSentJson();
            long id1 = first.get("id").getAsLong();
            long id2 = second.get("id").getAsLong();
            assertEquals(id1 + 1, id2, "IDs should be sequential");
        }

        @Test
        @DisplayName("future completes with result on success response")
        void futureCompletesOnSuccess() throws Exception {
            transport.start(mockProcess);

            CompletableFuture<JsonElement> future = transport.sendRequest("test/method", null);
            long id = readSentJson().get("id").getAsLong();

            // Feed a success response
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.addProperty("id", id);
            JsonObject resultObj = new JsonObject();
            resultObj.addProperty("status", "ok");
            response.add("result", resultObj);
            feedLine(response.toString());

            JsonElement result = future.get(5, TimeUnit.SECONDS);
            assertTrue(result.isJsonObject());
            assertEquals("ok", result.getAsJsonObject().get("status").getAsString());
        }

        @Test
        @DisplayName("future completes with primitive result")
        void futureCompletesWithPrimitiveResult() throws Exception {
            transport.start(mockProcess);

            CompletableFuture<JsonElement> future = transport.sendRequest("test/method", null);
            long id = readSentJson().get("id").getAsLong();

            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.addProperty("id", id);
            response.addProperty("result", "hello");
            feedLine(response.toString());

            JsonElement result = future.get(5, TimeUnit.SECONDS);
            assertEquals("hello", result.getAsString());
        }

        @Test
        @DisplayName("future completes exceptionally with JsonRpcException on error response")
        void futureCompletesWithJsonRpcException() throws Exception {
            transport.start(mockProcess);

            CompletableFuture<JsonElement> future = transport.sendRequest("test/method", null);
            long id = readSentJson().get("id").getAsLong();

            JsonObject error = new JsonObject();
            error.addProperty("code", -32600);
            error.addProperty("message", "Invalid Request");
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.addProperty("id", id);
            response.add("error", error);
            feedLine(response.toString());

            ExecutionException ex = assertThrows(ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
            assertInstanceOf(JsonRpcException.class, ex.getCause());
            JsonRpcException rpcEx = (JsonRpcException) ex.getCause();
            assertEquals(-32600, rpcEx.getCode());
            assertEquals("Invalid Request", rpcEx.getMessage());
        }

        @Test
        @DisplayName("error response with data field appends data to message")
        void errorWithDataAppendsToMessage() throws Exception {
            transport.start(mockProcess);

            CompletableFuture<JsonElement> future = transport.sendRequest("test/method", null);
            long id = readSentJson().get("id").getAsLong();

            JsonObject error = new JsonObject();
            error.addProperty("code", -32603);
            error.addProperty("message", "Internal error");
            error.addProperty("data", "stack trace details");
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.addProperty("id", id);
            response.add("error", error);
            feedLine(response.toString());

            ExecutionException ex = assertThrows(ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
            JsonRpcException rpcEx = (JsonRpcException) ex.getCause();
            assertEquals(-32603, rpcEx.getCode());
            assertEquals("Internal error: stack trace details", rpcEx.getMessage());
        }

        @Test
        @DisplayName("error response with blank data does not append separator")
        void errorWithBlankDataDoesNotAppend() throws Exception {
            transport.start(mockProcess);

            CompletableFuture<JsonElement> future = transport.sendRequest("test/method", null);
            long id = readSentJson().get("id").getAsLong();

            JsonObject error = new JsonObject();
            error.addProperty("code", -32000);
            error.addProperty("message", "Server error");
            error.addProperty("data", "   ");
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.addProperty("id", id);
            response.add("error", error);
            feedLine(response.toString());

            ExecutionException ex = assertThrows(ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
            JsonRpcException rpcEx = (JsonRpcException) ex.getCause();
            assertEquals("Server error", rpcEx.getMessage(),
                "Blank data should not be appended");
        }

        @Test
        @DisplayName("error response with null data does not append")
        void errorWithNullDataDoesNotAppend() throws Exception {
            transport.start(mockProcess);

            CompletableFuture<JsonElement> future = transport.sendRequest("test/method", null);
            long id = readSentJson().get("id").getAsLong();

            JsonObject error = new JsonObject();
            error.addProperty("code", -32000);
            error.addProperty("message", "Server error");
            error.add("data", com.google.gson.JsonNull.INSTANCE);
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.addProperty("id", id);
            response.add("error", error);
            feedLine(response.toString());

            ExecutionException ex = assertThrows(ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
            JsonRpcException rpcEx = (JsonRpcException) ex.getCause();
            assertEquals("Server error", rpcEx.getMessage(),
                "Null data should not be appended");
        }

        @Test
        @DisplayName("correct response is correlated to the right future among multiple in-flight")
        void multipleInFlightCorrelation() throws Exception {
            transport.start(mockProcess);

            CompletableFuture<JsonElement> f1 = transport.sendRequest("m1", null);
            long id1 = readSentJson().get("id").getAsLong();

            CompletableFuture<JsonElement> f2 = transport.sendRequest("m2", null);
            long id2 = readSentJson().get("id").getAsLong();

            // Respond to the SECOND request first
            JsonObject resp2 = new JsonObject();
            resp2.addProperty("jsonrpc", "2.0");
            resp2.addProperty("id", id2);
            resp2.addProperty("result", "result-for-m2");
            feedLine(resp2.toString());

            assertEquals("result-for-m2", f2.get(5, TimeUnit.SECONDS).getAsString());
            assertFalse(f1.isDone(), "First future should still be pending");

            // Now respond to the first request
            JsonObject resp1 = new JsonObject();
            resp1.addProperty("jsonrpc", "2.0");
            resp1.addProperty("id", id1);
            resp1.addProperty("result", "result-for-m1");
            feedLine(resp1.toString());

            assertEquals("result-for-m1", f1.get(5, TimeUnit.SECONDS).getAsString());
        }
    }

    // ═══════════════════════════════════════════════
    // Sending Notifications
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("sendNotification")
    class SendNotificationTest {

        @Test
        @DisplayName("writes valid JSON-RPC 2.0 notification without id")
        void writesNotificationWithoutId() throws Exception {
            transport.start(mockProcess);

            JsonObject params = new JsonObject();
            params.addProperty("token", "abc");
            transport.sendNotification("$/progress", params);

            JsonObject sent = readSentJson();
            assertEquals("2.0", sent.get("jsonrpc").getAsString());
            assertFalse(sent.has("id"), "Notification must not have an id");
            assertEquals("$/progress", sent.get("method").getAsString());
            assertEquals("abc", sent.getAsJsonObject("params").get("token").getAsString());
        }

        @Test
        @DisplayName("null params omits the params field")
        void nullParamsOmitted() throws Exception {
            transport.start(mockProcess);

            transport.sendNotification("notifications/initialized", null);

            JsonObject sent = readSentJson();
            assertFalse(sent.has("params"), "params should be absent when null");
            assertEquals("notifications/initialized", sent.get("method").getAsString());
        }
    }

    // ═══════════════════════════════════════════════
    // Sending Responses
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("sendResponse")
    class SendResponseTest {

        @Test
        @DisplayName("writes valid JSON-RPC 2.0 success response")
        void writesSuccessResponse() throws Exception {
            transport.start(mockProcess);

            JsonElement id = new JsonPrimitive(42);
            JsonObject result = new JsonObject();
            result.addProperty("content", "file contents");
            transport.sendResponse(id, result);

            JsonObject sent = readSentJson();
            assertEquals("2.0", sent.get("jsonrpc").getAsString());
            assertEquals(42, sent.get("id").getAsInt());
            assertTrue(sent.has("result"));
            assertEquals("file contents",
                sent.getAsJsonObject("result").get("content").getAsString());
        }

        @Test
        @DisplayName("null result sends an empty JSON object")
        void nullResultSendsEmptyObject() throws Exception {
            transport.start(mockProcess);

            transport.sendResponse(new JsonPrimitive(1), null);

            JsonObject sent = readSentJson();
            assertTrue(sent.get("result").isJsonObject());
            assertEquals(0, sent.getAsJsonObject("result").size());
        }

        @Test
        @DisplayName("preserves string id")
        void preservesStringId() throws Exception {
            transport.start(mockProcess);

            transport.sendResponse(new JsonPrimitive("uuid-123"), new JsonPrimitive("ok"));

            JsonObject sent = readSentJson();
            assertEquals("uuid-123", sent.get("id").getAsString());
        }
    }

    // ═══════════════════════════════════════════════
    // Sending Errors
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("sendError")
    class SendErrorTest {

        @Test
        @DisplayName("writes valid JSON-RPC 2.0 error response")
        void writesErrorResponse() throws Exception {
            transport.start(mockProcess);

            transport.sendError(new JsonPrimitive(7), -32601, "Method not found");

            JsonObject sent = readSentJson();
            assertEquals("2.0", sent.get("jsonrpc").getAsString());
            assertEquals(7, sent.get("id").getAsInt());
            assertTrue(sent.has("error"));
            assertFalse(sent.has("result"), "Error response must not have result");

            JsonObject error = sent.getAsJsonObject("error");
            assertEquals(-32601, error.get("code").getAsInt());
            assertEquals("Method not found", error.get("message").getAsString());
        }

        @Test
        @DisplayName("error response does not include result field")
        void errorDoesNotIncludeResult() throws Exception {
            transport.start(mockProcess);

            transport.sendError(new JsonPrimitive(10), -32700, "Parse error");

            JsonObject sent = readSentJson();
            assertFalse(sent.has("result"));
            assertTrue(sent.has("error"));
        }
    }

    // ═══════════════════════════════════════════════
    // Incoming Request Dispatch
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("incoming requests")
    class IncomingRequestTest {

        @Test
        @DisplayName("dispatches incoming request to handler with id, method, and params")
        void dispatchesIncomingRequest() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            List<JsonRpcTransport.IncomingRequest> received = new CopyOnWriteArrayList<>();
            List<JsonElement> receivedIds = new CopyOnWriteArrayList<>();

            transport.onRequest((id, req) -> {
                receivedIds.add(id);
                received.add(req);
                latch.countDown();
            });
            transport.start(mockProcess);

            JsonObject request = new JsonObject();
            request.addProperty("jsonrpc", "2.0");
            request.addProperty("id", 99);
            request.addProperty("method", "tools/call");
            JsonObject params = new JsonObject();
            params.addProperty("name", "read_file");
            request.add("params", params);
            feedLine(request.toString());

            assertTrue(latch.await(5, TimeUnit.SECONDS), "Handler should be called");
            assertEquals(1, received.size());
            assertEquals("tools/call", received.get(0).method());
            assertNotNull(received.get(0).params());
            assertEquals("read_file", received.get(0).params().get("name").getAsString());
            assertEquals(99, receivedIds.get(0).getAsInt());
        }

        @Test
        @DisplayName("incoming request without params passes null")
        void incomingRequestWithoutParams() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            List<JsonRpcTransport.IncomingRequest> received = new CopyOnWriteArrayList<>();

            transport.onRequest((id, req) -> {
                received.add(req);
                latch.countDown();
            });
            transport.start(mockProcess);

            JsonObject request = new JsonObject();
            request.addProperty("jsonrpc", "2.0");
            request.addProperty("id", 1);
            request.addProperty("method", "ping");
            feedLine(request.toString());

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertNull(received.get(0).params());
        }

        @Test
        @DisplayName("multiple incoming requests are dispatched in order")
        void multipleRequestsInOrder() throws Exception {
            CountDownLatch latch = new CountDownLatch(2);
            List<String> methods = new CopyOnWriteArrayList<>();

            transport.onRequest((id, req) -> {
                methods.add(req.method());
                latch.countDown();
            });
            transport.start(mockProcess);

            JsonObject req1 = new JsonObject();
            req1.addProperty("jsonrpc", "2.0");
            req1.addProperty("id", 1);
            req1.addProperty("method", "first");
            feedLine(req1.toString());

            JsonObject req2 = new JsonObject();
            req2.addProperty("jsonrpc", "2.0");
            req2.addProperty("id", 2);
            req2.addProperty("method", "second");
            feedLine(req2.toString());

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals("first", methods.get(0));
            assertEquals("second", methods.get(1));
        }
    }

    // ═══════════════════════════════════════════════
    // Incoming Notification Dispatch
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("incoming notifications")
    class IncomingNotificationTest {

        @Test
        @DisplayName("dispatches incoming notification to handler")
        void dispatchesNotification() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            List<JsonRpcTransport.IncomingNotification> received = new CopyOnWriteArrayList<>();

            transport.onNotification(notif -> {
                received.add(notif);
                latch.countDown();
            });
            transport.start(mockProcess);

            JsonObject notification = new JsonObject();
            notification.addProperty("jsonrpc", "2.0");
            notification.addProperty("method", "$/progress");
            JsonObject params = new JsonObject();
            params.addProperty("token", "abc");
            notification.add("params", params);
            feedLine(notification.toString());

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(1, received.size());
            assertEquals("$/progress", received.get(0).method());
            assertNotNull(received.get(0).params());
            assertEquals("abc", received.get(0).params().get("token").getAsString());
        }

        @Test
        @DisplayName("incoming notification without params passes null")
        void notificationWithoutParams() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            List<JsonRpcTransport.IncomingNotification> received = new CopyOnWriteArrayList<>();

            transport.onNotification(notif -> {
                received.add(notif);
                latch.countDown();
            });
            transport.start(mockProcess);

            JsonObject notification = new JsonObject();
            notification.addProperty("jsonrpc", "2.0");
            notification.addProperty("method", "notifications/initialized");
            feedLine(notification.toString());

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertNull(received.get(0).params());
        }

        @Test
        @DisplayName("blank lines are silently skipped")
        void blankLinesSkipped() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            List<JsonRpcTransport.IncomingNotification> received = new CopyOnWriteArrayList<>();

            transport.onNotification(notif -> {
                received.add(notif);
                latch.countDown();
            });
            transport.start(mockProcess);

            feedLine("");
            feedLine("   ");
            feedLine("\t");

            JsonObject notification = new JsonObject();
            notification.addProperty("jsonrpc", "2.0");
            notification.addProperty("method", "test/after-blanks");
            feedLine(notification.toString());

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(1, received.size(), "Only the real notification should be dispatched");
            assertEquals("test/after-blanks", received.get(0).method());
        }
    }

    // ═══════════════════════════════════════════════
    // Stderr Handling
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("stderr handling")
    class StderrTest {

        @Test
        @DisplayName("stderr lines are dispatched to the handler")
        void stderrDispatched() throws Exception {
            CountDownLatch latch = new CountDownLatch(2);
            List<String> received = new CopyOnWriteArrayList<>();

            transport.onStderr(line -> {
                received.add(line);
                latch.countDown();
            });
            transport.start(mockProcess);

            stderrFeed.write("Error: something went wrong\n".getBytes(StandardCharsets.UTF_8));
            stderrFeed.write("Warning: deprecated API\n".getBytes(StandardCharsets.UTF_8));
            stderrFeed.flush();

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(2, received.size());
            assertEquals("Error: something went wrong", received.get(0));
            assertEquals("Warning: deprecated API", received.get(1));
        }

        @Test
        @DisplayName("stderr without handler does not throw")
        void stderrWithoutHandler() throws Exception {
            // Do NOT register an stderr handler — the transport should handle it gracefully
            transport.start(mockProcess);

            stderrFeed.write("some stderr output\n".getBytes(StandardCharsets.UTF_8));
            stderrFeed.flush();

            // Give the stderr thread time to process — no assertion needed, just no exception
            Thread.sleep(200);
        }
    }

    // ═══════════════════════════════════════════════
    // Debug Logger
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("debug logger")
    class DebugLoggerTest {

        @Test
        @DisplayName("logs outgoing messages with >>> prefix")
        void logsOutgoing() throws Exception {
            List<String> logs = new CopyOnWriteArrayList<>();
            transport.setDebugLogger(logs::add);
            transport.start(mockProcess);

            transport.sendNotification("test/outgoing", null);
            readSentLine(); // drain the pipe

            assertTrue(logs.stream().anyMatch(l -> l.startsWith(">>>")),
                "Should have a log entry starting with >>>");
            assertTrue(logs.stream().anyMatch(l -> l.contains("test/outgoing")),
                "Should mention the method name");
        }

        @Test
        @DisplayName("logs incoming messages with <<< prefix")
        void logsIncoming() throws Exception {
            CountDownLatch incomingLatch = new CountDownLatch(1);
            List<String> logs = new CopyOnWriteArrayList<>();
            transport.setDebugLogger(msg -> {
                logs.add(msg);
                if (msg.startsWith("<<<")) incomingLatch.countDown();
            });
            transport.onNotification(notif -> {
            }); // register handler so the message is processed
            transport.start(mockProcess);

            JsonObject notification = new JsonObject();
            notification.addProperty("jsonrpc", "2.0");
            notification.addProperty("method", "test/incoming");
            feedLine(notification.toString());

            assertTrue(incomingLatch.await(5, TimeUnit.SECONDS));
            assertTrue(logs.stream().anyMatch(
                l -> l.startsWith("<<<") && l.contains("test/incoming")));
        }

        @Test
        @DisplayName("both directions are logged for a request/response round-trip")
        void bothDirectionsLogged() throws Exception {
            CountDownLatch responseLatch = new CountDownLatch(1);
            List<String> logs = new CopyOnWriteArrayList<>();
            transport.setDebugLogger(msg -> {
                logs.add(msg);
                // Count <<< entries for the response
                if (msg.startsWith("<<<") && msg.contains("result")) {
                    responseLatch.countDown();
                }
            });
            transport.start(mockProcess);

            CompletableFuture<JsonElement> future = transport.sendRequest("test/roundtrip", null);
            long id = readSentJson().get("id").getAsLong();

            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.addProperty("id", id);
            response.addProperty("result", "done");
            feedLine(response.toString());

            future.get(5, TimeUnit.SECONDS);
            assertTrue(responseLatch.await(5, TimeUnit.SECONDS));

            assertTrue(logs.stream().anyMatch(l -> l.startsWith(">>>")),
                "Outgoing request should be logged");
            assertTrue(logs.stream().anyMatch(l -> l.startsWith("<<<")),
                "Incoming response should be logged");
        }

        @Test
        @DisplayName("null debug logger disables logging")
        void nullLoggerDisablesLogging() throws Exception {
            List<String> logs = new CopyOnWriteArrayList<>();
            transport.setDebugLogger(logs::add);
            transport.start(mockProcess);

            transport.sendNotification("logged/message", null);
            readSentLine();
            assertFalse(logs.isEmpty(), "Should have logged something");

            // Now disable logging
            logs.clear();
            transport.setDebugLogger(null);
            transport.sendNotification("unlogged/message", null);
            readSentLine();
            assertTrue(logs.isEmpty(), "Should not log after logger set to null");
        }
    }

    // ═══════════════════════════════════════════════
    // Process Exit
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("process exit")
    class ProcessExitTest {

        @Test
        @DisplayName("pending futures completed with IOException when process exits")
        void pendingFuturesCompletedOnExit() throws Exception {
            transport.start(mockProcess);

            CompletableFuture<JsonElement> future = transport.sendRequest("test/method", null);
            readSentLine(); // drain outgoing message from the pipe

            // Simulate process exit by closing the agent stdout stream
            feedToTransport.close();

            ExecutionException ex = assertThrows(ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, ex.getCause());
            assertTrue(ex.getCause().getMessage().contains("exited unexpectedly"));
        }

        @Test
        @DisplayName("process exit with stderr includes stderr context in error")
        void processExitIncludesStderr() throws Exception {
            transport.start(mockProcess);

            CompletableFuture<JsonElement> future = transport.sendRequest("test/method", null);
            readSentLine();

            // Feed stderr before closing stdout
            stderrFeed.write("FATAL: out of memory\n".getBytes(StandardCharsets.UTF_8));
            stderrFeed.flush();
            // Give stderr thread time to buffer the line
            Thread.sleep(300);

            feedToTransport.close();

            ExecutionException ex = assertThrows(ExecutionException.class,
                () -> future.get(5, TimeUnit.SECONDS));
            IOException cause = (IOException) ex.getCause();
            assertTrue(cause.getMessage().contains("exited unexpectedly"),
                "Should mention 'exited unexpectedly', got: " + cause.getMessage());
            assertTrue(cause.getMessage().contains("FATAL: out of memory"),
                "Should include stderr content, got: " + cause.getMessage());
        }

        @Test
        @DisplayName("transport is no longer alive after process exit")
        void notAliveAfterProcessExit() throws Exception {
            transport.start(mockProcess);
            assertTrue(transport.isAlive());

            feedToTransport.close();

            // Wait for the reader thread to detect the closed stream
            CompletableFuture<Void> aliveCheck = CompletableFuture.runAsync(() -> {
                while (transport.isAlive()) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
            aliveCheck.get(5, TimeUnit.SECONDS);
            assertFalse(transport.isAlive());
        }
    }

    // ═══════════════════════════════════════════════
    // Round-trip Integration
    // ═══════════════════════════════════════════════

    @Nested
    @DisplayName("round-trip integration")
    class RoundTripTest {

        @Test
        @DisplayName("full round-trip: send request, receive response, handle incoming request")
        void fullRoundTrip() throws Exception {
            CountDownLatch incomingLatch = new CountDownLatch(1);
            List<String> incomingMethods = new CopyOnWriteArrayList<>();

            transport.onRequest((id, req) -> {
                incomingMethods.add(req.method());
                // Reply to the incoming request
                JsonObject result = new JsonObject();
                result.addProperty("handled", true);
                transport.sendResponse(id, result);
                incomingLatch.countDown();
            });
            transport.start(mockProcess);

            // 1. Send an outgoing request
            CompletableFuture<JsonElement> future = transport.sendRequest("initialize", null);
            long outId = readSentJson().get("id").getAsLong();

            // 2. Simulate an incoming request from the agent (before responding to ours)
            JsonObject agentRequest = new JsonObject();
            agentRequest.addProperty("jsonrpc", "2.0");
            agentRequest.addProperty("id", 500);
            agentRequest.addProperty("method", "tools/call");
            feedLine(agentRequest.toString());

            // 3. Wait for the handler to process and send back a response
            assertTrue(incomingLatch.await(5, TimeUnit.SECONDS));
            assertEquals("tools/call", incomingMethods.get(0));

            // 4. Read the response the handler sent back
            JsonObject handlerResponse = readSentJson();
            assertEquals(500, handlerResponse.get("id").getAsInt());
            assertTrue(handlerResponse.getAsJsonObject("result").get("handled").getAsBoolean());

            // 5. Now respond to our original outgoing request
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.addProperty("id", outId);
            JsonObject initResult = new JsonObject();
            initResult.addProperty("protocolVersion", "2024-11-05");
            response.add("result", initResult);
            feedLine(response.toString());

            JsonElement result = future.get(5, TimeUnit.SECONDS);
            assertEquals("2024-11-05",
                result.getAsJsonObject().get("protocolVersion").getAsString());
        }
    }
}
