package com.github.catatafishen.ideagentforcopilot.psi;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Symbol-level editing tools that resolve PSI symbols by name and perform
 * structural edits (replace body, insert before/after) using line-range operations.
 */
class SymbolEditingTools extends AbstractToolHandler {

    private static final String PARAM_FILE = "file";
    private static final String PARAM_SYMBOL = "symbol";
    private static final String PARAM_NEW_BODY = "new_body";
    private static final String PARAM_CONTENT = "content";
    private static final String PARAM_LINE = "line";

    private final FileTools fileTools;

    SymbolEditingTools(Project project, FileTools fileTools) {
        super(project);
        this.fileTools = fileTools;
        register("replace_symbol_body", this::replaceSymbolBody);
        register("insert_before_symbol", this::insertBeforeSymbol);
        register("insert_after_symbol", this::insertAfterSymbol);
    }

    // ---- replace_symbol_body ----

    private String replaceSymbolBody(JsonObject args) throws Exception {
        String error = validateArgs(args, PARAM_NEW_BODY);
        if (error != null) return error;

        String pathStr = args.get(PARAM_FILE).getAsString();
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        String newBody = args.get(PARAM_NEW_BODY).getAsString();
        Integer lineHint = args.has(PARAM_LINE) ? args.get(PARAM_LINE).getAsInt() : null;

        // Resolve symbol AND replace atomically on the EDT to prevent TOCTOU races.
        // Multiple concurrent replace_symbol_body calls (e.g., from a single agent response)
        // would otherwise resolve symbols at stale line offsets.
        CompletableFuture<String> result = new CompletableFuture<>();
        int[] lineRange = new int[2];
        String[] symbolType = new String[1];

        EdtUtil.invokeLater(() -> {
            try {
                SymbolLocation loc = resolveSymbol(pathStr, symbolName, lineHint);
                if (loc == null) {
                    result.complete(symbolNotFoundMessage(pathStr, symbolName, lineHint));
                    return;
                }
                lineRange[0] = loc.startLine;
                lineRange[1] = loc.endLine;
                symbolType[0] = loc.type;

                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    result.complete(ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                    return;
                }
                Document doc = FileDocumentManager.getInstance().getDocument(vf);
                if (doc == null) {
                    result.complete("Cannot open document: " + pathStr);
                    return;
                }

                int startOffset = doc.getLineStartOffset(loc.startLine - 1);
                int endOffset = doc.getLineEndOffset(loc.endLine - 1);
                if (endOffset < doc.getTextLength() && doc.getText().charAt(endOffset) == '\n') {
                    endOffset++;
                }
                String normalized = newBody.replace("\r\n", "\n").replace("\r", "\n");
                if (!normalized.isEmpty() && !normalized.endsWith("\n")) {
                    normalized += "\n";
                }

                final int fStart = startOffset;
                final int fEnd = endOffset;
                final String fNew = normalized;

                ApplicationManager.getApplication().runWriteAction(() ->
                    com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                        project, () -> doc.replaceString(fStart, fEnd, fNew),
                        "Replace Symbol Body", null)
                );

                // Commit PSI so subsequent symbol lookups see the updated tree
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(doc);
                FileDocumentManager.getInstance().saveDocument(doc);

                int replacedLines = loc.endLine - loc.startLine + 1;
                int newLineCount = (int) fNew.chars().filter(c -> c == '\n').count();
                result.complete("Replaced lines " + loc.startLine + "-" + loc.endLine
                    + " (" + replacedLines + " lines) with " + newLineCount + " lines in " + pathStr);
            } catch (Exception e) {
                result.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
            }
        });

        String resultStr = result.get(15, TimeUnit.SECONDS);
        if (!resultStr.startsWith(ToolUtils.ERROR_PREFIX) && !resultStr.startsWith("Symbol '")) {
            int newLineCount = (int) newBody.chars().filter(c -> c == '\n').count() + 1;
            fileTools.queueAutoFormat(pathStr);
            FileTools.followFileIfEnabled(project, pathStr, lineRange[0], lineRange[0] + newLineCount - 1,
                FileTools.HIGHLIGHT_EDIT, "replacing " + symbolType[0] + " " + symbolName);
            FileAccessTracker.recordWrite(project, pathStr);
        }
        return resultStr;
    }

    // ---- insert_before_symbol ----

    private String insertBeforeSymbol(JsonObject args) throws Exception {
        String error = validateArgs(args, PARAM_CONTENT);
        if (error != null) return error;

        String pathStr = args.get(PARAM_FILE).getAsString();
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        String content = args.get(PARAM_CONTENT).getAsString();
        Integer lineHint = args.has(PARAM_LINE) ? args.get(PARAM_LINE).getAsInt() : null;

        CompletableFuture<String> result = new CompletableFuture<>();
        int[] anchorLine = new int[1];

        EdtUtil.invokeLater(() -> {
            try {
                SymbolLocation loc = resolveSymbol(pathStr, symbolName, lineHint);
                if (loc == null) {
                    result.complete(symbolNotFoundMessage(pathStr, symbolName, lineHint));
                    return;
                }
                anchorLine[0] = loc.startLine;

                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    result.complete(ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                    return;
                }
                Document doc = FileDocumentManager.getInstance().getDocument(vf);
                if (doc == null) {
                    result.complete("Cannot open document: " + pathStr);
                    return;
                }

                String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
                if (!normalized.endsWith("\n")) {
                    normalized += "\n";
                }
                int offset = doc.getLineStartOffset(loc.startLine - 1);
                final String fContent = normalized;
                final int fOffset = offset;

                ApplicationManager.getApplication().runWriteAction(() ->
                    com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                        project, () -> doc.insertString(fOffset, fContent),
                        "Insert Before Symbol", null)
                );

                com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(doc);
                FileDocumentManager.getInstance().saveDocument(doc);

                int newLineCount = (int) fContent.chars().filter(c -> c == '\n').count();
                result.complete("Inserted " + newLineCount + " lines before line " + loc.startLine + " in " + pathStr);
            } catch (Exception e) {
                result.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
            }
        });

        String resultStr = result.get(15, TimeUnit.SECONDS);
        if (!resultStr.startsWith(ToolUtils.ERROR_PREFIX) && !resultStr.startsWith("Symbol '")) {
            int insertedLines = (int) content.chars().filter(c -> c == '\n').count() + 1;
            fileTools.queueAutoFormat(pathStr);
            FileTools.followFileIfEnabled(project, pathStr, anchorLine[0], anchorLine[0] + insertedLines - 1,
                FileTools.HIGHLIGHT_EDIT, "inserting before " + symbolName);
            FileAccessTracker.recordWrite(project, pathStr);
        }
        return resultStr;
    }

    // ---- insert_after_symbol ----

    private String insertAfterSymbol(JsonObject args) throws Exception {
        String error = validateArgs(args, PARAM_CONTENT);
        if (error != null) return error;

        String pathStr = args.get(PARAM_FILE).getAsString();
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        String content = args.get(PARAM_CONTENT).getAsString();
        Integer lineHint = args.has(PARAM_LINE) ? args.get(PARAM_LINE).getAsInt() : null;

        CompletableFuture<String> result = new CompletableFuture<>();
        int[] endLine = new int[1];

        EdtUtil.invokeLater(() -> {
            try {
                SymbolLocation loc = resolveSymbol(pathStr, symbolName, lineHint);
                if (loc == null) {
                    result.complete(symbolNotFoundMessage(pathStr, symbolName, lineHint));
                    return;
                }
                endLine[0] = loc.endLine;

                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    result.complete(ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                    return;
                }
                Document doc = FileDocumentManager.getInstance().getDocument(vf);
                if (doc == null) {
                    result.complete("Cannot open document: " + pathStr);
                    return;
                }

                String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
                if (!normalized.endsWith("\n")) {
                    normalized += "\n";
                }
                int offset = doc.getLineEndOffset(loc.endLine - 1);
                if (offset < doc.getTextLength() && doc.getText().charAt(offset) == '\n') {
                    offset++;
                }
                final String fContent = normalized;
                final int fOffset = offset;

                ApplicationManager.getApplication().runWriteAction(() ->
                    com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                        project, () -> doc.insertString(fOffset, fContent),
                        "Insert After Symbol", null)
                );

                com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(doc);
                FileDocumentManager.getInstance().saveDocument(doc);

                int newLineCount = (int) fContent.chars().filter(c -> c == '\n').count();
                result.complete("Inserted " + newLineCount + " lines after line " + loc.endLine + " in " + pathStr);
            } catch (Exception e) {
                result.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
            }
        });

        String resultStr = result.get(15, TimeUnit.SECONDS);
        if (!resultStr.startsWith(ToolUtils.ERROR_PREFIX) && !resultStr.startsWith("Symbol '")) {
            int insertedLines = (int) content.chars().filter(c -> c == '\n').count() + 1;
            int insertStart = endLine[0] + 1;
            fileTools.queueAutoFormat(pathStr);
            FileTools.followFileIfEnabled(project, pathStr, insertStart, insertStart + insertedLines - 1,
                FileTools.HIGHLIGHT_EDIT, "inserting after " + symbolName);
            FileAccessTracker.recordWrite(project, pathStr);
        }
        return resultStr;
    }

    // ---- Symbol resolution ----

    private record SymbolLocation(int startLine, int endLine, String type, String name) {
    }

    @Nullable
    private SymbolLocation resolveSymbol(String pathStr, String symbolName, @Nullable Integer lineHint) {
        return ApplicationManager.getApplication().runReadAction((Computable<SymbolLocation>) () -> {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) return null;

            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return null;

            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc == null) return null;

            List<SymbolLocation> matches = new ArrayList<>();
            psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
                @Override
                public void visitElement(@NotNull PsiElement element) {
                    if (element instanceof PsiNamedElement named) {
                        String name = named.getName();
                        if (symbolName.equals(name)) {
                            String type = ToolUtils.classifyElement(element);
                            if (type != null) {
                                TextRange range = element.getTextRange();
                                int startLine = doc.getLineNumber(range.getStartOffset()) + 1;
                                int endLine = doc.getLineNumber(range.getEndOffset()) + 1;
                                matches.add(new SymbolLocation(startLine, endLine, type, name));
                            }
                        }
                    }
                    super.visitElement(element);
                }
            });

            if (matches.isEmpty()) return null;
            if (matches.size() == 1) return matches.getFirst();

            // Disambiguate by line hint
            if (lineHint != null) {
                for (SymbolLocation loc : matches) {
                    if (loc.startLine == lineHint) return loc;
                }
                // Fall back to closest match
                SymbolLocation closest = matches.getFirst();
                int minDist = Math.abs(closest.startLine - lineHint);
                for (SymbolLocation loc : matches) {
                    int dist = Math.abs(loc.startLine - lineHint);
                    if (dist < minDist) {
                        closest = loc;
                        minDist = dist;
                    }
                }
                return closest;
            }
            return matches.getFirst();
        });
    }

    // ---- Validation helpers ----

    private static @Nullable String validateArgs(JsonObject args, String contentParam) {
        if (!args.has(PARAM_FILE) || args.get(PARAM_FILE).isJsonNull())
            return ToolUtils.ERROR_PATH_REQUIRED;
        if (!args.has(PARAM_SYMBOL) || args.get(PARAM_SYMBOL).isJsonNull())
            return "Missing required parameter: symbol";
        if (!args.has(contentParam) || args.get(contentParam).isJsonNull())
            return "Missing required parameter: " + contentParam;
        return null;
    }

    private String symbolNotFoundMessage(String pathStr, String symbolName, @Nullable Integer lineHint) {
        // Provide helpful context by listing available symbols
        String available = ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) return ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return "";
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc == null) return "";

            List<String> symbols = new ArrayList<>();
            psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
                @Override
                public void visitElement(@NotNull PsiElement element) {
                    if (element instanceof PsiNamedElement named && named.getName() != null) {
                        String type = ToolUtils.classifyElement(element);
                        if (type != null) {
                            int line = doc.getLineNumber(element.getTextOffset()) + 1;
                            symbols.add(type + " " + named.getName() + " (line " + line + ")");
                        }
                    }
                    super.visitElement(element);
                }
            });
            if (symbols.isEmpty()) return "";
            return "\nAvailable symbols: " + String.join(", ", symbols);
        });

        String msg = "Symbol '" + symbolName + "' not found in " + pathStr;
        if (lineHint != null) msg += " (near line " + lineHint + ")";
        return msg + available;
    }
}
