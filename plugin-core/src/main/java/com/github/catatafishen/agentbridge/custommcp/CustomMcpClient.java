package com.github.catatafishen.agentbridge.custommcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * HTTP JSON-RPC 2.0 MCP client for communicating with an external MCP server
 * using the streamable HTTP transport (single POST endpoint).
 * <p>
 * All HTTP connections are short-lived (created and closed per request).
 * Session state is maintained via the {@code Mcp-Session-Id} header as defined by the
 * MCP Streamable HTTP transport specification. Servers that do not return a session ID
 * during initialization are treated as stateless — no session header is ever sent.
 * <p>
 * Thread-safe: concurrent {@link #callTool} invocations are supported. Session recovery
 * (on HTTP 404) uses a {@link ReentrantLock} to ensure only one thread re-initializes.
 */
public final class CustomMcpClient implements AutoCloseable, McpToolCaller {

    private static final Logger LOG = Logger.getInstance(CustomMcpClient.class);
    private static final Gson GSON = new Gson();
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int CLOSE_TIMEOUT_MS = 2_000;
    private static final String PROTOCOL_VERSION = "2025-11-25";
    static final String SESSION_HEADER = "Mcp-Session-Id";

    private final String url;
    private final AtomicInteger requestId = new AtomicInteger(1);

    /**
     * Session ID received from the server during initialization.
     * Volatile because it is read by concurrent {@link #callTool} threads
     * and written under {@link #reinitLock} during session recovery.
     */
    private volatile String sessionId;

    private final ReentrantLock reinitLock = new ReentrantLock();

    public CustomMcpClient(@NotNull String url) {
        this.url = url;
    }

    /**
     * Metadata for a tool discovered from the remote MCP server.
     */
    public record ToolInfo(
        @NotNull String name,
        @NotNull String description,
        @Nullable JsonObject inputSchema
    ) {
    }

    /**
     * Returns the current session ID, or {@code null} if no session is active.
     * Visible for testing only.
     */
    @VisibleForTesting
    @Nullable
    String getSessionId() {
        return sessionId;
    }

    /**
     * Sends the MCP {@code initialize} handshake.
     * Must be called once before {@link #listTools()}.
     * <p>
     * Per spec, the initialize request is sent <b>without</b> a session ID header.
     * If the server returns an {@code Mcp-Session-Id} response header, it is captured
     * and included in all subsequent requests.
     *
     * @throws IOException if the server is unreachable or returns an error response
     */
    public void initialize() throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL_VERSION);
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        params.add("capabilities", capabilities);
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "agentbridge");
        clientInfo.addProperty("version", "1.0");
        params.add("clientInfo", clientInfo);

        // Per spec: InitializeRequest MUST NOT include a session ID
        JsonObject response = sendRequestInternal("initialize", params, false, false);
        if (response.has("error")) {
            throw new IOException("MCP initialize failed: " + errorMessage(response));
        }
    }

    /**
     * Retrieves the list of tools from the remote server.
     *
     * @return list of discovered tools; empty if the server reports none
     * @throws IOException on communication or protocol error
     */
    @NotNull
    public List<ToolInfo> listTools() throws IOException {
        JsonObject response = sendRequest("tools/list", new JsonObject());
        if (response.has("error")) {
            throw new IOException("MCP tools/list failed: " + errorMessage(response));
        }

        List<ToolInfo> tools = new ArrayList<>();
        if (!response.has("result")) return tools;

        JsonObject result = response.getAsJsonObject("result");
        if (!result.has("tools")) return tools;

        for (JsonElement element : result.getAsJsonArray("tools")) {
            JsonObject toolObj = element.getAsJsonObject();
            String name = toolObj.has("name") ? toolObj.get("name").getAsString() : "";
            String desc = toolObj.has("description") ? toolObj.get("description").getAsString() : "";
            JsonObject schema = toolObj.has("inputSchema") ? toolObj.getAsJsonObject("inputSchema") : null;
            if (!name.isEmpty()) {
                tools.add(new ToolInfo(name, desc, schema));
            }
        }
        return tools;
    }

    /**
     * Calls a tool on the remote server with the given arguments.
     *
     * @param toolName  the original (un-namespaced) tool name on the remote server
     * @param arguments the JSON arguments to pass
     * @return the tool result as text, or an error description on failure
     */
    @Override
    @NotNull
    public String callTool(@NotNull String toolName, @NotNull JsonObject arguments) {
        try {
            JsonObject params = new JsonObject();
            params.addProperty("name", toolName);
            params.add("arguments", arguments);

            JsonObject response = sendRequest("tools/call", params);
            if (response.has("error")) {
                return "Error from MCP server: " + errorMessage(response);
            }
            if (!response.has("result")) return "";

            JsonObject result = response.getAsJsonObject("result");
            boolean isError = result.has("isError") && result.get("isError").getAsBoolean();
            String text = extractTextContent(result);
            return isError ? "Error: " + text : text;
        } catch (IOException e) {
            LOG.warn("Failed to call tool '" + toolName + "' on " + url, e);
            return "Failed to reach MCP server at " + url + ": " + e.getMessage();
        }
    }

    /**
     * Explicitly terminates the MCP session by sending an HTTP DELETE to the server endpoint.
     * <p>
     * Per spec, clients <b>SHOULD</b> send DELETE with the {@code Mcp-Session-Id} header when
     * they no longer need the session. The server may respond with 405 (Method Not Allowed) if
     * it does not support client-initiated session termination — this is expected and silenced.
     * <p>
     * Safe to call multiple times; no-op if no session is active.
     */
    @Override
    public void close() {
        String sid = sessionId;
        if (sid == null) return;
        sessionId = null;

        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            try {
                conn.setRequestMethod("DELETE");
                conn.setConnectTimeout(CLOSE_TIMEOUT_MS);
                conn.setReadTimeout(CLOSE_TIMEOUT_MS);
                conn.setRequestProperty(SESSION_HEADER, sid);
                conn.getResponseCode();
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            LOG.debug("Session termination for " + url + " failed (expected if server doesn't support DELETE): "
                + e.getMessage());
        }
    }

    /**
     * Public entry point for non-initialize requests. Includes the session header
     * when a session is active and supports automatic session recovery on HTTP 404.
     */
    @NotNull
    private JsonObject sendRequest(@NotNull String method, @NotNull JsonObject params) throws IOException {
        return sendRequestInternal(method, params, true, false);
    }

    /**
     * Sends a JSON-RPC 2.0 POST request and returns the parsed response object.
     * Handles optional SSE envelope ({@code data: …}) transparently.
     *
     * @param method               the MCP method name (e.g. "tools/list", "tools/call")
     * @param params               the JSON-RPC params object
     * @param includeSessionHeader whether to include the Mcp-Session-Id header (false for initialize)
     * @param isRetry              true if this is a retry after session recovery — prevents infinite loops
     * @return the parsed JSON-RPC response object
     * @throws IOException on communication or unrecoverable protocol error
     */
    @NotNull
    private JsonObject sendRequestInternal(
        @NotNull String method,
        @NotNull JsonObject params,
        boolean includeSessionHeader,
        boolean isRetry
    ) throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", requestId.getAndIncrement());
        request.addProperty("method", method);
        request.add("params", params);

        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IOException("Unsupported URL scheme: " + scheme
                + " (only http and https are supported). URL: " + url);
        }

        // Capture the session ID to use for this specific request before sending it.
        // This is used both to set the header and to detect genuine session expiry on 404.
        String sentSessionId = sessionId;

        byte[] bodyBytes = GSON.toJson(request).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json, text/event-stream");

            if (includeSessionHeader && sentSessionId != null) {
                conn.setRequestProperty(SESSION_HEADER, sentSessionId);
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int status = conn.getResponseCode();

            // Capture session ID from response headers (may be set on any response, primarily initialize)
            String newSessionId = conn.getHeaderField(SESSION_HEADER);
            if (newSessionId != null && !newSessionId.isBlank()) {
                sessionId = newSessionId;
            }

            // Session expired — only treat 404 as session expiry when we actually sent a session header.
            // Without this guard, a real 404 (wrong URL) would be mistaken for session expiry
            // and trigger a spurious re-initialize + retry.
            if (status == 404 && !isRetry && sentSessionId != null) {
                return handleSessionExpiry(method, params, sentSessionId);
            }

            if (status == 202) {
                return new JsonObject();
            }

            InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (stream == null) return new JsonObject();

            String responseBody;
            try (stream) {
                responseBody = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }

            return JsonParser.parseString(stripSseEnvelope(responseBody)).getAsJsonObject();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Handles session expiry by re-initializing and retrying the original request.
     * Uses a {@link ReentrantLock} so that concurrent threads encountering 404 do not
     * all attempt to re-initialize — only the first one does, and others wait and reuse
     * the new session.
     *
     * @param expiredSessionId the session ID that was sent with the failed request, used for
     *                         a double-check under the lock to skip re-initialization when a
     *                         concurrent thread has already established a new session
     */
    @NotNull
    private JsonObject handleSessionExpiry(
        @NotNull String method,
        @NotNull JsonObject params,
        @NotNull String expiredSessionId
    ) throws IOException {
        LOG.info("Session expired on " + url + " (HTTP 404), re-initializing");
        reinitLock.lock();
        try {
            // Double-check: another thread may have already re-initialized while we waited for the lock.
            // Only re-initialize if the current session is still the expired one (or has been cleared).
            // If a different (newer) session is active, skip re-init and fall through to retry with it.
            if (sessionId == null || expiredSessionId.equals(sessionId)) {
                sessionId = null;
                initialize();
            }
        } finally {
            reinitLock.unlock();
        }
        return sendRequestInternal(method, params, true, true);
    }

    /**
     * Extracts concatenated text from an MCP {@code content} array.
     */
    @NotNull
    private static String extractTextContent(@NotNull JsonObject result) {
        if (!result.has("content")) return "";
        JsonArray content = result.getAsJsonArray("content");
        StringBuilder sb = new StringBuilder();
        for (JsonElement element : content) {
            JsonObject item = element.getAsJsonObject();
            if ("text".equals(item.has("type") ? item.get("type").getAsString() : "")) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(item.has("text") ? item.get("text").getAsString() : "");
            }
        }
        return sb.toString();
    }

    /**
     * Extracts the error message from a JSON-RPC error response.
     */
    @NotNull
    private static String errorMessage(@NotNull JsonObject response) {
        if (response.has("error") && response.get("error").isJsonObject()) {
            JsonObject err = response.getAsJsonObject("error");
            return err.has("message") ? err.get("message").getAsString() : err.toString();
        }
        return "unknown error";
    }

    /**
     * Strips the {@code data: } SSE envelope if present.
     * Some MCP servers return {@code data: {...}\n\n} even for single non-streaming responses.
     */
    @NotNull
    private static String stripSseEnvelope(@NotNull String body) {
        String trimmed = body.trim();
        if (!trimmed.startsWith("data:")) return body;
        for (String line : trimmed.split("\n")) {
            String stripped = line.trim();
            if (stripped.startsWith("data:")) {
                String json = stripped.substring("data:".length()).trim();
                if (!json.isEmpty()) return json;
            }
        }
        return body;
    }
}
