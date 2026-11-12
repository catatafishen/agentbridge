package com.github.catatafishen.agentbridge.psi.tools.refactoring;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.file.FileTool;
import com.github.catatafishen.agentbridge.ui.renderers.RefactorRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.usageView.UsageInfo;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@SuppressWarnings("java:S112")
public final class RefactorTool extends RefactoringTool {

    private static final Logger LOG = Logger.getInstance(RefactorTool.class);
    private static final String PARAM_SYMBOL = "symbol";
    private static final String PARAM_NEW_NAME = "new_name";
    private static final String PARAM_OPERATION = "operation";
    private static final String PARAM_TEXT_OCCURRENCES = "search_text_occurrences";
    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_LISTED_FILES = 20;

    public RefactorTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "refactor";
    }

    @Override
    public boolean requiresIndex() {
        return true;
    }

    @Override
    public @NotNull String displayName() {
        return "Refactor";
    }

    @Override
    public @NotNull String description() {
        return "Rename or safe-delete a symbol using IntelliJ's refactoring engine. Rename updates the declaration "
            + "and all semantic references, and lists the files it changed. By default it does NOT touch comments, "
            + "strings or non-code files; pass search_text_occurrences=true to also rename textual mentions "
            + "(ignored for locals and parameters). Safe-delete refuses and lists usages if the symbol is still used.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "{operation} {symbol}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_OPERATION, TYPE_STRING, "Refactoring type: 'rename' or 'safe_delete'"),
            Param.required("path", TYPE_STRING, "Absolute or project-relative path to the file containing the symbol"),
            Param.required(PARAM_SYMBOL, TYPE_STRING, "Name of the symbol to refactor (class, method, field, or variable)"),
            Param.optional("line", TYPE_INTEGER, "Line number to disambiguate if multiple symbols share the same name"),
            Param.optional(PARAM_NEW_NAME, TYPE_STRING, "New name for 'rename' operation. Required when operation is 'rename'"),
            Param.optional(PARAM_TEXT_OCCURRENCES, TYPE_BOOLEAN, "Rename only: also replace the name in comments, strings "
                + "and non-code files (default: false). Plain-text matching: use only for distinctive names. "
                + "Ignored for local variables and parameters")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return RefactorRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has(PARAM_OPERATION) || !args.has("path") || !args.has(PARAM_SYMBOL)) {
            return "Error: 'operation', 'path', and 'symbol' parameters are required";
        }
        String operation = args.get(PARAM_OPERATION).getAsString();
        String pathStr = args.get("path").getAsString();
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        int targetLine = args.has("line") ? args.get("line").getAsInt() : -1;
        String newName = args.has(PARAM_NEW_NAME) ? args.get(PARAM_NEW_NAME).getAsString() : null;
        boolean textSearch = args.has(PARAM_TEXT_OCCURRENCES) && args.get(PARAM_TEXT_OCCURRENCES).getAsBoolean();

        if ("rename".equals(operation) && (newName == null || newName.isEmpty())) {
            return "Error: 'new_name' is required for rename operation";
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                String result = resolveAndRefactor(operation, pathStr, symbolName, targetLine, newName, textSearch);
                resultFuture.complete(result);
            } catch (Exception e) {
                resultFuture.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
            }
        });

        String result;
        try {
            result = resultFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // The refactoring is queued or running on the EDT and cannot be cancelled from here,
            // so it may still complete. Reporting a plain failure hid applied changes (issue #1238).
            return "Error: '" + operation + "' of '" + symbolName + "' did not finish within " + TIMEOUT_SECONDS
                + "s. It may still be running and may have been partially or fully applied. "
                + "Check git_status / git_diff before retrying; do not issue further refactor calls until it settles.";
        }
        if (!result.startsWith("Error")) {
            FileTool.followFileIfEnabled(project, pathStr, Math.max(targetLine, 1), Math.max(targetLine, 1),
                FileTool.HIGHLIGHT_EDIT, FileTool.agentLabel(project) + " refactored");
        }
        return result;
    }

    private String resolveAndRefactor(String operation, String pathStr, String symbolName,
                                      int targetLine, String newName, boolean textSearch) throws Exception {
        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;

        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_CANNOT_PARSE + pathStr;

        Document document = FileDocumentManager.getInstance().getDocument(vf);
        PsiNamedElement targetElement = findNamedElement(psiFile, document, symbolName, targetLine);
        if (targetElement == null) {
            return "Error: Symbol '" + symbolName + "' not found in " + pathStr +
                (targetLine > 0 ? " at line " + targetLine : "") +
                ". Use search_symbols to find the correct name and location.";
        }

        FileTool.notifyBeforeEdit(project, vf, document);
        String[] result = new String[1];
        try {
            PlatformApiCompat.writeActionRunAndWait(() -> {
                try {
                    result[0] = executeRefactoring(operation, targetElement, symbolName, newName, pathStr, textSearch);
                } catch (Exception e) {
                    LOG.warn("Refactoring error", e);
                    result[0] = "Error during refactoring: " + e.getMessage();
                }
            });
            // Save outside the write action, so before-save hooks (trailing-space stripping etc.)
            // are not nested inside the refactoring, but still inside the agent-edit scope so
            // the edits those hooks make are tracked for review.
            FileDocumentManager.getInstance().saveAllDocuments();
        } finally {
            FileTool.notifyEditComplete();
        }
        return result[0];
    }

    private String executeRefactoring(String operation, PsiNamedElement targetElement,
                                      String symbolName, String newName, String pathStr,
                                      boolean textSearch) {
        return switch (operation) {
            case "rename" -> performRename(targetElement, symbolName, newName, pathStr, textSearch);
            case "safe_delete" -> performSafeDelete(targetElement, symbolName, pathStr);
            case "inline" -> "Error: 'inline' refactoring is not yet supported via this tool. " +
                "Use edit_text to manually inline the code.";
            case "extract_method" -> "Error: 'extract_method' requires a code selection range " +
                "which is not well-suited for tool-based invocation. " +
                "Use edit_text to manually extract the method.";
            default -> "Error: Unknown operation '" + operation + "'. Supported: rename, safe_delete";
        };
    }

    private String performRename(PsiNamedElement targetElement, String symbolName,
                                 String newName, String pathStr, boolean textSearchRequested) {
        // Locally-scoped symbols (locals, parameters) can never be referenced from comments or
        // non-code files elsewhere; a text search for a common word like "include" rewrote
        // LICENSE, docs and C++ #include lines across the repo (issue #1238).
        boolean localSymbol = targetElement.getUseScope() instanceof LocalSearchScope;
        boolean textSearch = textSearchRequested && !localSymbol;

        var factory = com.intellij.refactoring.RefactoringFactory.getInstance(project);
        var rename = factory.createRename(targetElement, newName);
        rename.setSearchInComments(textSearch);
        rename.setSearchInNonJavaFiles(textSearch);
        // VirtualFile handles survive file renames (e.g. renaming a class renames its file), so the
        // paths are resolved only after the refactoring. The declaration file is always included:
        // an unused symbol has no usages but its own file still changes.
        Set<VirtualFile> touched = new LinkedHashSet<>();
        PsiFile declarationFile = targetElement.getContainingFile();
        if (declarationFile != null && declarationFile.getVirtualFile() != null) {
            touched.add(declarationFile.getVirtualFile());
        }
        int[] usageCount = new int[1];
        CommandProcessor.getInstance().executeCommand(
            project,
            () -> {
                UsageInfo[] usages = rename.findUsages();
                usageCount[0] = usages.length;
                for (UsageInfo usage : usages) {
                    VirtualFile file = usage.getVirtualFile();
                    if (file != null) touched.add(file);
                }
                rename.doRefactoring(usages);
            },
            "Rename " + symbolName + " to " + newName,
            null
        );

        PsiDocumentManager.getInstance(project).commitAllDocuments();
        Set<String> touchedFiles = new TreeSet<>();
        for (VirtualFile file : touched) touchedFiles.add(relativize(file));

        StringBuilder sb = new StringBuilder()
            .append("Renamed '").append(symbolName).append("' to '").append(newName).append("'\n")
            .append("  Updated ").append(usageCount[0]).append(" usages in ")
            .append(touchedFiles.size()).append(" file(s).\n")
            .append("  File: ").append(pathStr).append('\n');
        if (textSearchRequested && localSymbol) {
            sb.append("  Note: search_text_occurrences ignored — the symbol is locally scoped.\n");
        }
        int shown = 0;
        for (String file : touchedFiles) {
            if (shown++ >= MAX_LISTED_FILES) {
                sb.append("  ... and ").append(touchedFiles.size() - MAX_LISTED_FILES).append(" more\n");
                break;
            }
            sb.append("  - ").append(file).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String relativize(VirtualFile file) {
        String basePath = project.getBasePath();
        String path = file.getPath();
        return basePath != null && path.startsWith(basePath + "/") ? path.substring(basePath.length() + 1) : path;
    }

    private String performSafeDelete(PsiNamedElement targetElement, String symbolName, String pathStr) {
        var refs = ReferencesSearch.search(targetElement, GlobalSearchScope.projectScope(project)).findAll();

        if (!refs.isEmpty()) {
            return formatUsageReport(symbolName, refs);
        }

        CommandProcessor.getInstance().executeCommand(
            project,
            targetElement::delete,
            "Safe Delete " + symbolName,
            null
        );
        PsiDocumentManager.getInstance(project).commitAllDocuments();
        FileDocumentManager.getInstance().saveAllDocuments();

        return "Safely deleted '" + symbolName + "' (no usages found).\n  File: " + pathStr;
    }

    private String formatUsageReport(String symbolName, Collection<PsiReference> refs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Cannot safely delete '").append(symbolName)
            .append("' - it has ").append(refs.size()).append(" usages:\n");
        String basePath = project.getBasePath();
        int shown = 0;
        for (var ref : refs) {
            if (shown++ >= 10) {
                sb.append("  ... and ").append(refs.size() - 10).append(" more\n");
                break;
            }
            PsiFile refFile = ref.getElement().getContainingFile();
            int line = -1;
            if (refFile != null && refFile.getVirtualFile() != null) {
                Document refDoc = FileDocumentManager.getInstance().getDocument(refFile.getVirtualFile());
                if (refDoc != null) {
                    line = refDoc.getLineNumber(ref.getElement().getTextOffset()) + 1;
                }
            }
            String displayPath = "?";
            if (refFile != null && refFile.getVirtualFile() != null) {
                String rel = relativize(basePath, refFile.getVirtualFile().getPath());
                displayPath = rel != null ? rel : refFile.getName();
            }
            sb.append("  ").append(displayPath).append(':').append(line).append('\n');
        }
        return sb.toString();
    }
}
