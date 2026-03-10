package com.github.catatafishen.ideagentforcopilot.psi;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles file read/write/create/delete tool calls for the PSI Bridge.
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
class FileTools extends AbstractToolHandler {

    private static final Logger LOG = Logger.getInstance(FileTools.class);

    private static final String PARAM_CONTENT = "content";
    private static final String PARAM_START_LINE = "start_line";
    private static final String PARAM_END_LINE = "end_line";
    private static final String PARAM_NEW_STR = "new_str";
    private static final String FORMAT_CHARS_SUFFIX = " chars)";
    static final java.awt.Color HIGHLIGHT_EDIT = new java.awt.Color(80, 160, 80, 40);
    static final java.awt.Color HIGHLIGHT_READ = new java.awt.Color(80, 120, 200, 35);

    /**
     * Returns a label like "ui-reviewer", "claude-sonnet-4.5", or "Agent" as fallback.
     */
    static String agentLabel(Project project) {
        ToolLayerSettings settings = ToolLayerSettings.getInstance(project);
        String agent = settings.getActiveAgentLabel();
        if (agent != null) return agent;
        String model = settings.getSelectedModel();
        return model != null ? model : "Agent";
    }

    // Files modified during the current agent turn that need formatting at turn end
    private final java.util.Set<String> pendingAutoFormat =
        java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    /**
     * Queue a file for deferred auto-format (reformat + optimize imports).
     * Called by other tool handlers (e.g., SymbolEditingTools) that modify files.
     */
    void queueAutoFormat(String path) {
        pendingAutoFormat.add(path);
    }

    /**
     * Extract context lines around an edit region for the response.
     * Returns ~5 lines before and after the edited region so the agent
     * can see what the file looks like post-edit/format.
     */
    private static String contextLines(Document doc, int editStartOffset, int editEndOffset) {
        int totalLines = doc.getLineCount();
        if (totalLines == 0) return "";
        int startLine = doc.getLineNumber(Math.min(editStartOffset, doc.getTextLength() - 1));
        int endLine = doc.getLineNumber(Math.min(editEndOffset, doc.getTextLength() - 1));
        int ctxStart = Math.max(0, startLine - 3);
        int ctxEnd = Math.min(totalLines - 1, endLine + 3);
        StringBuilder sb = new StringBuilder("\n\nContext after edit (lines ").append(ctxStart + 1).append("-").append(ctxEnd + 1).append("):\n");
        for (int i = ctxStart; i <= ctxEnd; i++) {
            int s = doc.getLineStartOffset(i);
            int e = doc.getLineEndOffset(i);
            sb.append(i + 1).append(": ").append(doc.getText(new com.intellij.openapi.util.TextRange(s, e))).append("\n");
        }
        return sb.toString();
    }

    FileTools(Project project) {
        super(project);
        register("read_file", this::readFile);
        register("intellij_read_file", this::readFile);
        register("write_file", this::writeFile);
        register("intellij_write_file", this::writeFile);
        register("edit_text", this::writeFile);
        register("create_file", this::createFile);
        register("delete_file", this::deleteFile);
        register("rename_file", this::renameFile);
        register("move_file", this::moveFile);
        register("undo", this::undo);
        register("redo", this::redo);
        register("reload_from_disk", this::reloadFromDisk);
    }

    private String readFile(JsonObject args) {
        if (!args.has("path") || args.get("path").isJsonNull())
            return ToolUtils.ERROR_PATH_REQUIRED;
        String pathStr = args.get("path").getAsString();
        int startLine = args.has(PARAM_START_LINE) ? args.get(PARAM_START_LINE).getAsInt() : -1;
        int endLine = args.has(PARAM_END_LINE) ? args.get(PARAM_END_LINE).getAsInt() : -1;

        String result = ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) return ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;

            String content = readFileContent(vf);
            if (content.startsWith("Error")) return content;

            if (startLine > 0 || endLine > 0) {
                return extractLineRange(content, startLine, endLine);
            }

            // Add directory marking hint for excluded/generated files
            String hint = getDirectoryMarkingHint(vf);
            return hint != null ? hint + "\n" + content : content;
        });

        followFileIfEnabled(project, pathStr, startLine > 0 ? startLine : -1, endLine > 0 ? endLine : -1, HIGHLIGHT_READ, agentLabel(project) + " is reading");
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
        return null;
    }

    private String extractLineRange(String content, int startLine, int endLine) {
        String[] lines = content.split("\n", -1);
        int from = Math.max(0, (startLine > 0 ? startLine - 1 : 0));
        int to = Math.min(lines.length, (endLine > 0 ? endLine : lines.length));
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(i + 1).append(": ").append(lines[i]).append("\n");
        }
        return sb.toString();
    }

    /**
     * Guard against reentrant navigate() calls. IntelliJ's navigate() pumps EDT events
     * while waiting for tab creation, which can dispatch another followFileIfEnabled.
     * Two overlapping tab insertions race inside JBTabsImpl.updateText() causing NPE.
     */
    private static final AtomicBoolean navigating = new AtomicBoolean(false);

    /**
     * Opens the file in the editor if "Follow Agent Files" is enabled.
     * Scrolls to the middle of [startLine, endLine] and briefly highlights the region.
     * Package-private so other tool handlers can reuse it.
     */
    static void followFileIfEnabled(Project project, String pathStr, int startLine, int endLine,
                                    java.awt.Color highlightColor, String actionLabel) {
        if (!ToolLayerSettings.getInstance(project).getFollowAgentFiles()) return;

        EdtUtil.invokeLater(() -> {
            if (!navigating.compareAndSet(false, true)) return;
            try {
                VirtualFile vf = ToolUtils.resolveVirtualFile(project, pathStr);
                if (vf == null) return;

                FileEditorManager fem = FileEditorManager.getInstance(project);
                int midLine = (startLine > 0 && endLine > 0)
                    ? (startLine + endLine) / 2
                    : Math.max(startLine, 1);
                if (midLine > 0) {
                    new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, midLine - 1, 0)
                        .navigate(false);
                    scrollAndHighlight(fem, vf, startLine, endLine, midLine, highlightColor, actionLabel);
                } else {
                    fem.openFile(vf, false);
                }

                selectInProjectView(project, vf);
            } finally {
                navigating.set(false);
            }
        });
    }

    /**
     * Cooldown for project view selection to avoid excessive tree jumps.
     */
    private static final long PROJECT_VIEW_COOLDOWN_MS = 5_000;
    private static volatile long lastProjectViewSelectMs;

    /**
     * Selects the given file in the Project View tree without requesting focus.
     * Throttled to avoid excessive tree navigation during rapid file access.
     */
    private static void selectInProjectView(Project project, VirtualFile vf) {
        long now = System.currentTimeMillis();
        if (now - lastProjectViewSelectMs < PROJECT_VIEW_COOLDOWN_MS) return;
        lastProjectViewSelectMs = now;

        try {
            // Ensure the Project tool window is visible
            var twm = com.intellij.openapi.wm.ToolWindowManager.getInstance(project);
            var tw = twm.getToolWindow("Project");
            if (tw != null && !tw.isVisible()) {
                tw.show();
            }

            var projectView = com.intellij.ide.projectView.ProjectView.getInstance(project);
            projectView.select(null, vf, false);
        } catch (Exception e) {
            LOG.debug("Project view select failed", e);
        }
    }

    private static void scrollAndHighlight(FileEditorManager fem, VirtualFile vf,
                                           int startLine, int endLine, int midLine,
                                           java.awt.Color highlightColor, String actionLabel) {
        for (com.intellij.openapi.fileEditor.FileEditor fe : fem.getEditors(vf)) {
            if (fe instanceof TextEditor textEditor) {
                com.intellij.openapi.editor.Editor editor = textEditor.getEditor();
                Document doc = editor.getDocument();
                int lineCount = doc.getLineCount();
                if (midLine - 1 >= lineCount) break;

                // If the highlighted range fits in the viewport, center it;
                // otherwise scroll so the start (with the action label) is visible near the top.
                int visibleLines = editor.getScrollingModel().getVisibleArea().height
                    / editor.getLineHeight();
                int rangeLines = endLine - startLine + 1;
                boolean fitsInViewport = startLine <= 0 || endLine <= 0
                    || rangeLines <= visibleLines;

                if (fitsInViewport) {
                    int offset = doc.getLineStartOffset(Math.max(midLine - 1, 0));
                    editor.getCaretModel().moveToOffset(offset);
                    editor.getScrollingModel().scrollTo(
                        editor.offsetToLogicalPosition(offset),
                        com.intellij.openapi.editor.ScrollType.CENTER);
                } else {
                    // Place start line a few lines from the top so the inlay label is visible
                    int topLine = Math.max(startLine - 2, 1);
                    int offset = doc.getLineStartOffset(Math.max(topLine - 1, 0));
                    editor.getCaretModel().moveToOffset(offset);
                    editor.getScrollingModel().scrollTo(
                        editor.offsetToLogicalPosition(offset),
                        com.intellij.openapi.editor.ScrollType.CENTER);
                }

                flashLineRange(editor, doc, startLine, endLine, highlightColor, actionLabel, textEditor);
                break;
            }
        }
    }

    private static void flashLineRange(com.intellij.openapi.editor.Editor editor, Document doc,
                                       int startLine, int endLine,
                                       java.awt.Color color, String actionLabel,
                                       TextEditor disposableParent) {
        int lineCount = doc.getLineCount();
        if (startLine <= 0 || endLine <= 0 || startLine > lineCount) return;

        int hlStart = doc.getLineStartOffset(startLine - 1);
        int hlEnd = doc.getLineEndOffset(Math.min(endLine, lineCount) - 1);
        if (hlEnd <= hlStart) return;

        var attrs = new com.intellij.openapi.editor.markup.TextAttributes();
        attrs.setBackgroundColor(color);
        var markup = editor.getMarkupModel();
        var hl = markup.addRangeHighlighter(
            hlStart, hlEnd,
            com.intellij.openapi.editor.markup.HighlighterLayer.SELECTION - 1,
            attrs,
            com.intellij.openapi.editor.markup.HighlighterTargetArea.LINES_IN_RANGE);

        // Add an inline label above the highlighted region
        var inlay = editor.getInlayModel().addBlockElement(
            hlStart, true, true, 0,
            new AgentActionRenderer(actionLabel, color));

        var alarm = new com.intellij.util.Alarm(
            com.intellij.util.Alarm.ThreadToUse.SWING_THREAD, disposableParent);
        alarm.addRequest(() -> {
            try {
                markup.removeHighlighter(hl);
                if (inlay != null) inlay.dispose();
            } catch (Exception ignored) {
            }
        }, 2500);
    }

    /**
     * Renders a small label ("Agent is reading" / "Agent is editing") as a block inlay above
     * the highlighted region. Uses the same tint color as the range highlight.
     */
    private static class AgentActionRenderer implements com.intellij.openapi.editor.EditorCustomElementRenderer {
        private final String text;
        private final java.awt.Color bgColor;

        AgentActionRenderer(String text, java.awt.Color bgColor) {
            this.text = text;
            this.bgColor = new java.awt.Color(
                bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue(),
                Math.min(bgColor.getAlpha() * 3, 255));
        }

        @Override
        public int calcWidthInPixels(com.intellij.openapi.editor.Inlay inlay) {
            var editor = inlay.getEditor();
            var metrics = editor.getContentComponent().getFontMetrics(
                editor.getColorsScheme().getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN));
            return metrics.stringWidth(text) + 16;
        }

        @Override
        public int calcHeightInPixels(com.intellij.openapi.editor.Inlay inlay) {
            return inlay.getEditor().getLineHeight();
        }

        @Override
        public void paint(com.intellij.openapi.editor.Inlay inlay,
                          java.awt.Graphics g, java.awt.Rectangle targetRegion,
                          com.intellij.openapi.editor.markup.TextAttributes textAttributes) {
            var g2 = (java.awt.Graphics2D) g;
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(bgColor);
            g2.fillRoundRect(targetRegion.x, targetRegion.y,
                targetRegion.width, targetRegion.height, 6, 6);
            var editor = inlay.getEditor();
            g2.setFont(editor.getColorsScheme().getFont(
                com.intellij.openapi.editor.colors.EditorFontType.PLAIN));
            g2.setColor(editor.getColorsScheme().getDefaultForeground());
            var metrics = g2.getFontMetrics();
            int textY = targetRegion.y + (targetRegion.height + metrics.getAscent() - metrics.getDescent()) / 2;
            g2.drawString(text, targetRegion.x + 8, textY);
        }
    }

    private static final String PARAM_AUTO_FORMAT = "auto_format_and_optimize_imports";
    private static final String PARAM_AUTO_FORMAT_LEGACY = "auto_format";

    /**
     * Resolves the auto-format flag from tool args, supporting both the new
     * {@code auto_format_and_optimize_imports} name and the legacy {@code auto_format} name.
     */
    private static boolean resolveAutoFormat(JsonObject args) {
        if (args.has(PARAM_AUTO_FORMAT)) return args.get(PARAM_AUTO_FORMAT).getAsBoolean();
        if (args.has(PARAM_AUTO_FORMAT_LEGACY)) return args.get(PARAM_AUTO_FORMAT_LEGACY).getAsBoolean();
        return true; // default
    }

    private String writeFile(JsonObject args) throws Exception {
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
        followFileIfEnabled(project, pathStr, followRange[0], followRange[1], HIGHLIGHT_EDIT, agentLabel(project) + " is editing");
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
            ApplicationManager.getApplication().runWriteAction(() ->
                com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                    project, () -> doc.setText(newContent), "Write File", null)
            );
            FileDocumentManager.getInstance().saveDocument(doc);
            String syntaxWarning = checkSyntaxErrors(pathStr);
            if (autoFormat && syntaxWarning.isEmpty()) pendingAutoFormat.add(pathStr);
            String formatNote = autoFormat && syntaxWarning.isEmpty() ? " (auto-format queued)" : "";
            resultFuture.complete("Written: " + pathStr + " (" + newContent.length() + FORMAT_CHARS_SUFFIX + formatNote + syntaxWarning);
        } else {
            ApplicationManager.getApplication().runWriteAction(() -> {
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
        ApplicationManager.getApplication().runWriteAction(() -> {
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
        // Normalize line endings in old_str/new_str for consistent matching
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
        ApplicationManager.getApplication().runWriteAction(() ->
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                project, () -> doc.replaceString(finalIdx, finalIdx + finalLen, normalizedNew),
                "Edit File", null)
        );
        FileDocumentManager.getInstance().saveDocument(doc);
        String syntaxWarning = checkSyntaxErrors(pathStr);
        if (autoFormat && syntaxWarning.isEmpty()) pendingAutoFormat.add(pathStr);
        followRange[0] = doc.getLineNumber(finalIdx) + 1;
        int ctxEnd = Math.min(finalIdx + normalizedNew.length(), doc.getTextLength());
        followRange[1] = doc.getLineNumber(Math.max(ctxEnd - 1, finalIdx)) + 1;
        String formatNote = autoFormat && syntaxWarning.isEmpty() ? " (auto-format queued)" : "";
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
        ApplicationManager.getApplication().runWriteAction(() ->
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                project, () -> doc.replaceString(fStart, fEnd, fNew),
                "Edit File (line range)", null)
        );
        FileDocumentManager.getInstance().saveDocument(doc);
        String syntaxWarning = checkSyntaxErrors(pathStr);
        if (autoFormat && syntaxWarning.isEmpty()) pendingAutoFormat.add(pathStr);
        int ctxEnd = Math.min(fStart + fNew.length(), doc.getTextLength());
        followRange[1] = doc.getLineNumber(Math.max(ctxEnd - 1, fStart)) + 1;
        String formatNote = autoFormat && syntaxWarning.isEmpty() ? " (auto-format queued)" : "";
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
     * Finds the closest line in {@code text} that contains the first non-blank line of
     * {@code normalizedOld}, and returns a hint string with that line and its neighbours.
     * Helps the agent understand why old_str didn't match.
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
        ApplicationManager.getApplication().runWriteAction(() ->
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
                new com.intellij.codeInsight.actions.ReformatCodeProcessor(psiFile, false).run();
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
            }, "Pre-Format for Edit", null)
        );
    }

    /**
     * Auto-format and optimize imports on all files modified during the agent turn.
     * Called before git stage/commit to ensure formatting is included, and also
     * at turn end as a safety net. Runs synchronously on the EDT.
     */
    void flushPendingAutoFormat() {
        if (pendingAutoFormat.isEmpty()) return;

        java.util.List<String> paths = new java.util.ArrayList<>(pendingAutoFormat);
        pendingAutoFormat.clear();

        EdtUtil.invokeAndWait(() -> {
            for (String pathStr : paths) {
                try {
                    VirtualFile vf = resolveVirtualFile(pathStr);
                    if (vf == null) continue;
                    PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                    if (psiFile == null) continue;

                    ApplicationManager.getApplication().runWriteAction(() ->
                        com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, () -> {
                            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
                            new com.intellij.codeInsight.actions.OptimizeImportsProcessor(project, psiFile).run();
                            new com.intellij.codeInsight.actions.ReformatCodeProcessor(psiFile, false).run();
                            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
                        }, "Auto-Format (Deferred)", null)
                    );
                    LOG.info("Deferred auto-format: " + pathStr);
                } catch (Exception e) {
                    LOG.warn("Deferred auto-format failed for " + pathStr + ": " + e.getMessage());
                }
            }
            // Save all documents to disk so git sees the formatted content
            ApplicationManager.getApplication().runWriteAction(() ->
                FileDocumentManager.getInstance().saveAllDocuments());
        });
    }

    /**
     * Check for syntax errors in a file after writing. Returns a warning string
     * if errors are found, or empty string if the file is clean.
     * Runs on EDT (caller must be on EDT).
     */
    private String checkSyntaxErrors(String pathStr) {
        try {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) return "";
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return "";
            Document doc = psiFile.getViewProvider().getDocument();

            java.util.List<String> errors = new java.util.ArrayList<>();
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

    private void collectPsiErrors(com.intellij.psi.PsiElement element, Document doc,
                                  java.util.List<String> errors) {
        if (element instanceof com.intellij.psi.PsiErrorElement err) {
            int line = doc != null ? doc.getLineNumber(err.getTextOffset()) + 1 : -1;
            errors.add("  Line " + line + ": " + err.getErrorDescription());
        }
        for (com.intellij.psi.PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            collectPsiErrors(child, doc, errors);
        }
    }

    private String createFile(JsonObject args) throws Exception {
        if (!args.has("path") || !args.has(PARAM_CONTENT)) {
            return "Error: 'path' and 'content' parameters are required";
        }
        String pathStr = args.get("path").getAsString();
        String content = args.get(PARAM_CONTENT).getAsString();

        // Resolve path
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

        // Create parent directories
        Path parentDir = filePath.getParent();
        if (parentDir != null) {
            Files.createDirectories(parentDir);
        }
        // Write content
        Files.writeString(filePath, content, StandardCharsets.UTF_8);

        // Refresh VFS so IntelliJ sees the file
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
        followFileIfEnabled(project, pathStr, 1, lineCount, HIGHLIGHT_EDIT, agentLabel(project) + " created");
        FileAccessTracker.recordWrite(project, pathStr);
        return result;
    }

    private String deleteFile(JsonObject args) throws Exception {
        if (!args.has("path")) return ToolUtils.ERROR_PATH_REQUIRED;
        String pathStr = args.get("path").getAsString();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ReadAction.nonBlocking(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                    return null;
                }
                if (vf.isDirectory()) {
                    resultFuture.complete("Error: Cannot delete directories. Path is a directory: " + pathStr);
                    return null;
                }
                scheduleFileDeletion(vf, pathStr, resultFuture);
                return null;
            } catch (Exception e) {
                resultFuture.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
                return null;
            }
        }).inSmartMode(project).submit(AppExecutorUtil.getAppExecutorService());

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private void scheduleFileDeletion(VirtualFile vf, String pathStr, CompletableFuture<String> resultFuture) {
        EdtUtil.invokeLater(() ->
            ApplicationManager.getApplication().runWriteAction(() -> {
                try {
                    com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                        project,
                        () -> {
                            try {
                                vf.delete(FileTools.this);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        },
                        "Delete File: " + vf.getName(),
                        null
                    );
                    resultFuture.complete("Deleted file: " + pathStr);
                } catch (Exception e) {
                    resultFuture.complete("Error deleting file: " + e.getMessage());
                }
            })
        );
    }

    private String renameFile(JsonObject args) throws Exception {
        if (!args.has("path") || !args.has("new_name"))
            return ToolUtils.ERROR_PREFIX + "'path' and 'new_name' parameters are required";
        String pathStr = args.get("path").getAsString();
        String newName = args.get("new_name").getAsString();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ReadAction.nonBlocking(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                    return null;
                }
                String oldName = vf.getName();
                EdtUtil.invokeLater(() ->
                    ApplicationManager.getApplication().runWriteAction(() -> {
                        try {
                            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                                project,
                                () -> {
                                    try {
                                        vf.rename(FileTools.this, newName);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                },
                                "Rename File: " + oldName + " to " + newName,
                                null
                            );
                            resultFuture.complete("Renamed " + oldName + " to " + newName);
                        } catch (Exception e) {
                            resultFuture.complete("Error renaming file: " + e.getMessage());
                        }
                    })
                );
                return null;
            } catch (Exception e) {
                resultFuture.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
                return null;
            }
        }).inSmartMode(project).submit(AppExecutorUtil.getAppExecutorService());

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private String moveFile(JsonObject args) throws Exception {
        if (!args.has("path") || !args.has("destination"))
            return ToolUtils.ERROR_PREFIX + "'path' and 'destination' parameters are required";
        String pathStr = args.get("path").getAsString();
        String destStr = args.get("destination").getAsString();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        ReadAction.nonBlocking(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                    return null;
                }
                VirtualFile destDir = resolveVirtualFile(destStr);
                if (destDir == null || !destDir.isDirectory()) {
                    resultFuture.complete(ToolUtils.ERROR_PREFIX + "Destination directory not found: " + destStr);
                    return null;
                }
                String oldPath = vf.getPath();
                EdtUtil.invokeLater(() ->
                    ApplicationManager.getApplication().runWriteAction(() -> {
                        try {
                            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                                project,
                                () -> {
                                    try {
                                        vf.move(FileTools.this, destDir);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                },
                                "Move File: " + vf.getName(),
                                null
                            );
                            resultFuture.complete("Moved " + oldPath + " to " + destDir.getPath() + "/" + vf.getName());
                        } catch (Exception e) {
                            resultFuture.complete("Error moving file: " + e.getMessage());
                        }
                    })
                );
                return null;
            } catch (Exception e) {
                resultFuture.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
                return null;
            }
        }).inSmartMode(project).submit(AppExecutorUtil.getAppExecutorService());

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    /**
     * Undo the last editor action on a file. Uses IntelliJ's built-in UndoManager
     * which tracks all commands registered via CommandProcessor.
     */
    private void performUndo(String pathStr, int count, CompletableFuture<String> resultFuture) {
        try {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) {
                resultFuture.complete(ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                return;
            }
            com.intellij.openapi.fileEditor.FileEditor fileEditor = findFileEditor(vf);
            UndoManager undoManager = UndoManager.getInstance(project);
            String result = executeUndoSteps(undoManager, fileEditor, count, pathStr);
            resultFuture.complete(result);
        } catch (Exception e) {
            resultFuture.complete("Undo failed: " + e.getMessage());
        }
    }

    private com.intellij.openapi.fileEditor.FileEditor findFileEditor(VirtualFile vf) {
        var editors = FileEditorManager.getInstance(project).getEditors(vf);
        for (var ed : editors) {
            if (ed instanceof TextEditor) return ed;
        }
        return editors.length > 0 ? editors[0] : null;
    }

    private String executeUndoSteps(UndoManager undoManager, com.intellij.openapi.fileEditor.FileEditor fileEditor, int count, String pathStr) {
        StringBuilder actions = new StringBuilder();
        int undone = 0;
        for (int i = 0; i < count; i++) {
            if (!undoManager.isUndoAvailable(fileEditor)) break;
            String actionName = PlatformApiCompat.getUndoActionName(undoManager, fileEditor);
            undoManager.undo(fileEditor);
            undone++;
            if (!actions.isEmpty()) actions.append(", ");
            actions.append(actionName != null && !actionName.isEmpty() ? actionName : "unknown");
        }
        if (undone == 0) {
            return "Nothing to undo for " + pathStr;
        }
        FileDocumentManager.getInstance().saveAllDocuments();
        return "Undid " + undone + " action(s) on " + pathStr + ": " + actions;
    }

    private String undo(JsonObject args) throws Exception {
        if (!args.has("path")) return ToolUtils.ERROR_PATH_REQUIRED;
        String pathStr = args.get("path").getAsString();
        int count = args.has("count") ? args.get("count").getAsInt() : 1;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> performUndo(pathStr, count, resultFuture));
        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private String redo(JsonObject args) throws Exception {
        if (!args.has("path")) return ToolUtils.ERROR_PATH_REQUIRED;
        String pathStr = args.get("path").getAsString();
        int count = args.has("count") ? args.get("count").getAsInt() : 1;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> performRedo(pathStr, count, resultFuture));
        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private void performRedo(String pathStr, int count, CompletableFuture<String> resultFuture) {
        try {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) {
                resultFuture.complete(ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                return;
            }
            com.intellij.openapi.fileEditor.FileEditor fileEditor = findFileEditor(vf);
            UndoManager undoManager = UndoManager.getInstance(project);
            String result = executeRedoSteps(undoManager, fileEditor, count, pathStr);
            resultFuture.complete(result);
        } catch (Exception e) {
            resultFuture.complete("Redo failed: " + e.getMessage());
        }
    }

    private String executeRedoSteps(UndoManager undoManager, com.intellij.openapi.fileEditor.FileEditor fileEditor, int count, String pathStr) {
        StringBuilder actions = new StringBuilder();
        int redone = 0;
        for (int i = 0; i < count; i++) {
            if (!undoManager.isRedoAvailable(fileEditor)) break;
            String actionName = PlatformApiCompat.getRedoActionName(undoManager, fileEditor);
            undoManager.redo(fileEditor);
            redone++;
            if (!actions.isEmpty()) actions.append(", ");
            actions.append(actionName != null && !actionName.isEmpty() ? actionName : "unknown");
        }
        if (redone == 0) {
            return "Nothing to redo for " + pathStr;
        }
        FileDocumentManager.getInstance().saveAllDocuments();
        return "Redid " + redone + " action(s) on " + pathStr + ": " + actions;
    }

    /**
     * Refreshes one or more files/directories in IntelliJ's VFS from disk.
     * Useful after external tools (e.g. build scripts) modify files outside the editor.
     */
    private String reloadFromDisk(JsonObject args) {
        String basePath = project.getBasePath();
        if (basePath == null) return "No project base path";

        if (!args.has("path") || args.get("path").isJsonNull()) {
            // Refresh entire project root
            VirtualFile root = LocalFileSystem.getInstance().findFileByPath(basePath);
            if (root == null) return "Project root not found";
            VfsUtil.markDirtyAndRefresh(false, true, true, root);
            return "Reloaded project root from disk (" + basePath + ")";
        }

        String pathStr = args.get("path").getAsString();
        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) {
            // File may not be in VFS yet — refresh parent directory
            java.io.File f = new java.io.File(pathStr);
            if (!f.isAbsolute()) f = new java.io.File(basePath, pathStr);
            java.io.File parent = f.getParentFile();
            if (parent != null) {
                VirtualFile parentVf = LocalFileSystem.getInstance().refreshAndFindFileByPath(parent.getAbsolutePath());
                if (parentVf != null) return "Reloaded parent directory: " + parent.getAbsolutePath();
            }
            return "File not found: " + pathStr;
        }

        VfsUtil.markDirtyAndRefresh(false, vf.isDirectory(), true, vf);
        return "Reloaded from disk: " + vf.getPath();
    }
}
