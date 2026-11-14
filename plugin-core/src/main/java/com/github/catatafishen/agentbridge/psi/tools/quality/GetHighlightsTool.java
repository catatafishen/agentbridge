package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.settings.DiagnosticFilterSettings;
import com.google.gson.JsonObject;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Gets cached editor highlights for open files.
 */
public final class GetHighlightsTool extends QualityTool {

    private static final Logger LOG = Logger.getInstance(GetHighlightsTool.class);
    /**
     * Editor banners that are produced by AgentBridge itself (e.g., the agent-edit review
     * banner) are noise for the agent — the agent generated the edit, and the human-facing
     * banner exists purely so the user can review later. Filter them out by a prefix.
     * Git-side gates (AgentEditSession.isGateActive) still enforce commit/push blocking
     * when there are pending changes, so the agent doesn't need the banner to behave correctly.
     */
    static final String[] AGENT_EDIT_BANNER_PREFIXES = {
        "[BANNER] Review pending:",
        "[BANNER] Edited by agent:",
    };

    private static final String PARAM_INCLUDE_UNINDEXED = "include_unindexed";
    private static final String PARAM_START_LINE = "start_line";
    private static final String PARAM_END_LINE = "end_line";
    private static final String PARAM_INCLUDE_FIXES = "include_fixes";

    /**
     * Upper bound on the quick-fix names listed per problem. Inspections routinely offer six to
     * eight fixes (rule configuration, suppression, "report to YouTrack", …) of which only the
     * first few are real code changes, so listing them all multiplied the output size without
     * adding actionable information.
     */
    private static final int MAX_FIXES_PER_PROBLEM = 3;

    /**
     * A single {@code get_highlights} request. Grouped into a record because the parameters are
     * threaded unchanged through four call layers down to {@link #collectFileHighlights}.
     *
     * @param limit            maximum number of problems to return
     * @param includeUnindexed whether to analyze files outside the project index
     * @param startLine        1-based inclusive lower line bound, or 0 for none
     * @param endLine          1-based inclusive upper line bound, or 0 for none
     * @param includeFixes     whether to list quick-fix action names under each problem
     * @param analyzed         highlights computed on demand for a file that is not open in an editor, or
     *                         {@code null} to read the editor daemon's cache instead
     */
    private record HighlightQuery(int limit, boolean includeUnindexed, int startLine, int endLine,
                                  boolean includeFixes,
                                  Map<Document, List<HighlightInfo>> analyzed) {
    }

    public GetHighlightsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_highlights";
    }

    @Override
    public boolean requiresIndex() {
        return true;
    }

    @Override
    public @NotNull String displayName() {
        return "Get Highlights";
    }

    @Override
    public @NotNull String description() {
        return "Get editor highlights for a focused section of a file. " +
            "For a file that is open in an editor this reads the editor's cached analysis; for a closed file " +
            "(requested with 'path') it analyzes the file on demand, without opening an editor and without waiting. " +
            "Without 'path' only files that are already analyzed (open in an editor) are reported. " +
            "Returns the richest diagnostic output available: all severities, inspections, typos " +
            "and grammar errors, one problem per line. " +
            "Set include_fixes=true to also list the available quick-fix action names per problem " +
            "(needed before calling apply_quickfix; off by default because it multiplies output size). " +
            "Fixes that the IDE computes lazily, such as 'Import class' for an unresolved symbol, are only " +
            "listed for a file open in an editor; for a closed file the entry says so instead of listing none. " +
            "Designed for targeted inspection using start_line/end_line (e.g. a single method or class). " +
            "Large files without a line range produce many results — if output is truncated, " +
            "narrow with start_line/end_line. " +
            "For a whole-file problem summary, use get_problems. " +
            "For compile errors only, use get_compilation_errors.";
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
        return schema(
            Param.optional("path", TYPE_STRING, "Optional: file path to check. If omitted, checks all open files", ""),
            Param.optional(PARAM_MAX_RESULTS, TYPE_INTEGER, "Maximum number of highlights to return (default: 100)"),
            Param.optional(PARAM_INCLUDE_UNINDEXED, TYPE_BOOLEAN, "If true, also include highlights from files not indexed by the project (default: false)"),
            Param.optional(PARAM_START_LINE, TYPE_INTEGER, "Only return highlights on or after this line (1-based, inclusive). If end_line is omitted, includes all lines from start_line onwards."),
            Param.optional(PARAM_END_LINE, TYPE_INTEGER, "Only return highlights on or before this line (1-based, inclusive). Used with start_line. Defaults to no upper bound when start_line is set."),
            Param.optional(PARAM_INCLUDE_FIXES, TYPE_BOOLEAN, "If true, list up to " + MAX_FIXES_PER_PROBLEM
                + " quick-fix action names under each problem, for use with apply_quickfix (default: false). "
                + "Grammar and spelling problems never list fixes because apply_quickfix cannot apply them.")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String pathStr = args.has("path") ? args.get("path").getAsString() : null;
        int limit = args.has(PARAM_MAX_RESULTS) ? args.get(PARAM_MAX_RESULTS).getAsInt() : 100;
        boolean includeUnindexed = args.has(PARAM_INCLUDE_UNINDEXED) && args.get(PARAM_INCLUDE_UNINDEXED).getAsBoolean();
        boolean includeFixes = args.has(PARAM_INCLUDE_FIXES) && args.get(PARAM_INCLUDE_FIXES).getAsBoolean();
        int startLine = args.has(PARAM_START_LINE) ? args.get(PARAM_START_LINE).getAsInt() : 0;
        int endLine = args.has(PARAM_END_LINE) ? args.get(PARAM_END_LINE).getAsInt() : 0;

        // Normalize: end_line without start_line → treat start_line as 1
        if (endLine > 0 && startLine == 0) {
            startLine = 1;
        }
        String rangeError = validateLineRange(startLine, endLine);
        if (rangeError != null) return rangeError;

        if (!project.isInitialized()) {
            return ERROR_IDE_INITIALIZING;
        }

        Map<Document, List<HighlightInfo>> analyzed = analyzeRequestedFile(pathStr, includeUnindexed);
        HighlightQuery query = new HighlightQuery(limit, includeUnindexed, startLine, endLine, includeFixes, analyzed);

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                getHighlightsCached(pathStr, query, resultFuture);
            } catch (Exception e) {
                LOG.error("Error getting highlights", e);
                resultFuture.complete("Error getting highlights: " + e.getMessage());
            }
        });
        return resultFuture.get(30, TimeUnit.SECONDS);
    }

    private String validateLineRange(int startLine, int endLine) {
        if (startLine < 0 || endLine < 0) {
            return "Error: start_line and end_line are 1-based line numbers when provided; "
                + "omit the parameter (or pass 0) to leave that bound unset.";
        }
        if (endLine > 0 && startLine > endLine) {
            return "Error: Invalid range — start_line (" + startLine + ") > end_line (" + endLine + "). Lines are 1-based.";
        }
        return null;
    }

    /**
     * Analyzes the requested file on demand when it is not open in an editor.
     *
     * <p>The editor daemon only analyzes files with an open editor, so its cache is empty for a closed file. The
     * old implementation opened the file and waited up to 15 s for a daemon event. {@code MainPassesRunner}
     * computes the same highlights, with their quick-fixes, without an editor and without waiting.</p>
     *
     * @return the highlights per document, or {@code null} when the file is open (its cache is read instead),
     * when no single file was requested, or when the file is not part of the analysis
     */
    private Map<Document, List<HighlightInfo>> analyzeRequestedFile(String pathStr, boolean includeUnindexed) {
        if (pathStr == null || pathStr.isEmpty()) return null;
        VirtualFile vf = resolveVirtualFileWithFallback(pathStr);
        if (vf == null) return null;

        boolean analyzable = PlatformApiCompat.readActionCompute(() ->
            includeUnindexed || ProjectRootManager.getInstance(project).getFileIndex().isInSourceContent(vf));
        if (!analyzable) return null;

        return analyzeIfNotOpen(vf, "Collecting highlights");
    }

    private void getHighlightsCached(String pathStr, HighlightQuery query,
                                     CompletableFuture<String> resultFuture) {
        StringBuilder result = new StringBuilder();
        ApplicationManager.getApplication().runReadAction(() -> {
            ProjectFileIndex fileIndex = ProjectRootManager.getInstance(project).getFileIndex();
            Collection<VirtualFile> allFiles =
                collectFilesForHighlightAnalysis(pathStr, query.includeUnindexed(), fileIndex, resultFuture);
            if (resultFuture.isDone()) return;

            LOG.info("Analyzing " + allFiles.size() + " files for highlights (cached mode)");

            List<String> problems = new ArrayList<>();
            int[] counts = analyzeFilesForHighlights(allFiles, query, problems);
            result.append(buildHighlightsSummary(counts, problems, query.limit(), allFiles.size()));
            // Only an on-demand analysis can leave fixes uncomputed; the editor computes all of them.
            if (query.analyzed() != null && counts[2] > 0) {
                result.append("\n\nNote: ").append(EDITOR_ONLY_FIXES_NOTE);
            }
        });
        if (resultFuture.isDone()) return;

        appendEditorNotificationsIfPresent(result, pathStr);

        ToolLayerSettings settings = ToolLayerSettings.getInstance(project);
        boolean computedOnDemand = query.analyzed() != null;
        if (!computedOnDemand && !settings.getFollowAgentFiles() && !settings.getAllowTransientFileOpens()) {
            result.append("""


                Note: Results above are from cached analysis only.
                Files not already open in the editor were not analyzed, because the editor daemon only
                analyzes open files. Request a single file with 'path' to analyze it without opening it.""");
        }

        resultFuture.complete(result.toString());
    }

    private String buildHighlightsSummary(int[] counts, List<String> problems, int limit, int fileCount) {
        if (problems.isEmpty()) {
            return String.format(
                "No highlights found in %d files analyzed (0 files with issues). " +
                    "Note: This reads cached daemon analysis results from already-analyzed files. " +
                    "For comprehensive code quality analysis, use run_inspections instead.",
                fileCount);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Found %d problems across %d files (showing up to %d):%n%n",
            counts[0], counts[1], limit));
        sb.append(String.join("\n", problems));
        if (counts[0] >= limit) {
            sb.append(String.format(
                "%n%n[Results capped at %d. Use start_line and end_line to focus on a specific " +
                    "section (e.g. a single method or class) to get complete coverage for that range.]", limit));
        }
        return sb.toString();
    }

    private void appendEditorNotificationsIfPresent(StringBuilder result, String pathStr) {
        if (pathStr == null || pathStr.isEmpty()) return;
        try {
            List<String> notifications = collectEditorNotifications(pathStr);
            if (!notifications.isEmpty()) {
                result.append("\n\n--- Editor Notifications ---\n");
                result.append(String.join("\n", notifications));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.info("Interrupted while collecting editor notifications: " + e.getMessage());
        } catch (ExecutionException | TimeoutException e) {
            LOG.info("Failed to collect editor notifications: " + e.getMessage());
        }
    }

    /**
     * @return {@code [problem count, files with problems, problems listed without fixes]}
     */
    private int[] analyzeFilesForHighlights(Collection<VirtualFile> files, HighlightQuery query,
                                            List<String> problems) {
        String basePath = project.getBasePath();
        int totalCount = 0;
        int filesWithProblems = 0;
        int[] problemsWithoutFixes = {0};
        for (VirtualFile vf : files) {
            if (totalCount >= query.limit()) break;
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc != null) {
                String relPath = basePath != null ? relativize(basePath, vf.getPath()) : vf.getName();
                int added = collectFileHighlights(doc, relPath, query.limit() - totalCount, query, problems,
                    problemsWithoutFixes);
                if (added > 0) filesWithProblems++;
                totalCount += added;
            }
        }
        return new int[]{totalCount, filesWithProblems, problemsWithoutFixes[0]};
    }

    private int collectFileHighlights(Document doc, String relPath, int remaining,
                                      HighlightQuery query, List<String> problems, int[] problemsWithoutFixes) {
        List<HighlightInfo> highlights = new ArrayList<>();
        int added = 0;
        DiagnosticFilterSettings filter = DiagnosticFilterSettings.getInstance(project);
        try {
            List<HighlightInfo> onDemand = query.analyzed() == null ? null : query.analyzed().get(doc);
            if (onDemand != null) {
                highlights.addAll(onDemand);
                // MainPassesRunner returns the highlights in pass order (errors from a later pass come last), the
                // editor cache in document order. Sort the same way so that max_results keeps the first problems
                // of the file instead of whichever pass finished first.
                highlights.sort(Comparator.comparingInt(HighlightInfo::getStartOffset)
                    .thenComparingInt(HighlightInfo::getEndOffset));
            } else {
                DaemonCodeAnalyzerEx.processHighlights(
                    doc, project, null, 0, doc.getTextLength(), highlights::add);
            }

            for (var h : highlights) {
                if (added >= remaining) break;
                String description = h.getDescription();
                if (description != null && !description.isBlank() && filter.shouldInclude(h)) {
                    int line = doc.getLineNumber(h.getStartOffset()) + 1;
                    if (isInLineRange(line, query.startLine(), query.endLine())) {
                        StringBuilder entry = new StringBuilder(
                            String.format(FORMAT_LOCATION, relPath, line, h.getSeverity().getName(), description));
                        if (query.includeFixes() && appendFixNames(entry, h)) {
                            problemsWithoutFixes[0]++;
                        }
                        problems.add(entry.toString());
                        added++;
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to analyze file: " + relPath, e);
        }
        return added;
    }

    /**
     * Appends up to {@link #MAX_FIXES_PER_PROBLEM} quick-fix names, one per line with a plain
     * {@code Fix:} prefix so action names are unambiguous for {@code apply_quickfix}.
     *
     * <p>Grammar and spelling problems are skipped entirely: their fixes come from Grazie and the
     * spell checker, which {@code apply_quickfix} cannot invoke, so listing them is pure noise.</p>
     *
     * @return {@code true} when the problem is eligible for fixes but none are listed
     */
    private static boolean appendFixNames(StringBuilder entry, HighlightInfo h) {
        if (DiagnosticFilterSettings.isNaturalLanguageSeverity(h.getSeverity())) return false;
        List<String> fixes = collectQuickFixNames(h);
        entry.append(formatFixLines(fixes));
        return fixes.isEmpty();
    }

    /**
     * Renders quick-fix names as indented {@code Fix:} lines, capped at
     * {@link #MAX_FIXES_PER_PROBLEM} with a trailing count of what was omitted.
     *
     * @return the lines to append, each prefixed with a newline; empty when there are no fixes
     */
    static String formatFixLines(@NotNull List<String> fixes) {
        if (fixes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(fixes.size(), MAX_FIXES_PER_PROBLEM);
        for (int i = 0; i < shown; i++) {
            sb.append("\n    Fix: ").append(fixes.get(i));
        }
        if (fixes.size() > shown) {
            sb.append("\n    Fix: … (").append(fixes.size() - shown)
                .append(" more, use get_available_actions)");
        }
        return sb.toString();
    }

    private static boolean isInLineRange(int line, int startLine, int endLine) {
        return startLine == 0 || (line >= startLine && (endLine == 0 || line <= endLine));
    }

    /**
     * Collects editor notification banners for a file.
     * Must be called outside a read action since it dispatches to EDT for Swing component creation.
     */
    private List<String> collectEditorNotifications(String pathStr) throws ExecutionException, InterruptedException, TimeoutException {
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    future.complete(Collections.emptyList());
                    return;
                }

                var fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project);
                var editors = fem.getEditors(vf);
                if (editors.length == 0) {
                    future.complete(Collections.emptyList());
                    return;
                }

                var editor = editors[0];
                List<String> notifications = PlatformApiCompat.collectEditorNotificationTexts(project, vf, editor)
                    .stream()
                    .filter(GetHighlightsTool::isVisibleToAgent)
                    .toList();

                future.complete(notifications);
            } catch (Exception e) {
                future.complete(Collections.emptyList());
            }
        });
        return future.get(10, TimeUnit.SECONDS);
    }

    /**
     * Filters editor notifications to those the agent should actually see.
     * AgentBridge's own agent-edit review banner is suppressed — see AGENT_EDIT_BANNER_PREFIXES.
     */
    static boolean isVisibleToAgent(@NotNull String notification) {
        for (String prefix : AGENT_EDIT_BANNER_PREFIXES) {
            if (notification.startsWith(prefix)) return false;
        }
        return true;
    }
}
