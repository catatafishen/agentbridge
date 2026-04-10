package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.SearchResultRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.UsageSearchContext;
import com.intellij.psi.search.searches.ReferencesSearch;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds all usages of a symbol throughout the project.
 */
public final class FindReferencesTool extends NavigationTool {

    public FindReferencesTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "find_references";
    }

    @Override
    public @NotNull String displayName() {
        return "Find References";
    }

    @Override
    public @NotNull String description() {
        return "Find all usages of a symbol throughout the project. Semantic — finds references even through renames and imports. " +
            "Returns file paths and line numbers. For textual search, use search_text. For finding symbol definitions, use search_symbols.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.SEARCH;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("symbol", TYPE_STRING, "The exact symbol name to search for"),
            Param.optional("file_pattern", TYPE_STRING, "Optional glob pattern to filter files (e.g., '*.java')", "")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SearchResultRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        if (!args.has(PARAM_SYMBOL) || args.get(PARAM_SYMBOL).isJsonNull())
            return "Error: 'symbol' parameter is required";
        String symbol = args.get(PARAM_SYMBOL).getAsString();
        String filePattern = args.has(PARAM_FILE_PATTERN) ? args.get(PARAM_FILE_PATTERN).getAsString() : "";

        showSearchFeedback("🔍 Finding references: " + symbol);
        String result = ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            List<String> results = new ArrayList<>();
            String basePath = project.getBasePath();
            GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

            PsiElement definition = findDefinition(symbol, scope);
            if (definition != null) {
                collectDefinitionReferences(definition, scope, filePattern, basePath, results);
            }
            if (results.isEmpty()) {
                collectWordReferences(symbol, scope, filePattern, basePath, results);
            }
            if (results.isEmpty()) return "No references found for '" + symbol + "'";
            return results.size() + " references found:\n" + String.join("\n", results);
        });
        showSearchFeedback("✓ Reference search complete: " + symbol);
        return result;
    }

    private void collectDefinitionReferences(PsiElement definition, GlobalSearchScope scope,
                                             String filePattern, String basePath, List<String> results) {
        var compiledGlob = filePattern.isEmpty() ? null : ToolUtils.compileGlob(filePattern);
        for (PsiReference ref : ReferencesSearch.search(definition, scope).findAll()) {
            if (results.size() >= 100) break;
            String entry = buildReferenceEntry(ref, filePattern, compiledGlob, basePath);
            if (entry != null) results.add(entry);
        }
    }

    private void collectWordReferences(String symbol, GlobalSearchScope scope,
                                       String filePattern, String basePath, List<String> results) {
        var compiledGlob = filePattern.isEmpty() ? null : ToolUtils.compileGlob(filePattern);
        PsiSearchHelper.getInstance(project).processElementsWithWord(
            (element, offsetInElement) -> {
                com.intellij.psi.PsiFile file = element.getContainingFile();
                if (file == null || file.getVirtualFile() == null) return true;
                String relPath = basePath != null
                    ? relativize(basePath, file.getVirtualFile().getPath())
                    : file.getVirtualFile().getPath();
                if (!filePattern.isEmpty() && ToolUtils.doesNotMatchGlob(relPath, filePattern, compiledGlob))
                    return true;

                com.intellij.openapi.editor.Document doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                    .getDocument(file.getVirtualFile());
                if (doc != null) {
                    int line = doc.getLineNumber(element.getTextOffset()) + 1;
                    String lineText = ToolUtils.getLineText(doc, line - 1);
                    String entry = String.format(FORMAT_LINE_REF, relPath, line, lineText);
                    if (!results.contains(entry)) results.add(entry);
                }
                return results.size() < 100;
            },
            scope, symbol, UsageSearchContext.IN_CODE, true
        );
    }
}
