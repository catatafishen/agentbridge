package com.github.catatafishen.agentbridge.services.hooks;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates hook execution at each point in the MCP tool call pipeline.
 * Called from {@code McpProtocolHandler.handleToolsCall()} at the four trigger points.
 *
 * <p>Pipeline: {@code permission → pre → (tool executes) → success / failure}
 *
 * <p>Each trigger supports chaining: multiple hook entries are executed sequentially,
 * with each entry's output feeding into the next.
 */
public final class HookPipeline {

    private static final Logger LOG = Logger.getInstance(HookPipeline.class);

    private HookPipeline() {
    }

    /**
     * Result of the permission hook chain.
     */
    public sealed interface PermissionResult {
        record Allowed() implements PermissionResult {
        }

        record Denied(@NotNull String reason) implements PermissionResult {
        }
    }

    /**
     * Result of the pre-tool hook chain.
     */
    public sealed interface PreHookResult {
        record Unchanged(@NotNull JsonObject arguments) implements PreHookResult {
        }

        record Modified(@NotNull JsonObject arguments) implements PreHookResult {
        }

        record Blocked(@NotNull String error) implements PreHookResult {
        }
    }

    /**
     * Runs the permission hook chain for a tool. All entries must allow; any deny stops the chain.
     * If no hooks are registered, returns allowed.
     */
    public static @NotNull PermissionResult runPermissionHooks(@NotNull Project project,
                                                               @NotNull String toolName,
                                                               @NotNull JsonObject arguments)
        throws HookExecutor.HookExecutionException {

        List<HookEntryConfig> entries = HookRegistry.getInstance(project)
            .findEntries(toolName, HookTrigger.PERMISSION);
        if (entries.isEmpty()) return new PermissionResult.Allowed();

        ToolHookConfig config = Objects.requireNonNull(
            HookRegistry.getInstance(project).findConfig(toolName));

        HookPayload payload = HookPayload.forPreExecution(
            toolName, arguments, project.getName(), Instant.now().toString());

        for (HookEntryConfig entry : entries) {
            HookResult result = HookExecutor.execute(entry, HookTrigger.PERMISSION, payload, config);
            if (result instanceof HookResult.PermissionDecision(boolean allowed, String reason) && !allowed) {
                String resolvedReason = reason != null ? reason : "Denied by permission hook";
                LOG.info("Permission hook denied tool " + toolName + ": " + resolvedReason);
                return new PermissionResult.Denied(resolvedReason);
            }
        }
        return new PermissionResult.Allowed();
    }

    /**
     * Runs the pre-tool hook chain. Each entry can modify arguments or block execution.
     * Modified arguments are passed to subsequent entries in the chain.
     * If no hooks are registered, returns the original arguments unchanged.
     */
    public static @NotNull PreHookResult runPreHooks(@NotNull Project project,
                                                     @NotNull String toolName,
                                                     @NotNull JsonObject arguments)
        throws HookExecutor.HookExecutionException {

        List<HookEntryConfig> entries = HookRegistry.getInstance(project)
            .findEntries(toolName, HookTrigger.PRE);
        if (entries.isEmpty()) return new PreHookResult.Unchanged(arguments);

        ToolHookConfig config = Objects.requireNonNull(
            HookRegistry.getInstance(project).findConfig(toolName));
        JsonObject currentArgs = arguments;
        boolean modified = false;

        for (HookEntryConfig entry : entries) {
            HookPayload payload = HookPayload.forPreExecution(
                toolName, currentArgs, project.getName(), Instant.now().toString());

            HookResult result = HookExecutor.execute(entry, HookTrigger.PRE, payload, config);

            if (result instanceof HookResult.PreHookFailure(String error)) {
                LOG.info("Pre-hook blocked tool " + toolName + ": " + error);
                return new PreHookResult.Blocked(error);
            }
            if (result instanceof HookResult.ModifiedArguments(JsonObject modifiedArguments)) {
                currentArgs = modifiedArguments;
                modified = true;
                LOG.info("Pre-hook modified arguments for " + toolName);
            }
        }

        return modified ? new PreHookResult.Modified(currentArgs) : new PreHookResult.Unchanged(arguments);
    }

    /**
     * Runs the success hook chain. Each entry can modify or append to tool output.
     * Modified output is passed to subsequent entries in the chain.
     * If no hooks are registered, returns the original output unchanged.
     */
    public static @Nullable String runSuccessHooks(@NotNull Project project,
                                                   @NotNull String toolName,
                                                   @NotNull JsonObject arguments,
                                                   @Nullable String output,
                                                   long durationMs)
        throws HookExecutor.HookExecutionException {

        List<HookEntryConfig> entries = HookRegistry.getInstance(project)
            .findEntries(toolName, HookTrigger.SUCCESS);
        if (entries.isEmpty()) return output;

        ToolHookConfig config = Objects.requireNonNull(
            HookRegistry.getInstance(project).findConfig(toolName));
        String currentOutput = output;

        for (HookEntryConfig entry : entries) {
            HookPayload payload = HookPayload.forPostExecution(
                toolName, arguments, currentOutput, false, project.getName(),
                Instant.now().toString(), durationMs);

            HookResult result = HookExecutor.execute(entry, HookTrigger.SUCCESS, payload, config);
            currentOutput = applyOutputResult(result, currentOutput);
        }

        return currentOutput;
    }

    /**
     * Runs the failure hook chain. Each entry can modify or append to the error message.
     * Modified error messages are passed to subsequent entries in the chain.
     * If no hooks are registered, returns the original error unchanged.
     */
    public static @NotNull String runFailureHooks(@NotNull Project project,
                                                  @NotNull String toolName,
                                                  @NotNull JsonObject arguments,
                                                  @NotNull String errorMessage,
                                                  long durationMs)
        throws HookExecutor.HookExecutionException {

        List<HookEntryConfig> entries = HookRegistry.getInstance(project)
            .findEntries(toolName, HookTrigger.FAILURE);
        if (entries.isEmpty()) return errorMessage;

        ToolHookConfig config = Objects.requireNonNull(
            HookRegistry.getInstance(project).findConfig(toolName));
        String currentError = errorMessage;

        for (HookEntryConfig entry : entries) {
            HookPayload payload = HookPayload.forPostExecution(
                toolName, arguments, currentError, true, project.getName(),
                Instant.now().toString(), durationMs);

            HookResult result = HookExecutor.execute(entry, HookTrigger.FAILURE, payload, config);
            String modified = applyOutputResult(result, currentError);
            if (modified != null) {
                currentError = modified;
            }
        }

        return currentError;
    }

    private static @Nullable String applyOutputResult(@NotNull HookResult result, @Nullable String original) {
        if (result instanceof HookResult.OutputModification mod) {
            if (mod.isReplacement()) {
                return mod.replacedOutput();
            }
            if (mod.appendedText() != null) {
                String base = original != null ? original : "";
                return base + mod.appendedText();
            }
        }
        return original;
    }
}
