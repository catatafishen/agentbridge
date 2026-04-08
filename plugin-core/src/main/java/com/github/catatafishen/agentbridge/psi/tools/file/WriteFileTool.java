package com.github.catatafishen.agentbridge.psi.tools.file;

import com.github.catatafishen.agentbridge.psi.CodeChangeTracker;
import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.FileAccessTracker;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.WriteFileRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Writes full file content or creates a new file through IntelliJ's editor buffer.
 * Also serves as the base for {@link EditTextTool} which shares the same write logic.
 */
@SuppressWarnings("java:S112")
public class WriteFileTool extends FileTool {

    private static final Logger LOG = Logger.getInstance(WriteFileTool.class);

    protected static final String PARAM_CONTENT = "content";
    protected static final String PARAM_START_LINE = "start_line";
    protected static final String PARAM_END_LINE = "end_line";
    protected static final String PARAM_NEW_STR = "new_str";
    private static final String FORMAT_CHARS_SUFFIX = " chars)";
    private static final String AUTO_FORMAT_SUFFIX = " (auto-format queued)";
    private static final String PARAM_AUTO_FORMAT = "auto_format_and_optimize_imports";
    private static final String PARAM_AUTO_FORMAT_LEGACY = "auto_format";
    private static final String MSG_CANNOT_OPEN = "Cannot open document: ";
    private static final String MSG_EDITED_PREFIX = "Edited: ";

    public WriteFileTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "write_file";
    }

    @Override
    public @NotNull String displayName() {
        return "Write File";
    }

    @Override
    public @NotNull String description() {
        return "Write full file content or create a new file through IntelliJ's editor buffer. "
            + "Auto-format and import optimization is deferred until turn end "
            + "(controlled by auto_format_and_optimize_imports param)";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Write {path}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"path", TYPE_STRING, "Absolute or project-relative path to the file to write or create"},
            {PARAM_CONTENT, TYPE_STRING, "Full file content to write (replaces entire file). Creates the file if it doesn't exist"},
            {PARAM_AUTO_FORMAT, TYPE_BOOLEAN,
                "Auto-format code AND optimize imports after writing (default: true). "
                    + "Formatting is DEFERRED until the end of the current turn or before git commit — "
                    + "safe for multi-step edits within a single turn. "
                    + "⚠️ Import optimization REMOVES imports it considers unused — "
                    + "if you add imports in one edit and reference them in a later edit, "
                    + "set this to false or combine both changes in one edit"}
        }, "path", PARAM_CONTENT);
    }

    @Override
    public @NotNull Object resultRenderer() {
        return WriteFileRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("path") || args.get("path").isJsonNull())
            return ToolUtils.ERROR_PATH_REQUIRED;
        String pathStr = args.get("path").getAsString();
        boolean autoFormat = resolveAutoFormat(args);

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        // [0] = start line, [1] = end line (1-based) to scroll/highlight after write; -1 = don't.
        int[] followRange = {-1, -1};

        // For full-content writes of new files, do disk I/O on the current background thread
        // rather than dispatching to EDT — blocking disk operations on the UI thread cause freezes.
        if (args.has(PARAM_CONTENT)) {
            VirtualFile existingVf = com.intellij.openapi.application.ReadAction.compute(
                () -> resolveVirtualFile(pathStr));
            if (existingVf == null) {
                createNewFile(pathStr, args.get(PARAM_CONTENT).getAsString(), resultFuture);
                String result = resultFuture.get(15, TimeUnit.SECONDS);
                if (autoFormat && !result.startsWith("Error")) queueAutoFormat(project, pathStr);
                followRange[0] = 1;
                followFileIfEnabled(project, pathStr, followRange[0], followRange[1],
                    HIGHLIGHT_EDIT, agentLabel(project) + " is editing");
                FileAccessTracker.recordWrite(project, pathStr);
                return result;
            }
        }

        EdtUtil.invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);

                if (args.has(PARAM_CONTENT)) {
                    writeFileFullContent(vf, pathStr, args.get(PARAM_CONTENT).getAsString(),
                        autoFormat, resultFuture);
                    followRange[0] = 1;
                } else if (args.has("old_str") && args.has(PARAM_NEW_STR)) {
                    handlePartialEditArgs(vf, pathStr, args, autoFormat, resultFuture, followRange);
                } else if (args.has(PARAM_START_LINE) && args.has(PARAM_NEW_STR)) {
                    followRange[0] = args.get(PARAM_START_LINE).getAsInt();
                    writeFileLineRange(vf, pathStr, args, autoFormat, resultFuture, followRange);
                } else {
                    resultFuture.complete("write_file requires either 'content' (full write), " +
                        "'old_str'+'new_str' (partial edit), or 'start_line'+'new_str' (line-range replace)");
                }
            } catch (Exception e) {
                resultFuture.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
            }
        });

        String result = resultFuture.get(15, TimeUnit.SECONDS);
        followFileIfEnabled(project, pathStr, followRange[0], followRange[1],
            HIGHLIGHT_EDIT, agentLabel(project) + " is editing");
        FileAccessTracker.recordWrite(project, pathStr);
        return result;
    }

    private void writeFileFullContent(VirtualFile vf, String pathStr, String newContent,
                                      boolean autoFormat, CompletableFuture<String> resultFuture) {
        if (vf == null) {
            createNewFile(pathStr, newContent, resultFuture);
            return;
        }
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc != null) {
            String oldContent = doc.getText();
            WriteAction.run(() ->
                CommandProcessor.getInstance().executeCommand(
                    project, () -> doc.setText(newContent), "Write File", null)
            );
            FileDocumentManager.getInstance().saveDocument(doc);
            int[] diff = CodeChangeTracker.diffLines(oldContent, newContent);
            CodeChangeTracker.recordChange(diff[0], diff[1]);
            String syntaxWarning = checkSyntaxErrors(doc, pathStr);
            if (autoFormat && syntaxWarning.isEmpty()) queueAutoFormat(project, pathStr);
            String formatNote = autoFormat && syntaxWarning.isEmpty() ? AUTO_FORMAT_SUFFIX : "";
            resultFuture.complete("Written: " + pathStr + " (" + newContent.length() + FORMAT_CHARS_SUFFIX + formatNote + syntaxWarning);
        } else {
            WriteAction.run(() -> {
                try (var os = vf.getOutputStream(this)) {
                    os.write(newContent.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    resultFuture.complete("Error writing: " + e.getMessage());
                }
            });
            CodeChangeTracker.recordChange(CodeChangeTracker.countLines(newContent), 0);
            resultFuture.complete("Written: " + pathStr);
        }
    }

    private void createNewFile(String pathStr, String content, CompletableFuture<String> resultFuture) {
        String normalized = pathStr.replace('\\', '/');
        String basePath = project.getBasePath();
        String fullPath;
        if (normalized.startsWith("/")) {
            fullPath = normalized;
        } else if (basePath != null) {
            fullPath = Path.of(basePath, normalized).toString();
        } else {
            fullPath = normalized;
        }
        // File I/O outside the write lock — holding the write lock during disk writes delays
        // all other write-lock consumers (including IntelliJ's own VFS reload callbacks)
        Path filePath = Path.of(fullPath);
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, content);
        } catch (IOException e) {
            resultFuture.complete("Error creating file: " + e.getMessage());
            return;
        }
        // VFS refresh after file is on disk — no write lock held during I/O
        String finalFullPath = fullPath;
        WriteAction.run(() -> {
            LocalFileSystem.getInstance().refreshAndFindFileByPath(finalFullPath);
            CodeChangeTracker.recordChange(CodeChangeTracker.countLines(content), 0);
        });
        resultFuture.complete("Created: " + pathStr);
    }

    private void handlePartialEditArgs(VirtualFile vf, String pathStr, JsonObject args,
                                       boolean autoFormat, CompletableFuture<String> resultFuture,
                                       int[] followRange) {
        boolean replaceAll = args.has("replace_all") && args.get("replace_all").getAsBoolean();
        boolean caseSensitive = !args.has("case_sensitive") || args.get("case_sensitive").getAsBoolean();
        String oldStr = args.get("old_str").getAsString();
        String newStr = args.get(PARAM_NEW_STR).getAsString();
        if (replaceAll) {
            writeFileReplaceAll(vf, pathStr, oldStr, newStr, autoFormat, caseSensitive, resultFuture, followRange);
        } else {
            writeFilePartialEdit(vf, pathStr, oldStr, newStr, autoFormat, resultFuture, followRange, caseSensitive);
        }
    }

    // S107 (too many params): cohesive param set for a targeted file edit; splitting would obscure the API
    @SuppressWarnings("java:S107")
    private void writeFilePartialEdit(VirtualFile vf, String pathStr, String oldStr, String newStr,
                                      boolean autoFormat, CompletableFuture<String> resultFuture,
                                      int[] followRange, boolean caseSensitive) {
        if (vf == null) {
            resultFuture.complete(ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
            return;
        }
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) {
            resultFuture.complete(MSG_CANNOT_OPEN + pathStr);
            return;
        }
        String normalizedOld = oldStr.replace("\r\n", "\n").replace("\r", "\n");
        String normalizedNew = newStr.replace("\r\n", "\n").replace("\r", "\n");

        int[] match = findMatchPosition(doc, vf, pathStr, normalizedOld, autoFormat, caseSensitive);
        int idx = match[0];
        int matchLen = match[1];

        if (idx == -1) {
            resultFuture.complete("old_str not found in " + pathStr +
                ". Ensure the text matches exactly (check whitespace, indentation, line endings)." +
                closestMatchHint(doc.getText(), normalizedOld));
            return;
        }
        // Check for multiple matches
        String text = doc.getText();
        String searchableText = caseSensitive ? text : text.toLowerCase();
        String searchableOld = caseSensitive ? normalizedOld : normalizedOld.toLowerCase();
        String dedupText = (matchLen == normalizedOld.length()) ? searchableText : ToolUtils.normalizeForMatch(searchableText);
        String dedupOld = (matchLen == normalizedOld.length()) ? searchableOld : ToolUtils.normalizeForMatch(searchableOld);
        if (dedupText.indexOf(dedupOld, idx + 1) != -1) {
            resultFuture.complete("old_str matches multiple locations in " + pathStr
                + ". Make it more specific, or use replace_all: true.");
            return;
        }
        final int finalIdx = idx;
        final int finalLen = matchLen;
        WriteAction.run(() ->
            CommandProcessor.getInstance().executeCommand(
                project, () -> doc.replaceString(finalIdx, finalIdx + finalLen, normalizedNew),
                "Edit File", null)
        );
        FileDocumentManager.getInstance().saveDocument(doc);
        CodeChangeTracker.recordChange(CodeChangeTracker.countLines(normalizedNew), CodeChangeTracker.countLines(normalizedOld));
        String syntaxWarning = checkSyntaxErrors(doc, pathStr);
        if (autoFormat && syntaxWarning.isEmpty()) queueAutoFormat(project, pathStr);
        followRange[0] = doc.getLineNumber(finalIdx) + 1;
        int ctxEnd = Math.min(finalIdx + normalizedNew.length(), doc.getTextLength());
        followRange[1] = doc.getLineNumber(Math.max(ctxEnd - 1, finalIdx)) + 1;
        String formatNote = autoFormat && syntaxWarning.isEmpty() ? AUTO_FORMAT_SUFFIX : "";
        resultFuture.complete(MSG_EDITED_PREFIX + pathStr + " (replaced " + finalLen + " chars with " + normalizedNew.length() + FORMAT_CHARS_SUFFIX
            + contextLines(doc, finalIdx, ctxEnd) + formatNote + syntaxWarning);
    }

    /**
     * Replaces a range of lines (start_line to end_line inclusive, 1-based) with new_str.
     * If end_line is omitted, only start_line is replaced.
     */
    private void writeFileLineRange(VirtualFile vf, String pathStr, JsonObject args,
                                    boolean autoFormat, CompletableFuture<String> resultFuture,
                                    int[] followRange) {
        if (vf == null) {
            resultFuture.complete(ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
            return;
        }
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) {
            resultFuture.complete(MSG_CANNOT_OPEN + pathStr);
            return;
        }
        int startLine = args.get(PARAM_START_LINE).getAsInt();
        int endLine = args.has(PARAM_END_LINE) ? args.get(PARAM_END_LINE).getAsInt() : startLine;
        String newStr = args.get(PARAM_NEW_STR).getAsString().replace("\r\n", "\n").replace("\r", "\n");

        int lineCount = doc.getLineCount();
        if (startLine < 1 || startLine > lineCount) {
            resultFuture.complete("start_line " + startLine + " out of range (file has " + lineCount + " lines)");
            return;
        }
        if (endLine < startLine || endLine > lineCount) {
            resultFuture.complete("end_line " + endLine + " out of range (file has " + lineCount + " lines, start_line=" + startLine + ")");
            return;
        }

        int startOffset = doc.getLineStartOffset(startLine - 1);
        int endOffset = doc.getLineEndOffset(endLine - 1);
        // Include the trailing newline if present so the replacement is clean
        if (endOffset < doc.getTextLength() && doc.getCharsSequence().charAt(endOffset) == '\n') {
            endOffset++;
        }
        // Ensure new_str ends with newline for clean line replacement
        if (!newStr.isEmpty() && !newStr.endsWith("\n")) {
            newStr += "\n";
        }

        final int fStart = startOffset;
        final int fEnd = endOffset;
        final String fNew = newStr;
        int replacedLines = endLine - startLine + 1;
        WriteAction.run(() ->
            CommandProcessor.getInstance().executeCommand(
                project, () -> doc.replaceString(fStart, fEnd, fNew),
                "Edit File (Line Range)", null)
        );
        FileDocumentManager.getInstance().saveDocument(doc);
        CodeChangeTracker.recordChange(CodeChangeTracker.countLines(fNew), replacedLines);
        String syntaxWarning = checkSyntaxErrors(doc, pathStr);
        if (autoFormat && syntaxWarning.isEmpty()) queueAutoFormat(project, pathStr);
        int ctxEnd = Math.min(fStart + fNew.length(), doc.getTextLength());
        followRange[1] = doc.getLineNumber(Math.max(ctxEnd - 1, fStart)) + 1;
        String formatNote = autoFormat && syntaxWarning.isEmpty() ? AUTO_FORMAT_SUFFIX : "";
        resultFuture.complete(MSG_EDITED_PREFIX + pathStr + " (replaced lines " + startLine + "-" + endLine
            + " (" + replacedLines + " lines) with " + fNew.length() + FORMAT_CHARS_SUFFIX
            + contextLines(doc, fStart, ctxEnd) + formatNote + syntaxWarning);
    }

    /**
     * Returns [index, matchLength] or [-1, 0] if not found.
     */
    private int[] findMatchPosition(Document doc, VirtualFile vf, String pathStr,
                                    String normalizedOld, boolean autoFormat, boolean caseSensitive) {
        String text = doc.getText();
        int idx = indexOf(text, normalizedOld, caseSensitive);
        int matchLen = normalizedOld.length();

        if (idx == -1 && autoFormat) {
            formatFileSync(vf);
            text = doc.getText();
            idx = indexOf(text, normalizedOld, caseSensitive);
            if (idx != -1) {
                LOG.info("write_file: match succeeded after auto-format for " + pathStr);
                return new int[]{idx, matchLen};
            }
        }

        if (idx == -1) {
            String normText = ToolUtils.normalizeForMatch(text);
            String normOld = ToolUtils.normalizeForMatch(normalizedOld);
            idx = indexOf(normText, normOld, caseSensitive);
            if (idx != -1) {
                LOG.info("write_file: normalized match succeeded for " + pathStr);
                matchLen = ToolUtils.findOriginalLength(text, idx, normOld.length());
            } else {
                LOG.warn("write_file: old_str not found in " + pathStr +
                    " (exact, formatted, and normalized all failed)");
            }
        }
        return new int[]{idx, matchLen};
    }

    private static int indexOf(String text, String target, boolean caseSensitive) {
        if (caseSensitive) return text.indexOf(target);
        return text.toLowerCase().indexOf(target.toLowerCase());
    }

    // S107: 8 params are a cohesive unit for a single targeted file-replace operation; splitting adds complexity
    @SuppressWarnings("java:S107")
    private void writeFileReplaceAll(VirtualFile vf, String pathStr, String oldStr, String newStr,
                                     boolean autoFormat, boolean caseSensitive,
                                     CompletableFuture<String> resultFuture, int[] followRange) {
        if (vf == null) {
            resultFuture.complete(ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
            return;
        }
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) {
            resultFuture.complete(MSG_CANNOT_OPEN + pathStr);
            return;
        }
        String normalizedOld = oldStr.replace("\r\n", "\n").replace("\r", "\n");
        String normalizedNew = newStr.replace("\r\n", "\n").replace("\r", "\n");

        String text = doc.getText();
        String searchText = caseSensitive ? text : text.toLowerCase();
        String searchOld = caseSensitive ? normalizedOld : normalizedOld.toLowerCase();

        List<Integer> positions = new ArrayList<>();
        int pos = 0;
        while ((pos = searchText.indexOf(searchOld, pos)) != -1) {
            positions.add(pos);
            pos += searchOld.length();
        }
        if (positions.isEmpty()) {
            resultFuture.complete("old_str not found in " + pathStr +
                ". Ensure the text matches exactly (check whitespace, indentation, line endings)." +
                closestMatchHint(text, normalizedOld));
            return;
        }
        WriteAction.run(() ->
            CommandProcessor.getInstance().executeCommand(project, () -> {
                for (int i = positions.size() - 1; i >= 0; i--) {
                    int start = positions.get(i);
                    doc.replaceString(start, start + normalizedOld.length(), normalizedNew);
                }
            }, "Edit File", null)
        );
        FileDocumentManager.getInstance().saveDocument(doc);
        CodeChangeTracker.recordChange(
            positions.size() * CodeChangeTracker.countLines(normalizedNew),
            positions.size() * CodeChangeTracker.countLines(normalizedOld));
        String syntaxWarning = checkSyntaxErrors(doc, pathStr);
        if (autoFormat && syntaxWarning.isEmpty()) queueAutoFormat(project, pathStr);
        int firstPos = positions.getFirst();
        followRange[0] = doc.getLineNumber(firstPos) + 1;
        int ctxEnd = Math.min(firstPos + normalizedNew.length(), doc.getTextLength());
        followRange[1] = doc.getLineNumber(Math.max(ctxEnd - 1, firstPos)) + 1;
        String formatNote = autoFormat && syntaxWarning.isEmpty() ? AUTO_FORMAT_SUFFIX : "";
        resultFuture.complete(MSG_EDITED_PREFIX + pathStr + " (replaced " + positions.size()
            + " occurrence(s) of " + normalizedOld.length() + " chars with " + normalizedNew.length()
            + FORMAT_CHARS_SUFFIX + formatNote + syntaxWarning);
    }

    /**
     * Finds the closest line in {@code text} containing the first non-blank line of
     * {@code normalizedOld}, returning a hint to help the agent understand mismatches.
     */
    private static String closestMatchHint(String text, String normalizedOld) {
        String firstLine = null;
        for (String l : normalizedOld.split("\n")) {
            String t = l.trim();
            if (!t.isEmpty()) {
                firstLine = t;
                break;
            }
        }
        if (firstLine == null) return "";
        String[] docLines = text.split("\n");
        for (int i = 0; i < docLines.length; i++) {
            if (docLines[i].contains(firstLine)) {
                int start = Math.max(0, i - 1);
                int end = Math.min(docLines.length - 1, i + 3);
                StringBuilder ctx = new StringBuilder("\nClosest match found at line ").append(i + 1).append(":\n");
                for (int j = start; j <= end; j++) {
                    ctx.append("  L").append(j + 1).append(": ").append(docLines[j]).append("\n");
                }
                return ctx.toString();
            }
        }
        return "";
    }

    /**
     * Synchronously format a file on the current EDT thread.
     * Used as a fallback when old_str matching fails — formatting normalizes
     * line endings, whitespace, and indentation for more reliable matching.
     */
    private void formatFileSync(VirtualFile vf) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return;
        WriteAction.run(() ->
            CommandProcessor.getInstance().executeCommand(project, () -> {
                PsiDocumentManager.getInstance(project).commitAllDocuments();
                new com.intellij.codeInsight.actions.ReformatCodeProcessor(psiFile, false).run();
                PsiDocumentManager.getInstance(project).commitAllDocuments();
            }, "Pre-Format for Edit", null)
        );
    }

    /**
     * Check for syntax errors in a file after writing.
     * Returns a warning string if errors are found, or empty string if clean.
     * Runs on EDT (caller must be on EDT). Commits only the supplied document rather than
     * all open documents to avoid stalling unrelated document trees.
     */
    private String checkSyntaxErrors(Document doc, String pathStr) {
        try {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) return "";
            // Commit only the document we just wrote — not all open documents
            PsiDocumentManager.getInstance(project).commitDocument(doc);
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return "";

            List<String> errors = new ArrayList<>();
            collectPsiErrors(psiFile, doc, errors);

            if (errors.isEmpty()) return "";
            int count = Math.min(errors.size(), 5);
            String summary = "\n\nWARNING: " + errors.size() + " syntax error(s) after write:\n"
                + String.join("\n", errors.subList(0, count));
            if (errors.size() > count) summary += "\n  ... and " + (errors.size() - count) + " more";
            return summary;
        } catch (Exception e) {
            return "";
        }
    }

    private static void collectPsiErrors(com.intellij.psi.PsiElement element, Document doc,
                                         List<String> errors) {
        if (element instanceof PsiErrorElement err) {
            int line = doc != null ? doc.getLineNumber(err.getTextOffset()) + 1 : -1;
            errors.add("  Line " + line + ": " + err.getErrorDescription());
        }
        for (var child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            collectPsiErrors(child, doc, errors);
        }
    }

    /**
     * Extract context lines around an edit region for the response.
     * Returns ~3 lines before and after the edited region.
     */
    private static String contextLines(Document doc, int editStartOffset, int editEndOffset) {
        int totalLines = doc.getLineCount();
        if (totalLines == 0) return "";
        int startLine = doc.getLineNumber(Math.min(editStartOffset, doc.getTextLength() - 1));
        int endLine = doc.getLineNumber(Math.min(editEndOffset, doc.getTextLength() - 1));
        int ctxStart = Math.max(0, startLine - 3);
        int ctxEnd = Math.min(totalLines - 1, endLine + 3);
        StringBuilder sb = new StringBuilder("\n\nContext after edit (lines ")
            .append(ctxStart + 1).append("-").append(ctxEnd + 1).append("):\n");
        for (int i = ctxStart; i <= ctxEnd; i++) {
            int s = doc.getLineStartOffset(i);
            int e = doc.getLineEndOffset(i);
            sb.append(i + 1).append(": ").append(doc.getText(new TextRange(s, e))).append("\n");
        }
        return sb.toString();
    }

    private static boolean resolveAutoFormat(JsonObject args) {
        if (args.has(PARAM_AUTO_FORMAT)) return args.get(PARAM_AUTO_FORMAT).getAsBoolean();
        if (args.has(PARAM_AUTO_FORMAT_LEGACY)) return args.get(PARAM_AUTO_FORMAT_LEGACY).getAsBoolean();
        return true;
    }
}
