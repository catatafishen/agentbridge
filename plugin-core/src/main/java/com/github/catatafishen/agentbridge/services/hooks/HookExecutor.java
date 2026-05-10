package com.github.catatafishen.agentbridge.services.hooks;

import com.github.catatafishen.agentbridge.services.ProcessStreamUtils;
import com.github.catatafishen.agentbridge.settings.ShellEnvironment;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Executes hook scripts with the stdin/stdout JSON protocol.
 * Handles timeout enforcement, failSilently behavior, async mode, and result parsing.
 */
public final class HookExecutor {

    private static final Logger LOG = Logger.getInstance(HookExecutor.class);
    private static final Gson GSON = new Gson();
    private static final int MAX_HOOK_OUTPUT_CHARS = 16_000;
    private static final String JSON_KEY_ERROR = "error";
    private static final String STATE_ERROR = "error";
    private static final String STATE_SUCCESS = "success";

    private HookExecutor() {
    }

    public static @NotNull HookResult execute(@NotNull HookEntryConfig entry,
                                              @NotNull HookTrigger trigger,
                                              @NotNull HookPayload payload,
                                              @NotNull ToolHookConfig config,
                                              @NotNull Map<String, String> projectEnv) throws HookExecutionException {
        Path scriptPath = config.resolveScript(entry);
        if (scriptPath == null) {
            return new HookResult.NoOp();
        }

        if (entry.async()) {
            startAsync(scriptPath, entry, payload, config.toolId(), projectEnv);
            return new HookResult.NoOp();
        }

        try {
            String stdout = runScript(scriptPath, entry, payload, projectEnv);
            return parseResult(trigger, stdout);
        } catch (IOException e) {
            if (entry.failSilently()) {
                LOG.warn("Hook script failed (failSilently=true) for tool '"
                    + config.toolId() + "' trigger " + trigger.jsonKey() + ": " + e.getMessage());
                return new HookResult.NoOp();
            }
            throw new HookExecutionException(config.toolId(), trigger, e.getMessage(), e);
        }
    }

    private static void startAsync(@NotNull Path scriptPath,
                                   @NotNull HookEntryConfig entry,
                                   @NotNull HookPayload payload,
                                   @NotNull String toolId,
                                   @NotNull Map<String, String> projectEnv) {
        CompletableFuture.runAsync(() -> {
            try {
                runScript(scriptPath, entry, payload, projectEnv);
            } catch (IOException e) {
                LOG.warn("Async hook script failed for tool '" + toolId + "': " + e.getMessage());
            }
        });
    }

    private static @NotNull String runScript(@NotNull Path scriptPath,
                                             @NotNull HookEntryConfig entry,
                                             @NotNull HookPayload payload,
                                             @NotNull Map<String, String> projectEnv) throws IOException {
        List<String> command = buildCommand(scriptPath);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(scriptPath.getParent().toFile());

        // Layer environment variables: project context → per-entry overrides → argument values
        Map<String, String> env = builder.environment();
        env.putAll(projectEnv);

        if (!entry.env().isEmpty()) {
            env.putAll(entry.env());
        }

        Map<String, String> argEnv = HookEnvironmentProvider.getArgumentEnvironment(payload.arguments());
        env.putAll(argEnv);

        Process process = builder.start();
        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());

        try (var stdin = process.getOutputStream()) {
            stdin.write(GSON.toJson(payload).getBytes(StandardCharsets.UTF_8));
        }

        boolean finished;
        try {
            finished = process.waitFor(entry.timeout(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Hook script interrupted", e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Hook timed out after " + entry.timeout() + "s: " + scriptPath);
        }

        String out = await(stdout, "stdout");
        String err = await(stderr, "stderr");
        int exitCode = process.exitValue();

        if (exitCode != 0) {
            String detail = formatOutput(out, err);
            throw new IOException("Hook exited with code " + exitCode + detail);
        }

        if (!err.isBlank()) {
            LOG.info("Hook stderr: " + truncate(err));
        }
        return out;
    }

    static @NotNull HookResult parseResult(@NotNull HookTrigger trigger, @Nullable String stdout) {
        if (stdout == null || stdout.isBlank()) return new HookResult.NoOp();

        String trimmed = stdout.trim();
        JsonElement element = parseJsonOrNull(trimmed);

        if (element == null || !element.isJsonObject()) {
            if (trigger == HookTrigger.SUCCESS || trigger == HookTrigger.FAILURE) {
                return new HookResult.OutputModification(stripTrailingLineBreaks(stdout), null);
            }
            return new HookResult.NoOp();
        }

        JsonObject obj = element.getAsJsonObject();

        return switch (trigger) {
            case PERMISSION -> parsePermissionResult(obj);
            case PRE -> parsePreResult(obj);
            case SUCCESS, FAILURE -> parseOutputResult(obj, stdout);
        };
    }

    private static @NotNull HookResult parsePermissionResult(@NotNull JsonObject obj) {
        if (!obj.has("decision")) return new HookResult.NoOp();
        String decision = obj.get("decision").getAsString();
        boolean allowed = "allow".equalsIgnoreCase(decision);
        String reason = obj.has("reason") ? obj.get("reason").getAsString() : null;
        return new HookResult.PermissionDecision(allowed, reason);
    }

    private static @NotNull HookResult parsePreResult(@NotNull JsonObject obj) {
        if (obj.has(JSON_KEY_ERROR)) {
            JsonElement error = obj.get(JSON_KEY_ERROR);
            String message = error.isJsonNull() ? "" : error.getAsString();
            if (message.isBlank()) {
                message = "Pre-hook stopped tool execution";
            }
            return new HookResult.PreHookFailure(message);
        }
        if (!obj.has("arguments")) return new HookResult.NoOp();
        return new HookResult.ModifiedArguments(obj.getAsJsonObject("arguments"));
    }

    private static @NotNull HookResult parseOutputResult(@NotNull JsonObject obj, @NotNull String rawStdout) {
        Boolean stateOverride = parseStateOverride(obj);

        if (obj.has("output")) {
            JsonElement output = obj.get("output");
            String text = output.isJsonNull() ? "" : output.getAsString();
            return new HookResult.OutputModification(text, null, stateOverride);
        }
        if (obj.has("append")) {
            JsonElement append = obj.get("append");
            String text = append.isJsonNull() ? null : append.getAsString();
            return new HookResult.OutputModification(null, text, stateOverride);
        }
        return new HookResult.OutputModification(stripTrailingLineBreaks(rawStdout), null, stateOverride);
    }

    /**
     * Parses the optional "state" field from hook output JSON.
     * Returns {@code true} for "success", {@code false} for "error", or {@code null} if absent.
     */
    private static @Nullable Boolean parseStateOverride(@NotNull JsonObject obj) {
        if (!obj.has("state")) return null;
        String state = obj.get("state").getAsString();
        return switch (state.toLowerCase()) {
            case STATE_SUCCESS -> true;
            case STATE_ERROR -> false;
            default -> null;
        };
    }

    private static final boolean IS_WINDOWS = com.intellij.openapi.util.SystemInfo.isWindows;

    /**
     * Build the command list used to invoke the hook script.
     * <ul>
     *   <li>PowerShell scripts ({@code .ps1}) are invoked via {@code powershell}.</li>
     *   <li>Other scripts are invoked with the interpreter declared in their shebang
     *       line (e.g. {@code #!/usr/bin/env bash} → {@code bash scriptPath}).
     *       If the shebang uses {@code /usr/bin/env}, the resolver token after {@code env}
     *       is extracted so that e.g. {@code #!/usr/bin/env python3} works correctly.</li>
     *   <li>Scripts with no readable shebang fall back to {@link ShellEnvironment#getShellPath()}
     *       ({@code /bin/sh} on Unix; the discovered POSIX shell on Windows).</li>
     *   <li>On Windows, script paths are normalized to forward slashes so that
     *       POSIX shell parameter expansion ({@code ${0%/*}}) works correctly.</li>
     * </ul>
     */
    private static @NotNull List<String> buildCommand(@NotNull Path scriptPath) {
        List<String> cmd = new ArrayList<>();
        String fileName = scriptPath.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".ps1")) {
            cmd.add("powershell");
            cmd.add("-ExecutionPolicy");
            cmd.add("Bypass");
            cmd.add("-File");
        } else {
            cmd.add(resolveInterpreter(scriptPath));
        }
        String scriptPathStr = scriptPath.toAbsolutePath().toString();
        // POSIX shells expect forward slashes; backslashes break ${0%/*} in _lib.sh
        if (IS_WINDOWS) {
            scriptPathStr = scriptPathStr.replace('\\', '/');
        }
        cmd.add(scriptPathStr);
        return cmd;
    }

    /**
     * Extract the interpreter from the script's shebang line.
     * <p>
     * On Windows, {@code sh} and {@code bash} shebangs — whether absolute or via
     * {@code /usr/bin/env} — are redirected to {@link ShellEnvironment#getShellPath()}
     * (the discovered Git for Windows shell) since they are not on the default
     * Windows PATH. Other Unix absolute paths (e.g. {@code /usr/bin/python3}) are
     * reduced to their basename for PATH-based resolution.
     * Falls back to {@link ShellEnvironment#getShellPath()} when the shebang is
     * absent or unreadable.
     */
    private static @NotNull String resolveInterpreter(@NotNull Path scriptPath) {
        try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(scriptPath)) {
            String firstLine = reader.readLine();
            if (firstLine == null || !firstLine.startsWith("#!")) return ShellEnvironment.getShellPath();
            String shebang = firstLine.substring(2).trim();
            String interpreter;
            // /usr/bin/env python3  →  python3  (PATH lookup, works everywhere)
            if (shebang.startsWith("/usr/bin/env ")) {
                String[] parts = shebang.substring("/usr/bin/env ".length()).trim().split("\\s+");
                interpreter = parts[0].isEmpty() ? ShellEnvironment.getShellPath() : parts[0];
            } else {
                interpreter = shebang.split("\\s+")[0];
            }
            if (!IS_WINDOWS) return interpreter;
            // On Windows, redirect sh/bash/dash to the discovered POSIX shell
            // (these are not on the default Windows PATH)
            if (isShellInterpreter(interpreter)) return ShellEnvironment.getShellPath();
            // Other Unix absolute paths (/usr/bin/python3) → basename for PATH lookup
            if (interpreter.startsWith("/")) {
                return interpreter.substring(interpreter.lastIndexOf('/') + 1);
            }
            return interpreter;
        } catch (java.io.IOException e) {
            return ShellEnvironment.getShellPath();
        }
    }

    /**
     * Returns {@code true} if the interpreter is a common POSIX shell (sh, bash, or dash)
     * that needs to be redirected to {@link ShellEnvironment#getShellPath()} on Windows.
     * <p>
     * Only the shells our hook scripts actually use are listed here. Other shells (zsh, ksh,
     * etc.) may be installed on Windows (e.g., via Cygwin) and should be resolved via PATH,
     * not redirected to the Git for Windows shell.
     */
    private static boolean isShellInterpreter(@NotNull String interpreter) {
        String basename = interpreter.contains("/")
            ? interpreter.substring(interpreter.lastIndexOf('/') + 1)
            : interpreter;
        return switch (basename) {
            case "sh", "bash", "dash" -> true;
            default -> false;
        };
    }

    private static CompletableFuture<String> readAsync(InputStream stream) {
        return ProcessStreamUtils.readAsync(stream, MAX_HOOK_OUTPUT_CHARS);
    }

    private static String await(CompletableFuture<String> future, String streamName) throws IOException {
        return ProcessStreamUtils.await(future, streamName);
    }

    private static @Nullable JsonElement parseJsonOrNull(String text) {
        try {
            return JsonParser.parseString(text);
        } catch (JsonParseException e) {
            return null;
        }
    }

    private static String stripTrailingLineBreaks(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\r' || text.charAt(end - 1) == '\n')) {
            end--;
        }
        return text.substring(0, end);
    }

    private static String formatOutput(String stdout, String stderr) {
        String combined = (stdout + stderr).trim();
        if (combined.isEmpty()) return "";
        return ": " + truncate(combined);
    }

    private static String truncate(String text) {
        return text.length() > MAX_HOOK_OUTPUT_CHARS
            ? text.substring(0, MAX_HOOK_OUTPUT_CHARS) + "..."
            : text;
    }

    /**
     * Thrown when a hook script fails and is configured with {@code failSilently: false}.
     */
    public static final class HookExecutionException extends Exception {
        private final String toolId;
        private final HookTrigger trigger;

        public HookExecutionException(String toolId, HookTrigger trigger, String message, Throwable cause) {
            super("Hook for tool '" + toolId + "' (" + trigger.jsonKey() + ") failed: " + message, cause);
            this.toolId = toolId;
            this.trigger = trigger;
        }

        public String toolId() {
            return toolId;
        }

        public HookTrigger trigger() {
            return trigger;
        }
    }
}
