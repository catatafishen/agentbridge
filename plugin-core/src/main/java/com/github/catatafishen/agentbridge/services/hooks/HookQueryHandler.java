package com.github.catatafishen.agentbridge.services.hooks;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.sun.net.httpserver.HttpExchange;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

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
public final class HookQueryHandler extends AbstractHookHandler {

    private static final int MAX_BODY_BYTES = 8192;

    public HookQueryHandler(@NotNull Project project) {
        super(project);
    }

    @Override
    int maxBodyBytes() {
        return MAX_BODY_BYTES;
    }

    @Override
    String handlerName() {
        return "Hook query";
    }

    @Override
    String processPost(@NotNull JsonObject request) {
        String action = request.has("action") ? request.get("action").getAsString() : "";
        if ("classify_path".equals(action)) {
            return classifyPath(request);
        }
        return errorJson("Unknown action: " + action);
    }

    @Override
    void sendError(@NotNull HttpExchange exchange, int status, @NotNull String message) throws IOException {
        sendJson(exchange, status, errorJson(message));
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
}
