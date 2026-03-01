package com.github.catatafishen.ideagentforcopilot.psi;

import com.github.catatafishen.ideagentforcopilot.services.CopilotSettings;
import com.github.catatafishen.ideagentforcopilot.services.ToolPermission;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Lightweight HTTP bridge exposing IntelliJ PSI/AST analysis to the MCP server.
 * The MCP server (running as a separate process) delegates tool calls here for
 * accurate code intelligence instead of regex-based scanning.
 * <p>
 * Architecture: Copilot Agent → MCP Server (stdio) → PSI Bridge (HTTP) → IntelliJ PSI
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
@Service(Service.Level.PROJECT)
public final class PsiBridgeService implements Disposable {
    private static final Logger LOG = Logger.getInstance(PsiBridgeService.class);
    private static final Gson GSON = new GsonBuilder().create();

    // HTTP Constants
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";

    private final Project project;
    private final RunConfigurationService runConfigService;
    private final Map<String, ToolHandler> toolRegistry = new LinkedHashMap<>();
    private final FileTools fileTools;
    private HttpServer httpServer;
    private int port;

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

    @SuppressWarnings("unused") // Public API - may be used by external integrations
    public int getPort() {
        return port;
    }

    /**
     * Runs deferred auto-format and import optimization on all files modified during the turn.
     */
    public void flushPendingAutoFormat() {
        fileTools.flushPendingAutoFormat();
    }

    public synchronized void start() {
        if (httpServer != null) return;
        try {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            httpServer.createContext("/tools/call", this::handleToolCall);
            httpServer.createContext("/tools/list", this::handleToolsList);
            httpServer.createContext("/health", this::handleHealth);
            httpServer.createContext("/reload", this::handleReload);
            httpServer.setExecutor(Executors.newFixedThreadPool(8));
            httpServer.start();
            port = httpServer.getAddress().getPort();
            writeBridgeFile();
            LOG.info("PSI Bridge started on port " + port + " for project: " + project.getBasePath());
        } catch (Exception e) {
            LOG.error("Failed to start PSI Bridge", e);
            String detail = buildExceptionDetail(e);
            com.intellij.notification.Notification notification =
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup("Copilot Notifications")
                    .createNotification(
                        "IDE Agent for Copilot: PSI bridge failed to start",
                        "IntelliJ code tools will be unavailable.\n" + detail,
                        com.intellij.notification.NotificationType.ERROR);
            notification.addAction(com.intellij.notification.NotificationAction.createSimple(
                "Open IDE Log", () -> com.intellij.ide.actions.RevealFileAction.openFile(
                    new java.io.File(com.intellij.openapi.application.PathManager.getLogPath(), "idea.log"))));
            notification.notify(project);
        }
    }

    /**
     * Builds a concise but informative error summary from an exception chain.
     */
    private static String buildExceptionDetail(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cause = t;
        int depth = 0;
        while (cause != null && depth < 4) {
            if (depth > 0) sb.append("\nCaused by: ");
            String name = cause.getClass().getSimpleName();
            String msg = cause.getMessage();
            sb.append(name);
            if (msg != null && !msg.isBlank()) sb.append(": ").append(msg);
            cause = cause.getCause();
            depth++;
        }
        return sb.toString();
    }

    // Serialises all bridge-file writes/removes within the same JVM (single IntelliJ process).
    // File-level locking across separate IDE processes is not needed in practice.
    private static final Object BRIDGE_FILE_LOCK = new Object();

    private void writeBridgeFile() {
        if (ApplicationManager.getApplication().isUnitTestMode()) {
            LOG.info("Skipping bridge file write in unit test mode");
            return;
        }
        String projectPath = project.getBasePath();
        if (projectPath == null) return;

        synchronized (BRIDGE_FILE_LOCK) {
            try {
                Path bridgeDir = Path.of(System.getProperty("user.home"), ".copilot");
                Files.createDirectories(bridgeDir);
                Path bridgeFile = bridgeDir.resolve("psi-bridge.json");

                JsonObject registry = readRegistry(bridgeFile);

                // Add / update our entry
                JsonObject entry = new JsonObject();
                entry.addProperty("port", port);
                registry.add(projectPath, entry);

                Files.writeString(bridgeFile, GSON.toJson(registry));
                LOG.info("Bridge registry updated: " + registry.size() + " project(s) registered");
            } catch (IOException e) {
                LOG.error("Failed to write bridge file", e);
            }
        }
    }

    /**
     * Reads the bridge registry file. Handles both the legacy single-entry format
     * ({@code {"port":N,"projectPath":"…"}}) and the new multi-project map format.
     * Returns an empty object on any parse or I/O error.
     */
    private static JsonObject readRegistry(Path bridgeFile) {
        if (!Files.exists(bridgeFile)) return new JsonObject();
        try {
            String content = Files.readString(bridgeFile);
            JsonElement el = JsonParser.parseString(content);
            if (!el.isJsonObject()) return new JsonObject();
            JsonObject obj = el.getAsJsonObject();
            // Old single-entry format — discard; projects will re-register on startup.
            if (obj.has("port")) return new JsonObject();
            return obj;
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        boolean indexing = com.intellij.openapi.project.DumbService.getInstance(project).isDumb();
        JsonObject health = new JsonObject();
        health.addProperty("status", "ok");
        health.addProperty("indexing", indexing);
        byte[] resp = GSON.toJson(health).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(CONTENT_TYPE_HEADER, APPLICATION_JSON);
        exchange.sendResponseHeaders(200, resp.length);
        exchange.getResponseBody().write(resp);
        exchange.getResponseBody().close();
    }

    /**
     * Hot-reload endpoint for local development. Called by the deployToMainIde Gradle task.
     * Uses pure reflection to avoid direct references to internal APIs (verifier-safe).
     */
    @SuppressWarnings({"java:S3011", "java:S1181"})
    private void handleReload(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        try {
            var pluginIdClass = Class.forName("com.intellij.openapi.extensions.PluginId");
            var getId = pluginIdClass.getMethod("getId", String.class);
            var pluginId = getId.invoke(null, "com.github.catatafishen.ideagentforcopilot");

            var pmcClass = Class.forName("com.intellij.ide.plugins.PluginManagerCore");
            var findPlugin = pmcClass.getMethod("findPlugin", pluginIdClass);
            var descriptor = findPlugin.invoke(null, pluginId);
            if (descriptor == null) {
                sendJsonResponse(exchange, 404, "Plugin not installed");
                return;
            }

            var getPluginPath = descriptor.getClass().getMethod("getPluginPath");
            var pluginPath = (java.nio.file.Path) getPluginPath.invoke(descriptor);

            var dynClass = Class.forName("com.intellij.ide.plugins.DynamicPlugins");
            var implClass = Class.forName("com.intellij.ide.plugins.IdeaPluginDescriptorImpl");
            var allowMethod = dynClass.getMethod("allowLoadUnloadWithoutRestart", implClass);
            boolean dynamic = (boolean) allowMethod.invoke(null, descriptor);
            if (!dynamic) {
                sendJsonResponse(exchange, 409, "Not dynamic-compatible — restart IDE");
                return;
            }

            sendJsonResponse(exchange, 200, "Reloading...");

            // Schedule async reload so the HTTP response is sent before our classloader dies
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    Thread.sleep(500);
                    performReflectiveReload(pluginId, pluginPath);
                } catch (Throwable t) {
                    LOG.error("Hot-reload failed", t);
                }
            });
        } catch (Throwable t) {
            LOG.error("Reload endpoint error", t);
            sendJsonResponse(exchange, 500, "Error: " + t.getMessage());
        }
    }

    private void sendJsonResponse(HttpExchange exchange, int code, String message) throws IOException {
        JsonObject resp = new JsonObject();
        resp.addProperty("status", code == 200 ? "ok" : "error");
        resp.addProperty("message", message);
        byte[] bytes = GSON.toJson(resp).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(CONTENT_TYPE_HEADER, APPLICATION_JSON);
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    /** Unload → reload cycle using pure reflection (no direct internal API imports). */
    @SuppressWarnings({"java:S3011", "java:S1181"})
    private void performReflectiveReload(Object pluginId, java.nio.file.Path installPath) {
        try {
            var pluginIdClass = Class.forName("com.intellij.openapi.extensions.PluginId");
            var pmcClass = Class.forName("com.intellij.ide.plugins.PluginManagerCore");
            var dynClass = Class.forName("com.intellij.ide.plugins.DynamicPlugins");
            var dynInstance = dynClass.getField("INSTANCE").get(null);
            var descriptorClass = Class.forName("com.intellij.ide.plugins.PluginMainDescriptor");

            var findPlugin = pmcClass.getMethod("findPlugin", pluginIdClass);
            var descriptor = findPlugin.invoke(null, pluginId);
            if (descriptor == null) { LOG.error("Hot-reload: descriptor gone"); return; }

            LOG.info("Hot-reload: unloading...");
            var unload = dynInstance.getClass().getMethod("unloadPlugin", descriptorClass);
            boolean unloaded = (boolean) unload.invoke(dynInstance, descriptor);
            if (!unloaded) { LOG.error("Hot-reload: unload failed"); return; }
            LOG.info("Hot-reload: unloaded");

            var loaderClass = Class.forName("com.intellij.ide.plugins.PluginDescriptorLoader");
            var getBuildNumber = pmcClass.getMethod("getBuildNumber");
            var buildNumber = getBuildNumber.invoke(null);
            var loadDesc = loaderClass.getMethod("loadDescriptorFromArtifact",
                java.nio.file.Path.class, Class.forName("com.intellij.openapi.util.BuildNumber"));
            var newDescriptor = loadDesc.invoke(null, installPath, buildNumber);
            if (newDescriptor == null) { LOG.error("Hot-reload: no descriptor at " + installPath); return; }

            var loadPlugin = dynInstance.getClass().getMethod("loadPlugin", descriptorClass);
            boolean loaded = (boolean) loadPlugin.invoke(dynInstance, newDescriptor);
            LOG.info(loaded ? "Hot-reload: success!" : "Hot-reload: loadPlugin returned false");
        } catch (Throwable t) {
            LOG.error("Hot-reload: reflection failed", t);
        }
    }

    private void handleToolsList(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        JsonArray tools = new JsonArray();
        for (String name : toolRegistry.keySet()) {
            JsonObject tool = new JsonObject();
            tool.addProperty("name", name);
            tools.add(tool);
        }

        JsonObject response = new JsonObject();
        response.add("tools", tools);
        byte[] bytes = GSON.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(CONTENT_TYPE_HEADER, APPLICATION_JSON);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    private void handleToolCall(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject request = JsonParser.parseString(body).getAsJsonObject();
        String toolName = request.get("name").getAsString();
        JsonObject arguments = request.has("arguments")
            ? request.getAsJsonObject("arguments") : new JsonObject();

        LOG.info("PSI Bridge tool call: " + toolName + " args=" + arguments);

        // Enforce per-tool permissions (DENY / ASK / ALLOW)
        String denied = checkPluginToolPermission(toolName, arguments);
        if (denied != null) {
            JsonObject response = new JsonObject();
            response.addProperty("result", denied);
            byte[] bytes = GSON.toJson(response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(CONTENT_TYPE_HEADER, APPLICATION_JSON);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
            return;
        }

        String result;
        try {
            ToolHandler handler = toolRegistry.get(toolName);
            result = handler != null ? handler.handle(arguments) : "Unknown tool: " + toolName;
        } catch (com.intellij.openapi.application.ex.ApplicationUtil.CannotRunReadActionException e) {
            result = "Error: IDE is busy, please retry. " + e.getMessage();
        } catch (Exception e) {
            LOG.warn("PSI tool error: " + toolName, e);
            result = "Error: " + e.getMessage();
        }

        // Piggyback highlights after successful write operations
        if (isSuccessfulWrite(toolName, result) && arguments.has("path")) {
            LOG.info("Auto-highlights: piggybacking on write to " + arguments.get("path").getAsString());
            result = appendAutoHighlights(result, arguments.get("path").getAsString());
        }

        JsonObject response = new JsonObject();
        response.addProperty("result", result);
        byte[] bytes = GSON.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(CONTENT_TYPE_HEADER, APPLICATION_JSON);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
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

        // ASK: show a permission bubble in the chat panel and block until user responds
        ToolRegistry.ToolEntry entry = ToolRegistry.findById(toolName);
        String displayName = entry != null ? entry.displayName : toolName;
        String argsJson = arguments.toString();
        String reqId = java.util.UUID.randomUUID().toString();

        com.github.catatafishen.ideagentforcopilot.ui.ChatConsolePanel chatPanel =
            com.github.catatafishen.ideagentforcopilot.ui.ChatConsolePanel.Companion.getInstance(project);

        boolean allowed;
        if (chatPanel != null) {
            java.util.concurrent.CompletableFuture<Boolean> future = new java.util.concurrent.CompletableFuture<>();
            ApplicationManager.getApplication().invokeLater(() ->
                chatPanel.showPermissionRequest(reqId, displayName, argsJson, result -> {
                    future.complete(result);
                    return kotlin.Unit.INSTANCE;
                })
            );
            try {
                allowed = future.get(120, java.util.concurrent.TimeUnit.SECONDS);
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
            ApplicationManager.getApplication().invokeAndWait(() -> {
                String message = "<html><b>Allow: " + StringUtil.escapeXmlEntities(displayName) + "</b><br><br>"
                    + buildArgSummary(arguments) + "</html>";
                int choice = Messages.showYesNoDialog(
                    project, message, "Tool Permission Request",
                    "Allow", "Deny", Messages.getQuestionIcon()
                );
                result[0] = choice == Messages.YES;
            });
            allowed = result[0];
        }

        if (allowed) {
            LOG.info("PSI Bridge: ASK approved by user for " + toolName);
            return null;
        } else {
            LOG.info("PSI Bridge: ASK denied by user for " + toolName);
            return "Permission denied by user for tool '" + toolName + "'.";
        }
    }

    private ToolPermission resolvePluginPermission(String toolName, JsonObject arguments) {
        ToolRegistry.ToolEntry entry = ToolRegistry.findById(toolName);
        if (entry != null && entry.supportsPathSubPermissions) {
            String path = extractPathArg(arguments);
            if (path != null && !path.isEmpty()) {
                boolean inside = isInsideProject(path);
                return CopilotSettings.resolveEffectivePermission(toolName, inside);
            }
        }
        return CopilotSettings.getToolPermission(toolName);
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
        if (args.size() == 0) return "No arguments.";
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
        return ("write_file".equals(toolName) || "intellij_write_file".equals(toolName))
            && (result.startsWith("Edited:") || result.startsWith("Written:"));
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

    public void dispose() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
            LOG.info("PSI Bridge stopped");
        }
        if (ApplicationManager.getApplication().isUnitTestMode()) return;
        String projectPath = project.getBasePath();
        if (projectPath == null) return;
        synchronized (BRIDGE_FILE_LOCK) {
            try {
                Path bridgeFile = Path.of(System.getProperty("user.home"), ".copilot", "psi-bridge.json");
                JsonObject registry = readRegistry(bridgeFile);
                registry.remove(projectPath);
                // Normalise slashes in case the key was stored differently
                registry.remove(projectPath.replace('/', '\\'));
                registry.remove(projectPath.replace('\\', '/'));
                if (registry.size() == 0) {
                    Files.deleteIfExists(bridgeFile);
                } else {
                    Files.writeString(bridgeFile, GSON.toJson(registry));
                }
            } catch (IOException e) {
                LOG.warn("Failed to clean up bridge file", e);
            }
        }
    }
}
