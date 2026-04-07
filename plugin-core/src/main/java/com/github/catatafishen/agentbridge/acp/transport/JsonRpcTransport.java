package com.github.catatafishen.agentbridge.acp.transport;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Bidirectional JSON-RPC 2.0 transport over stdio.
 * Thread-safe. One instance per agent process.
 * <p>
 * Handles:
 * <ul>
 *   <li>Sending requests (with future-based response correlation)</li>
 *   <li>Sending notifications (fire-and-forget)</li>
 *   <li>Sending responses to incoming requests</li>
 *   <li>Receiving and dispatching incoming messages by type</li>
 *   <li>Stderr capture for diagnostics</li>
 * </ul>
 */
public class JsonRpcTransport {

    private static final Logger LOG = Logger.getInstance(JsonRpcTransport.class);

    private static final String JSONRPC_VERSION = "2.0";

    private static final String KEY_JSONRPC = "jsonrpc";
    private static final String KEY_ID = "id";
    private static final String KEY_METHOD = "method";
    private static final String KEY_PARAMS = "params";
    private static final String KEY_RESULT = "result";
    private static final String KEY_ERROR = "error";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_CODE = "code";

    private static final int STDERR_BUFFER_MAX = 20;

    private final Gson gson = new GsonBuilder().create();
    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonElement>> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicBoolean alive = new AtomicBoolean(false);
    private final Deque<String> stderrBuffer = new ArrayDeque<>();

    private @Nullable Process process;
    private @Nullable PrintWriter writer;
    private @Nullable Thread readerThread;
    private @Nullable Thread stderrThread;

    private @Nullable BiConsumer<JsonElement, IncomingRequest> requestHandler;
    private @Nullable Consumer<IncomingNotification> notificationHandler;
    private @Nullable Consumer<String> stderrHandler;
    private @Nullable Consumer<String> debugLogger;

    /**
     * An incoming JSON-RPC request from the agent.
     */
    public record IncomingRequest(String method, @Nullable JsonObject params) {
    }

    /**
     * An incoming JSON-RPC notification from the agent.
     */
    public record IncomingNotification(String method, @Nullable JsonObject params) {
    }

    // ─── Handler Registration ─────────────────────────

    /**
     * Register handler for incoming requests (agent → client).
     * Called with (requestId, request) — requestId is the raw JSON element
     * (may be numeric or a UUID string depending on the agent).
     */
    public void onRequest(BiConsumer<JsonElement, IncomingRequest> handler) {
        this.requestHandler = handler;
    }

    /**
     * Register handler for incoming notifications (agent → client).
     */
    public void onNotification(Consumer<IncomingNotification> handler) {
        this.notificationHandler = handler;
    }

    /**
     * Register handler for stderr output.
     */
    public void onStderr(Consumer<String> handler) {
        this.stderrHandler = handler;
    }

    /**
     * Set a logger callback for debug-level ACP message tracing.
     * When set, every incoming and outgoing JSON-RPC line is passed to this consumer.
     */
    public void setDebugLogger(@Nullable Consumer<String> logger) {
        this.debugLogger = logger;
    }

    // ─── Lifecycle ────────────────────────────────────

    /**
     * Attach to a running process and start reading.
     */
    public void start(Process agentProcess) {
        if (alive.getAndSet(true)) {
            throw new IllegalStateException("Transport already started");
        }

        this.process = agentProcess;
        this.writer = new PrintWriter(
            new OutputStreamWriter(agentProcess.getOutputStream(), StandardCharsets.UTF_8),
            true
        );

        this.readerThread = new Thread(this::readLoop, "jsonrpc-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();

        this.stderrThread = new Thread(this::stderrLoop, "jsonrpc-stderr");
        this.stderrThread.setDaemon(true);
        this.stderrThread.start();
    }

    /**
     * Stop the transport and clean up resources.
     */
    public void stop() {
        if (!alive.getAndSet(false)) {
            return;
        }

        pendingRequests.forEach((id, future) ->
            future.completeExceptionally(new IOException("Transport stopped")));
        pendingRequests.clear();

        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (stderrThread != null) {
            stderrThread.interrupt();
        }
        if (writer != null) {
            writer.close();
        }
    }

    public boolean isAlive() {
        return alive.get() && process != null && process.isAlive();
    }

    // ─── Sending ──────────────────────────────────────

    /**
     * Send a JSON-RPC request and return a future for the response.
     */
    public CompletableFuture<JsonElement> sendRequest(String method, @Nullable JsonObject params) {
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        pendingRequests.put(id, future);
        // Clean up the pending-request entry when the future completes for any reason.
        // We intentionally do NOT use orTimeout() here: the caller manages its own deadline
        // via future.get(timeout, unit). Using orTimeout() would race with a late-arriving
        // real response, prematurely removing the id and causing spurious "unknown request id"
        // warnings (and a missed result) when the actual response arrives after the timeout.
        future.whenComplete((result, error) -> pendingRequests.remove(id));
        writeLine(gson.toJson(buildRequest(id, method, params)));
        return future;
    }

    /**
     * Send a JSON-RPC notification (no response expected).
     */
    public void sendNotification(String method, @Nullable JsonObject params) {
        JsonObject msg = new JsonObject();
        msg.addProperty(KEY_JSONRPC, JSONRPC_VERSION);
        msg.addProperty(KEY_METHOD, method);
        if (params != null) {
            msg.add(KEY_PARAMS, params);
        }
        writeLine(gson.toJson(msg));
    }

    /**
     * Send a successful response to an incoming request.
     * The {@code id} must be the exact {@link JsonElement} received in the request.
     */
    public void sendResponse(JsonElement id, @Nullable JsonElement result) {
        JsonObject msg = new JsonObject();
        msg.addProperty(KEY_JSONRPC, JSONRPC_VERSION);
        msg.add(KEY_ID, id);
        msg.add(KEY_RESULT, result != null ? result : new JsonObject());
        writeLine(gson.toJson(msg));
    }

    /**
     * Send an error response to an incoming request.
     * The {@code id} must be the exact {@link JsonElement} received in the request.
     */
    public void sendError(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty(KEY_CODE, code);
        error.addProperty(KEY_MESSAGE, message);

        JsonObject msg = new JsonObject();
        msg.addProperty(KEY_JSONRPC, JSONRPC_VERSION);
        msg.add(KEY_ID, id);
        msg.add(KEY_ERROR, error);
        writeLine(gson.toJson(msg));
    }

    // ─── Reading ──────────────────────────────────────

    private void readLoop() {
        Process proc = Objects.requireNonNull(this.process, "Process not started");
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (alive.get() && (line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    processLine(line);
                }
            }
        } catch (IOException e) {
            if (alive.get()) {
                LOG.warn("JSON-RPC reader terminated", e);
            }
        } finally {
            alive.set(false);
            // Wait briefly for stderr to drain so we can include it in the failure message
            if (stderrThread != null) {
                try {
                    stderrThread.join(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            String stderrContext = buildStderrContext();
            IOException cause = stderrContext.isEmpty()
                ? new IOException("Agent process exited unexpectedly")
                : new IOException("Agent process exited unexpectedly: " + stderrContext);
            pendingRequests.forEach((id, future) -> future.completeExceptionally(cause));
            pendingRequests.clear();
        }
    }

    private void processLine(String line) {
        if (debugLogger != null) {
            debugLogger.accept("<<< " + line);
        }
        try {
            dispatchMessage(line);
        } catch (Exception e) {
            LOG.warn("Failed to parse JSON-RPC message: " + line, e);
        }
    }

    private void dispatchMessage(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

        if (obj.has(KEY_ID) && (obj.has(KEY_RESULT) || obj.has(KEY_ERROR))) {
            handleResponse(obj);
            return;
        }

        if (obj.has(KEY_ID) && obj.has(KEY_METHOD)) {
            handleIncomingRequest(obj);
            return;
        }

        if (obj.has(KEY_METHOD) && !obj.has(KEY_ID)) {
            handleNotification(obj);
        }
    }

    private void handleResponse(JsonObject obj) {
        long id = obj.get(KEY_ID).getAsLong();
        CompletableFuture<JsonElement> future = pendingRequests.remove(id);
        if (future == null) {
            LOG.warn("Received response for unknown request id: " + id);
            return;
        }

        if (obj.has(KEY_ERROR)) {
            JsonObject error = obj.getAsJsonObject(KEY_ERROR);
            String errorMsg = error.has(KEY_MESSAGE) ? error.get(KEY_MESSAGE).getAsString() : "Unknown error";
            if (error.has("data") && !error.get("data").isJsonNull()) {
                String data = error.get("data").getAsString();
                if (!data.isBlank()) errorMsg = errorMsg + ": " + data;
            }
            int code = error.has(KEY_CODE) ? error.get(KEY_CODE).getAsInt() : -1;
            future.completeExceptionally(new JsonRpcException(code, errorMsg));
        } else {
            future.complete(obj.get(KEY_RESULT));
        }
    }

    private void handleIncomingRequest(JsonObject obj) {
        if (requestHandler == null) {
            LOG.warn("No request handler registered, ignoring: " + obj.get(KEY_METHOD));
            return;
        }
        JsonElement id = obj.get(KEY_ID);
        String method = obj.get(KEY_METHOD).getAsString();
        JsonObject params = obj.has(KEY_PARAMS) ? obj.getAsJsonObject(KEY_PARAMS) : null;
        requestHandler.accept(id, new IncomingRequest(method, params));
    }

    private void handleNotification(JsonObject obj) {
        if (notificationHandler == null) {
            return;
        }
        String method = obj.get(KEY_METHOD).getAsString();
        JsonObject params = obj.has(KEY_PARAMS) ? obj.getAsJsonObject(KEY_PARAMS) : null;
        notificationHandler.accept(new IncomingNotification(method, params));
    }

    private void stderrLoop() {
        Process proc = Objects.requireNonNull(this.process, "Process not started");
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while (alive.get() && (line = reader.readLine()) != null) {
                synchronized (stderrBuffer) {
                    if (stderrBuffer.size() >= STDERR_BUFFER_MAX) stderrBuffer.pollFirst();
                    stderrBuffer.addLast(line);
                }
                if (stderrHandler != null) {
                    stderrHandler.accept(line);
                } else {
                    LOG.info("[agent stderr] " + line);
                }
            }
        } catch (IOException e) {
            if (alive.get()) {
                LOG.warn("Stderr reader terminated", e);
            }
        }
    }

    private String buildStderrContext() {
        synchronized (stderrBuffer) {
            if (stderrBuffer.isEmpty()) return "";
            // Return only the first 3 lines so the error message shown to the user
            // is concise. The full backtrace is already logged line-by-line as WARN.
            return stderrBuffer.stream()
                .filter(line -> !line.isBlank())
                .limit(3)
                .collect(java.util.stream.Collectors.joining(" | "));
        }
    }

    // ─── Helpers ──────────────────────────────────────

    private JsonObject buildRequest(long id, String method, @Nullable JsonObject params) {
        JsonObject msg = new JsonObject();
        msg.addProperty(KEY_JSONRPC, JSONRPC_VERSION);
        msg.addProperty(KEY_ID, id);
        msg.addProperty(KEY_METHOD, method);
        if (params != null) {
            msg.add(KEY_PARAMS, params);
        }
        return msg;
    }

    private synchronized void writeLine(String json) {
        if (writer == null || !alive.get()) {
            LOG.warn("Attempted write on dead transport");
            return;
        }
        if (debugLogger != null) {
            debugLogger.accept(">>> " + json);
        }
        writer.println(json);
        writer.flush();
    }
}
