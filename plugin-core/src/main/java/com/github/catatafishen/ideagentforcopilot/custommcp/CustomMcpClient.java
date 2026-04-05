package com.github.catatafishen.ideagentforcopilot.custommcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP JSON-RPC 2.0 MCP client for communicating with an external MCP server
 * using the streamable HTTP transport (single POST endpoint).
 * All connections are short-lived (created and closed per request).
 */
public final class CustomMcpClient {

    private static final Logger LOG = Logger.getInstance(CustomMcpClient.class);
    private static final Gson GSON = new Gson();
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final String PROTOCOL_VERSION = "2025-11-25";

    private final String url;
    private final AtomicInteger requestId = new AtomicInteger(1);

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
     * Sends the MCP {@code initialize} handshake.
     * Must be called once before {@link #listTools()}.
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
        clientInfo.addProperty("name", "ide-agent-for-copilot");
        clientInfo.addProperty("version", "1.0");
        params.add("clientInfo", clientInfo);

        JsonObject response = sendRequest("initialize", params);
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
     * Sends a JSON-RPC 2.0 POST request and returns the parsed response object.
     * Handles optional SSE envelope ({@code data: …}) transparently.
     */
    @NotNull
    private JsonObject sendRequest(@NotNull String method, @NotNull JsonObject params) throws IOException {
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

        byte[] bodyBytes = GSON.toJson(request).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json, text/event-stream");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int status = conn.getResponseCode();
            if (status == 202) {
                // Notification accepted — no response body
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
