package com.github.catatafishen.agentbridge.client.codex;

import com.github.catatafishen.agentbridge.client.ClientException;
import com.github.catatafishen.agentbridge.settings.McpServerSettings;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JSON-RPC transport over a child process's stdin/stdout.
 * Handles message framing, ID correlation, and reader thread lifecycle.
 * Protocol-specific dispatch is delegated to a {@link MessageHandler}.
 */
final class JsonRpcTransport {

    private static final Logger LOG = Logger.getInstance(JsonRpcTransport.class);

    // ── JSON-RPC field names ─────────────────────────────────────────────────

    private static final String F_ID = "id";
    private static final String F_METHOD = "method";
    private static final String F_PARAMS = "params";
    private static final String F_RESULT = "result";
    private static final String F_ERROR = "error";
    private static final String F_MESSAGE = "message";

    // ── Message classification enum ──────────────────────────────────────────

    enum MessageType {RESPONSE, SERVER_REQUEST, NOTIFICATION, UNKNOWN}

    // ── Callback interface ───────────────────────────────────────────────────

    /**
     * Callback for non-response messages (server requests and notifications).
     */
    interface MessageHandler {
        void onServerRequest(@NotNull JsonObject msg);

        void onNotification(@NotNull JsonObject msg);
    }

    // ── Fields ───────────────────────────────────────────────────────────────

    private final Project project;
    private final AtomicReference<OutputStream> stdin = new AtomicReference<>();
    private volatile boolean connected;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();
    private volatile MessageHandler messageHandler;

    // ── Constructor ──────────────────────────────────────────────────────────

    JsonRpcTransport(@NotNull Project project) {
        this.project = project;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    void setMessageHandler(@Nullable MessageHandler handler) {
        this.messageHandler = handler;
    }

    /**
     * Attach to a running process. Starts the reader thread.
     */
    void attach(@NotNull Process process) {
        stdin.set(process.getOutputStream());
        connected = true;
        startReaderThread(process);
    }

    /**
     * Shut down: close stdin, complete pending futures exceptionally, clear state.
     */
    void shutdown() {
        connected = false;
        OutputStream out = stdin.getAndSet(null);
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // quiet close
            }
        }
        pendingRequests.forEach((id, f) ->
            f.completeExceptionally(new ClientException("transport disconnected", null, true)));
        pendingRequests.clear();
    }

    boolean isConnected() {
        return connected;
    }

    /**
     * Send a JSON-RPC request. Returns a future resolved when the response arrives.
     */
    @NotNull
    CompletableFuture<JsonObject> sendRequest(@NotNull String method, @NotNull JsonObject params) {
        int id = nextId.getAndIncrement();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        JsonObject msg = new JsonObject();
        msg.addProperty(F_ID, id);
        msg.addProperty(F_METHOD, method);
        msg.add(F_PARAMS, params);

        if (!writeMessage(msg)) {
            pendingRequests.remove(id);
            future.completeExceptionally(new ClientException("Failed to write JSON-RPC message", null, true));
        }
        return future;
    }

    /**
     * Send a JSON-RPC notification (no response expected).
     */
    void sendNotification(@NotNull String method, @NotNull JsonObject params) {
        JsonObject msg = new JsonObject();
        msg.addProperty(F_METHOD, method);
        msg.add(F_PARAMS, params);
        writeMessage(msg);
    }

    /**
     * Send a JSON-RPC response to a server-originated request.
     */
    void sendResponse(@NotNull JsonElement id, @NotNull JsonElement result) {
        JsonObject msg = new JsonObject();
        msg.add(F_ID, id);
        msg.add(F_RESULT, result);
        writeMessage(msg);
    }

    /**
     * Send a JSON-RPC error response to a server-originated request.
     * Uses the standard {@code error} field (not {@code result}) per JSON-RPC 2.0 spec.
     */
    void sendErrorResponse(@NotNull JsonElement id, @NotNull JsonObject error) {
        JsonObject msg = new JsonObject();
        msg.add(F_ID, id);
        msg.add("error", error);
        writeMessage(msg);
    }

    // ── Message writing ──────────────────────────────────────────────────────

    private boolean writeMessage(@NotNull JsonObject msg) {
        OutputStream out = stdin.get();
        if (out == null) return false;
        try {
            String json = msg.toString();
            if (isDebugLoggingEnabled()) {
                LOG.info("[JsonRpc] >>> " + json);
            }
            synchronized (out) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
                out.flush();
            }
            return true;
        } catch (IOException e) {
            LOG.debug("JSON-RPC write failed: " + e.getMessage());
            return false;
        }
    }

    private boolean isDebugLoggingEnabled() {
        return project != null && McpServerSettings.getInstance(project).isDebugLoggingEnabled();
    }

    // ── Reader thread ────────────────────────────────────────────────────────

    private void startReaderThread(@NotNull Process proc) {
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    processLine(line);
                }
            } catch (IOException e) {
                if (connected) LOG.warn("JSON-RPC reader ended: " + e.getMessage());
            } finally {
                connected = false;
                pendingRequests.forEach((id, f) ->
                    f.completeExceptionally(new ClientException("transport disconnected", null, true)));
                pendingRequests.clear();
            }
        }, "jsonrpc-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Parses a single JSONL line and dispatches it.
     */
    private void processLine(@NotNull String line) {
        try {
            JsonObject msg = JsonParser.parseString(line).getAsJsonObject();
            if (isDebugLoggingEnabled()) {
                LOG.info("[JsonRpc] <<< " + line);
            }
            dispatchMessage(msg);
        } catch (RuntimeException e) {
            LOG.warn("JSON-RPC: could not parse line: " + line, e);
        }
    }

    /**
     * Routes an incoming JSONL message to the appropriate handler.
     *
     * <p>Three categories:
     * <ol>
     *   <li>Response to our request: has numeric {@code id} and {@code result}/{@code error} fields.</li>
     *   <li>Server-initiated request: has both {@code method} and a non-null {@code id}.</li>
     *   <li>Notification from server: has {@code method} but no {@code id} (or null {@code id}).</li>
     * </ol>
     */
    private void dispatchMessage(@NotNull JsonObject msg) {
        switch (classifyMessageType(msg)) {
            case RESPONSE -> handleResponse(msg);
            case SERVER_REQUEST -> {
                MessageHandler handler = messageHandler;
                if (handler != null) handler.onServerRequest(msg);
            }
            case NOTIFICATION -> {
                MessageHandler handler = messageHandler;
                if (handler != null) handler.onNotification(msg);
            }
            default -> {
                // Ignore — UNKNOWN message type requires no action
            }
        }
    }

    // ── Response handling ─────────────────────────────────────────────────────

    private void handleResponse(@NotNull JsonObject msg) {
        JsonElement idEl = msg.get(F_ID);
        if (idEl.isJsonPrimitive() && idEl.getAsJsonPrimitive().isNumber()) {
            int id = idEl.getAsInt();
            CompletableFuture<JsonObject> f = pendingRequests.remove(id);
            if (f != null) {
                completeResponseFuture(f, msg);
            }
        }
    }

    /**
     * Resolves a pending-request future from the given JSON-RPC result/error message.
     */
    private void completeResponseFuture(@NotNull CompletableFuture<JsonObject> f,
                                        @NotNull JsonObject msg) {
        if (msg.has(F_ERROR)) {
            JsonObject err = msg.getAsJsonObject(F_ERROR);
            String errMsg = extractJsonRpcErrorMessage(err);
            if (isCodexAuthError(errMsg)) {
                f.completeExceptionally(new ClientException(
                    "Codex not authenticated: " + errMsg
                        + " — run 'codex login' in a terminal, then retry.",
                    null, false));
            } else {
                f.completeExceptionally(new ClientException("JSON-RPC error: " + errMsg, null, false));
            }
        } else {
            JsonElement result = msg.get(F_RESULT);
            f.complete(result != null && result.isJsonObject() ? result.getAsJsonObject() : new JsonObject());
        }
    }

    // ── Static utility methods (package-private for testing) ──────────────────

    static MessageType classifyMessageType(@NotNull JsonObject msg) {
        boolean hasId = msg.has("id") && !msg.get("id").isJsonNull();
        boolean hasMethod = msg.has(F_METHOD);
        boolean hasResult = msg.has(F_RESULT) || msg.has(F_ERROR);
        if (hasId && hasResult && !hasMethod) return MessageType.RESPONSE;
        if (hasMethod && hasId) return MessageType.SERVER_REQUEST;
        if (hasMethod) return MessageType.NOTIFICATION;
        return MessageType.UNKNOWN;
    }

    static String extractJsonRpcErrorMessage(@NotNull JsonObject errorObj) {
        return errorObj.has(F_MESSAGE) ? errorObj.get(F_MESSAGE).getAsString() : errorObj.toString();
    }

    /**
     * Heuristic check for Codex auth-failure messages. Patterns include:
     * "not authenticated", "Unauthorized", "401", "Invalid API key", "Please run codex login".
     */
    static boolean isCodexAuthError(@Nullable String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("not authenticated")
            || lower.contains("unauthorized")
            || lower.contains("authentication required")
            || lower.contains("invalid api key")
            || lower.contains("please run codex login")
            || lower.contains("please log in")
            || lower.contains("401");
    }
}
