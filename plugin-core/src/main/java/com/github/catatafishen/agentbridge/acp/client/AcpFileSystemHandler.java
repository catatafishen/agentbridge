package com.github.catatafishen.agentbridge.acp.client;

import com.google.gson.JsonObject;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Handles ACP file system methods: {@code fs/read_text_file} and {@code fs/write_text_file}.
 * <p>
 * Reads from IntelliJ's editor buffer (includes unsaved changes), writes through
 * the Document API to maintain editor/VFS consistency.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/file-system">ACP File System</a>
 */
final class AcpFileSystemHandler {

    private static final Logger LOG = Logger.getInstance(AcpFileSystemHandler.class);

    private final Project project;

    AcpFileSystemHandler(Project project) {
        this.project = project;
    }

    JsonObject readTextFile(@NotNull JsonObject params) {
        String path = getRequiredString(params, "path");
        Integer startLine = getOptionalInt(params, "line");
        Integer lineLimit = getOptionalInt(params, "limit");

        String absolutePath = resolveAbsolutePath(path);
        String[] holder = new String[1];
        com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(() -> {
            VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(absolutePath);
            if (vf == null || vf.isDirectory()) {
                throw new IllegalArgumentException("File not found: " + absolutePath);
            }

            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc != null) {
                holder[0] = doc.getText();
            } else {
                try {
                    holder[0] = new String(vf.contentsToByteArray(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read file: " + absolutePath, e);
                }
            }
        });

        String content = holder[0];

        if (startLine != null || lineLimit != null) {
            String[] lines = content.split("\n", -1);
            int start = (startLine != null ? startLine : 1) - 1;
            int end = lineLimit != null ? Math.min(start + lineLimit, lines.length) : lines.length;
            start = Math.max(0, start);

            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                if (i > start) sb.append('\n');
                sb.append(lines[i]);
            }
            content = sb.toString();
        }

        JsonObject result = new JsonObject();
        result.addProperty("content", content);
        return result;
    }

    JsonObject writeTextFile(@NotNull JsonObject params) {
        String path = getRequiredString(params, "path");
        String content = getRequiredString(params, "content");

        String absolutePath = resolveAbsolutePath(path);

        com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait(() ->
            com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction(() -> {
                VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(absolutePath);

                if (vf == null) {
                    createNewFile(absolutePath, content);
                    return;
                }

                Document doc = FileDocumentManager.getInstance().getDocument(vf);
                if (doc != null) {
                    CommandProcessor.getInstance().executeCommand(project, () ->
                        doc.setText(content), "ACP Write Text File", null);
                    FileDocumentManager.getInstance().saveDocument(doc);
                } else {
                    try {
                        vf.setBinaryContent(content.getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to write file: " + absolutePath, e);
                    }
                }
            }));

        LOG.info("Wrote " + content.length() + " chars to " + absolutePath);
        return null;
    }

    private void createNewFile(String absolutePath, String content) {
        try {
            Path filePath = Path.of(absolutePath);
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, content, StandardCharsets.UTF_8);
            // Refresh VFS so IntelliJ picks up the new file
            LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath);
            LOG.info("Created new file: " + absolutePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create file: " + absolutePath, e);
        }
    }

    private String resolveAbsolutePath(String path) {
        if (path.startsWith("/") || path.startsWith("\\") || (path.length() > 1 && path.charAt(1) == ':')) {
            return path;
        }
        // Relative to project base path
        String basePath = project.getBasePath();
        if (basePath != null) {
            return basePath + "/" + path;
        }
        return path;
    }

    private static String getRequiredString(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        return obj.get(key).getAsString();
    }

    private static Integer getOptionalInt(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsInt();
        }
        return null;
    }
}
