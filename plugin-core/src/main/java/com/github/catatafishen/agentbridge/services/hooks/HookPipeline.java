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
 * with each entry's output feeding into the next. Per-entry {@code prependString}/
 * {@code appendString} are applied to the tool output after the entry's script runs
 * (or directly, for text-only entries with no script).
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
     * Wraps the pre-hook chain result together with any static text modifiers accumulated
     * from pre-hook entries that have {@code prependString}/{@code appendString} set.
     * These modifiers are applied to the final tool output after the tool executes successfully.
     *
     * @param result         the hook chain result (modified args, unchanged, or blocked)
     * @param pendingPrepend static text to prepend to success output (from pre-hook entries), or null
     * @param pendingAppend  static text to append to success output (from pre-hook entries), or null
     */
    public record PreHookOutput(
        @NotNull PreHookResult result,
        @Nullable String pendingPrepend,
        @Nullable String pendingAppend
    ) {
    }

    /**
     * Outcome of a success or failure hook chain, including the potentially modified output
     * and any state override (e.g., a failure hook resolving an error to success).
     *
     * @param output  the final output text after all hooks in the chain
     * @param isError the final error state — may differ from the original if a hook set {@code "state"}
     */
    public record PostHookOutcome(@Nullable String output, boolean isError) {
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
     * Modified arguments are passed to subsequent entries in the chain. Any per-entry
     * {@code prependString}/{@code appendString} are accumulated and returned as pending
     * output modifiers — they are applied to the tool output after the tool runs successfully.
     *
     * <p>If no hooks are registered, returns unchanged arguments with no pending modifiers.
     */
    public static @NotNull PreHookOutput runPreHooks(@NotNull Project project,
                                                     @NotNull String toolName,
                                                     @NotNull JsonObject arguments)
        throws HookExecutor.HookExecutionException {

        List<HookEntryConfig> entries = HookRegistry.getInstance(project)
            .findEntries(toolName, HookTrigger.PRE);
        if (entries.isEmpty()) {
            return new PreHookOutput(new PreHookResult.Unchanged(arguments), null, null);
        }

        ToolHookConfig config = Objects.requireNonNull(
            HookRegistry.getInstance(project).findConfig(toolName));
        JsonObject currentArgs = arguments;
        boolean modified = false;
        StringBuilder pendingPrepend = new StringBuilder();
        StringBuilder pendingAppend = new StringBuilder();

        for (HookEntryConfig entry : entries) {
            HookPayload payload = HookPayload.forPreExecution(
                toolName, currentArgs, project.getName(), Instant.now().toString());

            HookResult result = HookExecutor.execute(entry, HookTrigger.PRE, payload, config);

            if (result instanceof HookResult.PreHookFailure(String error)) {
                LOG.info("Pre-hook blocked tool " + toolName + ": " + error);
                return new PreHookOutput(new PreHookResult.Blocked(error), null, null);
            }
            if (result instanceof HookResult.ModifiedArguments(JsonObject modifiedArguments)) {
                currentArgs = modifiedArguments;
                modified = true;
                LOG.info("Pre-hook modified arguments for " + toolName);
            }

            accumulateText(pendingPrepend, entry.prependString());
            accumulateText(pendingAppend, entry.appendString());
        }

        PreHookResult hookResult = modified
            ? new PreHookResult.Modified(currentArgs)
            : new PreHookResult.Unchanged(arguments);
        String prepend = pendingPrepend.isEmpty() ? null : pendingPrepend.toString();
        String append = pendingAppend.isEmpty() ? null : pendingAppend.toString();
        return new PreHookOutput(hookResult, prepend, append);
    }

    public static @NotNull PostHookOutcome runSuccessHooks(@NotNull Project project,
                                                           @NotNull String toolName,
                                                           @NotNull JsonObject arguments,
                                                           @Nullable String output,
                                                           long durationMs)
        throws HookExecutor.HookExecutionException {

        List<HookEntryConfig> entries = HookRegistry.getInstance(project)
            .findEntries(toolName, HookTrigger.SUCCESS);
        if (entries.isEmpty()) return new PostHookOutcome(output, false);

        ToolHookConfig config = Objects.requireNonNull(
            HookRegistry.getInstance(project).findConfig(toolName));
        String currentOutput = output;
        boolean isError = false;

        for (HookEntryConfig entry : entries) {
            HookPayload payload = HookPayload.forPostExecution(
                toolName, arguments, currentOutput, isError, project.getName(),
                Instant.now().toString(), durationMs);

            HookResult result = HookExecutor.execute(entry, HookTrigger.SUCCESS, payload, config);
            if (result instanceof HookResult.OutputModification mod) {
                currentOutput = applyOutputText(mod, currentOutput);
                if (mod.stateOverride() != null) {
                    isError = !mod.stateOverride();
                }
            }
            currentOutput = applyEntryTextModifiers(entry, currentOutput);
        }

        return new PostHookOutcome(currentOutput, isError);
    }

    public static @NotNull PostHookOutcome runFailureHooks(@NotNull Project project,
                                                           @NotNull String toolName,
                                                           @NotNull JsonObject arguments,
                                                           @NotNull String errorMessage,
                                                           long durationMs)
        throws HookExecutor.HookExecutionException {

        List<HookEntryConfig> entries = HookRegistry.getInstance(project)
            .findEntries(toolName, HookTrigger.FAILURE);
        if (entries.isEmpty()) return new PostHookOutcome(errorMessage, true);

        ToolHookConfig config = Objects.requireNonNull(
            HookRegistry.getInstance(project).findConfig(toolName));
        String currentOutput = errorMessage;
        boolean isError = true;

        for (HookEntryConfig entry : entries) {
            HookPayload payload = HookPayload.forPostExecution(
                toolName, arguments, currentOutput, isError, project.getName(),
                Instant.now().toString(), durationMs);

            HookResult result = HookExecutor.execute(entry, HookTrigger.FAILURE, payload, config);
            if (result instanceof HookResult.OutputModification mod) {
                String modifiedOutput = applyOutputText(mod, currentOutput);
                if (modifiedOutput != null) {
                    currentOutput = modifiedOutput;
                }
                if (mod.stateOverride() != null) {
                    isError = !mod.stateOverride();
                    if (!isError) {
                        LOG.info("Failure hook resolved error to success for tool " + toolName);
                    }
                }
            }
            currentOutput = applyEntryTextModifiers(entry, currentOutput);
        }

        return new PostHookOutcome(currentOutput, isError);
    }

    /**
     * Applies an entry's static text modifiers (prependString/appendString) to the current output.
     */
    private static @Nullable String applyEntryTextModifiers(@NotNull HookEntryConfig entry,
                                                            @Nullable String current) {
        if (entry.prependString() != null && !entry.prependString().isEmpty()) {
            current = entry.prependString() + "\n\n" + (current != null ? current : "");
        }
        if (entry.appendString() != null && !entry.appendString().isEmpty()) {
            current = (current != null ? current : "") + "\n\n" + entry.appendString();
        }
        return current;
    }

    private static void accumulateText(@NotNull StringBuilder sb, @Nullable String text) {
        if (text != null && !text.isEmpty()) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(text);
        }
    }

    private static @Nullable String applyOutputText(@NotNull HookResult.OutputModification mod,
                                                    @Nullable String original) {
        if (mod.isReplacement()) {
            return mod.replacedOutput();
        }
        if (mod.appendedText() != null) {
            String base = original != null ? original : "";
            return base + mod.appendedText();
        }
        return original;
    }
}
