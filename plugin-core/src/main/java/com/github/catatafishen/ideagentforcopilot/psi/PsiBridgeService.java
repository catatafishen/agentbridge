package com.github.catatafishen.ideagentforcopilot.psi;

import com.github.catatafishen.ideagentforcopilot.services.ToolDefinition;
import com.github.catatafishen.ideagentforcopilot.services.ToolPermission;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Executes MCP tool calls inside IntelliJ, providing PSI/AST-backed code intelligence.
 * Called directly by {@link com.github.catatafishen.ideagentforcopilot.services.McpProtocolHandler}
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
        void toolCalled(String toolName, long durationMs, boolean success);
    }

    /**
     * Project-level message bus topic for tool call events (fire-and-forget notifications).
     */
    public static final Topic<ToolCallListener> TOOL_CALL_TOPIC =
        Topic.create("PsiBridgeService.ToolCall", ToolCallListener.class);

    private final Project project;
    private final ToolRegistry registry;
    private final FileTools fileTools;
    private final java.util.Set<String> sessionAllowedTools =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    public PsiBridgeService(@NotNull Project project) {
        this.project = project;
        this.registry = ToolRegistry.getInstance(project);
        this.fileTools = new FileTools(project);
        GitToolHandler gitToolHandler = new GitToolHandler(project, fileTools);

        // Initialize handler groups
        RunConfigurationService runConfigService = new RunConfigurationService(
                project, className -> ClassResolverUtil.resolveClass(project, className));

        CodeQualityTools qualityTools = new CodeQualityTools(project);

        TestTools testTools = new TestTools(project);
        ProjectTools projectTools = new ProjectTools(project);
        InfrastructureTools infraTools = new InfrastructureTools(project);
        EditorTools editorTools = new EditorTools(project);

        // Register OO-style individual tool classes
        boolean hasJava = PlatformApiCompat.isPluginInstalled("com.intellij.modules.java");
        var allTools = new java.util.ArrayList<com.github.catatafishen.ideagentforcopilot.psi.tools.Tool>();
        allTools.addAll(com.github.catatafishen.ideagentforcopilot.psi.tools.git.GitToolFactory.create(project, gitToolHandler));
        allTools.addAll(com.github.catatafishen.ideagentforcopilot.psi.tools.file.FileToolFactory.create(project, fileTools));
        allTools.addAll(com.github.catatafishen.ideagentforcopilot.psi.tools.navigation.NavigationToolFactory.create(project, hasJava));
        allTools.addAll(com.github.catatafishen.ideagentforcopilot.psi.tools.quality.QualityToolFactory.create(project, qualityTools, SonarQubeIntegration.isInstalled()));
        allTools.addAll(com.github.catatafishen.ideagentforcopilot.psi.tools.refactoring.RefactoringToolFactory.create(project, hasJava));
        allTools.addAll(com.github.catatafishen.ideagentforcopilot.psi.tools.editing.EditingToolFactory.create(project));
        allTools.addAll(com.github.catatafishen.ideagentforcopilot.psi.tools.testing.TestingToolFactory.create(project, testTools));
        allTools.addAll(com.github.catatafishen.ideagentforcopilot.psi.tools.project.ProjectToolFactory.create(project, projectTools, runConfigService, hasJava));
        allTools.addAll(com.github.catatafishen.ideagentforcopilot.psi.tools.infrastructure.InfrastructureToolFactory.create(project, infraTools));
        allTools.addAll(com.github.catatafishen.ideagentforcopilot.psi.tools.terminal.TerminalToolFactory.create(project));
        allTools.addAll(com.github.catatafishen.ideagentforcopilot.psi.tools.editor.EditorToolFactory.create(project, editorTools));
        allTools.addAll(com.github.catatafishen.ideagentforcopilot.psi.tools.builtin.BuiltInToolFactory.create(project));
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

    /**
     * Runs deferred auto-format and import optimization on all files modified during the turn.
     */
    public void flushPendingAutoFormat() {
        fileTools.flushPendingAutoFormat();
    }

    /**
     * Clears file access tracking (background tints in Project View) at end of turn.
     */
    public void clearFileAccessTracking() {
        FileAccessTracker.clear(project);
    }

    public String callTool(String toolName, JsonObject arguments) {
        ToolDefinition def = registry.findDefinition(toolName);
        if (def == null || !def.hasExecutionHandler()) {
            fireToolCallEvent(toolName, System.currentTimeMillis(), false);
            return "Unknown tool: " + toolName;
        }
        long startMs = System.currentTimeMillis();
        boolean success = true;

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
            String denied = checkPluginToolPermission(toolName, arguments);
            if (denied != null) {
                fireToolCallEvent(toolName, startMs, false);
                return denied;
            }

            String result = def.execute(arguments);

            // Piggyback highlights after successful write operations
            if (isSuccessfulWrite(toolName, result) && daemonWaiter != null) {
                LOG.info("Auto-highlights: piggybacking on write to " + filePathForHighlights);
                result = appendAutoHighlights(result, filePathForHighlights, daemonWaiter);
            }
            return result;
        } catch (com.intellij.openapi.application.ex.ApplicationUtil.CannotRunReadActionException e) {
            success = false;
            return "Error: IDE is busy, please retry. " + e.getMessage();
        } catch (Exception e) {
            LOG.warn("Tool call error: " + toolName, e);
            success = false;
            return "Error: " + e.getMessage();
        } finally {
            fireToolCallEvent(toolName, startMs, success);
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

    private void fireToolCallEvent(String toolName, long startTimeMs, boolean success) {
        long duration = System.currentTimeMillis() - startTimeMs;
        try {
            PlatformApiCompat.syncPublisher(project, TOOL_CALL_TOPIC)
                .toolCalled(toolName, duration, success);
        } catch (Exception e) {
            LOG.debug("Failed to fire tool call event", e);
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
            return "Permission denied: tool '" + toolName + "' is disabled in Tool Permissions settings.";
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

        // Build a structured context JSON for the permission bubble: {question, args}
        String resolvedQuestion = entry != null ? entry.resolvePermissionQuestion(arguments) : null;
        com.google.gson.JsonObject context = new com.google.gson.JsonObject();
        context.addProperty("question", resolvedQuestion != null ? resolvedQuestion
            : "Can I use " + displayName + "?");
        if (arguments != null) context.add("args", arguments);
        String argsJson = context.toString();

        com.github.catatafishen.ideagentforcopilot.ui.ChatConsolePanel chatPanel =
            com.github.catatafishen.ideagentforcopilot.ui.ChatConsolePanel.Companion.getInstance(project);

        com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse response;
        if (chatPanel != null) {
            java.util.concurrent.CompletableFuture<com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse> future =
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
                return "Permission request timed out for tool '" + toolName + "'.";
            } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
                Thread.currentThread().interrupt();
                return "Permission request interrupted for tool '" + toolName + "'.";
            }
        } else {
            // Fallback: modal dialog when JCEF / chat panel is unavailable
            boolean[] result = {false};
            EdtUtil.invokeAndWait(() -> {
                String message = "<html><b>Allow: " + StringUtil.escapeXmlEntities(displayName) + "</b><br><br>"
                    + buildArgSummary(arguments != null ? arguments : new com.google.gson.JsonObject()) + "</html>";
                int choice = Messages.showYesNoDialog(
                    project, message, "Tool Permission Request",
                    "Allow", "Deny", Messages.getQuestionIcon()
                );
                result[0] = choice == Messages.YES;
            });
            response = result[0]
                ? com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse.ALLOW_ONCE
                : com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse.DENY;
        }

        return switch (response) {
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
                yield "Permission denied by user for tool '" + toolName + "'.";
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
    private static String extractPathArg(JsonObject args) {
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

    private static String buildArgSummary(JsonObject args) {
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
    private static boolean isWriteToolName(String toolName) {
        return switch (toolName) {
            case "write_file", "intellij_write_file", "edit_text",
                 "create_file", "replace_symbol_body",
                 "insert_before_symbol", "insert_after_symbol" -> true;
            default -> false;
        };
    }

    private static boolean isSuccessfulWrite(String toolName, String result) {
        return switch (toolName) {
            case "write_file", "intellij_write_file", "edit_text" ->
                result.startsWith("Edited:") || result.startsWith("Written:");
            case "create_file" -> result.startsWith("✓ Created file:");
            case "replace_symbol_body" -> result.startsWith("Replaced lines ");
            case "insert_before_symbol" -> result.startsWith("Inserted ") && result.contains(" before ");
            case "insert_after_symbol" -> result.startsWith("Inserted ") && result.contains(" after ");
            default -> false;
        };
    }

    @Nullable
    private static String extractFilePath(JsonObject arguments) {
        if (arguments.has("path")) return arguments.get("path").getAsString();
        if (arguments.has("file")) return arguments.get("file").getAsString();
        return null;
    }

    /**
     * Returns the document's current modification stamp for the given file,
     * or -1 if the document is not loaded or the file is null.
     * Must be called inside a read action (or from EDT); wraps itself if needed.
     */
    private static long getDocumentStamp(@Nullable com.intellij.openapi.vfs.VirtualFile vf) {
        if (vf == null) return -1L;
        // Cast required for overload disambiguation: runReadAction(Computable) vs runReadAction(ThrowableComputable)
        return com.intellij.openapi.application.ApplicationManager.getApplication()
            .runReadAction((com.intellij.openapi.util.Computable<Long>) () -> {
                com.intellij.openapi.editor.Document doc =
                    com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
                return doc != null ? doc.getModificationStamp() : -1L;
            });
    }

    private String appendAutoHighlights(String writeResult, String path, DaemonWaiter preWriteWaiter) {
        com.intellij.openapi.vfs.VirtualFile vf = ToolUtils.resolveVirtualFile(project, path);
        try (DaemonWaiter activeWaiter = resolveActiveWaiter(preWriteWaiter, vf, path)) {
            activeWaiter.await();

            ToolDefinition highlightDef = registry.findDefinition("get_highlights");
            if (highlightDef == null || !highlightDef.hasExecutionHandler()) return writeResult;

            JsonObject highlightArgs = new JsonObject();
            highlightArgs.addProperty("path", path);
            highlightArgs.addProperty("include_unindexed", true);
            String highlights = highlightDef.execute(highlightArgs);
            if (highlights != null) {
                LOG.info("Auto-highlights: appended " + highlights.split("\n").length + " lines for " + path);
            }

            return highlights != null
                ? writeResult + "\n\n--- Highlights (auto) ---\n" + highlights
                : writeResult;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return writeResult;
        } catch (Exception e) {
            LOG.info("Auto-highlights after write failed: " + e.getMessage());
            return writeResult;
        }
    }

    private DaemonWaiter resolveActiveWaiter(
        DaemonWaiter preWriteWaiter,
        @Nullable com.intellij.openapi.vfs.VirtualFile vf,
        String path) {
        boolean alreadyOpen = vf != null
            && com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).isFileOpen(vf);
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
                long extraSleep = (lastFinishedAt + SETTLE_MS) - System.currentTimeMillis();
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
