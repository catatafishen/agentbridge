package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.settings.DiagnosticFilterSettings;
import com.google.gson.JsonObject;
import com.intellij.codeInsight.CodeSmellInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.MainPassesRunner;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.CodeSmellDetector;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Collects errors and warnings for all files in the current VCS changes in one call,
 * mirroring the "Analyze code" check IntelliJ runs before a commit.
 *
 * <p>The response starts with a per-file summary and lists problems errors-first with pagination.
 * With {@code include_fixes} the files on the requested page are analyzed once more in the background and the
 * available quick-fix names are attached, so the result can be fed straight into {@code apply_action}.
 * No editor is opened for this: the fixes come from {@link MainPassesRunner}, the same mechanism that
 * {@link CodeSmellDetector} uses internally.</p>
 */
public final class GetCommitProblemsTool extends QualityTool {

    private static final Logger LOG = Logger.getInstance(GetCommitProblemsTool.class);
    private static final int TIMEOUT_SECONDS = 150;
    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final String PARAM_ERRORS_ONLY = "errors_only";
    private static final String PARAM_SUMMARY_ONLY = "summary_only";
    private static final String PARAM_INCLUDE_FIXES = "include_fixes";
    private static final String UNKNOWN_PATH = "<unknown>";

    private final Supplier<List<VirtualFile>> changedFilesSupplier;

    public GetCommitProblemsTool(Project project) {
        this(project, null);
    }

    /**
     * Test seam: lets a test supply the "changed files" instead of reading them from the VCS, since a
     * light fixture has no repository. A {@code null} supplier reads the files from {@link ChangeListManager}.
     * The supplier is invoked inside a read action.
     */
    GetCommitProblemsTool(Project project, Supplier<List<VirtualFile>> changedFilesSupplier) {
        super(project);
        this.changedFilesSupplier = changedFilesSupplier != null ? changedFilesSupplier : this::collectChangedFiles;
    }

    @Override
    public @NotNull String id() {
        return "get_commit_problems";
    }

    @Override
    public boolean requiresIndex() {
        return true;
    }

    @Override
    public @NotNull String displayName() {
        return "Get Commit Problems";
    }

    @Override
    public @NotNull String description() {
        return "Get errors and warnings for all files with uncommitted changes (the same check IntelliJ runs "
            + "before a commit) in a single call. Files do not need to be open in an editor. "
            + "Use this before git_commit instead of calling get_problems per file. "
            + "The response starts with a summary (totals and a per-file count, files with most errors first), "
            + "followed by one line per problem as 'path:line [SEVERITY] message', errors first. "
            + "Paginate with max_results (default " + DEFAULT_MAX_RESULTS + ") and offset; "
            + "use summary_only=true for just the summary and errors_only=true to hide warnings. "
            + "Set include_fixes=true to list quick-fix action names under each problem on the returned page, "
            + "ready for apply_action. This re-analyzes only the files on the returned page, in the background "
            + "without opening editors, so keep max_results small for large change sets. "
            + "Fixes that the IDE computes lazily, such as 'Import class' for an unresolved symbol, cannot be "
            + "listed this way; such a problem is marked as 'not computed' instead of showing no fix. "
            + "Deleted files are skipped; weak warnings and information hints are not included. "
            + "Respects the severity toggles in MCP Diagnostic Filter settings. "
            + "Timeout " + TIMEOUT_SECONDS + "s.";
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
            Param.optional(PARAM_MAX_RESULTS, TYPE_INTEGER,
                "Maximum number of problems to list (default: " + DEFAULT_MAX_RESULTS + ")"),
            Param.optional(PARAM_OFFSET, TYPE_INTEGER,
                "Number of problems to skip, for pagination (default: 0)"),
            Param.optional(PARAM_ERRORS_ONLY, TYPE_BOOLEAN,
                "If true, only report errors and hide warnings (default: false)"),
            Param.optional(PARAM_SUMMARY_ONLY, TYPE_BOOLEAN,
                "If true, return only the summary without individual problems (default: false)"),
            Param.optional(PARAM_INCLUDE_FIXES, TYPE_BOOLEAN,
                "If true, list quick-fix action names under each listed problem, for use with apply_action. "
                    + "Re-analyzes the files on the returned page, so it is slower (default: false)")
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        int limit = args.has(PARAM_MAX_RESULTS) ? args.get(PARAM_MAX_RESULTS).getAsInt() : DEFAULT_MAX_RESULTS;
        int offset = args.has(PARAM_OFFSET) ? args.get(PARAM_OFFSET).getAsInt() : 0;
        if (limit < 1 || offset < 0) {
            return "Error: max_results must be >= 1 and offset must be >= 0.";
        }
        var page = new CommitProblemsFormatter.Page(
            flag(args, PARAM_ERRORS_ONLY), flag(args, PARAM_SUMMARY_ONLY), offset, limit);
        boolean includeFixes = flag(args, PARAM_INCLUDE_FIXES);

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                resultFuture.complete(analyze(page, includeFixes));
            } catch (ProcessCanceledException pce) {
                resultFuture.completeExceptionally(pce);
            } catch (Exception e) {
                LOG.warn("get_commit_problems failed", e);
                resultFuture.complete("Error: Failed to analyze changed files: " + e.getMessage());
            }
        });
        return resultFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static boolean flag(JsonObject args, String name) {
        return args.has(name) && args.get(name).getAsBoolean();
    }

    private @NotNull String analyze(CommitProblemsFormatter.Page page, boolean includeFixes) {
        List<VirtualFile> files = PlatformApiCompat.readActionCompute(changedFilesSupplier);
        if (files.isEmpty()) {
            return "No changed files to analyze. Nothing is modified or staged.";
        }

        Map<String, VirtualFile> filesByPath = new LinkedHashMap<>();
        List<CommitProblemsFormatter.Entry> entries = collectEntries(files, filesByPath);

        List<CommitProblemsFormatter.Entry> sorted =
            CommitProblemsFormatter.filterAndSort(entries, page.errorsOnly());
        List<CommitProblemsFormatter.Entry> pageEntries =
            page.summaryOnly() ? List.of() : CommitProblemsFormatter.pageOf(sorted, page.offset(), page.limit());
        FixLookup lookup = includeFixes && !pageEntries.isEmpty()
            ? attachFixes(pageEntries, filesByPath)
            : new FixLookup(pageEntries, List.of(), null);
        String response = CommitProblemsFormatter.format(sorted, files.size(), lookup.entries(), page);
        String note = includeFixes && lookup.entries().stream().anyMatch(e -> e.fixes().isEmpty())
            ? "\n\nNote: " + EDITOR_ONLY_FIXES_NOTE : "";
        return response + CommitProblemsFormatter.fixesUnavailableNote(lookup.unavailablePaths(), lookup.reason())
            + note;
    }

    /**
     * Entries of a page with quick-fix names attached, plus the files whose fixes could not be determined and why.
     */
    private record FixLookup(@NotNull List<CommitProblemsFormatter.Entry> entries,
                             @NotNull List<String> unavailablePaths,
                             String reason) {
    }

    private @NotNull List<CommitProblemsFormatter.Entry> collectEntries(
        List<VirtualFile> files, Map<String, VirtualFile> filesByPath) {
        // CodeSmellDetector must NOT run inside a read action: it manages its own progress and threading.
        // It also refuses to run on a pooled thread without a progress indicator ("Must run from Event Dispatch
        // Thread or with a progress indicator"), so provide one.
        List<CodeSmellInfo> smells = new ArrayList<>();
        ProgressManager.getInstance().runProcess(
            () -> smells.addAll(CodeSmellDetector.getInstance(project).findCodeSmells(files)),
            new ProgressIndicatorBase());
        DiagnosticFilterSettings filter = DiagnosticFilterSettings.getInstance(project);

        List<CommitProblemsFormatter.Entry> entries = new ArrayList<>();
        for (var s : smells) {
            String msg = s.getDescription();
            if (msg == null || msg.isBlank()) continue;
            HighlightSeverity severity = s.getSeverity();
            if (!filter.isSeverityEnabled(severity != null ? severity : HighlightSeverity.WARNING)) continue;
            VirtualFile vf = FileDocumentManager.getInstance().getFile(s.getDocument());
            String path = vf == null ? UNKNOWN_PATH : displayPath(vf);
            if (vf != null) filesByPath.putIfAbsent(path, vf);
            int line = s.getStartLine() >= 0 ? s.getStartLine() + 1 : 1;
            entries.add(new CommitProblemsFormatter.Entry(path, line,
                severity != null ? severity.getName() : CommitProblemsFormatter.SEVERITY_WARNING, msg, List.of()));
        }
        return entries;
    }

    private @NotNull String displayPath(@NotNull VirtualFile vf) {
        String basePath = project.getBasePath();
        return basePath != null ? relativize(basePath, vf.getPath()) : vf.getName();
    }

    /**
     * Looks up quick-fix names for the entries on the page. All files of the page are analyzed in one
     * {@link MainPassesRunner} run; entries are matched to highlights by line and description.
     *
     * <p>Unlike the editor daemon, {@link MainPassesRunner} works on closed files, so nothing is opened and
     * nothing waits for a daemon event. Failures are reported in the result instead of being swallowed, so the
     * caller knows that "no fixes listed" means "not determined", not "none exist".</p>
     */
    private @NotNull FixLookup attachFixes(
        List<CommitProblemsFormatter.Entry> pageEntries, Map<String, VirtualFile> filesByPath) {
        Set<String> paths = new LinkedHashSet<>();
        pageEntries.forEach(e -> paths.add(e.path()));
        List<VirtualFile> pageFiles = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (String path : paths) {
            VirtualFile vf = filesByPath.get(path);
            if (vf == null) unavailable.add(path);
            else pageFiles.add(vf);
        }

        Map<String, Map<String, List<String>>> fixesByPath;
        try {
            fixesByPath = pageFiles.isEmpty() ? Map.of() : analyzeFixes(pageFiles);
        } catch (ProcessCanceledException pce) {
            throw pce;
        } catch (Exception e) {
            LOG.warn("get_commit_problems: quick-fix analysis failed", e);
            return new FixLookup(pageEntries, new ArrayList<>(paths), String.valueOf(e.getMessage()));
        }

        List<CommitProblemsFormatter.Entry> result = new ArrayList<>();
        for (var e : pageEntries) {
            var perFile = fixesByPath.get(e.path());
            List<String> fixes = perFile == null ? null : perFile.get(fixKey(e.line(), e.message()));
            result.add(fixes == null ? e : e.withFixes(fixes));
        }
        return new FixLookup(result, unavailable, unavailable.isEmpty() ? null : "file path could not be resolved");
    }

    /**
     * Runs the main highlighting passes over {@code files} (no editor needed) and returns, per file path, the
     * quick-fix names keyed by {@link #fixKey}. Must not be called inside a read action: the runner manages its own
     * progress and threading and needs an explicit progress indicator on a pooled thread.
     */
    private @NotNull Map<String, Map<String, List<String>>> analyzeFixes(List<VirtualFile> files) {
        Map<Document, List<HighlightInfo>> highlights =
            runMainPasses(files, HighlightSeverity.WARNING, "Collecting quick-fixes");

        return PlatformApiCompat.readActionCompute(() -> {
            Map<String, Map<String, List<String>>> byPath = new LinkedHashMap<>();
            for (var entry : highlights.entrySet()) {
                Document doc = entry.getKey();
                VirtualFile vf = FileDocumentManager.getInstance().getFile(doc);
                if (vf == null) continue;
                Map<String, List<String>> perFile = byPath.computeIfAbsent(displayPath(vf), k -> new LinkedHashMap<>());
                for (HighlightInfo h : entry.getValue()) {
                    collectFixes(h, doc, perFile);
                }
            }
            return byPath;
        });
    }

    private void collectFixes(HighlightInfo h, Document doc, Map<String, List<String>> perFile) {
        String description = h.getDescription();
        if (description == null || description.isBlank()
            || DiagnosticFilterSettings.isNaturalLanguageSeverity(h.getSeverity())) {
            return;
        }
        List<String> fixes = collectQuickFixNames(h);
        if (!fixes.isEmpty()) {
            perFile.putIfAbsent(fixKey(doc.getLineNumber(h.getStartOffset()) + 1, description), fixes);
        }
    }

    private static String fixKey(int line, String message) {
        return line + "\u0000" + message;
    }

    private @NotNull List<VirtualFile> collectChangedFiles() {
        Set<VirtualFile> result = new LinkedHashSet<>();
        for (Change change : ChangeListManager.getInstance(project).getAllChanges()) {
            ContentRevision after = change.getAfterRevision();
            if (after == null) continue; // deleted file
            VirtualFile vf = after.getFile().getVirtualFile();
            if (vf != null && vf.isValid() && !vf.isDirectory()) {
                result.add(vf);
            }
        }
        return new ArrayList<>(result);
    }
}
