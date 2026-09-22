package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.services.ActiveAgentManager;
import com.github.catatafishen.agentbridge.services.AgentTabTracker;
import com.github.catatafishen.agentbridge.ui.renderers.SearchResultRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class SearchTextTool extends NavigationTool {

    private static final String PARAM_REGEX = "regex";
    private static final String PARAM_CASE_SENSITIVE = "case_sensitive";
    private static final String PARAM_CONTEXT_LINES = "context_lines";

    /**
     * Holds a single match position for visual display in follow-agent mode.
     * {@code psiFile} is resolved inside the read-action search pass so the EDT
     * invokeLater in showResultsInUsageView does not need to call PsiManager at all.
     */
    private record MatchPosition(VirtualFile vf, @Nullable com.intellij.psi.PsiFile psiFile,
                                 int startOffset, int endOffset) {
    }

    /**
     * Client-safe inline response budget for broad searches.
     */
    private static final int MAX_OUTPUT_BYTES = 16 * 1024; // 16 KiB
    private static final int OUTPUT_METADATA_RESERVE_BYTES = 256;
    private static final int MAX_ENTRY_BYTES = MAX_OUTPUT_BYTES - OUTPUT_METADATA_RESERVE_BYTES;
    private static final int MAX_LINE_BYTES = 4 * 1024;
    private static final String TRUNCATION_SUFFIX = "… [truncated]";

    /**
     * Encapsulates the user-provided search configuration (resolves S107: too many params).
     */
    private record SearchConfig(String query, String filePattern, boolean isRegex,
                                boolean caseSensitive, int maxResults, int offset,
                                int contextLines, boolean followAgent) {
    }

    private record SearchParams(Pattern pattern, String basePath, String filePattern,
                                Pattern compiledFileGlob,
                                List<String> results, @Nullable List<MatchPosition> positions,
                                AtomicInteger skippedLarge, int maxResults, int offset,
                                AtomicInteger totalSeen, int contextLines,
                                AtomicInteger totalOutputBytes, AtomicBoolean outputTruncated,
                                String entrySeparator, @Nullable Predicate<VirtualFile> scopeFilter) {
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
            Param.optional(PARAM_SCOPE, TYPE_STRING, SCOPE_DESCRIPTION, SCOPE_PROJECT),
            Param.optional(PARAM_REGEX, TYPE_BOOLEAN, "If true, treat query as regex. Default: false (literal match)"),
            Param.optional(PARAM_CASE_SENSITIVE, TYPE_BOOLEAN, "Case-sensitive search. Default: true"),
            Param.optional(PARAM_MAX_RESULTS, TYPE_INTEGER, "Maximum results to return (default: 100)"),
            Param.optional(PARAM_OFFSET, TYPE_INTEGER, "Number of results to skip for pagination (default: 0)"),
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
        int offset = args.has(PARAM_OFFSET) ? args.get(PARAM_OFFSET).getAsInt() : 0;
        int contextLines = args.has(PARAM_CONTEXT_LINES) ? args.get(PARAM_CONTEXT_LINES).getAsInt() : 0;
        boolean followAgent = ActiveAgentManager.getFollowAgentFiles(project);
        String scopeName = readScopeParam(args);
        Predicate<VirtualFile> scopeFilter = buildScopeFilter(scopeName);

        var cfg = new SearchConfig(query, filePattern, isRegex, caseSensitive, maxResults, offset, contextLines, followAgent);

        showSearchFeedback("Searching text: " + query);
        // NonBlockingReadAction: iterating all project files can hold the read lock for several
        // seconds on large projects. runReadAction() would block ALL write actions (EDT, indexing,
        // daemon analysis) for the entire duration and cause "IDE not responding" freezes.
        // executeSynchronously() yields to write actions when they need to run, then restarts the
        // search (performSearch creates fresh collections, so restart is safe).
        String result = ReadAction.nonBlocking(
            () -> performSearch(cfg, scopeFilter)
        ).executeSynchronously();
        showSearchFeedback("Text search complete: " + query);
        return result;
    }

    /**
     * Compiles the search pattern from user-provided parameters.
     *
     * @return the compiled {@link Pattern}, or {@code null} if {@code query} is
     * an invalid regex (only possible when {@code isRegex=true})
     */
    private static @Nullable Pattern compileSearchPattern(String query, boolean isRegex, boolean caseSensitive) {
        try {
            int flags = isRegex ? 0 : Pattern.LITERAL;
            if (!caseSensitive) flags |= Pattern.CASE_INSENSITIVE;
            return Pattern.compile(query, flags);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    private @Nullable Predicate<VirtualFile> buildScopeFilter(String scopeName) {
        if (scopeName == null) return null;
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        return switch (scopeName.toLowerCase(Locale.ROOT)) {
            case SCOPE_PRODUCTION -> vf -> !fileIndex.isInTestSourceContent(vf);
            case SCOPE_TESTS -> fileIndex::isInTestSourceContent;
            default -> null;
        };
    }

    private String performSearch(SearchConfig cfg, @Nullable Predicate<VirtualFile> scopeFilter) {
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;

        Pattern pattern = compileSearchPattern(cfg.query(), cfg.isRegex(), cfg.caseSensitive());
        if (pattern == null) {
            String msg = "Error: invalid regex: " + cfg.query();
            if (cfg.isRegex()) {
                msg += "\nTip: use | for OR (not \\|), and escape special characters"
                    + " like ( and { with \\ (e.g. \\( and \\{)";
            }
            return msg;
        }

        List<String> results = new ArrayList<>();
        List<MatchPosition> positions = cfg.followAgent() ? new ArrayList<>() : null;
        AtomicInteger skippedLarge = new AtomicInteger(0);
        AtomicInteger totalOutputBytes = new AtomicInteger(0);
        AtomicBoolean outputTruncated = new AtomicBoolean(false);
        String entrySeparator = cfg.contextLines() > 0 ? "\n---\n" : "\n";
        var compiledFileGlob = cfg.filePattern().isEmpty() ? null : ToolUtils.compileGlob(cfg.filePattern());
        var params = new SearchParams(pattern, basePath, cfg.filePattern(), compiledFileGlob, results, positions,
            skippedLarge, cfg.maxResults(), cfg.offset(), new AtomicInteger(0), cfg.contextLines(), totalOutputBytes,
            outputTruncated, entrySeparator, scopeFilter);
        ProjectFileIndex.getInstance(project).iterateContent(vf -> processFile(vf, params));

        if (positions != null && !positions.isEmpty()) {
            showResultsInUsageView(cfg.query(), positions);
        }

        StringBuilder sb = new StringBuilder();
        if (results.isEmpty()) {
            sb.append("No matches found for '").append(truncateUtf8(cfg.query(), 128)).append("'");
        } else {
            sb.append(results.size()).append(" matches:\n");
            sb.append(String.join(entrySeparator, results));
        }
        if (skippedLarge.get() > 0) {
            sb.append("\n(").append(skippedLarge.get()).append(" file(s) >1 MB skipped)");
        }
        if (outputTruncated.get()) {
            sb.append("\n(output truncated at 16 KiB)");
        }
        if (outputTruncated.get() || results.size() >= cfg.maxResults()) {
            int nextOffset = cfg.offset() + results.size();
            sb.append("\n\n(Showing ").append(results.size()).append(" results starting at offset ").append(cfg.offset())
                .append(". Use offset=").append(nextOffset).append(" to see more)");
        }
        return sb.toString();
    }

    private void showResultsInUsageView(String query, List<MatchPosition> positions) {
        // PsiFile objects are pre-resolved in the read-action pass (processFile → searchFileForPattern).
        // No PsiManager lookup needed on the EDT.
        List<MatchPosition> snapshot = List.copyOf(positions);
        ApplicationManager.getApplication().invokeLater(() -> {
            // This invokeLater fires after the tool call returns and FocusGuard is uninstalled.
            // Only suppress opening the Find window when the user is actively typing — that is,
            // chat is focused, the input is non-empty, and it changed within the last 10 seconds.
            // When the input is idle or empty, showing the Find window is useful follow-along.
            if (com.github.catatafishen.agentbridge.psi.PsiBridgeService.isUserTypingInChat(project)) return;
            Usage[] usages = snapshot.stream()
                .filter(pos -> pos.psiFile() != null)
                .map(pos -> (Usage) new UsageInfo2UsageAdapter(
                    new UsageInfo(pos.psiFile(), pos.startOffset(), pos.endOffset())))
                .toArray(Usage[]::new);

            UsageViewPresentation pres = new UsageViewPresentation();
            String tabText = "Search: " + query;
            pres.setTabText(tabText);
            com.intellij.usages.UsageView view =
                UsageViewManager.getInstance(project).showUsages(UsageTarget.EMPTY_ARRAY, usages, pres);
            AgentTabTracker.getInstance(project).trackTab("Find", tabText);
            // Auto-expand all groups so the agent's results are immediately visible —
            // by default the Find tool window collapses every file group, requiring an
            // extra click before any usages are shown. Schedule expansion on a later EDT
            // pump because usage nodes are appended asynchronously after showUsages returns.
            ApplicationManager.getApplication().invokeLater(() -> expandUsageViewIfSupported(view));
        });
    }

    /**
     * Expands all groups in a {@link com.intellij.usages.UsageView} by reflection, so we
     * don't take a compile-time dependency on {@code com.intellij.usages.impl.UsageViewImpl}
     * — that's an internal implementation class whose presence/signature can shift across
     * IDE versions and break Marketplace verification.
     */
    private static void expandUsageViewIfSupported(@Nullable com.intellij.usages.UsageView view) {
        if (view == null) return;
        try {
            java.lang.reflect.Method expandAllMethod = view.getClass().getMethod("expandAll");
            expandAllMethod.invoke(view);
        } catch (ReflectiveOperationException ignored) {
            // Older/newer IDEs may use a different impl or omit expandAll() — auto-expand
            // is a UX nicety, not a correctness requirement, so silently skip.
        }
    }

    private boolean processFile(VirtualFile vf, SearchParams p) {
        if (vf.isDirectory()) return true;
        if (p.scopeFilter() != null && !p.scopeFilter().test(vf)) return true;
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
        searchFileForPattern(vf, psiFile, relPath, p);
        return p.results().size() < p.maxResults() && !p.outputTruncated().get();
    }

    private static void searchFileForPattern(VirtualFile vf, @Nullable com.intellij.psi.PsiFile psiFile,
                                             String relPath, SearchParams p) {
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return;

        Matcher matcher = p.pattern().matcher(doc.getText());
        while (matcher.find() && p.results().size() < p.maxResults() && !p.outputTruncated().get()) {
            processMatch(vf, psiFile, relPath, doc, matcher, p);
        }
    }

    private static void processMatch(VirtualFile vf, @Nullable com.intellij.psi.PsiFile psiFile, String relPath,
                                     Document doc, Matcher matcher, SearchParams p) {
        if (p.totalSeen().getAndIncrement() < p.offset()) return;

        int matchLine = doc.getLineNumber(matcher.start()) + 1;
        int lineStartOffset = doc.getLineStartOffset(matchLine - 1);
        String lineText = ToolUtils.getLineText(doc, matchLine - 1);
        int matchStartInLine = matcher.start() - lineStartOffset;
        int matchEndInLine = Math.min(lineText.length(), matcher.end() - lineStartOffset);
        String entry = p.contextLines() <= 0
            ? formatMatchLineReference(relPath, matchLine, lineText, matchStartInLine, matchEndInLine)
            : buildMatchWithContext(doc, relPath, matchLine, lineText, matchStartInLine, matchEndInLine,
            p.contextLines());

        int separatorBytes = p.results().isEmpty() ? 0 : utf8Length(p.entrySeparator());
        int entryBytes = utf8Length(entry);
        if (p.totalOutputBytes().get() + separatorBytes + entryBytes > MAX_ENTRY_BYTES) {
            p.outputTruncated().set(true);
            return;
        }

        p.results().add(entry);
        p.totalOutputBytes().addAndGet(separatorBytes + entryBytes);
        if (p.positions() != null) {
            p.positions().add(new MatchPosition(vf, psiFile, matcher.start(), matcher.end()));
        }
    }

    private static String buildMatchWithContext(Document doc, String relPath, int matchLine, String lineText,
                                                int matchStartInLine, int matchEndInLine, int contextLines) {
        List<String> before = new ArrayList<>();
        int beforeStart = Math.max(1, matchLine - contextLines);
        for (int l = beforeStart; l < matchLine; l++) {
            before.add(formatContextLine(relPath, l, ToolUtils.getLineText(doc, l - 1)));
        }

        List<String> after = new ArrayList<>();
        int afterEnd = Math.min(doc.getLineCount(), matchLine + contextLines);
        for (int l = matchLine + 1; l <= afterEnd; l++) {
            after.add(formatContextLine(relPath, l, ToolUtils.getLineText(doc, l - 1)));
        }

        String primary = formatMatchLineReference(relPath, matchLine, lineText, matchStartInLine, matchEndInLine);
        return combinePrimaryWithContext(primary, before, after, MAX_ENTRY_BYTES);
    }

    /**
     * Keeps the match in a long line visible by trimming the line around, rather than before, the match.
     */
    private static String formatMatchLineReference(String relPath, int line, String lineText,
                                                   int matchStartInLine, int matchEndInLine) {
        String prefix = String.format(FORMAT_LINE_REF, relPath, line, "");
        int lineBudget = MAX_LINE_BYTES - utf8Length(prefix);
        if (lineBudget <= 0) return truncateUtf8(prefix, MAX_LINE_BYTES);
        return prefix + truncateUtf8AroundMatch(lineText, matchStartInLine, matchEndInLine, lineBudget);
    }

    /**
     * Adds context only from the byte budget left after the primary matching line. Nearest preceding context
     * is retained first, while the primary line is always retained in full.
     */
    private static String combinePrimaryWithContext(String primary, List<String> before, List<String> after,
                                                    int maxBytes) {
        int remaining = maxBytes - utf8Length(primary);
        if (remaining <= 0) return primary;

        StringBuilder entry = new StringBuilder(primary);
        for (int i = before.size() - 1; i >= 0 && remaining > 1; i--) {
            String context = before.get(i);
            int contextBudget = remaining - 1;
            String included = utf8Length(context) <= contextBudget ? context : truncateUtf8(context, contextBudget);
            entry.insert(0, included + "\n");
            remaining -= utf8Length(included) + 1;
        }

        for (String context : after) {
            if (remaining <= 1) break;
            int contextBudget = remaining - 1;
            String included = utf8Length(context) <= contextBudget ? context : truncateUtf8(context, contextBudget);
            entry.append('\n').append(included);
            remaining -= utf8Length(included) + 1;
        }
        return entry.toString();
    }

    private static String truncateUtf8AroundMatch(String text, int matchStart, int matchEnd, int maxBytes) {
        if (utf8Length(text) <= maxBytes) return text;
        matchStart = Math.clamp(matchStart, 0, text.length());
        matchEnd = Math.clamp(matchEnd, matchStart, text.length());
        if (matchStart == matchEnd) return truncateUtf8(text, maxBytes);

        String match = text.substring(matchStart, matchEnd);
        int matchBytes = utf8Length(match);
        if (matchBytes >= maxBytes) return match;

        String before = text.substring(0, matchStart);
        String after = text.substring(matchEnd);
        int markerBytes = utf8Length(TRUNCATION_SUFFIX);
        boolean markBefore = !before.isEmpty() && matchBytes + markerBytes < maxBytes;
        boolean markAfter = !after.isEmpty() && matchBytes + markerBytes * (markBefore ? 2 : 1) < maxBytes;
        int contextBudget = maxBytes - matchBytes - (markBefore ? markerBytes : 0) - (markAfter ? markerBytes : 0);
        String beforeContext = truncateUtf8FromEnd(before, contextBudget / 2);
        String afterContext = truncateUtf8(after, contextBudget - utf8Length(beforeContext));

        StringBuilder result = new StringBuilder();
        if (markBefore) result.append(TRUNCATION_SUFFIX);
        result.append(beforeContext).append(match).append(afterContext);
        if (markAfter) result.append(TRUNCATION_SUFFIX);
        return result.toString();
    }

    private static String truncateUtf8FromEnd(String text, int maxBytes) {
        if (utf8Length(text) <= maxBytes) return text;
        StringBuilder result = new StringBuilder();
        int usedBytes = 0;
        for (int offset = text.length(); offset > 0; offset -= Character.charCount(text.codePointBefore(offset))) {
            int codePoint = text.codePointBefore(offset);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = utf8Length(character);
            if (usedBytes + characterBytes > maxBytes) break;
            result.insert(0, character);
            usedBytes += characterBytes;
        }
        return result.toString();
    }

    private static String formatContextLine(String relPath, int line, String lineText) {
        return truncateUtf8(String.format("  %s:%d:   %s", relPath, line, lineText), MAX_LINE_BYTES);
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String truncateUtf8(String text, int maxBytes) {
        if (utf8Length(text) <= maxBytes) return text;
        int suffixBytes = utf8Length(TRUNCATION_SUFFIX);
        boolean includeSuffix = maxBytes >= suffixBytes;
        int contentBudget = includeSuffix ? maxBytes - suffixBytes : Math.max(0, maxBytes);
        StringBuilder result = new StringBuilder();
        int usedBytes = 0;
        for (int offset = 0; offset < text.length(); offset = text.offsetByCodePoints(offset, 1)) {
            int codePoint = text.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = utf8Length(character);
            if (usedBytes + characterBytes > contentBudget) break;
            result.append(character);
            usedBytes += characterBytes;
        }
        return includeSuffix ? result.append(TRUNCATION_SUFFIX).toString() : result.toString();
    }

}
