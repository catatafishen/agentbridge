package com.github.catatafishen.ideagentforcopilot.psi.tools.quality;

import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.google.gson.JsonObject;
import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.ex.GlobalInspectionContextEx;
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl;
import com.intellij.codeInspection.ex.InspectionManagerEx;
import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs IntelliJ's full inspection engine on the project or a specific scope.
 *
 * <p>Lives in plugin-experimental (not plugin-core / marketplace) because it requires
 * {@link GlobalInspectionContextImpl}, which is {@code @ApiStatus.Internal}. There is no
 * public API equivalent — {@code GlobalInspectionContextBase.runTools()} is a no-op.
 * See the private {@link #runFullInspections} helper for the full rationale.</p>
 */
@SuppressWarnings("java:S112") // generic exceptions caught at JSON-RPC dispatch level
public final class RunInspectionsTool extends QualityTool {

    private static final Logger LOG = Logger.getInstance(RunInspectionsTool.class);
    private static final String SEVERITY_WARNING = "WARNING";
    private static final String FORMAT_FINDING = "%s:%d [%s/%s] %s";
    private static final String PARAM_MIN_SEVERITY = "min_severity";

    // Cached inspection results for pagination
    private List<String> cachedInspectionResults;
    private int cachedInspectionFileCount;
    private String cachedInspectionProfile;
    private long cachedInspectionTimestamp;

    public RunInspectionsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "run_inspections";
    }

    @Override
    public @NotNull String displayName() {
        return "Run Inspections";
    }

    @Override
    public @NotNull String description() {
        return "Run IntelliJ's full inspection engine on the project or a specific scope";
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
            {PARAM_SCOPE, TYPE_STRING, "Optional: file or directory path to inspect. Examples: 'src/main/java/com/example/MyClass.java' or 'src/main/java/com/example'"},
            {PARAM_LIMIT, TYPE_INTEGER, "Page size (default: 100). Maximum problems per response"},
            {PARAM_OFFSET, TYPE_INTEGER, "Number of problems to skip (default: 0). Use for pagination"},
            {PARAM_MIN_SEVERITY, TYPE_STRING, "Minimum severity filter. Options: ERROR, WARNING, WEAK_WARNING, INFO. Default: all severities included. Only set this if the user explicitly asks to filter by severity."}
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        int limit = args.has(PARAM_LIMIT) ? args.get(PARAM_LIMIT).getAsInt() : 100;
        int offset = args.has(PARAM_OFFSET) ? args.get(PARAM_OFFSET).getAsInt() : 0;
        String minSeverity = args.has(PARAM_MIN_SEVERITY) ? args.get(PARAM_MIN_SEVERITY).getAsString() : null;
        String scopePath = args.has(PARAM_SCOPE) ? args.get(PARAM_SCOPE).getAsString() : null;

        if (!project.isInitialized()) {
            return ERROR_IDE_INITIALIZING;
        }
        if (DumbService.isDumb(project)) {
            return "IDE is currently indexing the project. Please wait for indexing to finish and try again.";
        }

        // Serve from cache if available and fresh (5 min TTL)
        long cacheAge = System.currentTimeMillis() - cachedInspectionTimestamp;
        if (offset > 0 && cachedInspectionResults != null && cacheAge < 300_000) {
            LOG.info("Serving inspection page from cache (offset=" + offset + ", cache age=" + cacheAge + "ms)");
            return formatInspectionPage(cachedInspectionResults, cachedInspectionFileCount,
                cachedInspectionProfile, offset, limit);
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        try {
            runInspectionAnalysis(limit, offset, minSeverity, scopePath, resultFuture);
        } catch (Exception e) {
            LOG.error("Error running inspections", e);
            resultFuture.complete("Error running inspections: " + e.getMessage());
        }

        return resultFuture.get(600, TimeUnit.SECONDS);
    }

    // ── Inspection trigger ────────────────────────────────────

    /**
     * Runs the full inspection engine on the given scope using the supplied profile.
     *
     * <p><b>Why {@code GlobalInspectionContextImpl} is required:</b>
     * {@code GlobalInspectionContextBase.runTools()} is a no-op; only
     * {@code GlobalInspectionContextImpl.runTools()} performs real file-by-file inspection.
     * Using {@code GlobalInspectionContextEx} directly completes in ~40ms with 0 results.
     * {@code InspectionManagerEx} is the only way to obtain a {@code GlobalInspectionContextImpl}
     * with the correct {@code ContentManager}.
     *
     * <p>The {@code onFinished} callback is invoked <b>synchronously</b> before
     * {@code super.notifyInspectionsFinished}, which calls {@code cleanup()} and disposes the
     * context. Callers must collect all results inside the callback before returning.
     *
     * <p>No public API equivalent exists. This class lives in plugin-experimental specifically
     * to isolate this {@code @ApiStatus.Internal} usage from the marketplace-published plugin-core.
     */
    @SuppressWarnings("UnstableApiUsage")
    private static void runFullInspections(
        @NotNull Project project,
        @NotNull AnalysisScope scope,
        @NotNull InspectionProfileImpl profile,
        @NotNull Consumer<GlobalInspectionContextEx> onFinished) {
        InspectionManagerEx mgr = (InspectionManagerEx) InspectionManager.getInstance(project);
        GlobalInspectionContextImpl context =
            new GlobalInspectionContextImpl(project, mgr.getContentManager()) {
                @Override
                protected void notifyInspectionsFinished(@NotNull AnalysisScope finishedScope) {
                    // Collect results before calling super, which invokes cleanup() and disposes the context
                    onFinished.accept(this);
                    super.notifyInspectionsFinished(finishedScope);
                }
            };
        context.setExternalProfile(profile);
        // Run doInspections on a pooled thread, never the EDT.
        // DumbService.runWhenSmart() can dispatch its callback to the EDT when indexing is active;
        // running doInspections() on the EDT causes ProgressManager to show a modal dialog (Task.Modal),
        // which locks the EDT in a modal event loop. Any invokeLater() calls posted with NON_MODAL
        // modality state are then held back until the modal dialog closes — causing cascading
        // 30-second timeouts and an IDE freeze. Pooled thread avoids this entirely.
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            DumbService.getInstance(project).waitForSmartMode();
            context.doInspections(scope);
        });
    }

    // ── Inspection analysis pipeline ─────────────────────────

    private String formatInspectionPage(List<String> allProblems, int filesWithProblems,
                                        String profileName, int offset, int limit) {
        int total = allProblems.size();
        if (total == 0) {
            return "No inspection problems found (cached result).";
        }
        int effectiveOffset = Math.min(offset, total);
        int end = Math.min(effectiveOffset + limit, total);
        List<String> page = allProblems.subList(effectiveOffset, end);
        boolean hasMore = end < total;

        String summary = String.format(
            """
                Found %d total problems across %d files (profile: %s).
                Showing %d-%d of %d.%s
                Results are also visible in the IDE's Inspection Results view.

                """,
            total, filesWithProblems, profileName,
            effectiveOffset + 1, end, total,
            hasMore ? String.format(
                " WARNING: %d more problems not shown! Call run_inspections with offset=%d to see the rest.",
                total - end, end) : "");
        return summary + String.join("\n", page);
    }

    private void runInspectionAnalysis(int limit, int offset, String minSeverity,
                                       String scopePath,
                                       CompletableFuture<String> resultFuture) {
        Map<String, Integer> severityRank = Map.of(
            "ERROR", 4, SEVERITY_WARNING, 3, "WEAK_WARNING", 2,
            "LIKE_UNUSED_SYMBOL", 2, "INFORMATION", 1, "INFO", 1,
            "TEXT_ATTRIBUTES", 0, "GENERIC_SERVER_ERROR_OR_WARNING", 3
        );
        final int requiredRank = (minSeverity != null && !minSeverity.isEmpty())
            ? severityRank.getOrDefault(minSeverity.toUpperCase(), 0) : 0;
        try {
            LOG.info("Starting inspection analysis...");

            var profileManager = com.intellij.profile.codeInspection.InspectionProjectProfileManager.getInstance(project);
            var currentProfile = profileManager.getCurrentProfile();

            AnalysisScope scope = createAnalysisScope(scopePath, resultFuture);
            if (scope == null) return;

            LOG.info("Using inspection profile: " + currentProfile.getName());

            String basePath = project.getBasePath();
            String profileName = currentProfile.getName();

            runFullInspections(project, scope, currentProfile, ctx -> {
                LOG.info("Inspection analysis completed, collecting results...");
                LOG.info("Used tools count: " + ctx.getUsedTools().size());
                // Collect synchronously while the context is still valid — the callback is
                // invoked BEFORE super.notifyInspectionsFinished which calls cleanup().
                InspectionCollectionResult collected = ApplicationManager.getApplication().runReadAction(
                    (Computable<InspectionCollectionResult>) () -> collectInspectionProblems(ctx, severityRank, requiredRank, basePath));
                cacheAndCompleteInspection(collected, new InspectionPageParams(profileName, offset, limit), resultFuture);
            });

        } catch (Exception e) {
            LOG.error("Error setting up inspections", e);
            resultFuture.complete("Error setting up inspections: " + e.getMessage());
        }
    }

    private void cacheAndCompleteInspection(InspectionCollectionResult collected,
                                            InspectionPageParams pageParams,
                                            CompletableFuture<String> resultFuture) {
        cachedInspectionResults = new ArrayList<>(collected.problems);
        cachedInspectionFileCount = collected.fileCount;
        cachedInspectionProfile = pageParams.profileName;
        cachedInspectionTimestamp = System.currentTimeMillis();
        LOG.info("Cached " + collected.problems.size() + " inspection results for pagination" +
            " (skipped: " + collected.skippedNoDescription + " no-description, " +
            collected.skippedNoFile + " no-file)");

        if (collected.problems.isEmpty()) {
            resultFuture.complete("No inspection problems found. " +
                "The code passed all enabled inspections in the current profile (" +
                pageParams.profileName + "). Results are also visible in the IDE's Inspection Results view.");
        } else {
            resultFuture.complete(formatInspectionPage(
                collected.problems, collected.fileCount,
                pageParams.profileName, pageParams.offset, pageParams.limit));
        }
    }

    private AnalysisScope createAnalysisScope(
        String scopePath, CompletableFuture<String> resultFuture) {
        if (scopePath == null || scopePath.isEmpty()) {
            LOG.info("Analysis scope: entire project");
            return new AnalysisScope(project);
        }
        VirtualFile scopeFile = resolveVirtualFile(scopePath);
        if (scopeFile == null) {
            resultFuture.complete("Error: File or directory not found: " + scopePath);
            return null;
        }
        if (scopeFile.isDirectory()) {
            PsiDirectory psiDir = ApplicationManager.getApplication().runReadAction(
                (Computable<PsiDirectory>) () -> PsiManager.getInstance(project).findDirectory(scopeFile)
            );
            if (psiDir == null) {
                resultFuture.complete("Error: Cannot resolve directory: " + scopePath);
                return null;
            }
            LOG.info("Analysis scope: directory " + scopePath);
            return new AnalysisScope(psiDir);
        }
        PsiFile psiFile = ApplicationManager.getApplication().runReadAction(
            (Computable<PsiFile>) () -> PsiManager.getInstance(project).findFile(scopeFile));
        if (psiFile == null) {
            resultFuture.complete(ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_CANNOT_PARSE + scopePath);
            return null;
        }
        LOG.info("Analysis scope: file " + scopePath);
        return new AnalysisScope(psiFile);
    }

    // ── Inspection collection records ────────────────────────

    private record InspectionCollectionResult(List<String> problems, int fileCount,
                                              int skippedNoDescription, int skippedNoFile) {
    }

    private record InspectionPageParams(String profileName, int offset, int limit) {
    }

    private record InspectionContext(String basePath, Set<String> filesSet,
                                     Map<String, Integer> severityRank, int requiredRank) {
    }

    // ── Problem collection ───────────────────────────────────

    private InspectionCollectionResult collectInspectionProblems(
        GlobalInspectionContextEx ctx, Map<String, Integer> severityRank,
        int requiredRank, String basePath) {
        List<String> allProblems = new ArrayList<>();
        Set<String> filesSet = new HashSet<>();
        int skippedNoDescription = 0;
        int skippedNoFile = 0;
        int toolsWithProblems = 0;

        for (var tools : ctx.getUsedTools()) {
            var toolWrapper = tools.getTool();
            String toolId = toolWrapper.getShortName();

            var presentation = PlatformApiCompat.getInspectionPresentation(ctx, toolWrapper);
            if (presentation == null) continue;

            var context = new InspectionContext(basePath, filesSet, severityRank, requiredRank);
            int beforeSize = allProblems.size();
            int[] skipped = collectProblemsFromTool(presentation, toolId, context, allProblems);
            skippedNoDescription += skipped[0];
            skippedNoFile += skipped[1];
            if (allProblems.size() > beforeSize) {
                toolsWithProblems++;
                LOG.info("Inspection tool '" + toolId + "' found " +
                    (allProblems.size() - beforeSize) + " problems");
            }
        }
        LOG.info("Total: " + allProblems.size() + " problems from " + toolsWithProblems +
            " tools (out of " + ctx.getUsedTools().size() + " used tools)");
        return new InspectionCollectionResult(allProblems, filesSet.size(), skippedNoDescription, skippedNoFile);
    }

    private int[] collectProblemsFromTool(
        com.intellij.codeInspection.InspectionToolResultExporter presentation,
        String toolId, InspectionContext context, List<String> allProblems) {
        int skippedNoDescription = 0;
        int skippedNoFile = 0;

        var problemElements = presentation.getProblemElements();
        if (!problemElements.isEmpty()) {
            return collectFromProblemElements(problemElements, toolId, context, allProblems);
        }

        var flatDescriptors = presentation.getProblemDescriptors();
        int[] flatSkipped = collectFromFlatDescriptors(flatDescriptors, toolId, context, allProblems);
        skippedNoDescription += flatSkipped[0];
        skippedNoFile += flatSkipped[1];

        int[] exportSkipped = collectFromExportedResults(presentation, toolId, context, allProblems);
        skippedNoDescription += exportSkipped[0];
        skippedNoFile += exportSkipped[1];

        return new int[]{skippedNoDescription, skippedNoFile};
    }

    private int[] collectFromProblemElements(
        com.intellij.codeInspection.ui.util.SynchronizedBidiMultiMap<com.intellij.codeInspection.reference.RefEntity, com.intellij.codeInspection.CommonProblemDescriptor> problemElements,
        String toolId, InspectionContext context, List<String> allProblems) {
        int skippedNoDescription = 0;
        int skippedNoFile = 0;
        for (var refEntity : problemElements.keys()) {
            var descriptors = problemElements.get(refEntity);
            if (descriptors == null) continue;
            for (var descriptor : descriptors) {
                int result = processInspectionDescriptor(descriptor, refEntity, toolId, context, allProblems);
                if (result == 1) skippedNoDescription++;
                else if (result == 2) skippedNoFile++;
            }
        }
        return new int[]{skippedNoDescription, skippedNoFile};
    }

    private int[] collectFromFlatDescriptors(
        java.util.Collection<com.intellij.codeInspection.CommonProblemDescriptor> flatDescriptors,
        String toolId, InspectionContext context, List<String> allProblems) {
        int skippedNoDescription = 0;
        int skippedNoFile = 0;
        for (var descriptor : flatDescriptors) {
            int result = processInspectionDescriptor(descriptor, null, toolId, context, allProblems);
            if (result == 1) skippedNoDescription++;
            else if (result == 2) skippedNoFile++;
        }
        return new int[]{skippedNoDescription, skippedNoFile};
    }

    private int[] collectFromExportedResults(
        com.intellij.codeInspection.InspectionToolResultExporter presentation,
        String toolId, InspectionContext context, List<String> allProblems) {
        var hasProblems = presentation.hasReportedProblems();
        if (hasProblems != com.intellij.util.ThreeState.YES) {
            return new int[]{0, 0};
        }
        try {
            var elements = exportElements(presentation);
            return tallyExportedElements(elements, toolId, context, allProblems);
        } catch (Exception e) {
            LOG.warn("Failed to export results for tool '" + toolId + "': " + e.getMessage());
        }
        return new int[]{0, 0};
    }

    private List<org.jdom.Element> exportElements(
        com.intellij.codeInspection.InspectionToolResultExporter presentation) {
        presentation.updateContent();
        var elements = new ArrayList<org.jdom.Element>();
        presentation.exportResults(elements::add, entity -> true, desc -> true);

        if (elements.isEmpty()) {
            var content = presentation.getContent();
            for (var entry : content.entrySet()) {
                for (var refEntity : entry.getValue()) {
                    presentation.exportResults(elements::add, refEntity, desc -> true);
                }
            }
        }
        return elements;
    }

    private int[] tallyExportedElements(List<org.jdom.Element> elements, String toolId,
                                        InspectionContext context, List<String> allProblems) {
        int skippedNoDescription = 0;
        int skippedNoFile = 0;
        for (var element : elements) {
            String formatted = formatExportedElement(element, toolId,
                context.basePath(), context.filesSet());
            if (formatted != null && !formatted.isEmpty()) {
                if (shouldFilterBySeverity(element, context)) continue;
                allProblems.add(formatted);
            } else if (formatted == null) {
                skippedNoDescription++;
            } else {
                skippedNoFile++;
            }
        }
        return new int[]{skippedNoDescription, skippedNoFile};
    }

    private boolean shouldFilterBySeverity(org.jdom.Element element, InspectionContext context) {
        if (context.requiredRank() <= 0) return false;
        String severity = extractSeverityFromElement(element);
        int rank = context.severityRank().getOrDefault(severity.toUpperCase(), 0);
        return rank < context.requiredRank();
    }

    // ── Formatting helpers ───────────────────────────────────

    private String formatExportedElement(org.jdom.Element element, String toolId,
                                         String basePath, Set<String> filesSet) {
        String description = element.getChildText("description");
        if (description == null || description.isEmpty()) return null;

        String fileUrl = element.getChildText("file");
        if (fileUrl == null || fileUrl.isEmpty()) return "";

        String filePath = fileUrl;
        if (filePath.contains("$PROJECT_DIR$")) {
            filePath = filePath.replaceAll(".*\\$PROJECT_DIR\\$/", "");
        } else if (filePath.startsWith("file://")) {
            filePath = filePath.substring(7);
            if (basePath != null) {
                filePath = relativize(basePath, filePath);
            }
        }
        filesSet.add(filePath);

        String lineStr = element.getChildText("line");
        int line = 0;
        if (lineStr != null) {
            try {
                line = Integer.parseInt(lineStr);
            } catch (NumberFormatException ignored) { /* empty */ }
        }

        String severity = extractSeverityFromElement(element);

        description = description.replaceAll("<[^>]+>", "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").trim();

        return String.format(FORMAT_FINDING, filePath, line, severity, toolId, description);
    }

    private String extractSeverityFromElement(org.jdom.Element element) {
        var problemClass = element.getChild("problem_class");
        if (problemClass != null) {
            String severity = problemClass.getAttributeValue("severity");
            if (severity != null) return severity;
        }
        return SEVERITY_WARNING;
    }

    /**
     * Process a single inspection descriptor and add to allProblems if valid.
     *
     * @return 0=added, 1=skippedNoDescription, 2=skippedNoFile, 3=filtered by severity
     */
    private int processInspectionDescriptor(
        com.intellij.codeInspection.CommonProblemDescriptor descriptor,
        com.intellij.codeInspection.reference.RefEntity refEntity,
        String toolId, InspectionContext context, List<String> allProblems) {
        String formatted = formatInspectionDescriptor(
            descriptor, refEntity, toolId, context.basePath(), context.filesSet());
        if (formatted == null) return 1;
        if (formatted.isEmpty()) return 2;

        String severity = (descriptor instanceof com.intellij.codeInspection.ProblemDescriptor pd)
            ? pd.getHighlightType().toString() : SEVERITY_WARNING;
        if (context.requiredRank() > 0) {
            int rank = context.severityRank().getOrDefault(severity.toUpperCase(), 0);
            if (rank < context.requiredRank()) return 3;
        }

        allProblems.add(formatted);
        return 0;
    }

    @SuppressWarnings("java:S2589") // null check is defensive against runtime nulls despite @NotNull
    private String formatInspectionDescriptor(
        com.intellij.codeInspection.CommonProblemDescriptor descriptor,
        com.intellij.codeInspection.reference.RefEntity refEntity,
        String toolId, String basePath, Set<String> filesSet) {
        String description = descriptor.getDescriptionTemplate();
        //noinspection ConstantValue - description can be null at runtime despite @NotNull annotation
        if (description == null || description.isEmpty()) return null;

        String refText = "";
        int line = 0;
        String filePath = "";

        if (descriptor instanceof com.intellij.codeInspection.ProblemDescriptor pd) {
            var psiEl = pd.getPsiElement();
            if (psiEl != null) {
                refText = psiEl.getText();
                if (refText != null && refText.length() > 80) {
                    refText = refText.substring(0, 80) + "...";
                }
            }
            line = pd.getLineNumber() + 1;
            filePath = resolveDescriptorFilePath(pd, basePath, filesSet);
        } else if (refEntity != null) {
            filePath = resolveRefEntityFilePath(refEntity, basePath, filesSet);
            refText = refEntity.getName();
        }

        description = description.replaceAll("<[^>]+>", "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            .replace("#ref", refText != null ? refText : "").replace("#loc", "").trim();

        if (description.isEmpty()) return null;
        if (filePath.isEmpty()) return "";

        String severity = (descriptor instanceof com.intellij.codeInspection.ProblemDescriptor pd2)
            ? pd2.getHighlightType().toString() : SEVERITY_WARNING;

        return String.format(FORMAT_FINDING, filePath, line, severity, toolId, description);
    }

    // ── File path resolution ─────────────────────────────────

    private String resolveRefEntityFilePath(
        com.intellij.codeInspection.reference.RefEntity refEntity,
        String basePath, Set<String> filesSet) {
        if (refEntity instanceof com.intellij.codeInspection.reference.RefElement refElement) {
            String path = resolveFilePathFromElement(refElement.getPsiElement(), basePath);
            if (!path.isEmpty()) {
                filesSet.add(path);
                return path;
            }
        }
        if (refEntity instanceof com.intellij.codeInspection.reference.RefModule refModule) {
            String moduleLabel = "[module:" + refModule.getName() + "]";
            filesSet.add(moduleLabel);
            return moduleLabel;
        }
        return "";
    }

    private String resolveDescriptorFilePath(com.intellij.codeInspection.ProblemDescriptor pd,
                                             String basePath, Set<String> filesSet) {
        String filePath = resolveFilePathFromElement(pd.getPsiElement(), basePath);
        if (!filePath.isEmpty()) filesSet.add(filePath);
        return filePath;
    }

    private String resolveFilePathFromElement(com.intellij.psi.PsiElement psiElement, String basePath) {
        if (psiElement == null) return "";
        var containingFile = psiElement.getContainingFile();
        if (containingFile == null) return "";
        var vf = containingFile.getVirtualFile();
        if (vf == null) return "";
        return basePath != null ? relativize(basePath, vf.getPath()) : vf.getName();
    }
}
