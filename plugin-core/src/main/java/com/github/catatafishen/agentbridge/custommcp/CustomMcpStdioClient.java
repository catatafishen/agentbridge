package com.github.catatafishen.agentbridge.custommcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP client that communicates with a local subprocess via stdin/stdout
 * (MCP stdio transport). Each JSON-RPC message is a single line (delimited
 * by {@code \n}) on each stream.
 */
public final class CustomMcpStdioClient implements AutoCloseable, McpToolCaller {

    private static final Logger LOG = Logger.getInstance(CustomMcpStdioClient.class);
    private static final Gson GSON = new Gson();
    private static final String PROTOCOL_VERSION = "2025-03-26";
    private static final int INIT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private final String command;
    private final List<String> args;
    private final Map<String, String> environment;
    private final AtomicInteger requestId = new AtomicInteger(1);

    private Process process;
    private Writer stdin;
    private BufferedReader stdout;
    private BufferedReader stderr;
    private volatile boolean closed;

    public CustomMcpStdioClient(
        @NotNull String command,
        @NotNull List<String> args,
        @NotNull Map<String, String> environment
    ) {
        this.command = command;
        this.args = args;
        this.environment = environment;
    }

    /**
     * Starts the subprocess and sends the MCP {@code initialize} handshake.
     * Must be called once before {@link #listTools()} or {@link #callTool}.
     *
     * @throws IOException if the process cannot be started or initialization fails
     */
    public void initialize() throws IOException {
        if (closed) throw new IOException("Client is closed");

        List<String> fullCommand = new ArrayList<>();
        fullCommand.add(command);
        fullCommand.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(fullCommand);
        pb.redirectErrorStream(false);
        Map<String, String> env = pb.environment();
        env.putAll(environment);

        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("Failed to start MCP server process: " + command + " " + args, e);
        }

        stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        stderr = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));

        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL_VERSION);
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        params.add("capabilities", capabilities);
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "agentbridge");
        clientInfo.addProperty("version", "1.0");
        params.add("clientInfo", clientInfo);

        JsonObject response = sendRequestInternal("initialize", params, INIT_TIMEOUT_MS);
        CustomMcpClient.parseInitializeResult(response);
        sendJsonLine("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
    }

    /**
     * Retrieves the list of tools from the stdio MCP server.
     *
     * @return list of discovered tools; empty if the server reports none
     * @throws IOException on communication or protocol error
     */
    @NotNull
    public List<CustomMcpClient.ToolInfo> listTools() throws IOException {
        JsonObject response = sendRequest("tools/list", new JsonObject());
        return CustomMcpClient.parseToolList(response);
    }

    /**
     * Calls a tool on the stdio MCP server with the given arguments.
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
                return "Error from MCP server: " + CustomMcpClient.errorMessage(response);
            }
            if (!response.has("result")) return "";

            JsonObject result = response.getAsJsonObject("result");
            boolean isError = result.has("isError") && result.get("isError").getAsBoolean();
            String text = CustomMcpClient.extractTextContent(result);
            return isError ? "Error: " + text : text;
        } catch (IOException e) {
            LOG.warn("Failed to call tool '" + toolName + "' on stdio server " + command, e);
            return "Failed to call tool on " + command + ": " + e.getMessage();
        }
    }

    /**
     * Closes the stdin, stdout, and stderr streams, then destroys the subprocess.
     * Waits up to 2 seconds for the process to exit before forcibly destroying it.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;

        try {
            if (stdin != null) stdin.close();
        } catch (IOException ignored) {
        }
        try {
            if (stdout != null) stdout.close();
        } catch (IOException ignored) {
        }
        try {
            if (stderr != null) stderr.close();
        } catch (IOException ignored) {
        }
        if (process != null) {
            process.destroy();
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    @NotNull
    private JsonObject sendRequest(@NotNull String method, @NotNull JsonObject params) throws IOException {
        return sendRequestInternal(method, params, READ_TIMEOUT_MS);
    }

    @NotNull
    private synchronized JsonObject sendRequestInternal(
        @NotNull String method,
        @NotNull JsonObject params,
        int timeoutMs
    ) throws IOException {
        if (closed) throw new IOException("Client is closed");

        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", requestId.getAndIncrement());
        request.addProperty("method", method);
        request.add("params", params);

        sendJsonLine(GSON.toJson(request));

        // Drain any stderr lines (MCP servers log there) to avoid deadlock
        drainStderr();

        // Read response line from stdout
        String responseLine;
        try {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                if (!stdout.ready()) {
                    if (System.currentTimeMillis() >= deadline) {
                        throw new IOException("Read timeout after " + timeoutMs + "ms for method " + method);
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while reading response", e);
                    }
                    continue;
                }
                responseLine = stdout.readLine();
                if (responseLine == null) {
                    throw new IOException("Process closed stdout unexpectedly");
                }
                break;
            }
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("Read timeout")) throw e;
            throw new IOException("Failed to read from MCP server process", e);
        }

        if (responseLine.isBlank()) {
            throw new IOException("Empty response from MCP server");
        }

        return JsonParser.parseString(responseLine).getAsJsonObject();
    }

    private void sendJsonLine(@NotNull String json) throws IOException {
        stdin.write(json);
        stdin.write('\n');
        stdin.flush();
    }

    /**
     * Reads available lines from stderr (non-blocking) and logs them.
     * Prevents process deadlock when stderr buffer fills up.
     */
    private void drainStderr() {
        try {
            while (stderr.ready()) {
                String line = stderr.readLine();
                if (line != null) {
                    LOG.debug("[stdio-mcp stderr] " + line);
                }
            }
        } catch (IOException ignored) {
        }
    }
}
