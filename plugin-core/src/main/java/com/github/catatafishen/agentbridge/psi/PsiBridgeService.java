package com.github.catatafishen.agentbridge.psi;

import com.github.catatafishen.agentbridge.services.ActiveAgentManager;
import com.github.catatafishen.agentbridge.services.ToolChipRegistry;
import com.github.catatafishen.agentbridge.services.ToolDefinition;
import com.github.catatafishen.agentbridge.services.ToolPermission;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Executes MCP tool calls inside IntelliJ, providing PSI/AST-backed code intelligence.
 * Called directly by {@link com.github.catatafishen.agentbridge.services.McpProtocolHandler}
 * via Java method call — no HTTP required.
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
@Service(Service.Level.PROJECT)
public final class PsiBridgeService implements Disposable {
    private static final Logger LOG = Logger.getInstance(PsiBridgeService.class);

    /**
     * Listener notified after each MCP tool call completes.
     */
    public interface ToolCallListener {
        void toolCalled(String toolName, long durationMs, boolean success,
                        long inputSizeBytes, long outputSizeBytes, String clientId,
                        @Nullable String category, @Nullable String errorMessage);
    }

    /**
     * Listener notified to request focus restoration to the chat input.
     */
    public interface FocusRestoreListener {
        void restoreFocus();
    }

    /**
     * Project-level message bus topic for tool call events (fire-and-forget notifications).
     */
    public static final Topic<ToolCallListener> TOOL_CALL_TOPIC =
        Topic.create("PsiBridgeService.ToolCall", ToolCallListener.class);

    /**
     * Project-level message bus topic for requesting focus restoration to chat input.
     */
    public static final Topic<FocusRestoreListener> FOCUS_RESTORE_TOPIC =
        Topic.create("PsiBridgeService.FocusRestore", FocusRestoreListener.class);

    private static final Set<String> SYNC_TOOL_CATEGORIES = Set.of("FILE", "EDITING", "REFACTOR", "GIT");
    private final Map<String, ReentrantLock> toolLocks = new ConcurrentHashMap<>();
    private final java.util.concurrent.Semaphore writeToolSemaphore = new java.util.concurrent.Semaphore(1);
    private final WriteBatchCoordinator writeBatchCoordinator = new WriteBatchCoordinator(writeToolSemaphore);

    /**
     * Cached chat tool window activation state, updated asynchronously via {@code invokeLater}.
     * Read from any thread; written only on the EDT. Using volatile for safe cross-thread reads.
     * The value may lag by one tool call — acceptable for focus-restoration heuristics.
     */
    private volatile boolean chatToolWindowActiveCache;

    private final Project project;
    private final ToolRegistry registry;
    private final java.util.Set<String> sessionAllowedTools =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicReference<String> pendingNudge =
        new java.util.concurrent.atomic.AtomicReference<>();
    /**
     * When true, {@link #consumePendingNudge()} is suppressed and returns {@code null}.
     * Set while a sub-agent is active so nudges are held until the main agent resumes.
     */
    private volatile boolean nudgesHeld = false;
    private final java.util.Queue<String> messageQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicReference<Runnable> onNudgeConsumed =
        new java.util.concurrent.atomic.AtomicReference<>();

    public PsiBridgeService(@NotNull Project project) {
        this.project = project;
        this.registry = ToolRegistry.getInstance(project);

        // Initialize services
        RunConfigurationService runConfigService = new RunConfigurationService(
            project, className -> ClassResolverUtil.resolveClass(project, className));

        // Register OO-style individual tool classes
        boolean hasJava = PlatformApiCompat.isPluginInstalled("com.intellij.modules.java");
        var allTools = new java.util.ArrayList<com.github.catatafishen.agentbridge.psi.tools.Tool>();
        allTools.addAll(com.github.catatafishen.agentbridge.psi.tools.git.GitToolFactory.create(project));
        allTools.addAll(com.github.catatafishen.agentbridge.psi.tools.file.FileToolFactory.create(project));
        allTools.addAll(com.github.catatafishen.agentbridge.psi.tools.navigation.NavigationToolFactory.create(project, hasJava));
        allTools.addAll(com.github.catatafishen.agentbridge.psi.tools.quality.QualityToolFactory.create(project, SonarQubeIntegration.isInstalled()));
        allTools.addAll(com.github.catatafishen.agentbridge.psi.tools.refactoring.RefactoringToolFactory.create(project, hasJava));
        allTools.addAll(com.github.catatafishen.agentbridge.psi.tools.editing.EditingToolFactory.create(project));
        allTools.addAll(com.github.catatafishen.agentbridge.psi.tools.testing.TestingToolFactory.create(project));
        allTools.addAll(com.github.catatafishen.agentbridge.psi.tools.project.ProjectToolFactory.create(project, runConfigService, hasJava));
        allTools.addAll(com.github.catatafishen.agentbridge.psi.tools.infrastructure.InfrastructureToolFactory.create(project));
        allTools.addAll(com.github.catatafishen.agentbridge.psi.tools.terminal.TerminalToolFactory.create(project));
        allTools.addAll(com.github.catatafishen.agentbridge.psi.tools.editor.EditorToolFactory.create(project));
        allTools.addAll(com.github.catatafishen.agentbridge.psi.tools.debug.DebugToolFactory.create(project));
        allTools.addAll(com.github.catatafishen.agentbridge.psi.tools.database.DatabaseToolFactory.create(project));
        allTools.addAll(com.github.catatafishen.agentbridge.psi.tools.memory.MemoryToolFactory.create(project));
        registry.registerAll(allTools);
    }

    @SuppressWarnings("java:S1905") // Cast needed: IDE doesn't resolve Project→ComponentManager supertype
    public static PsiBridgeService getInstance(@NotNull Project project) {
        return ((ComponentManager) project).getService(PsiBridgeService.class);
    }

    /**
     * Clears the session-scoped "Allow for session" permission cache.
     * Called when the ACP session is closed or restarted.
     */
    public void clearSessionAllowedTools() {
        sessionAllowedTools.clear();
    }

    public void setPendingNudge(@Nullable String nudge) {
        if (nudge == null) {
            pendingNudge.set(null);
            return;
        }
        pendingNudge.updateAndGet(existing -> mergeNudges(existing, nudge));
    }

    public void setOnNudgeConsumed(@Nullable Runnable callback) {
        onNudgeConsumed.set(callback);
    }

    /**
     * Holds or releases nudge delivery. While held, {@link #consumePendingNudge()} returns
     * {@code null} so nudges are not injected into sub-agent tool results — they wait until the
     * main agent resumes and makes the next tool call.
     */
    public void setNudgesHeld(boolean held) {
        nudgesHeld = held;
    }

    public void addOnNudgeConsumed(@NotNull Runnable callback) {
        onNudgeConsumed.accumulateAndGet(callback, (current, newCb) ->
            current == null ? newCb : () -> {
                current.run();
                newCb.run();
            }
        );
    }

    public void enqueueMessage(@NotNull String message) {
        if (!message.trim().isEmpty()) {
            messageQueue.offer(message.trim());
        }
    }

    public void removeQueuedMessage(@NotNull String message) {
        messageQueue.remove(message.trim());
    }

    @Nullable
    public String getNextQueuedMessage() {
        return messageQueue.poll();
    }

    public boolean hasQueuedMessages() {
        return !messageQueue.isEmpty();
    }

    @Nullable
    private String consumePendingNudge() {
        if (nudgesHeld) return null;
        String nudge = pendingNudge.getAndSet(null);
        if (nudge != null) {
            Runnable cb = onNudgeConsumed.get();
            if (cb != null) cb.run();
        }
        return nudge;
    }

    /**
     * Runs deferred auto-format and import optimization on all files modified during the turn.
     */
    public void flushPendingAutoFormat() {
        com.github.catatafishen.agentbridge.psi.tools.file.FileTool.flushPendingAutoFormat(project);
    }

    /**
     * Clears file access tracking (background tints in Project View) at end of turn.
     */
    public void clearFileAccessTracking() {
        FileAccessTracker.clear(project);
    }

    public String callTool(String toolName, JsonObject arguments) {
        return callTool(toolName, arguments, null, null);
    }

    public String callTool(String toolName, JsonObject arguments, @Nullable String progressToken) {
        return callTool(toolName, arguments, progressToken, null);
    }

    public String callTool(String toolName, JsonObject arguments, @Nullable String progressToken, @Nullable String toolUseId) {
        LOG.info("PSI Bridge: calling " + toolName + " with args: " + arguments);
        long inputSize = arguments != null ? arguments.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length : 0;
        ToolDefinition def = registry.findDefinition(toolName);
        if (def == null || !def.hasExecutionHandler()) {
            String unknownErr = "Unknown tool: " + toolName;
            fireToolCallEvent(toolName, System.currentTimeMillis(), false, inputSize, 0, null, unknownErr);
            return unknownErr;
        }
        String categoryName = def.category() != null ? def.category().name() : null;
        long outputSize = 0;
        long startMs = System.currentTimeMillis();
        boolean success = true;
        String errorMessage = null;

        String argumentsHash = ToolChipRegistry.computeBaseHash(arguments);

        // Track if chat tool window is active before the tool call.
        // Only restore focus afterward if it was active before (don't steal focus if user switched away).
        boolean chatWasActive = isChatToolWindowActive(project);

        // Determine if this tool requires synchronous execution (file/git/editing tools).
        boolean requiresSync = isSyncCategory(def.category() != null ? def.category().name() : null);

        // Global write semaphore: serialize PSI-mutating tools to prevent EDT flooding and race
        // conditions. Multiple concurrent writes each posting lambdas via invokeLater can saturate
        // the EDT queue and cause the IDE to freeze.
        // Long-running execution tools (build, test, run-command) override needsWriteLock() = false
        // so they do not starve PSI-mutating tools for minutes at a time.
        // Sync-category tools (FILE, EDITING, REFACTOR, GIT) also use per-tool locks for ordering.
        boolean needsGlobalLock = def.needsWriteLock();

        // Track whether this is a write operation that should participate in batch draining.
        // Write tools register with WriteBatchCoordinator BEFORE acquiring the semaphore so
        // earlier writes can see them as "pending" and defer highlight collection.
        boolean isWriteOp = needsGlobalLock && isWriteToolName(toolName);
        // Tracks whether registerWrite() was called but unregisterWrite() hasn't been yet.
        // Ensures exactly one unregister per register across all exit paths.
        boolean writeRegistered = false;
        if (isWriteOp) {
            writeBatchCoordinator.registerWrite();
            writeRegistered = true;
        }

        // Check permission BEFORE acquiring the global write lock. The permission prompt
        // may block up to 120 s (waiting for user to respond). Holding the semaphore during
        // that wait would freeze all other non-readonly tool calls for the full duration.
        String denied = checkPluginToolPermission(toolName, arguments);
        if (denied != null) {
            if (writeRegistered) {
                writeBatchCoordinator.unregisterWrite();
                writeRegistered = false;
            }
            fireToolCallEvent(toolName, startMs, false, inputSize, 0, categoryName, denied);
            return denied;
        }

        if (needsGlobalLock) {
            String lockError = acquireWriteLock(toolName, startMs, inputSize, categoryName);
            if (lockError != null) {
                if (writeRegistered) {
                    writeBatchCoordinator.unregisterWrite();
                    writeRegistered = false;
                }
                return lockError;
            }
        }

        // Track whether we released the semaphore early to drain pending writes.
        // When true, the finally block must NOT release the semaphore again.
        boolean semaphoreReleasedEarly = false;

        // Subscribe to daemon events BEFORE the write to avoid the race where
        // the daemon finishes before we subscribe and we miss the event entirely.
        // For existing files, we resolve the VirtualFile now and make the waiter file-specific.
        // For new files (create_file), vfForHighlights is null; the fresh waiter in appendAutoHighlights
        // will be created after the file exists and will be file-specific.
        String filePathForHighlights = isWriteToolName(toolName) ? extractFilePath(arguments) : null;
        com.intellij.openapi.vfs.VirtualFile vfForHighlights = filePathForHighlights != null
            ? ToolUtils.resolveVirtualFile(project, filePathForHighlights) : null;

        // Record the document stamp NOW (before the write) so DaemonWaiter can reject
        // any in-flight daemon pass that analyzed the pre-edit document.
        long preWriteStamp = getDocumentStamp(vfForHighlights);

        // try-with-resources ensures the waiter is always disconnected.
        // appendAutoHighlights may close and re-subscribe internally; disconnect() is idempotent.
        try (DaemonWaiter daemonWaiter = filePathForHighlights != null
            ? new DaemonWaiter(project, vfForHighlights, preWriteStamp) : null) {
            // Acquire per-tool sync lock if needed, register with chip registry, then execute.
            ReentrantLock syncLock = requiresSync
                ? toolLocks.computeIfAbsent(toolName, k -> new ReentrantLock())
                : null;
            if (syncLock != null) syncLock.lock();
            String result;
            try {
                // Register with chip registry BEFORE executing so the chip can transition to "running"
                // Pass the resolved kind so the chip can update its color immediately.
                ToolChipRegistry.getInstance(project).registerMcp(toolName, arguments, def.kind().value(), toolUseId);
                result = def.execute(arguments, argumentsHash);
            } finally {
                if (syncLock != null) syncLock.unlock();
            }

            // Mark this write as complete — no longer "pending" from the coordinator's perspective.
            if (writeRegistered) {
                writeBatchCoordinator.unregisterWrite();
                writeRegistered = false;
            }

            // Piggyback highlights after successful write operations.
            //
            // WRITE BATCH DRAINING: When multiple write tool calls are queued (e.g., the agent
            // sends 3 edits in one turn), earlier writes may produce false-positive highlights
            // if collected immediately — e.g., "unused method" after edit 1 adds a method, even
            // though edit 2 will call it. To avoid this, we check whether other writes are still
            // pending. If so, we release the semaphore to let them execute first, then collect
            // highlights that reflect the final state after all writes.
            // See WriteBatchCoordinator for full documentation.
            if (isSuccessfulWrite(toolName, result) && daemonWaiter != null) {
                if (writeBatchCoordinator.hasPendingWrites()) {
                    LOG.info("Auto-highlights: deferring for " + filePathForHighlights
                        + " — draining " + writeBatchCoordinator.getPendingCount() + " pending write(s)");
                    writeBatchCoordinator.drainPendingWrites();
                    semaphoreReleasedEarly = true;
                    result = collectPostDrainHighlights(result, filePathForHighlights, vfForHighlights);
                } else {
                    LOG.info("Auto-highlights: piggybacking on write to " + filePathForHighlights);
                    result = appendAutoHighlights(result, filePathForHighlights, daemonWaiter);
                }
            }
            // Append pending nudge (user guidance injected on next tool call)
            result = appendNudgeToResult(result, consumePendingNudge());
            // Detect error results returned as strings (not exceptions).
            // Tools like git_branch return "Error: ..." without throwing, so success/errorMessage
            // must be updated here to match what McpProtocolHandler reports via isError.
            if (result.startsWith("Error")) {
                success = false;
                errorMessage = result;
            }
            outputSize = result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            // Cache result so the UI can display the actual error even when the ACP
            // tool_call_update:failed doesn't forward our error text back.
            ToolChipRegistry.getInstance(project).storeMcpResult(toolName, arguments, result);
            return result;
        } catch (com.intellij.openapi.application.ex.ApplicationUtil.CannotRunReadActionException e) {
            success = false;
            errorMessage = "Error: IDE is busy, please retry. " + e.getMessage();
            if (writeRegistered) writeBatchCoordinator.unregisterWrite();
            ToolChipRegistry.getInstance(project).storeMcpResult(toolName, arguments, errorMessage);
            return errorMessage;
        } catch (com.intellij.openapi.progress.ProcessCanceledException e) {
            // ProcessCanceledException must not be swallowed — signals IDE shutdown or project disposal.
            // Let it propagate so McpProtocolHandler can respond with an error and the MCP thread
            // terminates cleanly rather than silently masking the cancellation.
            if (writeRegistered) writeBatchCoordinator.unregisterWrite();
            throw e;
        } catch (Exception e) {
            LOG.warn("Tool call error: " + toolName, e);
            success = false;
            if (writeRegistered) writeBatchCoordinator.unregisterWrite();
            errorMessage = buildErrorWithModalDetail(
                "Error: " + e.getMessage(), EdtUtil.describeModalBlocker());
            ToolChipRegistry.getInstance(project).storeMcpResult(toolName, arguments, errorMessage);
            return errorMessage;
        } finally {
            if (needsGlobalLock && !semaphoreReleasedEarly) writeToolSemaphore.release();
            fireToolCallEvent(toolName, startMs, success, inputSize, outputSize, categoryName, errorMessage);
            if (chatWasActive) {
                fireFocusRestoreEvent();
            }
        }
    }

    /**
     * Dynamically registers a tool at runtime. Used by MacroToolRegistrar
     * (experimental plugin variant) to add user-recorded macros as MCP tools.
     */
    public void registerTool(ToolDefinition toolDef) {
        registry.register(toolDef);
    }

    /**
     * Removes a dynamically registered tool.
     */
    public void unregisterTool(String id) {
        registry.unregister(id);
    }

    /**
     * Acquires the global write lock to serialize all non-readonly tool calls.
     * Returns null if acquired successfully, or an error message if the lock could not be acquired.
     */
    @Nullable
    private String acquireWriteLock(String toolName, long startTimeMs,
                                    long inputSizeBytes, @Nullable String category) {
        try {
            if (!writeToolSemaphore.tryAcquire(60, java.util.concurrent.TimeUnit.SECONDS)) {
                String err = "Error: IDE is busy processing another tool call. Please retry shortly.";
                fireToolCallEvent(toolName, startTimeMs, false, inputSizeBytes, 0, category, err);
                return err;
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String err = "Error: Interrupted waiting for write lock.";
            fireToolCallEvent(toolName, startTimeMs, false, inputSizeBytes, 0, category, err);
            return err;
        }
    }

    private void fireToolCallEvent(String toolName, long startTimeMs, boolean success,
                                   long inputSizeBytes, long outputSizeBytes,
                                   @Nullable String category, @Nullable String errorMessage) {
        long duration = System.currentTimeMillis() - startTimeMs;
        String clientId = ActiveAgentManager.getInstance(project).getActiveProfileId();
        try {
            PlatformApiCompat.syncPublisher(project, TOOL_CALL_TOPIC)
                .toolCalled(toolName, duration, success, inputSizeBytes, outputSizeBytes,
                    clientId, category, errorMessage);
        } catch (Exception e) {
            LOG.debug("Failed to fire tool call event", e);
        }
    }

    private void fireFocusRestoreEvent() {
        try {
            PlatformApiCompat.syncPublisher(project, FOCUS_RESTORE_TOPIC)
                .restoreFocus();
        } catch (Exception e) {
            LOG.debug("Failed to fire focus restore event", e);
        }
    }

    /**
     * Checks if the AgentBridge chat tool window is currently active (has focus).
     * <p>
     * Thread-safe: on the EDT the check is performed synchronously (no stale cache).
     * Off the EDT, a blocking {@code invokeLater} with a short timeout is used so callers
     * get a fresh value rather than one that may lag by several tool calls.
     */
    public static boolean isChatToolWindowActive(@NotNull Project project) {
        PsiBridgeService service = getInstance(project);
        if (service == null) return false;

        var app = ApplicationManager.getApplication();
        if (app.isDispatchThread()) {
            refreshChatToolWindowCache(project, service);
        } else {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            app.invokeLater(() -> {
                refreshChatToolWindowCache(project, service);
                latch.countDown();
            });
            try {
                latch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return service.chatToolWindowActiveCache;
    }

    private static void refreshChatToolWindowCache(@NotNull Project project, @NotNull PsiBridgeService service) {
        try {
            com.intellij.openapi.wm.ToolWindowManager twm =
                com.intellij.openapi.wm.ToolWindowManager.getInstance(project);
            com.intellij.openapi.wm.ToolWindow tw = twm.getToolWindow("AgentBridge");
            service.chatToolWindowActiveCache = tw != null && tw.isActive();
        } catch (Exception e) {
            LOG.debug("Failed to refresh chat tool window state", e);
        }
    }

    /**
     * Checks the configured permission for a plugin tool call.
     * Returns null if the call is allowed, or an error message string if it is denied/rejected.
     * For ASK, injects a permission bubble into the chat panel and blocks until user responds.
     * Falls back to a modal dialog if the chat panel is unavailable (no JCEF, etc.).
     */
    @Nullable
    private String checkPluginToolPermission(String toolName, JsonObject arguments) {
        ToolPermission perm = resolvePluginPermission(toolName, arguments);
        if (perm == ToolPermission.ALLOW) return null;

        if (perm == ToolPermission.DENY) {
            LOG.info("PSI Bridge: DENY for tool " + toolName);
            return "Error: Permission denied: tool '" + toolName + "' is disabled in Tool Permissions settings.";
        }

        // Session-scoped allow: if user previously chose "Allow for session", skip the prompt
        if (sessionAllowedTools.contains(toolName)) {
            LOG.info("PSI Bridge: session-allowed for " + toolName);
            return null;
        }

        // ASK: show a permission bubble in the chat panel and block until user responds
        return askUserPermission(toolName, arguments);
    }

    @Nullable
    private String askUserPermission(String toolName, JsonObject arguments) {
        ToolDefinition entry = registry.findById(toolName);
        String displayName = entry != null ? entry.displayName() : toolName;
        String reqId = java.util.UUID.randomUUID().toString();

        // Build a structured context JSON for the permission bubble, containing question and args
        String resolvedQuestion = entry != null ? entry.resolvePermissionQuestion(arguments) : null;
        com.google.gson.JsonObject context = new com.google.gson.JsonObject();
        context.addProperty("question", resolvedQuestion != null ? resolvedQuestion
            : "Can I use " + displayName + "?");
        if (arguments != null) context.add("args", arguments);
        String argsJson = context.toString();

        com.github.catatafishen.agentbridge.ui.ChatConsolePanel chatPanel =
            com.github.catatafishen.agentbridge.ui.ChatConsolePanel.Companion.getInstance(project);

        com.github.catatafishen.agentbridge.bridge.PermissionResponse response;
        if (chatPanel != null) {
            java.util.concurrent.CompletableFuture<com.github.catatafishen.agentbridge.bridge.PermissionResponse> future =
                new java.util.concurrent.CompletableFuture<>();
            EdtUtil.invokeLater(() ->
                chatPanel.showPermissionRequest(reqId, displayName, argsJson, result -> {
                    future.complete(result);
                    return kotlin.Unit.INSTANCE;
                })
            );
            try {
                response = future.get(120, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                LOG.info("PSI Bridge: ASK timed out for " + toolName);
                return "Error: Permission request timed out for tool '" + toolName + "'.";
            } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
                Thread.currentThread().interrupt();
                return "Error: Permission request interrupted for tool '" + toolName + "'.";
            }
        } else {
            // Fallback: modal dialog when JCEF / chat panel is unavailable.
            // Must NOT use EdtUtil.invokeAndWait here: the modal detector in EdtUtil.pollUntilDone
            // detects the dialog it just opened and throws after ~1.5 s, leaving the dialog open
            // as an orphan. Use invokeLater + CompletableFuture instead so the background thread
            // waits for the user's actual response without triggering the modal blocker check.
            String message = "<html><b>Allow: " + StringUtil.escapeXmlEntities(displayName) + "</b><br><br>"
                + buildArgSummary(arguments != null ? arguments : new com.google.gson.JsonObject()) + "</html>";
            java.util.concurrent.CompletableFuture<Integer> choiceFuture = new java.util.concurrent.CompletableFuture<>();
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                int choice = Messages.showYesNoDialog(
                    project, message, "Tool Permission Request",
                    "Allow", "Deny", Messages.getQuestionIcon()
                );
                choiceFuture.complete(choice);
            }, com.intellij.openapi.application.ModalityState.defaultModalityState());
            try {
                int choice = choiceFuture.get(120, java.util.concurrent.TimeUnit.SECONDS);
                response = choice == Messages.YES
                    ? com.github.catatafishen.agentbridge.bridge.PermissionResponse.ALLOW_ONCE
                    : com.github.catatafishen.agentbridge.bridge.PermissionResponse.DENY;
            } catch (java.util.concurrent.TimeoutException e) {
                LOG.info("PSI Bridge: modal permission timed out for " + toolName);
                return "Error: Permission request timed out for tool '" + toolName + "'.";
            } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
                Thread.currentThread().interrupt();
                return "Error: Permission request interrupted for tool '" + toolName + "'.";
            }
        }

        return switch (response) {
            case ALLOW_ALWAYS -> {
                ActiveAgentManager.getInstance(project).getSettings().setToolPermission(toolName, ToolPermission.ALLOW);
                sessionAllowedTools.add(toolName);
                LOG.info("PSI Bridge: ASK approved permanently for " + toolName);
                yield null;
            }
            case ALLOW_SESSION -> {
                sessionAllowedTools.add(toolName);
                LOG.info("PSI Bridge: ASK approved for session for " + toolName);
                yield null;
            }
            case ALLOW_ONCE -> {
                LOG.info("PSI Bridge: ASK approved (once) for " + toolName);
                yield null;
            }
            default -> {
                LOG.info("PSI Bridge: ASK denied by user for " + toolName);
                yield "Error: Permission denied by user for tool '" + toolName + "'.";
            }
        };
    }

    private ToolPermission resolvePluginPermission(String toolName, JsonObject arguments) {
        ToolLayerSettings settings = ToolLayerSettings.getInstance(project);
        ToolDefinition entry = registry.findById(toolName);
        if (entry != null && entry.supportsPathSubPermissions()) {
            String path = extractPathArg(arguments);
            if (path != null && !path.isEmpty()) {
                boolean inside = isInsideProject(path);
                return settings.resolveEffectivePermission(toolName, inside);
            }
        }
        return settings.getToolPermission(toolName);
    }

    @Nullable
    static String extractPathArg(JsonObject args) {
        for (String key : new String[]{"path", "file", "file1", "file2"}) {
            if (args.has(key) && args.get(key).isJsonPrimitive()) {
                return args.get(key).getAsString();
            }
        }
        return null;
    }

    private boolean isInsideProject(String path) {
        return isPathUnderBase(path, project.getBasePath());
    }

    /**
     * Returns true if the given path (absolute or relative) falls under the given base path,
     * or if either is null/non-absolute (treating such cases as in-project for safety).
     */
    public static boolean isPathUnderBase(String path, @Nullable String basePath) {
        if (basePath == null) return true;
        java.io.File f = new java.io.File(path);
        if (!f.isAbsolute()) return true;
        try {
            return f.getCanonicalPath().startsWith(new java.io.File(basePath).getCanonicalPath());
        } catch (java.io.IOException e) {
            return true;
        }
    }

    static String buildArgSummary(JsonObject args) {
        if (args.isEmpty()) return "No arguments.";
        StringBuilder sb = new StringBuilder("<table>");
        int count = 0;
        for (Map.Entry<String, JsonElement> e : args.entrySet()) {
            if (count++ >= 5) {
                sb.append("<tr><td colspan='2'>…</td></tr>");
                break;
            }
            String val = e.getValue().isJsonPrimitive()
                ? e.getValue().getAsString() : e.getValue().toString();
            if (val.length() > 100) val = val.substring(0, 97) + "…";
            sb.append("<tr><td><b>").append(StringUtil.escapeXmlEntities(e.getKey()))
                .append(":</b>&nbsp;</td><td>").append(StringUtil.escapeXmlEntities(val))
                .append("</td></tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    /**
     * Returns true if the tool name is a write operation that should get auto-highlights.
     */
    static boolean isWriteToolName(String toolName) {
        return switch (toolName) {
            case "write_file", "edit_text", "create_file",
                 "replace_symbol_body", "insert_before_symbol",
                 "insert_after_symbol" -> true;
            default -> false;
        };
    }

    static boolean isSuccessfulWrite(String toolName, String result) {
        return switch (toolName) {
            case "write_file", "edit_text" -> result.startsWith("Edited:") || result.startsWith("Written:");
            case "create_file" -> result.startsWith("✓ Created file:");
            case "replace_symbol_body" -> result.startsWith("Replaced lines ");
            case "insert_before_symbol" -> result.startsWith("Inserted ") && result.contains(" before ");
            case "insert_after_symbol" -> result.startsWith("Inserted ") && result.contains(" after ");
            default -> false;
        };
    }

    @Nullable
    static String extractFilePath(JsonObject arguments) {
        if (arguments.has("path")) return arguments.get("path").getAsString();
        if (arguments.has("file")) return arguments.get("file").getAsString();
        return null;
    }

    /**
     * Merges a new nudge with any existing nudge text.
     * Returns just the new nudge if there's no existing text; concatenates with double-newline otherwise.
     */
    static String mergeNudges(@Nullable String existing, @NotNull String newNudge) {
        return (existing == null || existing.isEmpty()) ? newNudge : existing + "\n\n" + newNudge;
    }

    /**
     * Appends a nudge message to a tool result, or returns the result unchanged if nudge is null.
     */
    static String appendNudgeToResult(@NotNull String result, @Nullable String nudge) {
        return nudge != null ? result + "\n\n[User nudge]: " + nudge : result;
    }

    /**
     * Formats the combined write result and highlight output.
     * Returns the write result unchanged if highlights are null.
     */
    static String formatHighlightResult(@NotNull String writeResult, @Nullable String highlights) {
        return highlights != null
            ? writeResult + "\n\n--- Highlights (auto) ---\n" + highlights
            : writeResult;
    }

    /**
     * Builds an error message, optionally including modal dialog detail and a hint
     * to use the interact_with_modal tool.
     */
    static String buildErrorWithModalDetail(@NotNull String baseError, @NotNull String modalDetail) {
        if (!modalDetail.isEmpty()) {
            return baseError + "\n" + modalDetail.trim()
                + "\nUse the interact_with_modal tool to respond to the dialog.";
        }
        return baseError;
    }

    /**
     * Returns true if the given category name identifies a synchronous (serialized) tool category.
     */
    static boolean isSyncCategory(@Nullable String categoryName) {
        return categoryName != null && SYNC_TOOL_CATEGORIES.contains(categoryName);
    }

    /**
     * Computes how much additional sleep time is needed for the daemon debounce to settle.
     * Returns a non-positive value if no more sleep is needed.
     */
    static long computeExtraSleep(long lastFinishedAt, long settleMs, long now) {
        return (lastFinishedAt + settleMs) - now;
    }

    /**
     * Returns the document's current modification stamp for the given file,
     * or -1 if the document is not loaded or the file is null.
     * Must be called inside a read action (or from EDT); wraps itself if needed.
     */
    private static long getDocumentStamp(@Nullable com.intellij.openapi.vfs.VirtualFile vf) {
        if (vf == null) return -1L;
        return ApplicationManager.getApplication().runReadAction((Computable<Long>) () -> {
            com.intellij.openapi.editor.Document doc =
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
            return doc != null ? doc.getModificationStamp() : -1L;
        });
    }

    private String appendAutoHighlights(String writeResult, String path, DaemonWaiter preWriteWaiter) {
        com.intellij.openapi.vfs.VirtualFile vf = ToolUtils.resolveVirtualFile(project, path);
        try (DaemonWaiter activeWaiter = resolveActiveWaiter(preWriteWaiter, vf, path)) {
            return waitAndCollectHighlights(writeResult, path, activeWaiter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return writeResult;
        } catch (Exception e) {
            LOG.info("Auto-highlights after write failed: " + e.getMessage());
            return writeResult;
        }
    }

    /**
     * Collects highlights after a write batch drain. Creates a fresh {@link DaemonWaiter}
     * because the original waiter (created before this write) may have already settled on
     * an intermediate daemon pass that does not reflect the final document state.
     * <p>
     * Uses {@code postDrainStamp = getDocumentStamp(vf) - 1} so the fresh waiter only
     * accepts daemon passes that analyzed the document at its current (post-all-writes)
     * version. Explicitly restarts the daemon to ensure a new analysis pass fires.
     */
    private String collectPostDrainHighlights(
        String writeResult,
        String path,
        @Nullable com.intellij.openapi.vfs.VirtualFile vf) {

        if (vf == null) vf = ToolUtils.resolveVirtualFile(project, path);
        if (vf == null) return writeResult;

        // Use stamp - 1 so the waiter accepts passes where currentStamp >= postDrainStamp.
        // The check is "reject if currentStamp <= preWriteStamp", so stamp - 1 accepts
        // the current stamp itself while rejecting all prior versions.
        long postDrainStamp = getDocumentStamp(vf) - 1;

        com.intellij.openapi.vfs.VirtualFile target = vf;
        try (DaemonWaiter freshWaiter = new DaemonWaiter(project, vf, postDrainStamp)) {
            // Explicitly restart daemon analysis to guarantee a fresh pass fires after
            // all writes. The daemon may have already completed a stale pass (for an
            // intermediate document state) that this waiter correctly rejects.
            ApplicationManager.getApplication().invokeLater(() -> {
                com.intellij.psi.PsiFile psiFile =
                    com.intellij.psi.PsiManager.getInstance(project).findFile(target);
                if (psiFile != null) {
                    com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project)
                        .restart(psiFile, "Agent: re-analyzing after write batch drain");
                }
            });

            return waitAndCollectHighlights(writeResult, path, freshWaiter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return writeResult;
        } catch (Exception e) {
            LOG.info("Auto-highlights after batch drain failed: " + e.getMessage());
            return writeResult;
        }
    }

    /**
     * Shared logic: waits for the daemon to settle, then reads and appends highlights.
     */
    private String waitAndCollectHighlights(
        String writeResult, String path, DaemonWaiter waiter) throws Exception {

        waiter.await();

        ToolDefinition highlightDef = registry.findDefinition("get_highlights");
        if (highlightDef == null || !highlightDef.hasExecutionHandler()) return writeResult;

        JsonObject highlightArgs = new JsonObject();
        highlightArgs.addProperty("path", path);
        highlightArgs.addProperty("include_unindexed", true);
        String highlights = highlightDef.execute(highlightArgs);
        if (highlights != null) {
            LOG.info("Auto-highlights: appended " + highlights.split("\n").length + " lines for " + path);
        }

        return formatHighlightResult(writeResult, highlights);
    }

    private DaemonWaiter resolveActiveWaiter(
        DaemonWaiter preWriteWaiter,
        @Nullable com.intellij.openapi.vfs.VirtualFile vf,
        String path) {
        boolean alreadyOpen = vf != null
            && com.intellij.openapi.application.ReadAction.compute(
            () -> com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).isFileOpen(vf));
        if (alreadyOpen) return preWriteWaiter;
        // Subscribe a file-specific waiter BEFORE opening so we can't miss the new daemon pass.
        // Use preWriteStamp = -1: the write already happened before this waiter is created, so
        // any daemon pass that includes this file is necessarily post-write.
        preWriteWaiter.close();
        DaemonWaiter fresh = new DaemonWaiter(project, vf, -1L);
        openFileSilently(vf, path);
        return fresh;
    }

    private void openFileSilently(@Nullable com.intellij.openapi.vfs.VirtualFile vf, String path) {
        CompletableFuture<Void> opened = new CompletableFuture<>();
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
            try {
                com.intellij.openapi.vfs.VirtualFile target = vf != null
                    ? vf : ToolUtils.resolveVirtualFile(project, path);
                if (target != null) {
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                        .openFile(target, false);
                    // Explicitly restart daemon analysis so it doesn't wait for its own
                    // heuristic scheduling — avoids timeout in DaemonWaiter.await().
                    com.intellij.psi.PsiFile psiFile =
                        com.intellij.psi.PsiManager.getInstance(project).findFile(target);
                    if (psiFile != null) {
                        com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project)
                            .restart(psiFile, "Agent: file opened for highlight analysis");
                    }
                }
            } finally {
                opened.complete(null);
            }
        });
        try {
            opened.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.info("openFileSilently interrupted for " + path);
        } catch (Exception e) {
            LOG.info("openFileSilently timed out or failed for " + path + ": " + e.getMessage());
        }
    }

    /**
     * Subscribes to {@code DaemonCodeAnalyzer.DaemonListener} and waits until daemon analysis
     * has settled for the target file.
     *
     * <h3>Why debounce instead of a single latch?</h3>
     * IntelliJ fires multiple consecutive {@code daemonFinished} events for a single edit:
     * <ol>
     *   <li>A fast built-in pass (syntax, annotations) — fires first, within ~300ms</li>
     *   <li>A slow external-annotator pass (e.g. SonarLint) — fires ~800ms later once the
     *       Sonar analysis server responds</li>
     * </ol>
     * A single {@link java.util.concurrent.CountDownLatch} would trigger on the first (fast) pass and
     * read highlights before SonarLint has updated the markup model with fresh results.
     * The debounce approach keeps resetting a timer on every {@code daemonFinished} event, only
     * proceeding once there has been 600ms of silence — ensuring all passes have settled.
     */
    private static final class DaemonWaiter implements AutoCloseable {

        /**
         * How long (ms) to wait after the last {@code daemonFinished} event before considering
         * analysis settled. 600ms is long enough to bridge the gap between IntelliJ's fast pass
         * and SonarLint's follow-up external-annotator pass.
         */
        private static final long SETTLE_MS = 600L;

        private final java.util.concurrent.CountDownLatch firstPassLatch =
            new java.util.concurrent.CountDownLatch(1);
        private volatile long lastFinishedAt = 0L;
        private final Runnable disconnect;

        /**
         * @param preWriteStamp the document modificationStamp recorded BEFORE the write, or -1 to
         *                      accept any daemon pass (e.g. for freshly created files where the write
         *                      has already happened before this waiter is constructed).
         */
        DaemonWaiter(Project proj, @Nullable com.intellij.openapi.vfs.VirtualFile targetFile,
                     long preWriteStamp) {
            disconnect = PlatformApiCompat.subscribeDaemonListener(proj,
                new com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.DaemonListener() {
                    @Override
                    public void daemonFinished(
                        @NotNull java.util.Collection<? extends com.intellij.openapi.fileEditor.FileEditor> fileEditors) {
                        if (targetFile != null) {
                            boolean included = fileEditors.stream()
                                .anyMatch(fe -> targetFile.equals(fe.getFile()));
                            if (!included) return;

                            // Reject pre-write in-flight passes: only accept a daemon pass that
                            // analyzed a version of the document AFTER the write was applied.
                            if (preWriteStamp >= 0) {
                                long currentStamp = getDocumentStamp(targetFile);
                                if (currentStamp <= preWriteStamp) {
                                    LOG.info("Auto-highlights: ignoring pre-write daemon pass (stamp "
                                        + currentStamp + " <= " + preWriteStamp + ")");
                                    return;
                                }
                            }
                        }
                        lastFinishedAt = System.currentTimeMillis();
                        firstPassLatch.countDown();
                    }

                    @Override
                    public void daemonFinished() {
                        if (targetFile == null) {
                            lastFinishedAt = System.currentTimeMillis();
                            firstPassLatch.countDown();
                        }
                    }
                });
        }

        void await() throws InterruptedException {
            // Phase 1: wait for the first qualifying daemon pass (up to 5s)
            if (!firstPassLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                LOG.info("Auto-highlights: daemon wait timed out (5s), reading available highlights");
                return;
            }
            LOG.info("Auto-highlights: first daemon pass completed, settling for external annotators");

            // Phase 2: sleep for SETTLE_MS — gives SonarLint (and other external annotators)
            // time to complete their follow-up pass and update the markup model.
            long snapshotAt = lastFinishedAt;
            Thread.sleep(SETTLE_MS);

            // If a second pass fired while we slept (e.g. SonarLint's external annotator),
            // wait out the remaining settle time from that latest event.
            if (lastFinishedAt != snapshotAt) {
                long extraSleep = computeExtraSleep(lastFinishedAt, SETTLE_MS, System.currentTimeMillis());
                if (extraSleep > 0) {
                    Thread.sleep(extraSleep);
                }
            }
            LOG.info("Auto-highlights: daemon settled");
        }

        @Override
        public void close() {
            disconnect.run();
        }
    }

    @Override
    public void dispose() {
        // Nothing to dispose — tool handlers are stateless, lifecycle managed by IntelliJ
    }
}
