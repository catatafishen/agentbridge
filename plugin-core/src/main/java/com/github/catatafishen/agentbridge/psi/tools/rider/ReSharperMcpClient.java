package com.github.catatafishen.agentbridge.psi.tools.rider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP/JSON-RPC client for the resharper-mcp server (localhost:23741).
 *
 * <p>resharper-mcp is a companion plugin that exposes ReSharper code intelligence via
 * an MCP-over-HTTP server. It runs as a ReSharper SolutionComponent inside Rider's
 * .NET backend, which the JVM frontend cannot reach directly.
 *
 * <p>Only used when running in Rider and resharper-mcp is detected.
 *
 * @see <a href="https://github.com/joshua-light/resharper-mcp">resharper-mcp</a>
 */
public final class ReSharperMcpClient {

    public static final String BASE_URL = "http://127.0.0.1:23741";
    private static final int TIMEOUT_MS = 5_000;
    private static final long AVAILABILITY_CACHE_TTL_MS = 30_000;

    private static final AtomicBoolean cachedAvailable = new AtomicBoolean(false);
    private static final AtomicLong cacheExpiresAt = new AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicInteger nextId = new java.util.concurrent.atomic.AtomicInteger(1);

    private ReSharperMcpClient() {
    }

    /**
     * Returns true if resharper-mcp is reachable at its default port.
     * Result is cached for 30 seconds to avoid per-call latency.
     */
    public static boolean isAvailable() {
        long now = System.currentTimeMillis();
        if (now < cacheExpiresAt.get()) {
            return cachedAvailable.get();
        }
        boolean available = probe();
        cachedAvailable.set(available);
        cacheExpiresAt.set(now + AVAILABILITY_CACHE_TTL_MS);
        return available;
    }

    /**
     * Invalidates the cached availability so the next call probes again.
     */
    public static void invalidateCache() {
        cacheExpiresAt.set(0);
    }

    /**
     * Calls a resharper-mcp tool via JSON-RPC and returns the text content of the result.
     *
     * @param toolName  The MCP tool name (e.g. "search_symbol")
     * @param arguments The tool arguments as a JsonObject
     * @return The text content from the tool response, or an error string
     */
    public static String callTool(String toolName, JsonObject arguments) {
        JsonObject request = buildRequest(toolName, arguments);
        try {
            String responseBody = post(request.toString());
            return extractTextContent(responseBody);
        } catch (IOException e) {
            invalidateCache();
            return "Error: resharper-mcp unavailable — " + e.getMessage();
        }
    }

    private static JsonObject buildRequest(String toolName, JsonObject arguments) {
        JsonObject params = new JsonObject();
        params.addProperty("name", toolName);
        params.add("arguments", arguments);

        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("method", "tools/call");
        request.addProperty("id", nextId.getAndIncrement());
        request.add("params", params);
        return request;
    }

    private static boolean probe() {
        JsonObject ping = new JsonObject();
        ping.addProperty("jsonrpc", "2.0");
        ping.addProperty("method", "ping");
        ping.addProperty("id", 0);
        try {
            post(ping.toString());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String post(String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(BASE_URL).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) return "{}";
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Extracts the text content from an MCP tools/call response.
     *
     * <p>Expected format: {@code {"result":{"content":[{"type":"text","text":"..."}]}}}
     */
    private static String extractTextContent(String responseBody) {
        try {
            JsonObject response = JsonParser.parseString(responseBody).getAsJsonObject();
            if (response.has("error")) {
                return "Error: " + response.get("error").toString();
            }
            if (!response.has("result")) return responseBody;
            JsonObject result = response.getAsJsonObject("result");
            return extractFromResult(result, responseBody);
        } catch (Exception e) {
            return responseBody;
        }
    }

    private static String extractFromResult(JsonObject result, String fallback) {
        JsonArray content = result.getAsJsonArray("content");
        if (content == null || content.isEmpty()) return "(empty response)";

        if (result.has("isError") && result.get("isError").getAsBoolean()) {
            return "Error: " + content.get(0).getAsJsonObject().get("text").getAsString();
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.size(); i++) {
            JsonObject item = content.get(i).getAsJsonObject();
            if (item.has("text")) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(item.get("text").getAsString());
            }
        }
        return sb.isEmpty() ? fallback : sb.toString();
    }
}
