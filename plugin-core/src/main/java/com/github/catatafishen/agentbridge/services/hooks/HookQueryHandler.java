package com.github.catatafishen.agentbridge.services.hooks;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.sun.net.httpserver.HttpExchange;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Handles {@code POST /hooks/query} requests from hook scripts.
 * Provides IDE-aware utilities that shell scripts cannot compute on their own,
 * such as per-file source root classification using IntelliJ's project model.
 *
 * <p>Supported actions:</p>
 * <ul>
 *   <li>{@code classify_path} — classifies a file path as sources/test_sources/resources/etc.</li>
 * </ul>
 */
public final class HookQueryHandler {
    private static final Logger LOG = Logger.getInstance(HookQueryHandler.class);
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";
    private static final int MAX_BODY_BYTES = 8192;

    private final Project project;

    public HookQueryHandler(@NotNull Project project) {
        this.project = project;
    }

    public void handle(@NotNull HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");

        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Only POST is supported");
            return;
        }

        try {
            byte[] bodyBytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (bodyBytes.length > MAX_BODY_BYTES) {
                sendError(exchange, 413, "Request body exceeds " + MAX_BODY_BYTES + " bytes");
                return;
            }

            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            JsonObject request = JsonParser.parseString(body).getAsJsonObject();
            String action = request.has("action") ? request.get("action").getAsString() : "";

            String response;
            if ("classify_path".equals(action)) {
                response = classifyPath(request);
            } else {
                response = errorJson("Unknown action: " + action);
            }

            sendJson(exchange, 200, response);
        } catch (Exception e) {
            LOG.warn("Hook query error", e);
            sendError(exchange, 500, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            exchange.close();
        }
    }

    private String classifyPath(@NotNull JsonObject request) {
        if (!request.has("path")) {
            return errorJson("'path' is required for classify_path");
        }
        String path = request.get("path").getAsString();

        JsonObject result = new JsonObject();
        result.addProperty("path", path);

        String basePath = project.getBasePath();
        boolean inProject = basePath != null && path.startsWith(basePath);
        result.addProperty("inProject", inProject);

        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(path);
        if (vf == null) {
            result.addProperty("classification", "");
            result.addProperty("inContentRoot", false);
            result.addProperty("note", "File not found in VFS");
            return result.toString();
        }

        String classification = ApplicationManager.getApplication().runReadAction(
            (com.intellij.openapi.util.Computable<String>) () -> {
                ProjectFileIndex index = ProjectFileIndex.getInstance(project);
                if (index.isExcluded(vf)) return "excluded";
                String sourceClass = PlatformApiCompat.classifyFileSourceRoot(index, vf);
                if (!sourceClass.isEmpty()) return sourceClass;
                if (index.getContentRootForFile(vf) != null) return "content";
                return "";
            });

        result.addProperty("classification", classification);
        result.addProperty("inContentRoot", !classification.isEmpty() && !"excluded".equals(classification));

        return result.toString();
    }

    private static String errorJson(String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", message);
        return obj.toString();
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(CONTENT_TYPE, APPLICATION_JSON);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, errorJson(message));
    }
}
