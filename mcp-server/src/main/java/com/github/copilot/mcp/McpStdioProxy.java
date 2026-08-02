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
 * <p>McpHttpServer's Streamable-HTTP transport requires every non-{@code initialize} request to
 * carry the {@code Mcp-Session-Id} it handed back on {@code initialize} (see
 * {@code McpSessionRegistry}), and rejects unknown/expired ones with HTTP 404. This proxy
 * captures that header from the {@code initialize} response and echoes it on every later
 * request. If the session has since expired server-side (idle timeout, or the HTTP server was
 * restarted), it transparently re-initializes and retries once — the agent on the other end of
 * stdin/stdout never sees an HTTP session at all, so it has no way to recover on its own.</p>
 *
 * <p>Usage: {@code java -jar mcp-server.jar --port <N>}</p>
 */
public class McpStdioProxy {

    private static final Logger LOG = Logger.getLogger(McpStdioProxy.class.getName());
    private static final String SESSION_ID_HEADER = "Mcp-Session-Id";
    private static final int HTTP_SESSION_EXPIRED = 404;
    private static final int CONNECT_TIMEOUT_MS = 500;
    private static final int READ_TIMEOUT_MS = 600_000;
    private static final int RETRY_DELAY_MS = 500;
    private static final int MAX_RETRIES = 10;

    @SuppressWarnings("java:S106") // System.out is intentional — MCP protocol requires stdout
    public static void main(String[] args) {
        int port = parsePort(args);
        if (port <= 0) {
            System.err.println("Usage: java -jar mcp-server.jar --port <port>");
            System.exit(1);
        }

        String mcpUrl = buildMcpUrl(port);
        LOG.log(Level.INFO, "MCP stdio proxy starting, forwarding to {0}", mcpUrl);

        waitForServer(port);

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (isBlankLine(line)) continue;

                processMessage(mcpUrl, line);
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Stdin read error", e);
        }
    }

    /**
     * The {@code Mcp-Session-Id} minted by the most recent {@code initialize} call, echoed on
     * every later request. The stdin/stdout loop in {@link #main} is strictly sequential (one
     * message is fully forwarded before the next is read), so a plain static field is safe here.
     */
    private static volatile String sessionId;

    /**
     * The raw {@code initialize} line most recently read from stdin, cached so a mid-stream
     * session expiry (idle timeout, or the HTTP server being restarted) can be recovered from by
     * replaying it — see {@link #processMessage}.
     */
    private static volatile String lastInitializeMessage;

    @SuppressWarnings("java:S106") // System.out is intentional — MCP protocol requires stdout
    private static void processMessage(String mcpUrl, String line) {
        if (isInitializeMessage(line)) {
            lastInitializeMessage = line;
        }
        try {
            ForwardResult result = forwardToServer(mcpUrl, line);
            if (result.status() == HTTP_SESSION_EXPIRED
                && lastInitializeMessage != null
                && !isInitializeMessage(line)) {
                // The MCP Streamable-HTTP spec says a client that gets a 404 for its session
                // should recover by re-initializing. The agent on the other end of this stdio
                // pipe never sees Mcp-Session-Id at all, so it has no way to do that itself —
                // replay the cached `initialize` call here to mint a fresh session, then retry
                // the original request once against it.
                LOG.log(Level.INFO, "MCP session expired; re-initializing and retrying");
                forwardToServer(mcpUrl, lastInitializeMessage);
                result = forwardToServer(mcpUrl, line);
            }
            if (shouldForwardResponse(result.body())) {
                System.out.write(result.body().getBytes(StandardCharsets.UTF_8));
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

    /**
     * Builds the MCP endpoint URL for a given port.
     * Pure function — no I/O.
     */
    static String buildMcpUrl(int port) {
        return "http://127.0.0.1:" + port + "/mcp";
    }

    /**
     * Builds the health-check endpoint URL for a given port.
     * Pure function — no I/O.
     */
    static String buildHealthUrl(int port) {
        return "http://127.0.0.1:" + port + "/health";
    }

    /**
     * Returns {@code true} if a line (after trimming) is blank and should be
     * skipped in the message-reading loop.
     * Pure function — no I/O.
     */
    static boolean isBlankLine(String line) {
        return line == null || line.isEmpty();
    }

    /**
     * Returns {@code true} if the server response should be forwarded to stdout.
     * Notifications (HTTP 202) produce a null response; those are not forwarded.
     * Pure function — no I/O.
     */
    static boolean shouldForwardResponse(String response) {
        return response != null && !response.isEmpty();
    }

    private static void waitForServer(int port) {
        String healthUrl = buildHealthUrl(port);
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
                // Antipattern (DESIGN-PRINCIPLES.md): Thread.sleep blocks a thread. Kept here because
                // McpStdioProxy is a standalone process without IntelliJ platform APIs available.
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        LOG.log(Level.WARNING, "MCP server not reachable on port {0} after {1} retries",
            new Object[]{port, MAX_RETRIES});
    }

    /**
     * Result of forwarding one JSON-RPC message to the HTTP server: the HTTP status code and the
     * response body (null for a 202-Accepted notification with no body). Exposing the status
     * lets the caller detect an expired session (404) and self-heal — see
     * {@link #processMessage}.
     */
    private record ForwardResult(int status, String body) {
    }

    private static ForwardResult forwardToServer(String mcpUrl, String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(mcpUrl).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "application/json");
        if (sessionId != null) {
            conn.setRequestProperty(SESSION_ID_HEADER, sessionId);
        }

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        captureSessionId(conn);
        if (status == 202) {
            // Notification accepted — no response body
            return new ForwardResult(status, null);
        }
        if (status == 200) {
            try (InputStream is = conn.getInputStream()) {
                return new ForwardResult(status, new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

        // Error — read error stream
        try (InputStream es = conn.getErrorStream()) {
            if (es != null) {
                return new ForwardResult(status, new String(es.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        throw new IOException("MCP server returned HTTP " + status);
    }

    /**
     * Captures the {@code Mcp-Session-Id} response header, if present, so it can be echoed on
     * subsequent requests. Pure side effect on {@link #sessionId} — no return value, since the
     * caller (the HTTP connection) is already an I/O boundary.
     */
    private static void captureSessionId(HttpURLConnection conn) {
        String header = conn.getHeaderField(SESSION_ID_HEADER);
        if (header != null && !header.isBlank()) {
            sessionId = header;
        }
    }

    /**
     * Detects whether a raw JSON-RPC message is an {@code initialize} request, using the same
     * lightweight scan-to-delimiter approach as {@link #extractJsonRpcId} (no JSON library on
     * the classpath — this proxy is intentionally dependency-free). Pure function — no I/O.
     */
    static boolean isInitializeMessage(String message) {
        int methodIdx = message.indexOf("\"method\"");
        if (methodIdx < 0) return false;

        int colon = message.indexOf(':', methodIdx);
        if (colon < 0) return false;

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
        return "\"initialize\"".equals(message.substring(start, end).trim());
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
