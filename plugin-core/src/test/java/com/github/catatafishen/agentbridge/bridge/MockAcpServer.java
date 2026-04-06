package com.github.catatafishen.agentbridge.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Mock ACP server that simulates the copilot-language-server stdin/stdout protocol.
 * Used for integration testing the AcpClient without needing a real Copilot backend.
 * <p>
 * Each test can register handlers for specific methods. The server reads JSON-RPC
 * messages from its stdin pipe and writes responses/notifications to stdout.
 */
public class MockAcpServer implements Closeable {

    private final PipedOutputStream clientToServer = new PipedOutputStream();
    private final PipedInputStream serverFromClient;
    private final PipedOutputStream serverToClient = new PipedOutputStream();
    private final PipedInputStream clientFromServer;

    private final Map<String, Function<JsonObject, JsonObject>> requestHandlers = new ConcurrentHashMap<>();
    private final List<JsonObject> receivedRequests = new CopyOnWriteArrayList<>();
    private Thread readerThread;

    private volatile boolean running = true;

    public MockAcpServer() throws IOException {
        serverFromClient = new PipedInputStream(clientToServer);
        clientFromServer = new PipedInputStream(serverToClient);

        // Default handlers for common methods
        registerHandler("initialize", this::handleInitialize);
        registerHandler("initialized", params -> null); // notification, no response
        registerHandler("session/new", this::handleNewSession);
        registerHandler("session/set_model", this::handleSetModel);
    }

    /**
     * Register a handler for a specific JSON-RPC method.
     */
    public void registerHandler(String method, Function<JsonObject, JsonObject> handler) {
        requestHandlers.put(method, handler);
    }

    /**
     * Start the mock server's read loop.
     */
    public void start() {
        readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(serverFromClient))) {
                String line;
                while (running && (line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    try {
                        JsonObject msg = JsonParser.parseString(line).getAsJsonObject();
                        receivedRequests.add(msg);

                        String method = msg.has("method") ? msg.get("method").getAsString() : null;
                        boolean hasId = msg.has("id") && !msg.get("id").isJsonNull();

                        if (method != null && requestHandlers.containsKey(method)) {
                            JsonObject result = requestHandlers.get(method).apply(
                                msg.has("params") ? msg.getAsJsonObject("params") : new JsonObject());

                            if (hasId && result != null) {
                                JsonObject response = new JsonObject();
                                response.addProperty("jsonrpc", "2.0");
                                response.add("id", msg.get("id"));
                                response.add("result", result);
                                sendMessage(response);
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("MockAcpServer error: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                if (running) System.err.println("MockAcpServer reader ended: " + e.getMessage());
            }
        }, "mock-acp-server");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * Send a JSON-RPC message to the client (notification or agent-to-client request).
     */
    public synchronized void sendMessage(JsonObject msg) throws IOException {
        String json = new Gson().toJson(msg);
        serverToClient.write((json + "\n").getBytes());
        serverToClient.flush();
    }

    /**
     * Send a notification (no id, no response expected).
     */
    public void sendNotification(String method, JsonObject params) throws IOException {
        JsonObject msg = new JsonObject();
        msg.addProperty("jsonrpc", "2.0");
        msg.addProperty("method", method);
        msg.add("params", params);
        sendMessage(msg);
    }

    /**
     * Send an agent-to-client request (has id, expects response).
     */
    public void sendAgentRequest(long id, String method, JsonObject params) throws IOException {
        JsonObject msg = new JsonObject();
        msg.addProperty("jsonrpc", "2.0");
        msg.addProperty("id", id);
        msg.addProperty("method", method);
        msg.add("params", params);
        sendMessage(msg);
    }

    /**
     * Get all requests received from the client.
     */
    public List<JsonObject> getReceivedRequests() {
        return Collections.unmodifiableList(receivedRequests);
    }

    /**
     * Get requests received for a specific method.
     */
    public List<JsonObject> getReceivedRequests(String method) {
        return receivedRequests.stream()
            .filter(r -> method.equals(r.has("method") ? r.get("method").getAsString() : ""))
            .toList();
    }

    /**
     * Get the output stream that acts as the mock process's stdin (client writes here).
     */
    public OutputStream getProcessStdin() {
        return clientToServer;
    }

    /**
     * Get the input stream that acts as the mock process's stdout (client reads here).
     */
    public InputStream getProcessStdout() {
        return clientFromServer;
    }

    @Override
    public void close() {
        running = false;
        try {
            clientToServer.close();
        } catch (IOException ignored) { // streams closing on shutdown
        }
        try {
            serverToClient.close();
        } catch (IOException ignored) { // streams closing on shutdown
        }
        if (readerThread != null) readerThread.interrupt();
    }

    // --- Default handlers ---

    private JsonObject handleInitialize(@SuppressWarnings("unused") JsonObject params) {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", 1);

        JsonObject agentInfo = new JsonObject();
        agentInfo.addProperty("name", "mock-copilot");
        agentInfo.addProperty("version", "1.0.0");
        result.add("agentInfo", agentInfo);

        JsonArray authMethods = new JsonArray();
        JsonObject auth = new JsonObject();
        auth.addProperty("id", "mock-auth");
        auth.addProperty("name", "Mock Auth");
        auth.addProperty("description", "Mock authentication for testing");
        authMethods.add(auth);
        result.add("authMethods", authMethods);

        result.add("agentCapabilities", new JsonObject());
        return result;
    }

    private JsonObject handleNewSession(@SuppressWarnings("unused") JsonObject params) {
        JsonObject result = new JsonObject();
        result.addProperty("sessionId", "mock-session-" + UUID.randomUUID());

        // Include models
        JsonObject models = new JsonObject();
        JsonArray availableModels = new JsonArray();

        JsonObject gpt = new JsonObject();
        gpt.addProperty("modelId", "gpt-4.1");
        gpt.addProperty("name", "GPT-4.1");
        gpt.addProperty("description", "Mock GPT model");
        JsonObject gptMeta = new JsonObject();
        gptMeta.addProperty("copilotUsage", "1x");
        gpt.add("_meta", gptMeta);
        availableModels.add(gpt);

        JsonObject claude = new JsonObject();
        claude.addProperty("modelId", "claude-sonnet-4");
        claude.addProperty("name", "Claude Sonnet 4");
        claude.addProperty("description", "Mock Claude model");
        JsonObject claudeMeta = new JsonObject();
        claudeMeta.addProperty("copilotUsage", "1x");
        claude.add("_meta", claudeMeta);
        availableModels.add(claude);

        models.add("availableModels", availableModels);
        models.addProperty("currentModelId", "gpt-4.1");
        result.add("models", models);

        return result;
    }

    private JsonObject handleSetModel(@SuppressWarnings("unused") JsonObject params) {
        JsonObject result = new JsonObject();
        result.addProperty("status", "ok");
        return result;
    }

    /**
     * Helper to build a session/update notification with an agent_message_chunk.
     */
    public static JsonObject buildMessageChunk(String sessionId, String text) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        JsonObject update = new JsonObject();
        update.addProperty("sessionUpdate", "agent_message_chunk");
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);
        update.add("content", content);
        params.add("update", update);
        return params; // caller wraps in notification
    }

    /**
     * Helper to build a session/update notification with a tool_call.
     */
    public static JsonObject buildToolCall(String sessionId, String toolCallId, String title, String kind, String status) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        JsonObject update = new JsonObject();
        update.addProperty("sessionUpdate", "tool_call");
        update.addProperty("toolCallId", toolCallId);
        update.addProperty("title", title);
        update.addProperty("kind", kind);
        update.addProperty("status", status);
        params.add("update", update);
        return params;
    }

    /**
     * Helper to build a request_permission request from the agent.
     */
    public static JsonObject buildRequestPermission(String sessionId, String toolCallId) {
        return buildRequestPermission(sessionId, toolCallId, "other", "Tool call");
    }

    /**
     * Helper to build a request_permission request with a specific kind and title.
     */
    public static JsonObject buildRequestPermission(String sessionId, String toolCallId, String kind, String title) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("toolCallId", toolCallId);
        toolCall.addProperty("kind", kind);
        toolCall.addProperty("title", title);
        params.add("toolCall", toolCall);

        params.add("options", buildPermissionOptions());

        return params;
    }

    private static JsonArray buildPermissionOptions() {
        JsonArray options = new JsonArray();
        JsonObject allowOnce = new JsonObject();
        allowOnce.addProperty("optionId", "allow_once");
        allowOnce.addProperty("name", "Allow once");
        allowOnce.addProperty("kind", "allow_once");
        options.add(allowOnce);
        JsonObject rejectOnce = new JsonObject();
        rejectOnce.addProperty("optionId", "reject_once");
        rejectOnce.addProperty("name", "Reject");
        rejectOnce.addProperty("kind", "reject_once");
        options.add(rejectOnce);
        return options;
    }
}
