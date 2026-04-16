package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.psi.CodeChangeTracker;
import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.FileAccessTracker;
import com.github.catatafishen.agentbridge.ui.renderers.WriteFileRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("java:S112")
public final class CreateFileTool extends FileTool {

    private static final String FORMAT_CHARS_SUFFIX = " chars)";
    private static final String PARAM_CONTENT = "content";

    public CreateFileTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "create_file";
    }

    @Override
    public @NotNull String displayName() {
        return "Create File";
    }

    @Override
    public @NotNull String description() {
        return "Create a new file at the given path. File must not already exist — use write_file to overwrite existing files. " +
            "Does NOT update references. Use refactor(operation='rename') for reference-aware renames.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Create {path}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("path", TYPE_STRING, "Path for the new file (absolute or project-relative). File must not already exist"),
            Param.required(PARAM_CONTENT, TYPE_STRING, "Content to write to the file")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return WriteFileRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("path") || !args.has(PARAM_CONTENT)) {
            return "Error: 'path' and 'content' parameters are required";
        }
        String pathStr = args.get("path").getAsString();
        String content = args.get(PARAM_CONTENT).getAsString();

        String basePath = project.getBasePath();
        Path pathObj = Path.of(pathStr);
        Path filePath;
        if (pathObj.isAbsolute()) {
            filePath = pathObj;
        } else if (basePath != null) {
            filePath = Path.of(basePath, pathStr);
        } else {
            return "Error: Cannot resolve relative path without project base path";
        }

        if (Files.exists(filePath)) {
            return "Error: File already exists: " + pathStr +
                ". Use edit_text to modify existing files.";
        }

        Path parentDir = filePath.getParent();
        if (parentDir != null) {
            Files.createDirectories(parentDir);
        }
        Files.writeString(filePath, content, StandardCharsets.UTF_8);

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        int lineCount = content.split("\n", -1).length;
        EdtUtil.invokeLater(() -> {
            try {
                LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath.toString());
                resultFuture.complete("✓ Created file: " + pathStr + " (" + content.length() + FORMAT_CHARS_SUFFIX);
            } catch (Exception e) {
                resultFuture.complete("File created but VFS refresh failed: " + e.getMessage());
            }
        });

        String result = resultFuture.get(10, TimeUnit.SECONDS);
        CodeChangeTracker.recordChange(lineCount, 0);
        notifyFileCreated(project, pathStr);
        followFileIfEnabled(project, pathStr, 1, lineCount, HIGHLIGHT_EDIT, agentLabel(project) + " created");
        FileAccessTracker.recordWrite(project, pathStr);
        return result;
    }
}
