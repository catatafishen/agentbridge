package com.github.catatafishen.agentbridge.acp.client;

import com.github.catatafishen.agentbridge.settings.ShellEnvironment;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Handles ACP terminal methods: {@code terminal/create}, {@code terminal/output},
 * {@code terminal/wait_for_exit}, {@code terminal/kill}, and {@code terminal/release}.
 * <p>
 * Per the ACP spec, terminals represent shell command executions that the agent
 * can create and manage. Each terminal has a unique ID, captures output, and
 * tracks exit status.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/terminals">ACP Terminals</a>
 */
final class AcpTerminalHandler {

    private static final Logger LOG = Logger.getInstance(AcpTerminalHandler.class);
    private static final int DEFAULT_OUTPUT_BYTE_LIMIT = 1_048_576; // 1 MB
    private static final String OUTPUT_BYTE_LIMIT_KEY = "outputByteLimit";
    private static final String TERMINAL_ID_KEY = "terminalId";

    private final Project project;
    private final Map<String, ManagedTerminal> terminals = new ConcurrentHashMap<>();

    AcpTerminalHandler(Project project) {
        this.project = project;
    }

    /**
     * {@code terminal/create} — start a command in a new terminal.
     *
     * @return result with {@code terminalId}
     */
    JsonObject create(@NotNull JsonObject params) throws IOException {
        String command = getRequiredString(params, "command");
        String[] cmdArray = buildCommandArray(command, getStringArray(params, "args"));
        int outputByteLimit = getOutputByteLimit(params);

        ProcessBuilder pb = createProcessBuilder(params, cmdArray);
        String terminalId = "term_" + UUID.randomUUID().toString().substring(0, 12);
        Process process = pb.start();

        ManagedTerminal terminal = new ManagedTerminal(terminalId, process, outputByteLimit);
        terminal.startOutputCapture();
        terminals.put(terminalId, terminal);

        LOG.info("Created terminal " + terminalId + ": " + String.join(" ", cmdArray));

        JsonObject result = new JsonObject();
        result.addProperty(TERMINAL_ID_KEY, terminalId);
        return result;
    }

    private ProcessBuilder createProcessBuilder(@NotNull JsonObject params, String[] cmdArray) {
        ProcessBuilder pb = new ProcessBuilder(cmdArray);
        pb.redirectErrorStream(true);
        String cwd = getWorkingDirectory(params);
        if (cwd != null) {
            pb.directory(new File(cwd));
        }
        applyEnvironmentParams(params, pb.environment());
        pb.environment().putAll(ShellEnvironment.getEnvironment());
        return pb;
    }

    private String getWorkingDirectory(@NotNull JsonObject params) {
        return params.has("cwd") && params.get("cwd").isJsonPrimitive()
            ? params.get("cwd").getAsString() : project.getBasePath();
    }

    private static int getOutputByteLimit(@NotNull JsonObject params) {
        return params.has(OUTPUT_BYTE_LIMIT_KEY) && params.get(OUTPUT_BYTE_LIMIT_KEY).isJsonPrimitive()
            ? params.get(OUTPUT_BYTE_LIMIT_KEY).getAsInt() : DEFAULT_OUTPUT_BYTE_LIMIT;
    }

    private static String[] buildCommandArray(@NotNull String command, String[] args) {
        String[] cmdArray = new String[1 + args.length];
        cmdArray[0] = command;
        System.arraycopy(args, 0, cmdArray, 1, args.length);
        return cmdArray;
    }

    private static void applyEnvironmentParams(@NotNull JsonObject params, Map<String, String> env) {
        if (!params.has("env") || !params.get("env").isJsonArray()) {
            return;
        }
        for (JsonElement el : params.getAsJsonArray("env")) {
            addEnvironmentVariable(el, env);
        }
    }

    private static void addEnvironmentVariable(@NotNull JsonElement el, Map<String, String> env) {
        if (!el.isJsonObject()) {
            return;
        }
        JsonObject envVar = el.getAsJsonObject();
        String name = getStringOrNull(envVar, "name");
        String value = getStringOrNull(envVar, "value");
        if (name != null && value != null) {
            env.put(name, value);
        }
    }

    /**
     * {@code terminal/output} — get current output and optional exit status.
     */
    JsonObject output(@NotNull JsonObject params) {
        String terminalId = getRequiredString(params, TERMINAL_ID_KEY);
        ManagedTerminal terminal = requireTerminal(terminalId);

        JsonObject result = new JsonObject();
        result.addProperty("output", terminal.getOutput());
        result.addProperty("truncated", terminal.isTruncated());

        if (!terminal.process.isAlive()) {
            JsonObject exitStatus = new JsonObject();
            exitStatus.addProperty("exitCode", terminal.process.exitValue());
            exitStatus.add("signal", null);
            result.add("exitStatus", exitStatus);
        }

        return result;
    }

    private static final long WAIT_FOR_EXIT_TIMEOUT_SECONDS = 30L * 60; // 30 minutes

    /**
     * {@code terminal/wait_for_exit} — block until the command completes.
     * Uses a 30-minute timeout to prevent permanently blocking the transport
     * if a process hangs (e.g. tail -f, zombie process).
     */
    JsonObject waitForExit(@NotNull JsonObject params) throws InterruptedException {
        String terminalId = getRequiredString(params, TERMINAL_ID_KEY);
        ManagedTerminal terminal = requireTerminal(terminalId);

        boolean exited = terminal.process.waitFor(WAIT_FOR_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            terminal.process.destroyForcibly();
            terminal.stopOutputCapture();
            terminals.remove(terminalId);
            LOG.warn("terminal/wait_for_exit timed out after " + WAIT_FOR_EXIT_TIMEOUT_SECONDS
                + "s for terminal " + terminalId + " — process forcibly destroyed and resources released");
            throw new IllegalStateException("Process timed out after "
                + WAIT_FOR_EXIT_TIMEOUT_SECONDS + " seconds and was killed (terminal " + terminalId + ")");
        }

        terminal.awaitOutputCapture();

        JsonObject result = new JsonObject();
        result.addProperty("exitCode", terminal.process.exitValue());
        result.add("signal", null);
        return result;
    }

    /**
     * {@code terminal/kill} — terminate the command without releasing.
     */
    JsonObject kill(@NotNull JsonObject params) {
        String terminalId = getRequiredString(params, TERMINAL_ID_KEY);
        ManagedTerminal terminal = requireTerminal(terminalId);

        if (terminal.process.isAlive()) {
            terminal.process.destroy();
            LOG.info("Killed terminal " + terminalId);
        }

        return new JsonObject();
    }

    /**
     * {@code terminal/release} — kill if running and release all resources.
     */
    JsonObject release(@NotNull JsonObject params) {
        String terminalId = getRequiredString(params, TERMINAL_ID_KEY);
        ManagedTerminal terminal = terminals.remove(terminalId);
        if (terminal == null) {
            throw new IllegalArgumentException("Unknown terminal: " + terminalId);
        }

        if (terminal.process.isAlive()) {
            terminal.process.destroyForcibly();
        }
        terminal.stopOutputCapture();
        LOG.info("Released terminal " + terminalId);

        return new JsonObject();
    }

    /**
     * Releases all tracked terminals. Called from {@link AcpClient#stop()}.
     */
    void releaseAll() {
        for (var entry : terminals.entrySet()) {
            ManagedTerminal terminal = entry.getValue();
            if (terminal.process.isAlive()) {
                terminal.process.destroyForcibly();
            }
            terminal.stopOutputCapture();
        }
        terminals.clear();
    }

    private ManagedTerminal requireTerminal(String terminalId) {
        ManagedTerminal terminal = terminals.get(terminalId);
        if (terminal == null) {
            throw new IllegalArgumentException("Unknown terminal: " + terminalId);
        }
        return terminal;
    }

    // ── Parameter parsing helpers ─────────────────────────────────────────

    private static String getRequiredString(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        return obj.get(key).getAsString();
    }

    @Nullable
    private static String getStringOrNull(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private static String[] getStringArray(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            return new String[0];
        }
        var arr = obj.getAsJsonArray(key);
        String[] result = new String[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            result[i] = arr.get(i).getAsString();
        }
        return result;
    }

    // ── Managed terminal ─────────────────────────────────────────────────

    /**
     * Tracks a running process with its captured output.
     */
    private static final class ManagedTerminal {
        final String id;
        final Process process;
        final int outputByteLimit;

        private final StringBuilder outputBuffer = new StringBuilder();
        private volatile boolean truncated;
        private volatile Thread captureThread;

        ManagedTerminal(String id, Process process, int outputByteLimit) {
            this.id = id;
            this.process = process;
            this.outputByteLimit = outputByteLimit;
        }

        void startOutputCapture() {
            captureThread = Thread.ofPlatform().name("acp-terminal-" + id).start(() -> {
                try (InputStream is = process.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                        appendOutput(chunk);
                    }
                } catch (IOException e) {
                    if (process.isAlive()) {
                        LOG.warn("Output capture error for terminal " + id + ": " + e.getMessage());
                    }
                }
            });
        }

        void stopOutputCapture() {
            Thread t = captureThread;
            if (t != null) {
                t.interrupt();
                captureThread = null;
            }
        }

        void awaitOutputCapture() throws InterruptedException {
            Thread t = captureThread;
            if (t != null) {
                t.join();
            }
        }

        synchronized void appendOutput(String chunk) {
            outputBuffer.append(chunk);
            // Truncate from beginning if over limit (per ACP spec)
            if (outputBuffer.length() > outputByteLimit) {
                int excess = outputBuffer.length() - outputByteLimit;
                // Find next character boundary (ensure valid UTF-8)
                outputBuffer.delete(0, excess);
                truncated = true;
            }
        }

        synchronized String getOutput() {
            return outputBuffer.toString();
        }

        boolean isTruncated() {
            return truncated;
        }
    }
}
