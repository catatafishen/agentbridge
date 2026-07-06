package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.psi.FileAccessTracker;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.ReadFileRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
    static final int MAX_READ_LINES = 2000;

    public ReadFileTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "read_file";
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
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("path", TYPE_STRING, "Absolute or project-relative path to the file to read"),
            Param.optional(PARAM_START_LINE, TYPE_INTEGER,
                "Optional: first line to read (1-based, inclusive). 0, null, or empty means start of file."),
            Param.optional(PARAM_END_LINE, TYPE_INTEGER,
                "Optional: last line to read (1-based, inclusive). Use with start_line to read a range. "
                    + "0, null, or empty means end of file. Reads are always capped at " + MAX_READ_LINES + " lines.")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return ReadFileRenderer.INSTANCE;
    }

    private String validateInput(@NotNull JsonObject args) {
        if (!args.has("path") || args.get("path").isJsonNull())
            return ToolUtils.ERROR_PATH_REQUIRED;
        String lineParamError = validateLineParams(args);
        if (lineParamError != null) return lineParamError;
        return null;
    }

    private record FileReadResult(String error, String text, int startLine, int endLine) {
        static FileReadResult error(String message) {
            return new FileReadResult(message, null, 0, 0);
        }
    }

    /**
     * A line bound is "unset" — and defaults to start-of-file (start_line) or EOF (end_line) —
     * when the key is absent, JSON null, or a blank string. These sentinels are intentional:
     * agents routinely pass 0/null/"" to mean "no bound", so we treat them as such rather than
     * erroring. A non-blank, non-numeric value is still a hard error (see validateLineParams).
     */
    private static boolean isUnsetLineParam(@NotNull JsonObject args, String param) {
        if (!args.has(param) || args.get(param).isJsonNull()) {
            return true;
        }
        return args.get(param).isJsonPrimitive()
            && args.get(param).getAsJsonPrimitive().isString()
            && args.get(param).getAsString().isBlank();
    }

    /**
     * Returns the 1-based line bound, or 0 when unset (see {@link #isUnsetLineParam}).
     */
    private int getLineParam(@NotNull JsonObject args, String paramName) {
        return isUnsetLineParam(args, paramName) ? 0 : args.get(paramName).getAsInt();
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        String inputError = validateInput(args);
        if (inputError != null) {
            return inputError;
        }
        String pathStr = args.get("path").getAsString();
        // Range mode is active when at least one bound is set (> 0).
        // A missing/0/null bound means "unset": start defaults to line 1, end defaults to EOF.
        // Only when NEITHER bound is set do we fall back to buildFullResult (plain header + raw text).
        int startLine = getLineParam(args, PARAM_START_LINE);
        int endLine = getLineParam(args, PARAM_END_LINE);
        boolean rangeMode = startLine > 0 || endLine > 0;

        FileReadResult result = ReadAction.nonBlocking(
            () -> buildResult(pathStr, startLine, endLine, rangeMode)
        ).executeSynchronously();

        if (result.error() != null) {
            return result.error();
        }
        followFileIfEnabled(project, pathStr, result.startLine(), result.endLine(),
            HIGHLIGHT_READ, agentLabel(project) + " is reading");
        FileAccessTracker.recordRead(project, pathStr);
        return result.text();
    }

    private FileReadResult buildResult(String pathStr, int startLine, int endLine, boolean rangeMode) {
        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) return FileReadResult.error(ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
        String content;
        try {
            content = readFileContent(vf);
        } catch (IOException e) {
            return FileReadResult.error("Error reading file: " + e.getMessage());
        }
        String[] lines = content.split("\n", -1);
        return rangeMode
            ? buildRangeResult(lines, startLine, endLine)
            : buildFullResult(content, lines, getDirectoryMarkingHint(vf));
    }

    /**
     * Numbered slice [startLine, endLine] (1-based inclusive), capped at {@link #MAX_READ_LINES}.
     * 0 means "unset": startLine=0 defaults to line 1, endLine=0 defaults to EOF.
     * No line-total header — this is a targeted range read.
     */
    private FileReadResult buildRangeResult(String[] lines, int startLine, int endLine) {
        // 0 means "unset": start defaults to line 1 (index 0), end defaults to EOF
        int from = startLine == 0 ? 0 : Math.min(startLine - 1, lines.length);
        int to = endLine == 0 ? lines.length : Math.min(endLine, lines.length);
        if (to < from) to = from;
        StringBuilder sb = new StringBuilder();
        if (to - from > MAX_READ_LINES) {
            to = from + MAX_READ_LINES;
            sb.append(overflowNotice());
        }
        for (int i = from; i < to; i++) {
            sb.append(i + 1).append(": ").append(lines[i]).append("\n");
        }
        return new FileReadResult(null, sb.toString(), from + 1, to);
    }

    /**
     * Full-file read: {@code [N lines total]} header, optional directory hint, then raw content
     * truncated to the first {@link #MAX_READ_LINES} lines.
     */
    private FileReadResult buildFullResult(String content, String[] lines, String hint) {
        int totalLines = lines.length;
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(totalLines).append(" lines total]\n");
        if (hint != null && !hint.isEmpty()) {
            sb.append(hint);
        }
        if (totalLines > MAX_READ_LINES) {
            sb.append(overflowNotice());
            sb.append(String.join("\n", Arrays.copyOf(lines, MAX_READ_LINES)));
        } else {
            sb.append(content);
        }
        return new FileReadResult(null, sb.toString(), 1, Math.min(totalLines, MAX_READ_LINES));
    }

    private static String overflowNotice() {
        return "[Selection too long! Result truncated to maximum " + MAX_READ_LINES
            + " lines. Use start_line/end_line to read specific sections.]\n";
    }

    private @Nullable String validateLineParams(@NotNull JsonObject args) {
        for (String param : new String[]{PARAM_START_LINE, PARAM_END_LINE}) {
            // 0, null, and blank are intentional "no bound" sentinels — skip validation.
            if (isUnsetLineParam(args, param)) continue;
            int value;
            try {
                value = args.get(param).getAsInt();
            } catch (Exception e) {
                return ToolUtils.ERROR_PREFIX + param + " must be an integer, got: " + args.get(param);
            }
            if (value < 0)
                return ToolUtils.ERROR_PREFIX + param + " must be >= 0 (0 means start/end of file), got: " + value;
        }
        return null;
    }

    private String readFileContent(VirtualFile vf) throws IOException {
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc != null) {
            return doc.getText();
        }
        return new String(vf.contentsToByteArray(), StandardCharsets.UTF_8);
    }

    private String getDirectoryMarkingHint(VirtualFile vf) {
        var fileIndex = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project);
        if (fileIndex.isExcluded(vf)) {
            return "[excluded – this is a build output/generated file; prefer editing the source instead]\n";
        }
        if (fileIndex.isInGeneratedSources(vf)) {
            return "[generated – this file is auto-generated; prefer editing the source instead]\n";
        }
        if (fileIndex.isInTestSourceContent(vf)) {
            return "[test]\n";
        }
        if (fileIndex.isInSourceContent(vf)) {
            return "[source]\n";
        }
        return "";
    }
}
