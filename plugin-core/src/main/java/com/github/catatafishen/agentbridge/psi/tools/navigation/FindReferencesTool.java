package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.SearchResultRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.UsageSearchContext;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.util.Query;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Finds all usages of a symbol throughout the project.
 */
public final class FindReferencesTool extends NavigationTool {

    private static final String FORMAT_RICH_REF = "%s:%d %s %s";
    private static final Comparator<Hit> HIT_ORDER = Comparator.comparing(Hit::path)
        .thenComparingInt(Hit::line)
        .thenComparing(Hit::context)
        .thenComparing(Hit::lineText);

    public FindReferencesTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "find_references";
    }

    @Override
    public boolean requiresIndex() {
        return true;
    }

    @Override
    public @NotNull String displayName() {
        return "Find References";
    }

    @Override
    public @NotNull String description() {
        return "Find all usages of a symbol throughout the project. Semantic — finds references even through " +
            "renames and imports. Returns file paths and line numbers with usage context (CALL, IMPORT, TYPE_REF, " +
            "etc.) and containing method/class name. Add \"libraries\" to 'scope' to also search inside library / JDK " +
            "sources (after running download_sources). " +
            "For textual search, use search_text. For finding symbol definitions, use search_symbols.";
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
        return FileCategory.withScopeParam(schema(
            Param.required("symbol", TYPE_STRING, "The exact symbol name to search for"),
            Param.optional("file_pattern", TYPE_STRING, "Optional glob pattern to filter files (e.g., '*.java')", ""),
            Param.optional(PARAM_MAX_RESULTS, TYPE_INTEGER, "Maximum results to return (default: 100)"),
            Param.optional(PARAM_OFFSET, TYPE_INTEGER, "Number of results to skip for pagination (default: 0)")
        ));
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
        FileCategory.Selection selection = FileCategory.parseScope(args);
        if (selection.error() != null) return selection.error();
        int[] pagination = readPaginationParams(args, DEFAULT_MAX_RESULTS);
        int maxResults = pagination[0];
        int offset = pagination[1];

        showSearchFeedback("🔍 Finding references: " + symbol);
        String result = computeWithProgress(() -> {
            List<Hit> hits = new ArrayList<>();
            String basePath = project.getBasePath();
            GlobalSearchScope scope = resolveScope(selection);
            // The categories restrict where usages are reported, not where the symbol is declared: a symbol
            // declared in generated_sources must still be found when only usages in sources are asked for.
            GlobalSearchScope definitionScope = selection.includes(FileCategory.LIBRARIES)
                ? GlobalSearchScope.allScope(project) : GlobalSearchScope.projectScope(project);

            for (PsiElement definition : findDefinitions(symbol, definitionScope)) {
                collectDefinitionReferences(definition, scope, filePattern, basePath, hits);
            }
            // Word-index text fallback: only for unqualified symbols. A qualified symbol like
            // "WidgetA.render" that finds no PSI definitions must NOT fall back to a raw text
            // search of the simple name — that would return usages of every other class's
            // "render()" method too (e.g. "WidgetB.render()"), silently violating the qualifier.
            // If PSI couldn't identify the qualified symbol, the correct answer is "no references
            // found" rather than a set of false positives.
            boolean isQualified = !simpleNameOf(symbol).equals(symbol);
            if (hits.isEmpty() && !isQualified) {
                collectWordReferences(symbol, scope, filePattern, basePath, hits);
            }
            if (hits.isEmpty()) return "No references found for '" + symbol + "'";
            return formatPage(symbol, hits, offset, maxResults);
        });
        showSearchFeedback("✓ Reference search complete: " + symbol);
        return result;
    }

    /**
     * Sorts, de-duplicates and paginates hits. The platform searches files in parallel and returns hits
     * in no fixed order; sorting makes the output (and therefore pagination) identical across calls.
     */
    private static String formatPage(String symbol, List<Hit> hits, int offset, int maxResults) {
        List<Hit> ordered = hits.stream().distinct().sorted(HIT_ORDER).toList();
        if (offset >= ordered.size()) return "No references found for '" + symbol + "' at offset " + offset;
        List<Hit> page = ordered.subList(offset, Math.min(ordered.size(), offset + maxResults));
        String footer = offset + page.size() < ordered.size()
            ? "\n\n(Showing " + page.size() + " of " + ordered.size() + " results starting at offset " + offset
            + ". Use offset=" + (offset + page.size()) + " to see more)"
            : "";
        return page.size() + " references found:\n"
            + page.stream().map(Hit::format).collect(Collectors.joining("\n")) + footer;
    }

    private void collectDefinitionReferences(PsiElement definition, GlobalSearchScope scope,
                                             String filePattern, String basePath, List<Hit> hits) {
        var compiledGlob = filePattern.isEmpty() ? null : ToolUtils.compileGlob(filePattern);
        // The query is built under a read action, but findAll() runs outside one: the platform takes a
        // short read action per file, so a pending write action is never blocked for the whole search.
        // A definition deleted by a concurrent edit since findDefinitions() has nothing left to search for.
        Optional<Query<PsiReference>> query = readAction(() -> definition.isValid()
            ? Optional.of(ReferencesSearch.search(definition, scope))
            : Optional.empty());
        if (query.isEmpty()) return;
        for (PsiReference ref : query.get().findAll()) {
            Hit hit = readAction(() -> ref.getElement().isValid()
                ? buildRichReferenceHit(ref, filePattern, compiledGlob, basePath)
                : null);
            if (hit != null) hits.add(hit);
        }
    }

    /**
     * Builds a rich reference hit from a PSI reference, including usage type and container context.
     */
    private @Nullable Hit buildRichReferenceHit(PsiReference ref, String filePattern,
                                                Pattern compiledGlob, String basePath) {
        PsiElement refEl = ref.getElement();
        String context = ReferenceClassifier.formatContext(
            ReferenceClassifier.classifyUsage(ref),
            ReferenceClassifier.findContainerName(refEl));
        return buildRichHit(refEl, filePattern, compiledGlob, basePath, context);
    }

    private void collectWordReferences(String symbol, GlobalSearchScope scope,
                                       String filePattern, String basePath, List<Hit> hits) {
        var compiledGlob = filePattern.isEmpty() ? null : ToolUtils.compileGlob(filePattern);
        // The platform invokes this processor from several worker threads at once, so the collection
        // must be thread-safe: appending to a plain ArrayList silently loses hits.
        Queue<Hit> found = new ConcurrentLinkedQueue<>();
        PsiSearchHelper.getInstance(project).processElementsWithWord(
            (element, offsetInElement) -> {
                Hit hit = buildWordHit(element, filePattern, compiledGlob, basePath);
                if (hit != null) found.add(hit);
                return true;
            },
            scope, symbol, UsageSearchContext.IN_CODE, true
        );
        hits.addAll(found);
    }

    /**
     * Builds a reference hit for a word-search element with rich context, or returns null if filtered out.
     */
    private @Nullable Hit buildWordHit(PsiElement element,
                                       String filePattern, Pattern compiledGlob, String basePath) {
        String context = ReferenceClassifier.formatContext(
            ReferenceClassifier.classifyUsage(element),
            ReferenceClassifier.findContainerName(element));
        return buildRichHit(element, filePattern, compiledGlob, basePath, context);
    }

    /**
     * Common helper: resolves element → file → relPath → line → rich hit.
     * Returns null if the element's file is null or filtered out by the glob pattern.
     */
    private @Nullable Hit buildRichHit(PsiElement element, String filePattern,
                                       Pattern compiledGlob, String basePath, String context) {
        com.intellij.psi.PsiFile file = element.getContainingFile();
        if (file == null || file.getVirtualFile() == null) return null;
        String relPath = safeRelativize(basePath, file.getVirtualFile().getPath());
        if (!filePattern.isEmpty() && ToolUtils.doesNotMatchGlob(relPath, filePattern, compiledGlob)) return null;
        com.intellij.openapi.editor.Document doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getDocument(file.getVirtualFile());
        if (doc == null) return null;
        int line = doc.getLineNumber(element.getTextOffset()) + 1;
        return new Hit(relPath, line, context, ToolUtils.getLineText(doc, line - 1));
    }

    private record Hit(String path, int line, String context, String lineText) {
        String format() {
            return String.format(FORMAT_RICH_REF, path, line, context, lineText);
        }
    }
}
