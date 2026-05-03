package com.github.catatafishen.agentbridge.services.hooks;

import com.github.catatafishen.agentbridge.services.ProcessStreamUtils;
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

    private static final String DEFAULT_SHELL = "/bin/sh";

    /**
     * Build the command list used to invoke the hook script.
     * <ul>
     *   <li>PowerShell scripts ({@code .ps1}) are invoked via {@code powershell}.</li>
     *   <li>Other scripts are invoked with the interpreter declared in their shebang
     *       line (e.g. {@code #!/usr/bin/env bash} → {@code bash scriptPath}).
     *       If the shebang uses {@code /usr/bin/env}, the resolver token after {@code env}
     *       is extracted so that e.g. {@code #!/usr/bin/env python3} works correctly.</li>
     *   <li>Scripts with no readable shebang fall back to {@code /bin/sh}.</li>
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
        cmd.add(scriptPath.toAbsolutePath().toString());
        return cmd;
    }

    /**
     * Extract the interpreter from the script's shebang line.
     * Returns {@code /bin/sh} as a safe fallback when the shebang is absent or unreadable.
     */
    private static @NotNull String resolveInterpreter(@NotNull Path scriptPath) {
        try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(scriptPath)) {
            String firstLine = reader.readLine();
            if (firstLine == null || !firstLine.startsWith("#!")) return DEFAULT_SHELL;
            String shebang = firstLine.substring(2).trim();
            // /usr/bin/env python3  →  python3
            if (shebang.startsWith("/usr/bin/env ")) {
                String[] parts = shebang.substring("/usr/bin/env ".length()).trim().split("\\s+");
                return parts[0].isEmpty() ? DEFAULT_SHELL : parts[0];
            }
            // /bin/bash  or  /usr/bin/python3  →  use as-is
            return shebang.split("\\s+")[0];
        } catch (java.io.IOException e) {
            return DEFAULT_SHELL;
        }
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
