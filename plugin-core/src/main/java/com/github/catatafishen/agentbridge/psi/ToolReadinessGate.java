package com.github.catatafishen.agentbridge.psi;

import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Centralised pre-flight readiness checks for MCP tool execution.
 * <p>
 * Tools declare their requirements via {@link ToolDefinition#requiresIndex()},
 * {@link ToolDefinition#requiresSmartProject()}, and
 * {@link ToolDefinition#requiresInteractiveEdt()}. This gate enforces them with
 * a short opportunistic wait so transient pauses don't surface as errors, but
 * surfaces a clear, actionable nudge when the IDE is genuinely not ready.
 * <p>
 * The intent is to give the agent focused context — a single descriptive error
 * instead of a cryptic exception or empty result that wastes follow-up turns.
 */
public final class ToolReadinessGate {

    private static final Logger LOG = Logger.getInstance(ToolReadinessGate.class);

    /** How long we opportunistically wait for indexing to finish. */
    static final long INDEX_WAIT_MS = 30_000L;

    /** How long we opportunistically wait for project initialisation. */
    static final long PROJECT_INIT_WAIT_MS = 2_000L;

    /** Polling interval while waiting for project initialisation. */
    private static final long PROJECT_INIT_POLL_MS = 50L;

    private ToolReadinessGate() { }

    /**
     * Checks every applicable readiness gate for the given tool and project.
     *
     * @return null when all gates pass; otherwise an error string suitable to
     *         return directly to the MCP client (already prefixed with {@code "Error: "}).
     */
    @Nullable
    public static String checkReady(@NotNull Project project, @NotNull ToolDefinition def) {
        if (def.requiresSmartProject() || def.requiresIndex()) {
            String startupErr = awaitProjectInitialised(project, def.id(), PROJECT_INIT_WAIT_MS);
            if (startupErr != null) return startupErr;
        }
        if (def.requiresIndex()) {
            String indexErr = awaitSmartMode(project, def.id(), INDEX_WAIT_MS);
            if (indexErr != null) return indexErr;
        }
        if (def.requiresInteractiveEdt()) {
            String modalErr = checkNoModal(def.id());
            if (modalErr != null) return modalErr;
        }
        return null;
    }

    // ── Message builders ───────────────────────────────────────────────────────
    // Public so unit tests assert against the real production messages instead
    // of duplicating hard-coded strings that would silently drift.

    @NotNull
    public static String projectInitErrorMessage(@NotNull String toolName) {
        return "Error: Project is still initialising. Tool '" + toolName
            + "' depends on the project being fully opened. Retry shortly.";
    }

    @NotNull
    public static String indexingErrorMessage(@NotNull String toolName) {
        return "Error: IDE is still indexing after waiting 30s. Tool '" + toolName
            + "' depends on the symbol index, which is not yet ready. "
            + "Call get_indexing_status with wait=true to wait longer, then retry.";
    }

    @NotNull
    public static String modalErrorMessage(@NotNull String toolName, @NotNull String detail) {
        return "Error: A modal dialog is open and blocks tool '" + toolName + "'."
            + detail
            + " Use the interact_with_modal tool to inspect or dismiss the dialog, then retry.";
    }

    @NotNull
    public static String buildInProgressErrorMessage(@NotNull String toolName) {
        return "Error: A project build is already in progress. Tool '" + toolName
            + "' cannot run concurrently. Wait for the current build to finish and retry.";
    }

    /**
     * Returns null if the project is already initialised, or once it becomes
     * initialised within the timeout. Otherwise returns an error message.
     * <p>
     * Uses simple polling instead of {@code StartupManager.runAfterOpened()},
     * which is marked {@code @ApiStatus.Internal} and flagged by the plugin
     * verifier.
     */
    @Nullable
    static String awaitProjectInitialised(@NotNull Project project, @NotNull String toolName, long timeoutMs) {
        if (project.isDisposed()) {
            return "Error: Project is disposed. Tool '" + toolName + "' cannot run.";
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!project.isInitialized()) {
            if (project.isDisposed()) {
                return "Error: Project is disposed. Tool '" + toolName + "' cannot run.";
            }
            if (System.currentTimeMillis() >= deadline) {
                return projectInitErrorMessage(toolName);
            }
            try {
                Thread.sleep(PROJECT_INIT_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Error: Interrupted while waiting for project initialisation.";
            }
        }
        return null;
    }

    /**
     * Returns null if the project is in smart mode (indexing complete) or
     * becomes smart within the timeout. Otherwise returns an error message
     * that nudges the agent to call {@code get_indexing_status}.
     */
    @Nullable
    static String awaitSmartMode(@NotNull Project project, @NotNull String toolName, long timeoutMs) {
        if (project.isDisposed()) return null; // covered by initialisation check
        DumbService dumb = DumbService.getInstance(project);
        if (!dumb.isDumb()) return null;

        CompletableFuture<Void> smart = new CompletableFuture<>();
        dumb.runWhenSmart(() -> smart.complete(null));

        try {
            smart.get(timeoutMs, TimeUnit.MILLISECONDS);
            return null;
        } catch (TimeoutException e) {
            return indexingErrorMessage(toolName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Interrupted while waiting for indexing to finish.";
        } catch (Exception e) {
            LOG.debug("Smart-mode wait failed", e);
            return null; // be permissive on unexpected failure
        }
    }

    /**
     * Returns null when no modal dialog is blocking the EDT, otherwise an
     * error nudging the agent to call {@code interact_with_modal}.
     */
    @Nullable
    static String checkNoModal(@NotNull String toolName) {
        String detail = EdtUtil.describeModalBlocker();
        if (detail == null || detail.isEmpty()) return null;
        return modalErrorMessage(toolName, detail);
    }

    /**
     * Pre-flight check for build-style tools: returns an error if a project
     * build is currently in progress, otherwise null. Independent of
     * {@link #checkReady} because only build tools care.
     */
    @Nullable
    public static String checkNoBuildInProgress(@NotNull Project project, @NotNull String toolName) {
        try {
            CompilerManager cm = CompilerManager.getInstance(project);
            if (cm != null && cm.isCompilationActive()) {
                return buildInProgressErrorMessage(toolName);
            }
        } catch (Exception e) {
            LOG.debug("Build-in-progress check failed", e);
        }
        return null;
    }
}
