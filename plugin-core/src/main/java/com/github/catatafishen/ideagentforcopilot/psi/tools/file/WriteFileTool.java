package com.github.catatafishen.ideagentforcopilot.psi.tools.file;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.github.catatafishen.ideagentforcopilot.psi.FileAccessTracker;
import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.WriteFileRenderer;
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
    public @NotNull String kind() {
        return "edit";
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

        EdtUtil.invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);

                if (args.has(PARAM_CONTENT)) {
                    writeFileFullContent(vf, pathStr, args.get(PARAM_CONTENT).getAsString(),
                        autoFormat, resultFuture);
                    followRange[0] = 1;
                } else if (args.has("old_str") && args.has(PARAM_NEW_STR)) {
                    writeFilePartialEdit(vf, pathStr, args.get("old_str").getAsString(),
                        args.get(PARAM_NEW_STR).getAsString(), autoFormat, resultFuture, followRange);
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
            WriteAction.run(() ->
                CommandProcessor.getInstance().executeCommand(
                    project, () -> doc.setText(newContent), "Write File", null)
            );
            FileDocumentManager.getInstance().saveDocument(doc);
            String syntaxWarning = checkSyntaxErrors(pathStr);
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
            resultFuture.complete("Written: " + pathStr);
        }
    }

    private void createNewFile(String pathStr, String content, CompletableFuture<String> resultFuture) {
        WriteAction.run(() -> {
            try {
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
                Path filePath = Path.of(fullPath);
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, content);
                LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath);
                resultFuture.complete("Created: " + pathStr);
            } catch (IOException e) {
                resultFuture.complete("Error creating file: " + e.getMessage());
            }
        });
    }

    private void writeFilePartialEdit(VirtualFile vf, String pathStr, String oldStr, String newStr,
                                      boolean autoFormat, CompletableFuture<String> resultFuture,
                                      int[] followRange) {
        if (vf == null) {
            resultFuture.complete(ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
            return;
        }
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) {
            resultFuture.complete("Cannot open document: " + pathStr);
            return;
        }
        // Normalize line endings for consistent matching
        String normalizedOld = oldStr.replace("\r\n", "\n").replace("\r", "\n");
        String normalizedNew = newStr.replace("\r\n", "\n").replace("\r", "\n");

        int[] match = findMatchPosition(doc, vf, pathStr, normalizedOld, autoFormat);
        int idx = match[0];
        int matchLen = match[1];

        if (idx == -1) {
            resultFuture.complete("old_str not found in " + pathStr +
                ". Ensure the text matches exactly (check whitespace, indentation, line endings)." +
                closestMatchHint(doc.getText(), normalizedOld));
            return;
        }
        // Check for multiple matches using same strategy
        String text = doc.getText();
        String checkText = (matchLen == normalizedOld.length()) ? text : ToolUtils.normalizeForMatch(text);
        String checkOld = (matchLen == normalizedOld.length()) ? normalizedOld : ToolUtils.normalizeForMatch(normalizedOld);
        if (checkText.indexOf(checkOld, idx + 1) != -1) {
            resultFuture.complete("old_str matches multiple locations in " + pathStr + ". Make it more specific.");
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
        String syntaxWarning = checkSyntaxErrors(pathStr);
        if (autoFormat && syntaxWarning.isEmpty()) queueAutoFormat(project, pathStr);
        followRange[0] = doc.getLineNumber(finalIdx) + 1;
        int ctxEnd = Math.min(finalIdx + normalizedNew.length(), doc.getTextLength());
        followRange[1] = doc.getLineNumber(Math.max(ctxEnd - 1, finalIdx)) + 1;
        String formatNote = autoFormat && syntaxWarning.isEmpty() ? AUTO_FORMAT_SUFFIX : "";
        resultFuture.complete("Edited: " + pathStr + " (replaced " + finalLen + " chars with " + normalizedNew.length() + FORMAT_CHARS_SUFFIX
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
            resultFuture.complete("Cannot open document: " + pathStr);
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
        if (endOffset < doc.getTextLength() && doc.getText().charAt(endOffset) == '\n') {
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
        String syntaxWarning = checkSyntaxErrors(pathStr);
        if (autoFormat && syntaxWarning.isEmpty()) queueAutoFormat(project, pathStr);
        int ctxEnd = Math.min(fStart + fNew.length(), doc.getTextLength());
        followRange[1] = doc.getLineNumber(Math.max(ctxEnd - 1, fStart)) + 1;
        String formatNote = autoFormat && syntaxWarning.isEmpty() ? AUTO_FORMAT_SUFFIX : "";
        resultFuture.complete("Edited: " + pathStr + " (replaced lines " + startLine + "-" + endLine
            + " (" + replacedLines + " lines) with " + fNew.length() + FORMAT_CHARS_SUFFIX
            + contextLines(doc, fStart, ctxEnd) + formatNote + syntaxWarning);
    }

    /**
     * Returns [index, matchLength] or [-1, 0] if not found.
     */
    private int[] findMatchPosition(Document doc, VirtualFile vf, String pathStr,
                                    String normalizedOld, boolean autoFormat) {
        String text = doc.getText();
        int idx = text.indexOf(normalizedOld);
        int matchLen = normalizedOld.length();

        // Fallback 1: auto-format the file and retry (normalizes whitespace/line endings)
        if (idx == -1 && autoFormat) {
            formatFileSync(vf);
            text = doc.getText();
            idx = text.indexOf(normalizedOld);
            if (idx != -1) {
                LOG.info("write_file: match succeeded after auto-format for " + pathStr);
                return new int[]{idx, matchLen};
            }
        }

        if (idx == -1) {
            // Fallback 2: normalize Unicode chars and retry
            String normText = ToolUtils.normalizeForMatch(text);
            String normOld = ToolUtils.normalizeForMatch(normalizedOld);
            idx = normText.indexOf(normOld);
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
     * Runs on EDT (caller must be on EDT).
     */
    private String checkSyntaxErrors(String pathStr) {
        try {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) return "";
            PsiDocumentManager.getInstance(project).commitAllDocuments();
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return "";
            Document doc = psiFile.getViewProvider().getDocument();

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
