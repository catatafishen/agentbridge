package com.github.catatafishen.ideagentforcopilot.psi;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reflection-based integration with SonarQube for IDE (formerly SonarLint) plugin.
 * All interaction is via reflection since SonarLint has no public API.
 * Gracefully handles the plugin being absent or API changes.
 */
@SuppressWarnings("java:S3011") // setAccessible is inherent to this reflection-based integration
public final class SonarQubeIntegration {

    private static final Logger LOG = Logger.getInstance(SonarQubeIntegration.class);
    private static final String SONAR_PLUGIN_ID = "org.sonarlint.idea";
    private static final String UNKNOWN = "unknown";
    private static final String FINDING_FORMAT = "%s:%d [%s/%s] %s";
    private static final int POLL_INTERVAL_MS = 500;
    private static final int MAX_WAIT_SECONDS = 60;

    private final Project project;

    public SonarQubeIntegration(Project project) {
        this.project = project;
    }

    private static ClassLoader getSonarLintClassLoader() {
        return PlatformApiCompat.getPluginClassLoader(SONAR_PLUGIN_ID);
    }

    private static Class<?> loadSonarClass(String className) throws ClassNotFoundException {
        ClassLoader cl = getSonarLintClassLoader();
        if (cl == null) throw new ClassNotFoundException("SonarLint classloader not available");
        return Class.forName(className, true, cl);
    }

    public static boolean isInstalled() {
        return PlatformApiCompat.isPluginInstalled(SONAR_PLUGIN_ID);
    }

    private boolean isAnalysisRunning() {
        try {
            Class<?> trackerClass = loadSonarClass("org.sonarlint.intellij.analysis.RunningAnalysesTracker");
            Object tracker = PlatformApiCompat.getServiceByRawClass(project, trackerClass);
            if (tracker != null) {
                Method isEmptyMethod = trackerClass.getMethod("isEmpty");
                return !(boolean) isEmptyMethod.invoke(tracker);
            }
        } catch (Exception e) {
            LOG.debug("Could not check RunningAnalysesTracker: " + e.getMessage());
        }
        return false;
    }

    public String runAnalysis(String scope, int limit, int offset) {
        if (!isInstalled()) {
            return "Error: SonarQube for IDE plugin is not installed.";
        }

        try {
            String basePath = project.getBasePath();

            // If an analysis is currently running, wait for it to finish then return those results
            if (isAnalysisRunning()) {
                LOG.info("SonarQube analysis already in progress, waiting for completion");
                List<String> findings = waitForNewResults(basePath, null);
                return formatOutput(findings, limit, offset);
            }

            // Fast path: return existing cached results without re-triggering analysis
            List<String> existing = collectViaEdt(basePath);
            if (!existing.isEmpty()) {
                LOG.info("Returning " + existing.size() + " cached SonarQube findings");
                return formatOutput(existing, limit, offset);
            }

            // No cached results found — trigger a new analysis
            LOG.info("No cached results found, triggering SonarLint analysis for scope: " + scope);
            String actionId = resolveActionId(scope);
            CompletableFuture<Boolean> triggerResult = triggerAction(actionId);

            List<String> findings = waitForNewResults(basePath, triggerResult);
            return formatOutput(findings, limit, offset);
        } catch (Exception e) {
            LOG.warn("SonarQube analysis failed", e);
            return "Error running SonarQube analysis: " + e.getMessage();
        }
    }

    private String resolveActionId(String scope) {
        if ("changed".equalsIgnoreCase(scope)) {
            return "SonarLint.AnalyzeChangedFiles";
        }
        return "SonarLint.AnalyzeAllFiles";
    }

    /**
     * Fire-and-forget action trigger. Returns a CompletableFuture that resolves
     * when the EDT processes the action. No arbitrary timeout — the overall
     * analysis polling handles completion detection.
     */
    private CompletableFuture<Boolean> triggerAction(String actionId) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        AnAction action = ActionManager.getInstance().getAction(actionId);
        if (action == null) {
            LOG.warn("SonarLint action not found: " + actionId);
            future.complete(false);
            return future;
        }

        EdtUtil.invokeLater(() -> {
            try {
                var frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project);
                ActionManager.getInstance().tryToExecute(action, null, frame, "AgentBridge", true);
                future.complete(true);
            } catch (Exception e) {
                LOG.warn("Failed to trigger SonarLint action: " + actionId, e);
                future.complete(false);
            }
        });

        return future;
    }

    /**
     * Wait for analysis completion using a scheduled poller instead of Thread.sleep.
     * Uses ScheduledExecutorService to poll RunningAnalysesTracker.isEmpty() without
     * blocking a thread during wait intervals.
     * <p>
     * Phase 1: Wait for tracker to become non-empty (modules registered after async trigger).
     * Phase 2: Wait for tracker to become empty (all modules finished).
     * <p>
     * If triggerResult is provided, Phase 1 also checks if the trigger failed — if so,
     * falls back to collecting existing results immediately.
     */
    private List<String> waitForNewResults(String basePath, CompletableFuture<Boolean> triggerResult) {
        try {
            Class<?> trackerClass = loadSonarClass("org.sonarlint.intellij.analysis.RunningAnalysesTracker");
            Object tracker = PlatformApiCompat.getServiceByRawClass(project, trackerClass);
            Method isEmptyMethod = trackerClass.getMethod("isEmpty");

            if (tracker != null) {
                boolean completed = pollUntilComplete(tracker, isEmptyMethod, triggerResult);
                if (!completed) {
                    List<String> existing = collectViaEdt(basePath);
                    return existing.isEmpty()
                        ? List.of("SonarQube analysis could not be triggered. " +
                        "Open the SonarLint Report tab and click 'Analyze All Files' manually, then call this tool again.")
                        : existing;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.info("Interrupted while waiting for analysis results");
        } catch (Exception e) {
            LOG.info("RunningAnalysesTracker polling failed: " + e.getMessage());
        }

        return collectOrFallback(basePath);
    }

    /**
     * Poll the tracker using a ScheduledExecutorService instead of Thread.sleep loops.
     * Returns true if analysis completed normally, false if trigger failed early.
     */
    private boolean pollUntilComplete(Object tracker, Method isEmptyMethod,
                                      CompletableFuture<Boolean> triggerResult)
        throws InterruptedException {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        AtomicBoolean started = new AtomicBoolean(false);
        long deadline = System.currentTimeMillis() + MAX_WAIT_SECONDS * 1000L;

        var scheduler = com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService();
        ScheduledFuture<?> poller = scheduler.scheduleWithFixedDelay(() ->
                pollOnce(tracker, isEmptyMethod, triggerResult, done, started, deadline),
            0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

        try {
            return done.get(MAX_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            LOG.debug("Polling ended: " + e.getMessage());
            return true;
        } finally {
            poller.cancel(false);
        }
    }

    private void pollOnce(Object tracker, Method isEmptyMethod,
                          CompletableFuture<Boolean> triggerResult,
                          CompletableFuture<Boolean> done,
                          AtomicBoolean started, long deadline) {
        try {
            if (System.currentTimeMillis() > deadline) {
                done.complete(true);
                return;
            }
            if (!started.get() && checkTriggerFailed(triggerResult, done)) {
                return;
            }
            boolean empty = (boolean) isEmptyMethod.invoke(tracker);
            if (!started.get()) {
                if (!empty) {
                    started.set(true);
                }
            } else if (empty) {
                done.complete(true);
            }
        } catch (Exception e) {
            done.completeExceptionally(e);
        }
    }

    private boolean checkTriggerFailed(CompletableFuture<Boolean> triggerResult,
                                       CompletableFuture<Boolean> done) {
        if (triggerResult == null || !triggerResult.isDone()) return false;
        try {
            if (Boolean.FALSE.equals(triggerResult.get())) {
                LOG.warn("Trigger failed, aborting wait");
                done.complete(false);
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            done.complete(false);
            return true;
        } catch (Exception e) {
            done.complete(false);
            return true;
        }
        return false;
    }

    private List<String> collectOrFallback(String basePath) {
        // Collect via EDT first — SonarLint updates ReportPanel on EDT,
        // so running our collection on EDT ensures memory visibility of the latest results
        return collectViaEdt(basePath);
    }

    /**
     * Collect findings via EDT dispatch to ensure we see the latest results.
     * SonarLint updates ReportPanel on EDT, so queuing our collection on EDT
     * guarantees it runs after any pending SonarLint UI updates.
     */
    private List<String> collectViaEdt(String basePath) {
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> future.complete(collectAllFindings(basePath)));
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return collectAllFindings(basePath);
        } catch (Exception e) {
            LOG.debug("EDT collection failed, falling back to direct: " + e.getMessage());
            return collectAllFindings(basePath);
        }
    }

    private List<String> collectAllFindings(String basePath) {
        List<String> reportResults = collectFromReportTab(basePath);
        List<String> onTheFlyResults = collectFromOnTheFlyHolder(basePath);

        if (onTheFlyResults.isEmpty()) return reportResults;
        if (reportResults.isEmpty()) return onTheFlyResults;

        // Merge and deduplicate: report tab findings take precedence
        LinkedHashSet<String> merged = new LinkedHashSet<>(reportResults);
        merged.addAll(onTheFlyResults);
        return new ArrayList<>(merged);
    }

    /**
     * Collect findings from ReportTabManager → ALL ReportPanels → LiveFindings.
     * Iterates all panels to avoid missing results in a specific tab,
     * and deduplicates findings by formatted string.
     */
    private List<String> collectFromReportTab(String basePath) {
        List<String> results = new ArrayList<>();
        try {
            Class<?> reportTabManagerClass = loadSonarClass("org.sonarlint.intellij.ui.report.ReportTabManager");
            Object reportTabManager = PlatformApiCompat.getServiceByRawClass(project, reportTabManagerClass);
            if (reportTabManager == null) {
                LOG.info("ReportTabManager service not available");
                return results;
            }

            Field reportTabsField = reportTabManagerClass.getDeclaredField("reportTabs");
            reportTabsField.setAccessible(true);
            Map<?, ?> reportTabs = (Map<?, ?>) reportTabsField.get(reportTabManager);
            if (reportTabs == null || reportTabs.isEmpty()) {
                LOG.info("No report tabs found");
                return results;
            }

            LOG.info("Found " + reportTabs.size() + " report tab(s)");
            Set<String> seen = new HashSet<>();

            for (Map.Entry<?, ?> entry : reportTabs.entrySet()) {
                Object panel = entry.getValue();
                collectFindingsFromPanel(panel, basePath, results, seen);
            }

            LOG.info("Collected " + results.size() + " unique SonarQube findings from " + reportTabs.size() + " Report tab(s)");
        } catch (ClassNotFoundException e) {
            LOG.info("ReportTabManager class not found — SonarLint API may have changed");
        } catch (Exception e) {
            LOG.warn("Error collecting from ReportTab", e);
        }
        return results;
    }

    private void collectFindingsFromPanel(Object panel, String basePath,
                                          List<String> results, Set<String> seen) {
        try {
            Object analysisResult = getAnalysisResultFromPanel(panel);
            if (analysisResult == null) return;

            Method getFindingsMethod = analysisResult.getClass().getMethod("getFindings");
            Object liveFindings = getFindingsMethod.invoke(analysisResult);
            if (liveFindings == null) return;

            collectIssuesFromFindings(liveFindings, basePath, results, seen);
            collectHotspotsFromFindings(liveFindings, basePath, results, seen);
        } catch (Exception e) {
            LOG.debug("Could not collect findings from panel: " + e.getMessage());
        }
    }

    /**
     * Try method access first (public API), then fall back to field access (internal).
     */
    private Object getAnalysisResultFromPanel(Object panel) {
        // Try getter method first (more stable across versions)
        try {
            Method getter = findMethod(panel, "getLastAnalysisResult");
            if (getter != null) {
                return getter.invoke(panel);
            }
        } catch (Exception e) {
            LOG.debug("getLastAnalysisResult method not available: " + e.getMessage());
        }

        // Fall back to field access
        try {
            Field resultField = panel.getClass().getDeclaredField("lastAnalysisResult");
            resultField.setAccessible(true);
            return resultField.get(panel);
        } catch (Exception e) {
            LOG.debug("lastAnalysisResult field not available: " + e.getMessage());
        }
        return null;
    }

    private void collectIssuesFromFindings(Object liveFindings, String basePath,
                                           List<String> results, Set<String> seen)
        throws ReflectiveOperationException {
        Method getIssuesMethod = liveFindings.getClass().getMethod("getIssuesPerFile");
        collectFromPerFileMap(getIssuesMethod.invoke(liveFindings), basePath, results, seen);
    }

    private void collectHotspotsFromFindings(Object liveFindings, String basePath,
                                             List<String> results, Set<String> seen) {
        try {
            Method getHotspotsMethod = liveFindings.getClass().getMethod("getSecurityHotspotsPerFile");
            collectFromPerFileMap(getHotspotsMethod.invoke(liveFindings), basePath, results, seen);
        } catch (NoSuchMethodException e) {
            LOG.info("getSecurityHotspotsPerFile not available");
        } catch (Exception e) {
            LOG.debug("Error collecting hotspots: " + e.getMessage());
        }
    }

    private void collectFromPerFileMap(Object perFileMap, String basePath,
                                       List<String> results, Set<String> seen) {
        if (!(perFileMap instanceof Map<?, ?> map)) return;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof Collection<?> findings)) continue;
            for (Object finding : findings) {
                String formatted = formatLiveFinding(finding, basePath);
                if (formatted != null && seen.add(formatted)) results.add(formatted);
            }
        }
    }

    /**
     * Fallback: collect from OnTheFlyFindingsHolder (only open file findings).
     */
    private List<String> collectFromOnTheFlyHolder(String basePath) {
        List<String> results = new ArrayList<>();
        try {
            Class<?> submitterClass = loadSonarClass("org.sonarlint.intellij.analysis.AnalysisSubmitter");
            Object submitter = PlatformApiCompat.getServiceByRawClass(project, submitterClass);
            if (submitter == null) return results;

            Field holderField = submitterClass.getDeclaredField("onTheFlyFindingsHolder");
            holderField.setAccessible(true);
            Object holder = holderField.get(submitter);
            if (holder == null) return results;

            collectOnTheFlyIssues(holder, basePath, results);
            collectOnTheFlyHotspots(holder, basePath, results);

            if (!results.isEmpty()) {
                LOG.info("Collected " + results.size() + " findings from OnTheFlyFindingsHolder");
            }
        } catch (ClassNotFoundException e) {
            LOG.info("SonarLint classes not found");
        } catch (Exception e) {
            LOG.warn("Error collecting from OnTheFlyFindingsHolder", e);
        }
        return results;
    }

    private void collectOnTheFlyIssues(Object holder, String basePath, List<String> results)
        throws ReflectiveOperationException {
        Method getAllIssues = holder.getClass().getMethod("getAllIssues");
        Collection<?> issues = (Collection<?>) getAllIssues.invoke(holder);
        for (Object issue : issues) {
            String formatted = formatLiveFinding(issue, basePath);
            if (formatted != null) results.add(formatted);
        }
    }

    private void collectOnTheFlyHotspots(Object holder, String basePath, List<String> results) {
        try {
            Method getAllHotspots = holder.getClass().getMethod("getAllHotspots");
            Collection<?> hotspots = (Collection<?>) getAllHotspots.invoke(holder);
            for (Object hotspot : hotspots) {
                String formatted = formatLiveFinding(hotspot, basePath);
                if (formatted != null) results.add(formatted);
            }
        } catch (NoSuchMethodException e) {
            LOG.info("getAllHotspots not available");
        } catch (Exception e) {
            LOG.debug("Error collecting on-the-fly hotspots: " + e.getMessage());
        }
    }

    private String formatLiveFinding(Object finding, String basePath) {
        try {
            Method getMessage = findMethod(finding, "getMessage");
            String message = getMessage != null ? (String) getMessage.invoke(finding) : UNKNOWN;

            Method getRuleKey = findMethod(finding, "getRuleKey");
            String ruleKey = getRuleKey != null ? (String) getRuleKey.invoke(finding) : UNKNOWN;

            String filePath = getFilePath(finding, basePath);
            int line = getLineNumber(finding);
            String severity = getSeverity(finding);

            return String.format(FINDING_FORMAT, filePath, line, severity, ruleKey, message);
        } catch (Exception e) {
            LOG.debug("Could not format finding: " + e.getMessage());
            return null;
        }
    }

    private String getFilePath(Object finding, String basePath) {
        String path = getFilePathFromMethod(finding, basePath);
        if (path != null) return path;

        path = getFilePathViaPsiFile(finding, basePath);
        return path != null ? path : UNKNOWN;
    }

    private String getFilePathFromMethod(Object finding, String basePath) {
        try {
            Method fileMethod = findMethod(finding, "file");
            if (fileMethod != null) {
                Object vf = fileMethod.invoke(finding);
                if (vf instanceof VirtualFile virtualFile) {
                    return relativizePath(virtualFile.getPath(), basePath);
                }
            }
        } catch (Exception e) {
            LOG.debug("Could not get file path via file method");
        }
        return null;
    }

    private String getFilePathViaPsiFile(Object finding, String basePath) {
        try {
            Method psiFileMethod = findMethod(finding, "psiFile");
            if (psiFileMethod == null) return null;

            Object psiFile = psiFileMethod.invoke(finding);
            if (psiFile == null) return null;

            Method getVf = findMethod(psiFile, "getVirtualFile");
            if (getVf == null) return null;

            Object vf = getVf.invoke(psiFile);
            if (vf instanceof VirtualFile virtualFile) {
                return relativizePath(virtualFile.getPath(), basePath);
            }
        } catch (Exception e) {
            LOG.debug("Could not get file path via psiFile");
        }
        return null;
    }

    private static String relativizePath(String path, String basePath) {
        if (basePath != null && path.startsWith(basePath)) {
            return path.substring(basePath.length() + 1);
        }
        return path;
    }

    private int getLineNumber(Object finding) {
        try {
            Method getRangeMethod = findMethod(finding, "getRange");
            if (getRangeMethod != null) {
                Object rangeObj = getRangeMethod.invoke(finding);
                if (rangeObj instanceof com.intellij.openapi.editor.RangeMarker rm && rm.isValid()) {
                    // Cast required: disambiguates runReadAction(Computable) from runReadAction(ThrowableComputable)
                    //noinspection RedundantCast
                    return com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(
                        (com.intellij.openapi.util.Computable<Integer>) () ->
                            rm.getDocument().getLineNumber(rm.getStartOffset()) + 1
                    );
                }
            }
        } catch (Exception e) {
            LOG.debug("Could not get line number from finding");
        }
        return 0;
    }

    private String getSeverity(Object finding) {
        try {
            Method getImpact = findMethod(finding, "getHighestImpact");
            if (getImpact != null) {
                Object impact = getImpact.invoke(finding);
                if (impact != null) return impact.toString();
            }

            Method getSev = findMethod(finding, "getUserSeverity");
            if (getSev != null) {
                Object sev = getSev.invoke(finding);
                if (sev != null) return sev.toString();
            }
        } catch (Exception e) {
            LOG.debug("Could not get severity from finding");
        }
        return "WARNING";
    }

    private static Method findMethod(Object obj, String name) {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                return clazz.getMethod(name);
            } catch (NoSuchMethodException e) {
                try {
                    Method m = clazz.getDeclaredMethod(name);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {
                    // continue up hierarchy
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private String formatOutput(List<String> findings, int limit, int offset) {
        if (findings.isEmpty()) {
            return "SonarQube analysis complete. No issues found — the code is clean! " +
                "0 bugs, 0 code smells, 0 vulnerabilities, 0 security hotspots detected.";
        }

        int total = findings.size();
        int end = Math.min(offset + limit, total);
        int start = Math.min(offset, total);

        StringBuilder sb = new StringBuilder();
        sb.append("SonarQube findings (").append(total).append(" total");
        if (start > 0 || end < total) {
            sb.append(", showing ").append(start + 1).append("-").append(end);
        }
        sb.append("):\n\n");

        List<String> pageFindings = findings.subList(start, end);
        for (String finding : pageFindings) {
            sb.append(finding).append('\n');
        }

        if (end < total) {
            sb.append("\nWARNING: ").append(total - end).append(" more findings not shown. Use offset=")
                .append(end).append(" to see more.");
        }

        sb.append(SonarRuleDescriptions.buildRulesSection(pageFindings));

        return sb.toString();
    }
}
