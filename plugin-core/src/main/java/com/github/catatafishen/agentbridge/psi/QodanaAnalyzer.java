package com.github.catatafishen.agentbridge.psi;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles Qodana analysis execution and SARIF result parsing.
 */
public final class QodanaAnalyzer {

    private static final Logger LOG = Logger.getInstance(QodanaAnalyzer.class);

    private static final String PARAM_LIMIT = "limit";
    private static final String PARAM_LEVEL = "level";
    private static final String PARAM_MESSAGE = "message";
    private static final String FORMAT_FINDING = "%s:%d [%s/%s] %s";
    private static final String JSON_ARTIFACT_LOCATION = "artifactLocation";
    private static final String JSON_REGION = "region";
    private static final String ERROR_QODANA_POLLING = "Error polling Qodana results";

    private final Project project;

    public QodanaAnalyzer(Project project) {
        this.project = project;
    }

    public String runQodana(JsonObject args) throws ExecutionException, InterruptedException, TimeoutException {
        int limit = args.has(PARAM_LIMIT) ? args.get(PARAM_LIMIT).getAsInt() : 100;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                var actionManager = com.intellij.openapi.actionSystem.ActionManager.getInstance();
                var qodanaAction = actionManager.getAction("Qodana.RunQodanaAction");

                if (qodanaAction == null) {
                    resultFuture.complete("Error: Qodana plugin is not installed or not available. " +
                        "Install it from Settings > Plugins > Marketplace.");
                    return;
                }

                var dataContext = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                    .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                    .build();
                var presentation = qodanaAction.getTemplatePresentation().clone();
                var event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
                    dataContext, presentation, "QodanaTool",
                    com.intellij.openapi.actionSystem.ActionUiKind.NONE, null);

                com.intellij.openapi.actionSystem.ex.ActionUtil.updateAction(qodanaAction, event);
                if (!event.getPresentation().isEnabled()) {
                    resultFuture.complete("Error: Qodana action is not available. " +
                        "The project may not be fully loaded yet, or Qodana may already be running.");
                    return;
                }

                LOG.info("Triggering Qodana local analysis...");
                com.intellij.openapi.actionSystem.ex.ActionUtil.performAction(qodanaAction, event);

                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try {
                        pollQodanaResults(limit, resultFuture);
                    } catch (Exception e) {
                        LOG.error(ERROR_QODANA_POLLING, e);
                        resultFuture.complete("Qodana analysis was triggered but result polling failed: " +
                            e.getMessage() + ". Check the Qodana tab in the Problems tool window for results.");
                    }
                });

            } catch (Exception e) {
                LOG.error("Error triggering Qodana", e);
                resultFuture.complete("Error triggering Qodana: " + e.getMessage());
            }
        });

        return resultFuture.get(600, TimeUnit.SECONDS);
    }

    private void pollQodanaResults(int limit, CompletableFuture<String> resultFuture) {
        try {
            Class<?> serviceClass = loadQodanaServiceClass(limit, resultFuture);
            if (serviceClass == null) return;

            Object qodanaService = getQodanaServiceInstance(serviceClass, limit, resultFuture);
            if (qodanaService == null) return;

            waitForQodanaCompletion(qodanaService, serviceClass, limit, resultFuture);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error(ERROR_QODANA_POLLING, e);
            resultFuture.complete("Qodana analysis was triggered. Check the Qodana tab for results. " +
                "Polling error: " + e.getMessage());
        } catch (Exception e) {
            LOG.error(ERROR_QODANA_POLLING, e);
            resultFuture.complete("Qodana analysis was triggered. Check the Qodana tab for results. " +
                "Polling error: " + e.getMessage());
        }
    }

    private Class<?> loadQodanaServiceClass(int limit, CompletableFuture<String> resultFuture)
        throws InterruptedException {
        try {
            return Class.forName("org.jetbrains.qodana.run.QodanaRunInIdeService");
        } catch (ClassNotFoundException e) {
            LOG.info("QodanaRunInIdeService not available, waiting for SARIF output...");
            pollForSarifOutput(limit, resultFuture);
            return null;
        }
    }

    private void pollForSarifOutput(int limit, CompletableFuture<String> resultFuture)
        throws InterruptedException {
        CompletableFuture<Void> done = new CompletableFuture<>();
        var scheduler = AppExecutorUtil.getAppScheduledExecutorService();
        final int maxPolls = 300;
        final int[] counter = {0};

        java.util.concurrent.ScheduledFuture<?> poller = scheduler.scheduleAtFixedRate(() -> {
            try {
                String fallbackResult = tryFindSarifOutput(limit);
                if (fallbackResult != null) {
                    resultFuture.complete(fallbackResult);
                    done.complete(null);
                } else if (++counter[0] >= maxPolls) {
                    resultFuture.complete("Qodana analysis triggered. Check the Qodana tab in Problems for results. " +
                        "(Qodana service class not available for result polling)");
                    done.complete(null);
                }
            } catch (Exception ex) {
                done.completeExceptionally(ex);
            }
        }, 0, 1000, TimeUnit.MILLISECONDS);

        try {
            done.get(maxPolls, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ie;
        } catch (Exception ex) {
            LOG.debug("SARIF polling ended: " + ex.getMessage());
        } finally {
            poller.cancel(false);
        }
    }

    private Object getQodanaServiceInstance(Class<?> serviceClass, int limit,
                                            CompletableFuture<String> resultFuture) {
        Object qodanaService = PlatformApiCompat.getServiceByRawClass(project, serviceClass);
        if (qodanaService == null) {
            String fallbackResult = tryFindSarifOutput(limit);
            resultFuture.complete(Objects.requireNonNullElse(fallbackResult,
                "Qodana analysis triggered. Check the Qodana tab in Problems for results. " +
                    "(Could not access Qodana service to poll results)"));
            return null;
        }
        return qodanaService;
    }

    private void waitForQodanaCompletion(Object qodanaService, Class<?> serviceClass,
                                         int limit, CompletableFuture<String> resultFuture)
        throws ReflectiveOperationException, InterruptedException, IOException {
        var getRunState = serviceClass.getMethod("getRunState");
        var runStateFlow = getRunState.invoke(qodanaService);
        var getValueMethod = runStateFlow.getClass().getMethod("getValue");

        boolean completed = pollQodanaRunState(getValueMethod, runStateFlow, resultFuture);
        if (!completed) return;

        tryReadQodanaSarifResults(qodanaService, serviceClass, getValueMethod, limit, resultFuture);
    }

    private boolean pollQodanaRunState(java.lang.reflect.Method getValueMethod, Object runStateFlow,
                                       CompletableFuture<String> resultFuture) throws InterruptedException {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        var scheduler = AppExecutorUtil.getAppScheduledExecutorService();
        final int maxPolls = 480;
        final int[] counter = {0};
        final boolean[] wasRunning = {false};

        java.util.concurrent.ScheduledFuture<?> poller = scheduler.scheduleAtFixedRate(() -> {
            try {
                var state = getValueMethod.invoke(runStateFlow);
                String stateName = state.getClass().getSimpleName();
                int i = counter[0]++;

                if (stateName.contains("Running")) {
                    wasRunning[0] = true;
                    if (i % 30 == 0) {
                        LOG.info("Qodana analysis still running... (" + i + "s)");
                    }
                } else if (wasRunning[0]) {
                    LOG.info("Qodana analysis completed after ~" + i + "s");
                    done.complete(true);
                } else if (i > 10) {
                    resultFuture.complete("Qodana analysis was triggered but may require user interaction. " +
                        "Check the IDE for any Qodana dialogs or the Qodana tab in Problems for results.");
                    done.complete(false);
                }

                if (i >= maxPolls) {
                    done.complete(true);
                }
            } catch (Exception ex) {
                done.completeExceptionally(ex);
            }
        }, 0, 1000, TimeUnit.MILLISECONDS);

        try {
            return done.get(maxPolls, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ie;
        } catch (Exception ex) {
            LOG.debug("Qodana polling ended: " + ex.getMessage());
            return true;
        } finally {
            poller.cancel(false);
        }
    }

    private void tryReadQodanaSarifResults(Object qodanaService, Class<?> serviceClass,
                                           java.lang.reflect.Method getValueMethod, int limit,
                                           CompletableFuture<String> resultFuture) throws ReflectiveOperationException, IOException {
        var getRunsResults = serviceClass.getMethod("getRunsResults");
        var runsResultsFlow = getRunsResults.invoke(qodanaService);
        var outputs = (Set<?>) getValueMethod.invoke(runsResultsFlow);
        if (outputs != null && !outputs.isEmpty()) {
            var latest = outputs.iterator().next();
            var getSarifPath = latest.getClass().getMethod("getSarifPath");
            var sarifPath = (Path) getSarifPath.invoke(latest);
            if (sarifPath != null && Files.exists(sarifPath)) {
                String sarif = Files.readString(sarifPath);
                resultFuture.complete(parseSarifResults(sarif, limit));
                return;
            }
        }
        resultFuture.complete("Qodana analysis completed. Results are visible in the Qodana tab " +
            "of the Problems tool window. (SARIF output file not found for programmatic reading)");
    }

    private String tryFindSarifOutput(int limit) {
        String basePath = project.getBasePath();

        String result = tryCommonSarifLocations(basePath, limit);
        if (result != null) return result;

        return searchQodanaDirForSarif(basePath, limit);
    }

    private String tryCommonSarifLocations(String basePath, int limit) {
        Path[] candidates = {
            basePath != null ? Path.of(basePath, ".qodana", "results", "qodana.sarif.json") : null,
            Path.of("/tmp/qodana_output/qodana.sarif.json"),
            Path.of(System.getProperty("java.io.tmpdir"), "qodana_output", "qodana.sarif.json"),
        };
        for (var candidate : candidates) {
            if (candidate != null && Files.exists(candidate)) {
                try {
                    String sarif = Files.readString(candidate);
                    if (sarif.length() > 10) {
                        LOG.info("Found Qodana SARIF output at candidate path: " + candidate);
                        return parseSarifResults(sarif, limit);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to read SARIF file at " + candidate, e);
                }
            }
        }
        return null;
    }

    private String searchQodanaDirForSarif(String basePath, int limit) {
        if (basePath == null) return null;

        try {
            var qodanaDir = Path.of(basePath, ".qodana");
            if (!Files.isDirectory(qodanaDir)) return null;

            try (var stream = Files.walk(qodanaDir, 5)) {
                var sarifFile = stream
                    .filter(p -> p.getFileName().toString().endsWith(".sarif.json"))
                    .min((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(b)
                                .compareTo(Files.getLastModifiedTime(a));
                        } catch (Exception e) {
                            return 0;
                        }
                    });
                if (sarifFile.isPresent()) {
                    String sarif = Files.readString(sarifFile.get());
                    LOG.info("Found Qodana SARIF output via recursive search: " + sarifFile.get());
                    return parseSarifResults(sarif, limit);
                }
            }
        } catch (Exception e) {
            LOG.warn("Error searching for SARIF files", e);
        }
        return null;
    }

    String parseSarifResults(String sarifJson, int limit) {
        try {
            var sarif = JsonParser.parseString(sarifJson).getAsJsonObject();
            var runs = sarif.getAsJsonArray("runs");
            if (runs == null || runs.isEmpty()) {
                return "Qodana completed but no analysis runs found in SARIF output.";
            }

            List<String> problems = new ArrayList<>();
            Set<String> filesSet = new HashSet<>();
            String basePath = project.getBasePath();

            for (var runElement : runs) {
                collectSarifRunProblems(runElement.getAsJsonObject(), basePath, limit, problems, filesSet);
            }

            if (problems.isEmpty()) {
                return "Qodana analysis completed: no problems found. " +
                    "Results are also visible in the Qodana tab of the Problems tool window.";
            }

            String summary = String.format(
                """
                    Qodana found %d problems across %d files (showing up to %d).
                    Results are also visible in the Qodana tab of the Problems tool window.

                    """,
                problems.size(), filesSet.size(), limit);
            return summary + String.join("\n", problems);

        } catch (Exception e) {
            LOG.error("Error parsing SARIF results", e);
            return "Qodana analysis completed but SARIF parsing failed: " + e.getMessage() +
                ". Check the Qodana tab in the Problems tool window for results.";
        }
    }

    private void collectSarifRunProblems(JsonObject run, String basePath,
                                         int limit, List<String> problems, Set<String> filesSet) {
        var results = run.getAsJsonArray("results");
        if (results == null) return;

        for (var resultElement : results) {
            if (problems.size() >= limit) break;
            var result = resultElement.getAsJsonObject();

            String ruleId = result.has("ruleId") ? result.get("ruleId").getAsString() : "unknown";
            String level = result.has(PARAM_LEVEL) ? result.get(PARAM_LEVEL).getAsString() : "warning";
            String message = extractSarifMessage(result);
            SarifLocation loc = extractSarifLocation(result, basePath);

            if (!loc.filePath.isEmpty()) filesSet.add(loc.filePath);
            problems.add(String.format(FORMAT_FINDING, loc.filePath, loc.line, level, ruleId, message));
        }
    }

    private record SarifLocation(String filePath, int line) {
    }

    private String extractSarifMessage(JsonObject result) {
        if (result.has(PARAM_MESSAGE) && result.getAsJsonObject(PARAM_MESSAGE).has("text")) {
            return result.getAsJsonObject(PARAM_MESSAGE).get("text").getAsString();
        }
        return "";
    }

    private SarifLocation extractSarifLocation(JsonObject result, String basePath) {
        String filePath = "";
        int line = -1;
        if (!result.has("locations")) return new SarifLocation(filePath, line);

        var locations = result.getAsJsonArray("locations");
        if (locations.isEmpty()) return new SarifLocation(filePath, line);

        var loc = locations.get(0).getAsJsonObject();
        if (!loc.has("physicalLocation")) return new SarifLocation(filePath, line);

        var phys = loc.getAsJsonObject("physicalLocation");
        if (phys.has(JSON_ARTIFACT_LOCATION) &&
            phys.getAsJsonObject(JSON_ARTIFACT_LOCATION).has("uri")) {
            filePath = phys.getAsJsonObject(JSON_ARTIFACT_LOCATION).get("uri").getAsString();
            if (filePath.startsWith("file://")) filePath = filePath.substring(7);
            if (basePath != null) filePath = ToolUtils.relativize(basePath, filePath);
        }
        if (phys.has(JSON_REGION) &&
            phys.getAsJsonObject(JSON_REGION).has("startLine")) {
            line = phys.getAsJsonObject(JSON_REGION).get("startLine").getAsInt();
        }
        return new SarifLocation(filePath, line);
    }
}
