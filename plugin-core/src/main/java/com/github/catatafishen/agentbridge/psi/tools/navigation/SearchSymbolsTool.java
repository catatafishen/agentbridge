package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.SearchResultRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.UsageSearchContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Searches for classes, methods, or fields by name using IntelliJ's symbol index.
 */
public final class SearchSymbolsTool extends NavigationTool {

    public SearchSymbolsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "search_symbols";
    }

    @Override
    public boolean requiresIndex() {
        return true;
    }

    @Override
    public @NotNull String displayName() {
        return "Search Symbols";
    }

    @Override
    public @NotNull String description() {
        return "Search for classes, methods, or fields by name using IntelliJ's symbol index. " +
            "Semantic search — finds symbols even if the text doesn't appear literally (e.g. inherited members). " +
            "Use the 'scope' parameter to look up symbols inside library / JDK sources " +
            "(after running download_sources). " +
            "For textual/regex search across file contents, use search_text. " +
            "For all usages of a specific symbol, use find_references.";
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
            Param.required("query", TYPE_STRING, "Symbol name to search for, or '*' to list all symbols in the project"),
            Param.optional("type", TYPE_STRING, "Optional: filter by type (class, method, field, property). Default: all types", ""),
            Param.optional(PARAM_SCOPE, TYPE_STRING, SCOPE_DESCRIPTION, SCOPE_PROJECT),
            Param.optional(PARAM_MAX_RESULTS, TYPE_INTEGER, "Maximum results to return (default: 50 for exact, 200 for wildcard)"),
            Param.optional(PARAM_OFFSET, TYPE_INTEGER, "Number of results to skip for pagination (default: 0)")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SearchResultRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        String query = args.has(PARAM_QUERY) ? args.get(PARAM_QUERY).getAsString() : "";
        String typeFilter = args.has("type") ? args.get("type").getAsString() : "";
        String scopeName = readScopeParam(args);
        int[] pagination = readPaginationParams(args, -1); // -1 means "use per-mode default"
        int maxResults = pagination[0];
        int offset = pagination[1];

        showSearchFeedback("🔍 Searching symbols: " + query);
        String result = ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            if (query.isEmpty() || "*".equals(query)) {
                if (!SCOPE_PROJECT.equalsIgnoreCase(scopeName)) {
                    return "Error: Wildcard symbol listing is only supported with scope='project'. "
                        + "Use an exact query name when searching scope='libraries' or scope='all'.";
                }
                return searchWildcard(typeFilter, maxResults > 0 ? maxResults : 200, offset);
            }
            return searchExact(query, typeFilter, resolveScope(scopeName), maxResults > 0 ? maxResults : 50, offset);
        });
        showSearchFeedback("✓ Symbol search complete: " + query);
        return result;
    }

    private String searchWildcard(String typeFilter, int maxResults, int offset) {
        if (typeFilter.isEmpty())
            return "Provide a 'type' filter (class, interface, method, field) when using wildcard query";

        List<String> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String basePath = project.getBasePath();
        int[] fileCount = {0};

        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        fileIndex.iterateContent(vf -> {
            if (vf.isDirectory() || vf.getFileType().isBinary()) return true;
            if (!fileIndex.isInSourceContent(vf)) return true;
            fileCount[0]++;
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return true;
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc == null) return true;

            collectSymbolsFromFile(psiFile, doc, vf, typeFilter, basePath, seen, results);
            return results.size() < offset + maxResults;
        });

        if (results.isEmpty())
            return "No " + typeFilter + " symbols found (scanned " + fileCount[0]
                + " source files using AST analysis). This is a definitive result — no grep needed.";

        // Apply offset
        List<String> page = offset >= results.size() ? List.of() : results.subList(offset, Math.min(results.size(), offset + maxResults));
        if (page.isEmpty()) return "No more results at offset " + offset;

        String footer = page.size() >= maxResults
            ? "\n\n(Showing " + maxResults + " results starting at offset " + offset + ". Use offset=" + (offset + maxResults) + " to see more)"
            : "";
        return page.size() + " " + typeFilter + " symbols:\n" + String.join("\n", page) + footer;
    }

    private String searchExact(String query, String typeFilter, GlobalSearchScope scope, int maxResults, int offset) {
        List<String> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String basePath = project.getBasePath();

        PsiSearchHelper.getInstance(project).processElementsWithWord(
            (element, offsetInElement) -> {
                PsiElement parent = element.getParent();
                if (parent instanceof PsiNamedElement named && query.equals(named.getName())) {
                    String type = ToolUtils.classifyElement(parent);
                    if (type != null && (typeFilter.isEmpty() || type.equals(typeFilter))) {
                        addSymbolResult(parent, basePath, seen, results);
                    }
                }
                return results.size() < offset + maxResults;
            },
            scope, query, UsageSearchContext.IN_CODE, true
        );

        // Apply offset
        List<String> page = offset >= results.size() ? List.of() : results.subList(offset, Math.min(results.size(), offset + maxResults));
        if (page.isEmpty()) return "No symbols found matching '" + query + "'";
        String footer = page.size() >= maxResults
            ? "\n\n(Showing " + maxResults + " results starting at offset " + offset + ". Use offset=" + (offset + maxResults) + " to see more)"
            : "";
        return String.join("\n", page) + footer;
    }
}
