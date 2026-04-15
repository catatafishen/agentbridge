package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.services.ActiveAgentManager;
import com.github.catatafishen.agentbridge.services.AgentTabTracker;
import com.github.catatafishen.agentbridge.ui.renderers.SearchResultRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiManager;
import com.intellij.usageView.UsageInfo;
import com.intellij.usages.Usage;
import com.intellij.usages.UsageInfo2UsageAdapter;
import com.intellij.usages.UsageTarget;
import com.intellij.usages.UsageViewManager;
import com.intellij.usages.UsageViewPresentation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class SearchTextTool extends NavigationTool {

    private static final String PARAM_REGEX = "regex";
    private static final String PARAM_CASE_SENSITIVE = "case_sensitive";
    private static final String PARAM_MAX_RESULTS = "max_results";
    private static final String PARAM_CONTEXT_LINES = "context_lines";

    /**
     * Holds a single match position for visual display in follow-agent mode.
     * {@code psiFile} is resolved inside the read-action search pass so the EDT
     * invokeLater in showResultsInUsageView does not need to call PsiManager at all.
     */
    private record MatchPosition(VirtualFile vf, @Nullable com.intellij.psi.PsiFile psiFile,
                                 int startOffset, int endOffset) {
    }

    /** Hard cap on total output bytes — prevents 50+ MB responses from broad searches. */
    private static final int MAX_OUTPUT_BYTES = 256 * 1024; // 256 KB

    private record SearchParams(Pattern pattern, String basePath, String filePattern,
                                Pattern compiledFileGlob,
                                List<String> results, @Nullable List<MatchPosition> positions,
                                AtomicInteger skippedLarge, int maxResults, int contextLines,
                                AtomicInteger totalOutputBytes) {
    }

    public SearchTextTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "search_text";
    }

    @Override
    public @NotNull String displayName() {
        return "Search Text";
    }

    @Override
    public @NotNull String description() {
        return "Search for text or regex patterns across project files using IntelliJ's editor buffers. " +
            "Returns file paths with line numbers and matching text. " +
            "For semantic symbol lookup, use search_symbols instead. For finding all usages of a known symbol, use find_references.";
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
            Param.required("query", TYPE_STRING, "Text or regex pattern to search for"),
            Param.optional("file_pattern", TYPE_STRING, "Optional glob pattern to filter files (e.g., '*.kt', '*.java')", ""),
            Param.optional(PARAM_REGEX, TYPE_BOOLEAN, "If true, treat query as regex. Default: false (literal match)"),
            Param.optional(PARAM_CASE_SENSITIVE, TYPE_BOOLEAN, "Case-sensitive search. Default: true"),
            Param.optional(PARAM_MAX_RESULTS, TYPE_INTEGER, "Maximum results to return (default: 100)"),
            Param.optional(PARAM_CONTEXT_LINES, TYPE_INTEGER, "Lines of context before and after each match (default: 0). Reduces need for follow-up read_file calls.")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SearchResultRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        if (!args.has(PARAM_QUERY) || args.get(PARAM_QUERY).isJsonNull())
            return "Error: 'query' parameter is required";
        String query = args.get(PARAM_QUERY).getAsString();
        String filePattern = args.has(PARAM_FILE_PATTERN) ? args.get(PARAM_FILE_PATTERN).getAsString() : "";
        boolean isRegex = args.has(PARAM_REGEX) && args.get(PARAM_REGEX).getAsBoolean();
        boolean caseSensitive = !args.has(PARAM_CASE_SENSITIVE) || args.get(PARAM_CASE_SENSITIVE).getAsBoolean();
        int maxResults = args.has(PARAM_MAX_RESULTS) ? args.get(PARAM_MAX_RESULTS).getAsInt() : 100;
        int contextLines = args.has(PARAM_CONTEXT_LINES) ? args.get(PARAM_CONTEXT_LINES).getAsInt() : 0;
        boolean followAgent = ActiveAgentManager.getFollowAgentFiles(project);

        showSearchFeedback("Searching text: " + query);
        // Cast required: disambiguates Computable<T> vs ThrowableComputable<T,E> overloads.
        // The IDE falsely reports this as redundant; Gradle fails without it.
        Computable<String> action = () -> performSearch(query, filePattern, isRegex, caseSensitive, maxResults, contextLines, followAgent);
        String result = ApplicationManager.getApplication().runReadAction(action);
        showSearchFeedback("Text search complete: " + query);
        return result;
    }

    private String performSearch(String query, String filePattern, boolean isRegex,
                                 boolean caseSensitive, int maxResults, int contextLines,
                                 boolean followAgent) {
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;

        Pattern pattern = compileSearchPattern(query, isRegex, caseSensitive);
        if (pattern == null) return "Error: invalid regex: " + query;

        List<String> results = new ArrayList<>();
        List<MatchPosition> positions = followAgent ? new ArrayList<>() : null;
        AtomicInteger skippedLarge = new AtomicInteger(0);
        AtomicInteger totalOutputBytes = new AtomicInteger(0);
        var compiledFileGlob = filePattern.isEmpty() ? null : ToolUtils.compileGlob(filePattern);
        var params = new SearchParams(pattern, basePath, filePattern, compiledFileGlob, results, positions, skippedLarge, maxResults, contextLines, totalOutputBytes);
        ProjectFileIndex.getInstance(project).iterateContent(vf -> processFile(vf, params));

        if (positions != null && !positions.isEmpty()) {
            showResultsInUsageView(query, positions);
        }

        StringBuilder sb = new StringBuilder();
        if (results.isEmpty()) {
            sb.append("No matches found for '").append(query).append("'");
        } else {
            sb.append(results.size()).append(" matches:\n");
            String separator = contextLines > 0 ? "\n---\n" : "\n";
            sb.append(String.join(separator, results));
        }
        if (skippedLarge.get() > 0) {
            sb.append("\n(").append(skippedLarge.get()).append(" file(s) >1 MB skipped)");
        }
        if (totalOutputBytes.get() >= MAX_OUTPUT_BYTES) {
            sb.append("\n(output truncated at 256 KB — use a more specific query or file_pattern to narrow results)");
        }
        return sb.toString();
    }

    private void showResultsInUsageView(String query, List<MatchPosition> positions) {
        // PsiFile objects are pre-resolved in the read-action pass (processFile → searchFileForPattern).
        // No PsiManager lookup needed on the EDT.
        List<MatchPosition> snapshot = List.copyOf(positions);
        ApplicationManager.getApplication().invokeLater(() -> {
            Usage[] usages = snapshot.stream()
                .filter(pos -> pos.psiFile() != null)
                .map(pos -> (Usage) new UsageInfo2UsageAdapter(
                    new UsageInfo(pos.psiFile(), pos.startOffset(), pos.endOffset())))
                .toArray(Usage[]::new);

            UsageViewPresentation pres = new UsageViewPresentation();
            String tabText = "Search: " + query;
            pres.setTabText(tabText);
            UsageViewManager.getInstance(project).showUsages(UsageTarget.EMPTY_ARRAY, usages, pres);
            AgentTabTracker.getInstance(project).trackTab("Find", tabText);
        });
    }

    private boolean processFile(VirtualFile vf, SearchParams p) {
        if (vf.isDirectory()) return true;
        String relPath = relativize(p.basePath(), vf.getPath());
        if (relPath == null) return true;
        if (!p.filePattern().isEmpty() && ToolUtils.doesNotMatchGlob(relPath, p.filePattern(), p.compiledFileGlob()))
            return true;
        if (vf.getLength() > 1_000_000) {
            p.skippedLarge().incrementAndGet();
            return p.results().size() < p.maxResults();
        }
        // Pre-resolve PsiFile here (inside the existing read action) so showResultsInUsageView
        // does not need to call PsiManager on the EDT.
        com.intellij.psi.PsiFile psiFile = p.positions() != null
            ? PsiManager.getInstance(project).findFile(vf) : null;
        searchFileForPattern(vf, psiFile, p.pattern(), relPath, p.results(), p.positions(), p.maxResults(), p.contextLines(), p.totalOutputBytes());
        return p.results().size() < p.maxResults() && p.totalOutputBytes().get() < MAX_OUTPUT_BYTES;
    }

    private static void searchFileForPattern(VirtualFile vf, @Nullable com.intellij.psi.PsiFile psiFile,
                                             Pattern pattern,
                                             String relPath, List<String> results,
                                             @Nullable List<MatchPosition> positions,
                                             int maxResults, int contextLines,
                                             AtomicInteger totalOutputBytes) {
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return;
        String text = doc.getText();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && results.size() < maxResults && totalOutputBytes.get() < MAX_OUTPUT_BYTES) {
            int matchLine = doc.getLineNumber(matcher.start()) + 1;
            String lineText = ToolUtils.getLineText(doc, matchLine - 1);
            String entry;
            if (contextLines <= 0) {
                entry = String.format(FORMAT_LINE_REF, relPath, matchLine, lineText);
            } else {
                entry = buildMatchWithContext(doc, relPath, matchLine, lineText, contextLines);
            }
            results.add(entry);
            totalOutputBytes.addAndGet(entry.length());
            if (positions != null) {
                positions.add(new MatchPosition(vf, psiFile, matcher.start(), matcher.end()));
            }
        }
    }

    private static String buildMatchWithContext(Document doc, String relPath,
                                                int matchLine, String lineText, int contextLines) {
        int lineCount = doc.getLineCount();
        StringBuilder entry = new StringBuilder();
        int beforeStart = Math.max(1, matchLine - contextLines);
        for (int l = beforeStart; l < matchLine; l++) {
            entry.append(String.format("  %s:%d:   %s%n", relPath, l, ToolUtils.getLineText(doc, l - 1)));
        }
        entry.append(String.format(FORMAT_LINE_REF, relPath, matchLine, lineText));
        int afterEnd = Math.min(lineCount, matchLine + contextLines);
        for (int l = matchLine + 1; l <= afterEnd; l++) {
            entry.append(String.format("%n  %s:%d:   %s", relPath, l, ToolUtils.getLineText(doc, l - 1)));
        }
        return entry.toString();
    }

    private static Pattern compileSearchPattern(String query, boolean isRegex, boolean caseSensitive) {
        try {
            int flags = isRegex ? 0 : Pattern.LITERAL;
            if (!caseSensitive) flags |= Pattern.CASE_INSENSITIVE;
            return Pattern.compile(query, flags);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }
}
