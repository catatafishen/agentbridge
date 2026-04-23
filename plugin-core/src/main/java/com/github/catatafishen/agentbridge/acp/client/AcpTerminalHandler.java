package com.github.catatafishen.agentbridge.acp.client;

import com.github.catatafishen.agentbridge.acp.client.intercept.VisibleProcessRunner;
import com.github.catatafishen.agentbridge.settings.ShellEnvironment;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles ACP terminal methods: {@code terminal/create}, {@code terminal/output},
 * {@code terminal/wait_for_exit}, {@code terminal/kill}, and {@code terminal/release}.
 *
 * <p>Per the ACP spec, terminals represent shell command executions that the agent
 * can create and manage. Each terminal has a unique ID, captures output, and
 * tracks exit status.
 *
 * <p>Fall-through commands (those not redirected to MCP equivalents by
 * {@code AcpToolInterceptor}) are launched via {@link VisibleProcessRunner} so the
 * user can see them live in the Run tool window — there is no hidden execution path.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/terminals">ACP Terminals</a>
 */
final class AcpTerminalHandler {

    private static final Logger LOG = Logger.getInstance(AcpTerminalHandler.class);
    private static final int DEFAULT_OUTPUT_BYTE_LIMIT = 1_048_576; // 1 MB

    private final Project project;
    private final VisibleProcessRunner visibleRunner;
    private final Map<String, ManagedTerminal> terminals = new ConcurrentHashMap<>();

    AcpTerminalHandler(Project project) {
        this.project = project;
        this.visibleRunner = new VisibleProcessRunner(project);
    }

    /**
     * {@code terminal/create} — start a command in a new terminal that the user can see in
     * the IDE Run tool window. Output is mirrored to both the visible console and the ACP
     * output buffer so the agent observes a normal stdout stream while the user can watch
     * the process run.
     *
     * @return result with {@code terminalId}
     */
    JsonObject create(@NotNull JsonObject params) throws IOException {
        String command = getRequiredString(params, "command");
        String[] args = getStringArray(params, "args");
        String cwd = params.has("cwd") && params.get("cwd").isJsonPrimitive()
            ? params.get("cwd").getAsString() : project.getBasePath();
        int outputByteLimit = params.has("outputByteLimit") && params.get("outputByteLimit").isJsonPrimitive()
            ? params.get("outputByteLimit").getAsInt() : DEFAULT_OUTPUT_BYTE_LIMIT;

        Map<String, String> env = new HashMap<>(ShellEnvironment.getEnvironment());
        if (params.has("env") && params.get("env").isJsonArray()) {
            for (JsonElement el : params.getAsJsonArray("env")) {
                if (el.isJsonObject()) {
                    JsonObject envVar = el.getAsJsonObject();
                    String name = getStringOrNull(envVar, "name");
                    String value = getStringOrNull(envVar, "value");
                    if (name != null && value != null) {
                        env.put(name, value);
                    }
                }
            }
        }

        String terminalId = "term_" + UUID.randomUUID().toString().substring(0, 12);
        ManagedTerminal terminal = new ManagedTerminal(terminalId, outputByteLimit);

        GeneralCommandLine cmd = VisibleProcessRunner.buildCommandLine(command, args, cwd, env);

        String tabTitle = "Agent: " + truncate(command + " " + String.join(" ", args), 60);
        Process process;
        try {
            process = visibleRunner.start(cmd, tabTitle, terminal::appendOutput);
        } catch (ExecutionException e) {
            throw new IOException("Failed to start terminal command: " + e.getMessage(), e);
        }
        terminal.attachProcess(process);
        terminals.put(terminalId, terminal);

        LOG.info("Created visible terminal " + terminalId + ": " + cmd.getCommandLineString());

        JsonObject result = new JsonObject();
        result.addProperty("terminalId", terminalId);
        return result;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /**
     * {@code terminal/output} — get current output and optional exit status.
     */
    JsonObject output(@NotNull JsonObject params) {
        String terminalId = getRequiredString(params, "terminalId");
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
        String terminalId = getRequiredString(params, "terminalId");
        ManagedTerminal terminal = requireTerminal(terminalId);

        boolean exited = terminal.process.waitFor(WAIT_FOR_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            terminal.process.destroyForcibly();
            terminals.remove(terminalId);
            LOG.warn("terminal/wait_for_exit timed out after " + WAIT_FOR_EXIT_TIMEOUT_SECONDS
                + "s for terminal " + terminalId + " — process forcibly destroyed and resources released");
            throw new IllegalStateException("Process timed out after "
                + WAIT_FOR_EXIT_TIMEOUT_SECONDS + " seconds and was killed (terminal " + terminalId + ")");
        }

        JsonObject result = new JsonObject();
        result.addProperty("exitCode", terminal.process.exitValue());
        result.add("signal", null);
        return result;
    }

    /**
     * {@code terminal/kill} — terminate the command without releasing.
     */
    JsonObject kill(@NotNull JsonObject params) {
        String terminalId = getRequiredString(params, "terminalId");
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
        String terminalId = getRequiredString(params, "terminalId");
        ManagedTerminal terminal = terminals.remove(terminalId);
        if (terminal == null) {
            throw new IllegalArgumentException("Unknown terminal: " + terminalId);
        }

        if (terminal.process.isAlive()) {
            terminal.process.destroyForcibly();
        }
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
     * Tracks a running process with its captured output. Output is fed in by
     * {@link VisibleProcessRunner}'s {@code OutputSink} so there is exactly one
     * reader on the process input stream (the {@link com.intellij.execution.process.OSProcessHandler}
     * inside the runner).
     */
    private static final class ManagedTerminal {
        final String id;
        final int outputByteLimit;
        volatile Process process;

        private final StringBuilder outputBuffer = new StringBuilder();
        private volatile boolean truncated;

        ManagedTerminal(String id, int outputByteLimit) {
            this.id = id;
            this.outputByteLimit = outputByteLimit;
        }

        void attachProcess(Process process) {
            this.process = process;
        }

        synchronized void appendOutput(String chunk) {
            outputBuffer.append(chunk);
            if (outputBuffer.length() > outputByteLimit) {
                int excess = outputBuffer.length() - outputByteLimit;
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
