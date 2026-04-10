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
import com.intellij.psi.search.searches.ReferencesSearch;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("java:S112")
public final class RefactorTool extends RefactoringTool {

    private static final Logger LOG = Logger.getInstance(RefactorTool.class);
    private static final String PARAM_SYMBOL = "symbol";
    private static final String PARAM_NEW_NAME = "new_name";
    private static final String PARAM_OPERATION = "operation";

    public RefactorTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "refactor";
    }

    @Override
    public @NotNull String displayName() {
        return "Refactor";
    }

    @Override
    public @NotNull String description() {
        return "Rename or safe-delete a symbol using IntelliJ's refactoring engine";
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
            Param.required("file", TYPE_STRING, "Absolute or project-relative path to the file containing the symbol"),
            Param.required(PARAM_SYMBOL, TYPE_STRING, "Name of the symbol to refactor (class, method, field, or variable)"),
            Param.optional("line", TYPE_INTEGER, "Line number to disambiguate if multiple symbols share the same name"),
            Param.optional(PARAM_NEW_NAME, TYPE_STRING, "New name for 'rename' operation. Required when operation is 'rename'")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return RefactorRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has(PARAM_OPERATION) || !args.has("file") || !args.has(PARAM_SYMBOL)) {
            return "Error: 'operation', 'file', and 'symbol' parameters are required";
        }
        String operation = args.get(PARAM_OPERATION).getAsString();
        String pathStr = args.get("file").getAsString();
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        int targetLine = args.has("line") ? args.get("line").getAsInt() : -1;
        String newName = args.has(PARAM_NEW_NAME) ? args.get(PARAM_NEW_NAME).getAsString() : null;

        if ("rename".equals(operation) && (newName == null || newName.isEmpty())) {
            return "Error: 'new_name' is required for rename operation";
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                String result = resolveAndRefactor(operation, pathStr, symbolName, targetLine, newName);
                resultFuture.complete(result);
            } catch (Exception e) {
                resultFuture.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
            }
        });

        String result = resultFuture.get(30, TimeUnit.SECONDS);
        if (!result.startsWith("Error")) {
            FileTool.followFileIfEnabled(project, pathStr, Math.max(targetLine, 1), Math.max(targetLine, 1),
                FileTool.HIGHLIGHT_EDIT, FileTool.agentLabel(project) + " refactored");
        }
        return result;
    }

    private String resolveAndRefactor(String operation, String pathStr, String symbolName,
                                      int targetLine, String newName) throws Exception {
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

        String[] result = new String[1];
        PlatformApiCompat.writeActionRunAndWait(() -> {
            try {
                result[0] = executeRefactoring(operation, targetElement, symbolName, newName, pathStr);
            } catch (Exception e) {
                LOG.warn("Refactoring error", e);
                result[0] = "Error during refactoring: " + e.getMessage();
            }
        });
        return result[0];
    }

    private String executeRefactoring(String operation, PsiNamedElement targetElement,
                                      String symbolName, String newName, String pathStr) {
        return switch (operation) {
            case "rename" -> performRename(targetElement, symbolName, newName, pathStr);
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
                                 String newName, String pathStr) {
        var refs = ReferencesSearch.search(targetElement, GlobalSearchScope.projectScope(project)).findAll();
        int refCount = refs.size();

        var factory = com.intellij.refactoring.RefactoringFactory.getInstance(project);
        var rename = factory.createRename(targetElement, newName);
        rename.setSearchInComments(true);
        rename.setSearchInNonJavaFiles(true);
        CommandProcessor.getInstance().executeCommand(
            project,
            () -> {
                var usages = rename.findUsages();
                rename.doRefactoring(usages);
            },
            "Rename " + symbolName + " to " + newName,
            null
        );

        PsiDocumentManager.getInstance(project).commitAllDocuments();
        FileDocumentManager.getInstance().saveAllDocuments();

        return "Renamed '" + symbolName + "' to '" + newName + "'\n" +
            "  Updated " + refCount + " references across the project.\n" +
            "  File: " + pathStr;
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
