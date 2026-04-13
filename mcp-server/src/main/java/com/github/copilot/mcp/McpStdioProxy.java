package com.github.copilot.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thin stdio-to-HTTP proxy for the MCP protocol.
 * Reads MCP JSON-RPC messages from stdin, forwards them to the in-IDE McpHttpServer
 * via HTTP POST, and writes responses back to stdout.
 *
 * <p>This process is spawned by ACP-compatible agents (Copilot CLI, Claude, Kiro, etc.)
 * via {@code --additional-mcp-config}. The IDE's McpHttpServer handles all protocol
 * logic, tool schemas, and tool execution — this proxy is just a transport adapter.</p>
 *
 * <p>Usage: {@code java -jar mcp-server.jar --port <N>}</p>
 */
public class McpStdioProxy {

    private static final Logger LOG = Logger.getLogger(McpStdioProxy.class.getName());
    private static final int CONNECT_TIMEOUT_MS = 500;
    private static final int READ_TIMEOUT_MS = 180_000;
    private static final int RETRY_DELAY_MS = 500;
    private static final int MAX_RETRIES = 10;

    @SuppressWarnings("java:S106") // System.out is intentional — MCP protocol requires stdout
    public static void main(String[] args) {
        int port = parsePort(args);
        if (port <= 0) {
            System.err.println("Usage: java -jar mcp-server.jar --port <port>");
            System.exit(1);
        }

        String mcpUrl = "http://127.0.0.1:" + port + "/mcp";
        LOG.log(Level.INFO, "MCP stdio proxy starting, forwarding to {0}", mcpUrl);

        waitForServer(port);

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                processMessage(mcpUrl, line);
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Stdin read error", e);
        }
    }

    @SuppressWarnings("java:S106") // System.out is intentional — MCP protocol requires stdout
    private static void processMessage(String mcpUrl, String line) {
        try {
            String response = forwardToServer(mcpUrl, line);
            if (response != null && !response.isEmpty()) {
                System.out.write(response.getBytes(StandardCharsets.UTF_8));
                System.out.write('\n');
                System.out.flush();
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to forward MCP message", e);
            writeErrorResponse(line, e.getMessage());
        }
    }

    static int parsePort(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--port".equals(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static void waitForServer(int port) {
        String healthUrl = "http://127.0.0.1:" + port + "/health";
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(healthUrl).toURL().openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(3000);
                if (conn.getResponseCode() == 200) {
                    LOG.log(Level.INFO, "MCP server is ready on port {0}", port);
                    return;
                }
            } catch (IOException ignored) {
                // Server not ready yet
            }
            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        LOG.log(Level.WARNING, "MCP server not reachable on port {0} after {1} retries",
            new Object[]{port, MAX_RETRIES});
    }

    private static String forwardToServer(String mcpUrl, String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(mcpUrl).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "application/json");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        if (status == 202) {
            // Notification accepted — no response body
            return null;
        }
        if (status == 200) {
            try (InputStream is = conn.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        // Error — read error stream
        try (InputStream es = conn.getErrorStream()) {
            if (es != null) {
                return new String(es.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("MCP server returned HTTP " + status);
    }

    /**
     * Attempts to extract the request id from a JSON-RPC message and send
     * an error response back to stdout.
     */
    @SuppressWarnings("java:S106")
    static void writeErrorResponse(String originalMessage, String errorMessage) {
        try {
            String response = buildErrorResponse(originalMessage, errorMessage);
            System.out.write(response.getBytes(StandardCharsets.UTF_8));
            System.out.write('\n');
            System.out.flush();
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to write error response", e);
        }
    }

    /**
     * Builds a JSON-RPC error response by extracting the request id from the original message.
     * Pure function — no I/O.
     */
    static String buildErrorResponse(String originalMessage, String errorMessage) {
        String id = extractJsonRpcId(originalMessage);
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id
            + ",\"error\":{\"code\":-32603,\"message\":\""
            + errorMessage.replace("\"", "'") + "\"}}";
    }

    /**
     * Extracts the JSON-RPC "id" value from a raw JSON message without full parsing.
     * Returns "null" if not found.
     * Pure function — no I/O.
     */
    static String extractJsonRpcId(String message) {
        int idIdx = message.indexOf("\"id\"");
        if (idIdx < 0) return "null";

        int colon = message.indexOf(':', idIdx);
        if (colon < 0) return "null";

        int start = colon + 1;
        while (start < message.length() && Character.isWhitespace(message.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < message.length()
            && message.charAt(end) != ','
            && message.charAt(end) != '}') {
            end++;
        }
        return message.substring(start, end).trim();
    }
}
