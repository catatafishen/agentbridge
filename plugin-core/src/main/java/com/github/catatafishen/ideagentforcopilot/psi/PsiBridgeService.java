package com.github.catatafishen.ideagentforcopilot.psi;

import com.github.catatafishen.ideagentforcopilot.services.ToolPermission;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes MCP tool calls inside IntelliJ, providing PSI/AST-backed code intelligence.
 * Called directly by {@link com.github.catatafishen.ideagentforcopilot.services.McpProtocolHandler}
 * via Java method call — no HTTP required.
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
@Service(Service.Level.PROJECT)
public final class PsiBridgeService implements Disposable {
    private static final Logger LOG = Logger.getInstance(PsiBridgeService.class);
    private static final Gson GSON = new GsonBuilder().create();

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
    private final RunConfigurationService runConfigService;
    private final Map<String, ToolHandler> toolRegistry = new LinkedHashMap<>();
    private final FileTools fileTools;
    private final java.util.concurrent.atomic.AtomicBoolean permissionPending =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.Set<String> sessionAllowedTools =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    public PsiBridgeService(@NotNull Project project) {
        this.project = project;
        this.fileTools = new FileTools(project);
        GitToolHandler gitToolHandler = new GitToolHandler(project, fileTools);

        // Initialize handler groups
        RefactoringTools refactoringTools = new RefactoringTools(project);
        this.runConfigService = new RunConfigurationService(project, refactoringTools::resolveClass);

        // Register all tools from handler groups
        for (AbstractToolHandler handler : List.of(
            new CodeNavigationTools(project),
            fileTools,
            new CodeQualityTools(project),
            refactoringTools,
            new SymbolEditingTools(project),
            new TestTools(project, refactoringTools),
            new ProjectTools(project),
            new GitTools(project, gitToolHandler),
            new InfrastructureTools(project),
            new TerminalTools(project),
            new EditorTools(project)
        )) {
            toolRegistry.putAll(handler.getTools());
        }

        // RunConfigurationService tools (not an AbstractToolHandler)
        toolRegistry.put("list_run_configurations", args -> runConfigService.listRunConfigurations());
        toolRegistry.put("run_configuration", runConfigService::runConfiguration);
        toolRegistry.put("create_run_configuration", runConfigService::createRunConfiguration);
        toolRegistry.put("edit_run_configuration", runConfigService::editRunConfiguration);
        toolRegistry.put("delete_run_configuration", runConfigService::deleteRunConfiguration);
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
        ToolHandler handler = toolRegistry.get(toolName);
        if (handler == null) {
            fireToolCallEvent(toolName, System.currentTimeMillis(), false);
            return "Unknown tool: " + toolName;
        }
        long startMs = System.currentTimeMillis();
        boolean success = true;
        try {
            String denied = checkPluginToolPermission(toolName, arguments);
            if (denied != null) {
                fireToolCallEvent(toolName, startMs, false);
                return denied;
            }

            String result = handler.handle(arguments);

            // Piggyback highlights after successful write operations
            if (isSuccessfulWrite(toolName, result)) {
                String filePath = null;
                if (arguments.has("path")) {
                    filePath = arguments.get("path").getAsString();
                } else if (arguments.has("file")) {
                    filePath = arguments.get("file").getAsString();
                }
                if (filePath != null) {
                    LOG.info("Auto-highlights: piggybacking on write to " + filePath);
                    result = appendAutoHighlights(result, filePath);
                }
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
     * Returns the set of registered tool names (built-in + dynamically registered).
     * Used by MacroToolRegistrar in the experimental plugin variant.
     */
    public java.util.Set<String> getRegisteredToolNames() {
        return java.util.Collections.unmodifiableSet(toolRegistry.keySet());
    }

    /**
     * Dynamically registers a tool at runtime. Used by MacroToolRegistrar
     * (experimental plugin variant) to add user-recorded macros as MCP tools.
     */
    public void registerTool(String id, ToolHandler handler) {
        toolRegistry.put(id, handler);
    }

    /**
     * Removes a dynamically registered tool. Returns true if the tool existed.
     */
    public boolean unregisterTool(String id) {
        return toolRegistry.remove(id) != null;
    }

    private void fireToolCallEvent(String toolName, long startTimeMs, boolean success) {
        long duration = System.currentTimeMillis() - startTimeMs;
        try {
            project.getMessageBus().syncPublisher(TOOL_CALL_TOPIC)
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
        permissionPending.set(true);
        try {
            return askUserPermission(toolName, arguments);
        } finally {
            permissionPending.set(false);
        }
    }

    @Nullable
    private String askUserPermission(String toolName, JsonObject arguments) {
        ToolRegistry.ToolEntry entry = ToolRegistry.findById(toolName);
        String displayName = entry != null ? entry.displayName : toolName;
        String argsJson = arguments.toString();
        String reqId = java.util.UUID.randomUUID().toString();

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
                    + buildArgSummary(arguments) + "</html>";
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

        if (response == com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse.ALLOW_SESSION) {
            sessionAllowedTools.add(toolName);
            LOG.info("PSI Bridge: ASK approved for session for " + toolName);
            return null;
        } else if (response == com.github.catatafishen.ideagentforcopilot.bridge.PermissionResponse.ALLOW_ONCE) {
            LOG.info("PSI Bridge: ASK approved (once) for " + toolName);
            return null;
        } else {
            LOG.info("PSI Bridge: ASK denied by user for " + toolName);
            return "Permission denied by user for tool '" + toolName + "'.";
        }
    }

    private ToolPermission resolvePluginPermission(String toolName, JsonObject arguments) {
        ToolLayerSettings settings = ToolLayerSettings.getInstance(project);
        ToolRegistry.ToolEntry entry = ToolRegistry.findById(toolName);
        if (entry != null && entry.supportsPathSubPermissions) {
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
        String basePath = project.getBasePath();
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

    private static boolean isSuccessfulWrite(String toolName, String result) {
        return switch (toolName) {
            case "write_file", "intellij_write_file", "edit_text" ->
                result.startsWith("Edited:") || result.startsWith("Written:");
            case "replace_symbol_body" -> result.startsWith("Replaced lines ");
            case "insert_before_symbol" -> result.startsWith("Inserted ") && result.contains(" before ");
            case "insert_after_symbol" -> result.startsWith("Inserted ") && result.contains(" after ");
            default -> false;
        };
    }

    /**
     * Auto-run get_highlights on the edited file and append results to the write response.
     * Waits for the DaemonCodeAnalyzer to complete a pass after the edit, then collects highlights.
     */
    private String appendAutoHighlights(String writeResult, String path) {
        try {
            ToolHandler highlightHandler = toolRegistry.get("get_highlights");
            if (highlightHandler == null) return writeResult;

            // Wait for daemon to finish re-analyzing after the edit
            waitForDaemonPass();

            JsonObject highlightArgs = new JsonObject();
            highlightArgs.addProperty("path", path);
            String highlights = highlightHandler.handle(highlightArgs);
            LOG.info("Auto-highlights: appended " + highlights.split("\n").length + " lines for " + path);

            return writeResult + "\n\n--- Highlights (auto) ---\n" + highlights;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return writeResult;
        } catch (Exception e) {
            LOG.info("Auto-highlights after write failed: " + e.getMessage());
            return writeResult;
        }
    }

    /**
     * Wait for the DaemonCodeAnalyzer to finish its current pass.
     * Uses a fixed wait since DaemonCodeAnalyzer.isRunning() is internal API.
     */
    private void waitForDaemonPass() throws InterruptedException {
        Thread.sleep(2000);
        LOG.info("Auto-highlights: daemon pass wait completed (2s fixed), collecting highlights");
    }

    @Override
    public void dispose() {
        // Nothing to dispose — tool handlers are stateless, lifecycle managed by IntelliJ
    }
}
