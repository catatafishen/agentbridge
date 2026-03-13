package com.github.catatafishen.ideagentforcopilot.psi.tools.file;

import com.github.catatafishen.ideagentforcopilot.psi.FileAccessTracker;
import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.ReadFileRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reads a file via IntelliJ's editor buffer.
 */
@SuppressWarnings("java:S112")
public class ReadFileTool extends FileTool {

    private static final String PARAM_START_LINE = "start_line";
    private static final String PARAM_END_LINE = "end_line";
    private static final int MAX_READ_LINES = 2000;

    public ReadFileTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "intellij_read_file";
    }

    @Override
    public @NotNull String displayName() {
        return "Read File";
    }

    @Override
    public @NotNull String description() {
        return "Read a file via IntelliJ's editor buffer -- always returns the current in-memory content";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Absolute or project-relative path to the file to read"},
            {PARAM_START_LINE, TYPE_INTEGER, "Optional: first line to read (1-based, inclusive)"},
            {PARAM_END_LINE, TYPE_INTEGER, "Optional: last line to read (1-based, inclusive). Use with start_line to read a range"}
        }, "path");
    }

    @Override
    public @NotNull Object resultRenderer() {
        return ReadFileRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        if (!args.has("path") || args.get("path").isJsonNull())
            return ToolUtils.ERROR_PATH_REQUIRED;
        String pathStr = args.get("path").getAsString();
        int startLine = args.has(PARAM_START_LINE) ? args.get(PARAM_START_LINE).getAsInt() : -1;
        int endLine = args.has(PARAM_END_LINE) ? args.get(PARAM_END_LINE).getAsInt() : -1;

        String result = ReadAction.compute(() -> {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) return ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;

            String content = readFileContent(vf);
            if (content.startsWith("Error")) return content;

            if (startLine > 0 || endLine > 0) {
                return extractLineRange(content, startLine, endLine);
            }

            String hint = getDirectoryMarkingHint(vf);
            return applyReadHintAndTruncate(content, hint);
        });

        followFileIfEnabled(project, pathStr, startLine > 0 ? startLine : -1, endLine > 0 ? endLine : -1,
            HIGHLIGHT_READ, agentLabel(project) + " is reading");
        FileAccessTracker.recordRead(project, pathStr);
        return result;
    }

    private String readFileContent(VirtualFile vf) {
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc != null) {
            return doc.getText();
        }
        try {
            return new String(vf.contentsToByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    private String getDirectoryMarkingHint(VirtualFile vf) {
        var fileIndex = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project);
        if (fileIndex.isExcluded(vf)) {
            return "[excluded – this is a build output/generated file; prefer editing the source instead]";
        }
        if (fileIndex.isInGeneratedSources(vf)) {
            return "[generated – this file is auto-generated; prefer editing the source instead]";
        }
        if (fileIndex.isInTestSourceContent(vf)) {
            return "[test]";
        }
        if (fileIndex.isInSourceContent(vf)) {
            return "[source]";
        }
        return null;
    }

    private String applyReadHintAndTruncate(String content, String hint) {
        String[] lines = content.split("\n", -1);
        if (lines.length > MAX_READ_LINES) {
            String truncated = String.join("\n", Arrays.copyOfRange(lines, 0, MAX_READ_LINES));
            String header = "[Large file: " + lines.length + " lines total — showing first " + MAX_READ_LINES
                + " lines. Use start_line/end_line to read specific sections.]\n";
            return (hint != null ? hint + "\n" : "") + header + truncated;
        }
        return hint != null ? hint + "\n" + content : content;
    }

    private static String extractLineRange(String content, int startLine, int endLine) {
        String[] lines = content.split("\n", -1);
        int from = Math.max(0, (startLine > 0 ? startLine - 1 : 0));
        int to = Math.min(lines.length, (endLine > 0 ? endLine : lines.length));
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(i + 1).append(": ").append(lines[i]).append("\n");
        }
        return sb.toString();
    }
}
