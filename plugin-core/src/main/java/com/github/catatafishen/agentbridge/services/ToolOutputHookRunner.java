package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.psi.ToolError;
import com.github.catatafishen.agentbridge.settings.McpServerSettings;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs optional per-tool post-call hooks that can rewrite raw tool output before it is
 * returned to the agent.
 */
final class ToolOutputHookRunner {

    private static final Gson GSON = new Gson();
    private static final int HOOK_TIMEOUT_SECONDS = 10;
    private static final int MAX_HOOK_OUTPUT_CHARS = 16_000;

    private ToolOutputHookRunner() {
    }

    static @Nullable String applyHook(@NotNull Project project,
                                      @NotNull String toolName,
                                      @NotNull JsonObject arguments,
                                      @Nullable String rawOutput,
                                      @NotNull McpServerSettings settings) throws IOException {

        String command = settings.getToolOutputHookCommand(toolName);
        if (command.isBlank()) return rawOutput;

        HookPayload payload = new HookPayload(
            toolName,
            arguments,
            arguments.toString(),
            rawOutput,
            ToolError.isError(rawOutput),
            project.getName(),
            Instant.now().toString()
        );
        Path workingDirectory = project.getBasePath() == null ? null : Path.of(project.getBasePath());
        return runHookCommand(command, payload, rawOutput, workingDirectory);
    }

    static @Nullable String runHookCommand(@NotNull String command,
                                           @NotNull HookPayload payload,
                                           @Nullable String originalOutput,
                                           @Nullable Path workingDirectory) throws IOException {

        ProcessBuilder builder = new ProcessBuilder(shellCommand(command));
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        Process process = builder.start();
        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());

        try (var stdin = process.getOutputStream()) {
            stdin.write(GSON.toJson(payload).getBytes(StandardCharsets.UTF_8));
        }

        boolean finished;
        try {
            finished = process.waitFor(HOOK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Tool output hook interrupted", e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Tool output hook timed out after " + HOOK_TIMEOUT_SECONDS + " seconds: " + command);
        }

        String out = await(stdout, "stdout");
        String err = await(stderr, "stderr");
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException("Tool output hook failed with exit code " + exitCode + formatHookOutput(out, err));
        }
        return interpretHookOutput(out, originalOutput);
    }

    static @Nullable String interpretHookOutput(@Nullable String hookStdout, @Nullable String originalOutput) {
        if (hookStdout == null || hookStdout.isBlank()) return originalOutput;
        String trimmed = hookStdout.trim();
        var element = parseJsonOrNull(trimmed);
        if (element == null) return stripTrailingLineBreaks(hookStdout);
        if (!element.isJsonObject()) return stripTrailingLineBreaks(hookStdout);
        JsonObject object = element.getAsJsonObject();
        if (object.has("output")) {
            var output = object.get("output");
            return output.isJsonNull() ? "" : output.getAsString();
        }
        if (object.has("append")) {
            var append = object.get("append");
            if (append.isJsonNull()) return originalOutput;
            String base = originalOutput == null ? "" : originalOutput;
            return base + append.getAsString();
        }
        throw new IllegalArgumentException("Tool output hook JSON must contain an 'output' or 'append' field");
    }

    private static JsonElement parseJsonOrNull(String text) {
        try {
            return JsonParser.parseString(text);
        } catch (JsonParseException e) {
            return null;
        }
    }

    private static List<String> shellCommand(String command) {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return List.of("cmd.exe", "/c", command);
        }
        return List.of("/bin/sh", "-c", command);
    }

    private static CompletableFuture<String> readAsync(InputStream stream) {
        return ProcessStreamUtils.readAsync(stream, Integer.MAX_VALUE);
    }

    private static String await(CompletableFuture<String> future, String streamName) throws IOException {
        return ProcessStreamUtils.await(future, streamName);
    }

    private static String formatHookOutput(String stdout, String stderr) {
        String combined = (stdout + stderr).trim();
        if (combined.isEmpty()) return ": no hook output";
        if (combined.length() > MAX_HOOK_OUTPUT_CHARS) {
            combined = combined.substring(0, MAX_HOOK_OUTPUT_CHARS) + "...";
        }
        return ": " + combined;
    }

    private static String stripTrailingLineBreaks(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\r' || text.charAt(end - 1) == '\n')) {
            end--;
        }
        return text.substring(0, end);
    }

    record HookPayload(@NotNull String toolName,
                       @NotNull JsonObject arguments,
                       @NotNull String argumentsJson,
                       @Nullable String output,
                       boolean error,
                       @NotNull String projectName,
                       @NotNull String timestamp) {
    }
}
