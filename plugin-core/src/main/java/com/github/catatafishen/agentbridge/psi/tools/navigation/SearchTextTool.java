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
import com.intellij.psi.PsiFile;
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
import java.util.Objects;
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
     */
    private record MatchPosition(VirtualFile vf, int startOffset, int endOffset) {
    }

    private record SearchParams(Pattern pattern, String basePath, String filePattern,
                                List<String> results, @Nullable List<MatchPosition> positions,
                                AtomicInteger skippedLarge, int maxResults, int contextLines) {
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
        return "Search for text or regex patterns across project files using IntelliJ's editor buffers";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"query", TYPE_STRING, "Text or regex pattern to search for"},
            {"file_pattern", TYPE_STRING, "Optional glob pattern to filter files (e.g., '*.kt', '*.java')", ""},
            {PARAM_REGEX, TYPE_BOOLEAN, "If true, treat query as regex. Default: false (literal match)"},
            {PARAM_CASE_SENSITIVE, TYPE_BOOLEAN, "Case-sensitive search. Default: true"},
            {PARAM_MAX_RESULTS, TYPE_INTEGER, "Maximum results to return (default: 100)"},
            {PARAM_CONTEXT_LINES, TYPE_INTEGER, "Lines of context before and after each match (default: 0). Reduces need for follow-up read_file calls."}
        }, "query");
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
        var params = new SearchParams(pattern, basePath, filePattern, results, positions, skippedLarge, maxResults, contextLines);
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
        return sb.toString();
    }

    /**
     * Displays the pre-collected match positions in IntelliJ's Usage View — no second search.
     * Must be called from a read action; schedules EDT work via invokeLater.
     */
    private void showResultsInUsageView(String query, List<MatchPosition> positions) {
        // Capture the positions as a snapshot; PsiManager lookups happen on EDT (implicit read lock).
        List<MatchPosition> snapshot = List.copyOf(positions);
        ApplicationManager.getApplication().invokeLater(() -> {
            Usage[] usages = snapshot.stream()
                .map(pos -> {
                    PsiFile psiFile = PsiManager.getInstance(project).findFile(pos.vf());
                    if (psiFile == null) return null;
                    return (Usage) new UsageInfo2UsageAdapter(
                        new UsageInfo(psiFile, pos.startOffset(), pos.endOffset())
                    );
                })
                .filter(Objects::nonNull)
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
        if (!p.filePattern().isEmpty() && ToolUtils.doesNotMatchGlob(relPath, p.filePattern())) return true;
        if (vf.getLength() > 1_000_000) {
            p.skippedLarge().incrementAndGet();
            return p.results().size() < p.maxResults();
        }
        searchFileForPattern(vf, p.pattern(), relPath, p.results(), p.positions(), p.maxResults(), p.contextLines());
        return p.results().size() < p.maxResults();
    }

    private static void searchFileForPattern(VirtualFile vf, Pattern pattern,
                                             String relPath, List<String> results,
                                             @Nullable List<MatchPosition> positions,
                                             int maxResults, int contextLines) {
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return;
        String text = doc.getText();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && results.size() < maxResults) {
            int matchLine = doc.getLineNumber(matcher.start()) + 1;
            String lineText = ToolUtils.getLineText(doc, matchLine - 1);
            if (contextLines <= 0) {
                results.add(String.format(FORMAT_LINE_REF, relPath, matchLine, lineText));
            } else {
                results.add(buildMatchWithContext(doc, relPath, matchLine, lineText, contextLines));
            }
            if (positions != null) {
                positions.add(new MatchPosition(vf, matcher.start(), matcher.end()));
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
