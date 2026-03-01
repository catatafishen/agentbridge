package com.github.catatafishen.ideagentforcopilot.psi;

import com.google.gson.JsonObject;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.RunManager;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import org.jetbrains.annotations.NotNull;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Handles test-related tool calls: list_tests, run_tests, get_coverage.
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
class TestTools extends AbstractToolHandler {
    private static final Logger LOG = Logger.getInstance(TestTools.class);

    private static final String PARAM_FILE_PATTERN = "file_pattern";
    private static final String JSON_MODULE = "module";
    private static final String PARAM_MESSAGE = "message";
    private static final String TEST_TYPE_METHOD = "method";
    private static final String TEST_TYPE_CLASS = "class";
    private static final String TEST_TYPE_PATTERN = "pattern";
    private static final String ERROR_NO_PROJECT_PATH = "No project base path";
    private static final String JUNIT_TYPE_ID = "junit";

    private final RefactoringTools refactoringTools;

    TestTools(Project project, RefactoringTools refactoringTools) {
        super(project);
        this.refactoringTools = refactoringTools;
        register("list_tests", this::listTests);
        register("run_tests", this::runTests);
        register("get_coverage", this::getCoverage);
    }

    // ---- Run Configuration Helper (local copy for test configs) ----

    private String runConfiguration(JsonObject args) throws Exception {
        String name = args.get("name").getAsString();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                var settings = RunManager.getInstance(project).findConfigurationByName(name);
                if (settings == null) {
                    resultFuture.complete("Run configuration not found: '" + name
                        + "'. Use list_run_configurations to see available configs.");
                    return;
                }

                var executor = DefaultRunExecutor.getRunExecutorInstance();
                var envBuilder = ExecutionEnvironmentBuilder.createOrNull(executor, settings);
                if (envBuilder == null) {
                    resultFuture.complete("Cannot create execution environment for: " + name);
                    return;
                }

                var env = envBuilder.build();
                ExecutionManager.getInstance(project).restartRunProfile(env);
                resultFuture.complete("Started run configuration: " + name
                    + " [" + settings.getType().getDisplayName() + "]"
                    + "\nResults will appear in the IntelliJ Run panel.");
            } catch (Exception e) {
                resultFuture.complete("Error running configuration: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    // ---- JUnit Helper ----

    private com.intellij.execution.configurations.ConfigurationType findJUnitConfigurationType() {
        for (var ct : com.intellij.execution.configurations.ConfigurationType.CONFIGURATION_TYPE_EP.getExtensionList()) {
            String displayName = ct.getDisplayName().toLowerCase();
            if (displayName.contains(JUNIT_TYPE_ID)
                || ct.getId().toLowerCase().contains(JUNIT_TYPE_ID)) {
                return ct;
            }
        }
        return null;
    }

    // ---- Tool Methods ----

    private String listTests(JsonObject args) {
        String filePattern = args.has(PARAM_FILE_PATTERN) ? args.get(PARAM_FILE_PATTERN).getAsString() : "";

        return ReadAction.compute(() -> {
            List<String> tests = new ArrayList<>();
            String basePath = project.getBasePath();
            ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);

            fileIndex.iterateContent(vf -> {
                if (isTestSourceFile(vf, filePattern, fileIndex)) {
                    collectTestMethodsFromFile(vf, basePath, tests);
                }
                return tests.size() < 500;
            });

            if (tests.isEmpty()) return "No tests found";
            return tests.size() + " tests:\n" + String.join("\n", tests);
        });
    }

    private boolean isTestSourceFile(VirtualFile vf, String filePattern, ProjectFileIndex fileIndex) {
        if (vf.isDirectory()) return false;
        String name = vf.getName();
        if (!name.endsWith(ToolUtils.JAVA_EXTENSION) && !name.endsWith(".kt")) return false;
        if (!filePattern.isEmpty() && ToolUtils.doesNotMatchGlob(name, filePattern)) return false;
        return fileIndex.isInTestSourceContent(vf);
    }

    private void collectTestMethodsFromFile(VirtualFile vf, String basePath, List<String> tests) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return;
        Document doc = FileDocumentManager.getInstance().getDocument(vf);

        psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (!(element instanceof PsiNamedElement named)) {
                    super.visitElement(element);
                    return;
                }
                String type = ToolUtils.classifyElement(element);
                if ((ToolUtils.ELEMENT_TYPE_METHOD.equals(type) || ToolUtils.ELEMENT_TYPE_FUNCTION.equals(type)) && hasTestAnnotation(element)) {
                    String methodName = named.getName();
                    String className = getContainingClassName(element);
                    String relPath = basePath != null ? relativize(basePath, vf.getPath()) : vf.getPath();
                    int line = doc != null ? doc.getLineNumber(element.getTextOffset()) + 1 : 0;
                    tests.add(String.format("%s.%s (%s:%d)", className, methodName, relPath, line));
                }
                super.visitElement(element);
            }
        });
    }

    private String runTests(JsonObject args) throws Exception {
        String target = args.get("target").getAsString();
        String module = args.has(JSON_MODULE) ? args.get(JSON_MODULE).getAsString() : "";
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;

        String configResult = tryRunTestConfig(target);
        if (configResult != null) return configResult;

        // Wildcard patterns → try JUnit pattern config first, then Gradle run config
        if (target.contains("*")) {
            String patternResult = tryRunJUnitPattern(target);
            if (patternResult != null) return patternResult;

            return runTestsViaGradleConfig(target, module);
        }

        // Specific class/method → native JUnit runner, then Gradle run config fallback
        String junitResult = tryRunJUnitNatively(target);
        if (junitResult != null) return junitResult;

        return runTestsViaGradleConfig(target, module);
    }

    private String getCoverage(JsonObject args) {
        String file = args.has("file") ? args.get("file").getAsString() : "";
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;

        // Try JaCoCo XML report
        for (String module : List.of("", "plugin-core", "mcp-server")) {
            Path jacocoXml = module.isEmpty()
                ? Path.of(basePath, ToolUtils.BUILD_DIR, "reports", "jacoco", "test", "jacocoTestReport.xml")
                : Path.of(basePath, module, ToolUtils.BUILD_DIR, "reports", "jacoco", "test", "jacocoTestReport.xml");
            if (Files.exists(jacocoXml)) {
                return parseJacocoXml(jacocoXml, file);
            }
        }

        // Try IntelliJ's CoverageDataManager via reflection
        try {
            Class<?> cdmClass = Class.forName("com.intellij.coverage.CoverageDataManager");
            Object manager = project.getService(cdmClass);
            if (manager != null) {
                var getCurrentBundle = cdmClass.getMethod("getCurrentSuitesBundle");
                Object bundle = getCurrentBundle.invoke(manager);
                if (bundle != null) {
                    return "Coverage data available in IntelliJ. Use View > Tool Windows > Coverage to inspect.";
                }
            }
        } catch (Exception ignored) {
            // XML parsing or file access errors are non-fatal
        }

        return """
            No coverage data found. Run tests with coverage first:
              - IntelliJ: Right-click test → Run with Coverage
              - Gradle: Add jacoco plugin and run `gradlew jacocoTestReport`""";
    }

    // ---- Test & Run Helper Methods ----

    private String tryRunTestConfig(String target) {
        try {
            // Look for a matching test run configuration
            var configs = RunManager.getInstance(project).getAllSettings();
            for (var config : configs) {
                String typeName = config.getType().getDisplayName().toLowerCase();
                if ((typeName.contains(JUNIT_TYPE_ID) || typeName.contains("test"))
                    && config.getName().contains(target)) {
                    return runConfiguration(createJsonWithName(config.getName()));
                }
            }
        } catch (Exception ignored) {
            // XML parsing or file access errors are non-fatal
        }
        return null;
    }

    /**
     * Create a temporary JUnit run configuration and execute it via IntelliJ's native test runner.
     * This gives proper test tree UI, pass/fail counts, and rerun-failed support.
     */
    private String tryRunJUnitNatively(String target) {
        try {
            var junitType = findJUnitConfigurationType();
            if (junitType == null) return null;

            String[] parsed = parseTestTarget(target);
            String testClass = parsed[0];
            String testMethod = parsed[1];

            RefactoringTools.ClassInfo classInfo = refactoringTools.resolveClass(testClass);
            if (classInfo.fqn() == null) return null;

            final String resolvedClass = classInfo.fqn();
            final String resolvedMethod = testMethod;
            final Module resolvedModule = classInfo.module();
            String simpleName = resolvedClass.substring(resolvedClass.lastIndexOf('.') + 1);
            String configName = "Test: " + (resolvedMethod != null
                ? simpleName + "." + resolvedMethod : simpleName);

            // Subscribe to execution events before launching (on any thread)
            CompletableFuture<ProcessHandler> handlerFuture = new CompletableFuture<>();
            var connection = project.getMessageBus().connect();
            connection.subscribe(ExecutionManager.EXECUTION_TOPIC, new com.intellij.execution.ExecutionListener() {
                @Override
                public void processStarted(@NotNull String executorId,
                                           @NotNull com.intellij.execution.runners.ExecutionEnvironment env,
                                           @NotNull ProcessHandler handler) {
                    if (env.getRunnerAndConfigurationSettings() != null
                        && configName.equals(env.getRunnerAndConfigurationSettings().getName())) {
                        handlerFuture.complete(handler);
                        connection.disconnect();
                    }
                }

                @Override
                public void processNotStarted(@NotNull String executorId,
                                              @NotNull com.intellij.execution.runners.ExecutionEnvironment env) {
                    if (env.getRunnerAndConfigurationSettings() != null
                        && configName.equals(env.getRunnerAndConfigurationSettings().getName())) {
                        handlerFuture.complete(null);
                        connection.disconnect();
                    }
                }
            });

            // Launch on EDT (non-blocking)
            CompletableFuture<String> launchFuture = new CompletableFuture<>();
            EdtUtil.invokeLater(() -> {
                try {
                    String error = launchJUnitConfig(
                        junitType, resolvedClass, resolvedMethod, resolvedModule, configName);
                    launchFuture.complete(error);
                } catch (Exception e) {
                    LOG.warn("Failed to run JUnit natively, will fall back to Gradle", e);
                    launchFuture.complete("launch_failed");
                }
            });

            String launchError = launchFuture.get(10, TimeUnit.SECONDS);
            if (launchError != null) {
                connection.disconnect();
                return "launch_failed".equals(launchError) ? null : launchError;
            }

            // Wait for the process to start (up to 15s) — off EDT
            ProcessHandler handler;
            try {
                handler = handlerFuture.get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                connection.disconnect();
                return "Started tests via IntelliJ JUnit runner: " + configName
                    + "\nCould not capture process handle. Check the Run panel for results.";
            }

            if (handler == null) {
                return "Error: Test process failed to start for " + configName;
            }

            // Wait for the process to finish (up to 120s) — off EDT
            if (!handler.waitFor(120_000)) {
                return "Tests timed out after 120 seconds: " + configName;
            }

            int exitCode = handler.getExitCode() != null ? handler.getExitCode() : -1;

            String summary = (exitCode == 0 ? "\u2705 Tests PASSED" : "\u274C Tests FAILED (exit code " + exitCode + ")")
                + " — " + configName;

            String testOutput = collectTestRunOutput(configName);
            return testOutput.isEmpty() ? summary + "\nResults are visible in the IntelliJ test runner panel." : summary + "\n" + testOutput;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("tryRunJUnitNatively failed", e);
            return null;
        } catch (Exception e) {
            LOG.warn("tryRunJUnitNatively failed", e);
            return null;
        }
    }

    /**
     * Resolve wildcard target to matching test class FQNs and create a JUnit "pattern" config.
     */
    private String tryRunJUnitPattern(String target) {
        try {
            var junitType = findJUnitConfigurationType();
            if (junitType == null) return null;

            // Resolve matching test classes from the project index
            List<String> matchingClasses = ReadAction.compute(() -> {
                List<String> classes = new ArrayList<>();
                ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
                fileIndex.iterateContent(vf -> {
                    if (!fileIndex.isInTestSourceContent(vf)) return true;
                    if (vf.isDirectory()) return true;
                    String name = vf.getName();
                    if (!name.endsWith(ToolUtils.JAVA_EXTENSION) && !name.endsWith(".kt")) return true;
                    String simpleName = name.substring(0, name.lastIndexOf('.'));
                    if (ToolUtils.doesNotMatchGlob(simpleName, target)) return true;

                    PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                    if (psiFile == null) return true;
                    // Extract FQN from the PSI file
                    String fqn = extractClassFqn(psiFile, simpleName);
                    if (fqn != null) classes.add(fqn);
                    return classes.size() < 200;
                });
                return classes;
            });

            if (matchingClasses.isEmpty()) return null;

            String configName = "Test: " + target + " (" + matchingClasses.size() + " classes)";

            CompletableFuture<ProcessHandler> handlerFuture = new CompletableFuture<>();
            var connection = project.getMessageBus().connect();
            connection.subscribe(ExecutionManager.EXECUTION_TOPIC, new com.intellij.execution.ExecutionListener() {
                @Override
                public void processStarted(@NotNull String executorId,
                                           @NotNull com.intellij.execution.runners.ExecutionEnvironment env,
                                           @NotNull ProcessHandler handler) {
                    if (env.getRunnerAndConfigurationSettings() != null
                        && configName.equals(env.getRunnerAndConfigurationSettings().getName())) {
                        handlerFuture.complete(handler);
                        connection.disconnect();
                    }
                }

                @Override
                public void processNotStarted(@NotNull String executorId,
                                              @NotNull com.intellij.execution.runners.ExecutionEnvironment env) {
                    if (env.getRunnerAndConfigurationSettings() != null
                        && configName.equals(env.getRunnerAndConfigurationSettings().getName())) {
                        handlerFuture.complete(null);
                        connection.disconnect();
                    }
                }
            });

            CompletableFuture<String> launchFuture = new CompletableFuture<>();
            EdtUtil.invokeLater(() -> {
                try {
                    RunManager runManager = RunManager.getInstance(project);
                    var factory = junitType.getConfigurationFactories()[0];
                    var settings = runManager.createConfiguration(configName, factory);
                    RunConfiguration config = settings.getConfiguration();

                    // Configure as pattern-based test
                    var getData = config.getClass().getMethod("getPersistentData");
                    Object data = getData.invoke(config);
                    data.getClass().getField("TEST_OBJECT").set(data, TEST_TYPE_PATTERN);
                    data.getClass().getField("PATTERNS").set(data,
                        new java.util.LinkedHashSet<>(matchingClasses));

                    // Validate before running — prevents edit-config dialog on errors
                    try {
                        config.checkConfiguration();
                    } catch (com.intellij.execution.configurations.RuntimeConfigurationException e) {
                        launchFuture.complete("Error: Invalid pattern config: " + e.getLocalizedMessage());
                        return;
                    }

                    settings.setTemporary(true);
                    runManager.addConfiguration(settings);

                    var executor = DefaultRunExecutor.getRunExecutorInstance();
                    var envBuilder = ExecutionEnvironmentBuilder.createOrNull(executor, settings);
                    if (envBuilder == null) {
                        launchFuture.complete("Error: Cannot create execution environment");
                        return;
                    }
                    ExecutionManager.getInstance(project).restartRunProfile(envBuilder.build());
                    launchFuture.complete(null);
                } catch (Exception e) {
                    LOG.warn("Failed to run JUnit pattern config", e);
                    launchFuture.complete("launch_failed");
                }
            });

            String launchError = launchFuture.get(10, TimeUnit.SECONDS);
            if (launchError != null) {
                connection.disconnect();
                return "launch_failed".equals(launchError) ? null : launchError;
            }

            ProcessHandler handler;
            try {
                handler = handlerFuture.get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                connection.disconnect();
                return "Started tests via IntelliJ JUnit runner: " + configName
                    + "\nCould not capture process handle. Check the Run panel for results.";
            }

            if (handler == null) {
                return "Error: Test process failed to start for " + configName;
            }

            if (!handler.waitFor(120_000)) {
                return "Tests timed out after 120 seconds: " + configName;
            }

            int exitCode = handler.getExitCode() != null ? handler.getExitCode() : -1;

            String summary = (exitCode == 0 ? "\u2705 Tests PASSED" : "\u274C Tests FAILED (exit code " + exitCode + ")")
                + " \u2014 " + configName;

            String testOutput = collectTestRunOutput(configName);
            return testOutput.isEmpty() ? summary + "\nResults are visible in the IntelliJ test runner panel." : summary + "\n" + testOutput;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("tryRunJUnitPattern failed", e);
            return null;
        } catch (Exception e) {
            LOG.warn("tryRunJUnitPattern failed", e);
            return null;
        }
    }

    private String extractClassFqn(PsiFile psiFile, String simpleName) {
        try {
            // Try to get the package from the file
            var getPackageName = psiFile.getClass().getMethod("getPackageName");
            String pkg = (String) getPackageName.invoke(psiFile);
            return pkg != null && !pkg.isEmpty() ? pkg + "." + simpleName : simpleName;
        } catch (NoSuchMethodException e) {
            // Kotlin files or files without getPackageName — try text-based extraction
            String text = psiFile.getText();
            var matcher = java.util.regex.Pattern.compile("^package\\s+([\\w.]+)").matcher(text);
            if (matcher.find()) {
                return matcher.group(1) + "." + simpleName;
            }
            return simpleName;
        } catch (Exception e) {
            return simpleName;
        }
    }

    /**
     * Collect test results from the IntelliJ Run panel after a test run completes.
     * Looks up the RunContentDescriptor by config name and extracts test tree + console output.
     */
    private String collectTestRunOutput(String configName) {
        try {
            var manager = com.intellij.execution.ui.RunContentManager.getInstance(project);
            var descriptors = new ArrayList<>(manager.getAllDescriptors());

            com.intellij.execution.ui.RunContentDescriptor target = null;
            for (var d : descriptors) {
                if (d.getDisplayName() != null && d.getDisplayName().contains(configName)) {
                    target = d;
                    break;
                }
            }
            if (target == null) return "";

            var console = target.getExecutionConsole();
            if (console == null) return "";

            // Try SMTRunnerConsoleView (test tree)
            try {
                var getResultsViewer = console.getClass().getMethod("getResultsViewer");
                var viewer = getResultsViewer.invoke(console);
                if (viewer != null) {
                    var getAllTests = viewer.getClass().getMethod("getAllTests");
                    var tests = (java.util.List<?>) getAllTests.invoke(viewer);
                    if (tests != null && !tests.isEmpty()) {
                        StringBuilder sb = new StringBuilder("\n=== Test Results ===\n");
                        for (var test : tests) {
                            appendTestDetail(test, sb);
                        }
                        return sb.toString();
                    }
                }
            } catch (NoSuchMethodException ignored) {
                // Not an SMTRunnerConsoleView
            }

            // Fall back to plain console text
            try {
                var getTextMethod = console.getClass().getMethod("getText");
                String text = (String) getTextMethod.invoke(console);
                if (text != null && !text.isBlank()) {
                    return "\n=== Console Output ===\n" + ToolUtils.truncateOutput(text);
                }
            } catch (NoSuchMethodException ignored) {
                // getText not available
            }
        } catch (Exception e) {
            LOG.debug("Failed to collect test run output", e);
        }
        return "";
    }

    private void appendTestDetail(Object test, StringBuilder sb) throws Exception {
        var getName = test.getClass().getMethod("getPresentableName");
        var isPassed = test.getClass().getMethod("isPassed");
        var isDefect = test.getClass().getMethod("isDefect");
        String name = (String) getName.invoke(test);
        boolean passed = (boolean) isPassed.invoke(test);
        boolean defect = (boolean) isDefect.invoke(test);
        String status = passed ? "\u2705 PASSED" : defect ? "\u274C FAILED" : "\u26A0 UNKNOWN";
        sb.append("  ").append(status).append(" ").append(name).append("\n");

        if (defect) {
            try {
                String errorMsg = (String) test.getClass().getMethod("getErrorMessage").invoke(test);
                if (errorMsg != null && !errorMsg.isEmpty()) {
                    sb.append("    Error: ").append(errorMsg).append("\n");
                }
                String stacktrace = (String) test.getClass().getMethod("getStacktrace").invoke(test);
                if (stacktrace != null && !stacktrace.isEmpty()) {
                    sb.append("    Stacktrace:\n").append(stacktrace).append("\n");
                }
            } catch (NoSuchMethodException ignored) {
                // Method not available on this test result type
            }
        }
    }

    /**
     * Create and run a Gradle run configuration for tests (instead of shelling out).
     * This gives proper IntelliJ Run panel integration with test tree UI.
     */
    private String runTestsViaGradleConfig(String target, String module) {
        try {
            String taskPrefix = module.isEmpty() ? "" : ":" + module + ":";
            String configName = "Gradle Test: " + target;

            CompletableFuture<ProcessHandler> handlerFuture = new CompletableFuture<>();
            var connection = project.getMessageBus().connect();
            connection.subscribe(ExecutionManager.EXECUTION_TOPIC, new com.intellij.execution.ExecutionListener() {
                @Override
                public void processStarted(@NotNull String executorId,
                                           @NotNull com.intellij.execution.runners.ExecutionEnvironment env,
                                           @NotNull ProcessHandler handler) {
                    if (env.getRunnerAndConfigurationSettings() != null
                        && configName.equals(env.getRunnerAndConfigurationSettings().getName())) {
                        handlerFuture.complete(handler);
                        connection.disconnect();
                    }
                }

                @Override
                public void processNotStarted(@NotNull String executorId,
                                              @NotNull com.intellij.execution.runners.ExecutionEnvironment env) {
                    if (env.getRunnerAndConfigurationSettings() != null
                        && configName.equals(env.getRunnerAndConfigurationSettings().getName())) {
                        handlerFuture.complete(null);
                        connection.disconnect();
                    }
                }
            });

            CompletableFuture<String> launchFuture = new CompletableFuture<>();
            EdtUtil.invokeLater(() -> {
                try {
                    String error = createAndRunGradleTestConfig(configName, taskPrefix, target);
                    launchFuture.complete(error);
                } catch (Exception e) {
                    LOG.warn("Failed to create Gradle test config", e);
                    launchFuture.complete("launch_failed");
                }
            });

            String launchError = launchFuture.get(10, TimeUnit.SECONDS);
            if (launchError != null) {
                connection.disconnect();
                if ("launch_failed".equals(launchError)) {
                    return "Error: Failed to create Gradle test run configuration for: " + target;
                }
                return launchError;
            }

            ProcessHandler handler;
            try {
                handler = handlerFuture.get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                connection.disconnect();
                return "Started tests via Gradle run configuration: " + configName
                    + "\nCould not capture process handle. Check the Run panel for results.";
            }

            if (handler == null) {
                return "Error: Test process failed to start for " + configName;
            }

            if (!handler.waitFor(120_000)) {
                return "Tests timed out after 120 seconds: " + configName;
            }

            int exitCode = handler.getExitCode() != null ? handler.getExitCode() : -1;
            String basePath = project.getBasePath();
            if (basePath != null) {
                String xmlResults = parseJunitXmlResults(basePath, module);
                if (!xmlResults.isEmpty()) return xmlResults;
            }

            String summary = (exitCode == 0 ? "\u2705 Tests PASSED" : "\u274C Tests FAILED (exit code " + exitCode + ")")
                + " \u2014 " + configName;

            String testOutput = collectTestRunOutput(configName);
            return testOutput.isEmpty() ? summary : summary + "\n" + testOutput;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Test execution interrupted";
        } catch (Exception e) {
            LOG.warn("runTestsViaGradleConfig failed", e);
            return "Error: Failed to run tests via Gradle config: " + e.getMessage();
        }
    }

    private String createAndRunGradleTestConfig(String configName, String taskPrefix, String target) {
        try {
            RunManager runManager = RunManager.getInstance(project);

            // Find Gradle configuration type
            com.intellij.execution.configurations.ConfigurationType gradleType = null;
            for (var ct : com.intellij.execution.configurations.ConfigurationType.CONFIGURATION_TYPE_EP.getExtensionList()) {
                if ("GradleRunConfiguration".equals(ct.getId()) || ct.getDisplayName().contains("Gradle")) {
                    gradleType = ct;
                    break;
                }
            }

            if (gradleType == null) {
                return "Error: Gradle run configuration type not available";
            }

            var factory = gradleType.getConfigurationFactories()[0];
            var settings = runManager.createConfiguration(configName, factory);
            RunConfiguration config = settings.getConfiguration();

            // Configure Gradle settings via reflection
            var getSettings = config.getClass().getMethod("getSettings");
            Object gradleSettings = getSettings.invoke(config);

            // Set task names
            var setTaskNames = gradleSettings.getClass().getMethod("setTaskNames", List.class);
            setTaskNames.invoke(gradleSettings, List.of(taskPrefix + "test"));

            // Set script parameters (--tests pattern)
            var setScriptParameters = gradleSettings.getClass().getMethod("setScriptParameters", String.class);
            setScriptParameters.invoke(gradleSettings, "--tests " + target);

            // Set external project path
            String basePath = project.getBasePath();
            if (basePath != null) {
                var setExternalProjectPath = gradleSettings.getClass().getMethod("setExternalProjectPath", String.class);
                setExternalProjectPath.invoke(gradleSettings, basePath);
            }

            settings.setTemporary(true);
            runManager.addConfiguration(settings);

            var executor = DefaultRunExecutor.getRunExecutorInstance();
            var envBuilder = ExecutionEnvironmentBuilder.createOrNull(executor, settings);
            if (envBuilder == null) {
                return "Error: Cannot create execution environment for Gradle test";
            }

            ExecutionManager.getInstance(project).restartRunProfile(envBuilder.build());
            return null;
        } catch (Exception e) {
            LOG.warn("createAndRunGradleTestConfig failed", e);
            return "launch_failed";
        }
    }

    private String[] parseTestTarget(String target) {
        String testClass = target;
        String testMethod = null;
        int lastDot = target.lastIndexOf('.');
        if (lastDot > 0) {
            String possibleMethod = target.substring(lastDot + 1);
            String possibleClass = target.substring(0, lastDot);
            if (!possibleMethod.isEmpty() && Character.isLowerCase(possibleMethod.charAt(0))) {
                testClass = possibleClass;
                testMethod = possibleMethod;
            }
        }
        return new String[]{testClass, testMethod};
    }

    /**
     * EDT-only: create the JUnit config and launch it. Returns null on success, error string on failure.
     */
    private String launchJUnitConfig(
        com.intellij.execution.configurations.ConfigurationType junitType,
        String resolvedClass, String resolvedMethod, Module resolvedModule,
        String configName) throws Exception {
        RunManager runManager = RunManager.getInstance(project);
        var factory = junitType.getConfigurationFactories()[0];
        var settings = runManager.createConfiguration(configName, factory);
        RunConfiguration config = settings.getConfiguration();

        configureJUnitTestData(config, resolvedClass, resolvedMethod, resolvedModule);

        // Validate config before running — prevents edit-config dialog on errors
        try {
            config.checkConfiguration();
        } catch (com.intellij.execution.configurations.RuntimeConfigurationException e) {
            return "Error: Invalid test configuration: " + e.getLocalizedMessage();
        }

        settings.setTemporary(true);
        runManager.addConfiguration(settings);

        var executor = DefaultRunExecutor.getRunExecutorInstance();
        var envBuilder = ExecutionEnvironmentBuilder.createOrNull(executor, settings);
        if (envBuilder == null) {
            return "Error: Cannot create execution environment for JUnit test";
        }

        var env = envBuilder.build();
        ExecutionManager.getInstance(project).restartRunProfile(env);
        return null;
    }

    @SuppressWarnings("java:S3011")
    // reflection on JUnit config fields is required since API is not available at compile time
    private static void configureJUnitTestData(RunConfiguration config, String resolvedClass,
                                               String resolvedMethod, Module resolvedModule) throws Exception {
        var getData = config.getClass().getMethod("getPersistentData");
        Object data = getData.invoke(config);
        data.getClass().getField("MAIN_CLASS_NAME").set(data, resolvedClass);
        if (resolvedMethod != null) {
            data.getClass().getField("METHOD_NAME").set(data, resolvedMethod);
            data.getClass().getField("TEST_OBJECT").set(data, TEST_TYPE_METHOD);
        } else {
            data.getClass().getField("TEST_OBJECT").set(data, TEST_TYPE_CLASS);
        }

        if (resolvedModule != null) {
            try {
                var setModule = config.getClass().getMethod("setModule", Module.class);
                setModule.invoke(config, resolvedModule);
            } catch (NoSuchMethodException ignored) {
                // Method not available in this version
            }
        }
    }

    private static JsonObject createJsonWithName(String name) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", name);
        return obj;
    }

    private boolean hasTestAnnotation(PsiElement element) {
        return hasTestAnnotationViaReflection(element) || hasTestAnnotationViaText(element);
    }

    private boolean hasTestAnnotationViaReflection(PsiElement element) {
        try {
            var getModifierList = element.getClass().getMethod("getModifierList");
            Object modList = getModifierList.invoke(element);
            if (modList != null) {
                var getAnnotations = modList.getClass().getMethod("getAnnotations");
                Object[] annotations = (Object[]) getAnnotations.invoke(modList);
                for (Object anno : annotations) {
                    var getQualifiedName = anno.getClass().getMethod("getQualifiedName");
                    String qname = (String) getQualifiedName.invoke(anno);
                    if (qname != null && (qname.endsWith(".Test")
                        || qname.endsWith(".ParameterizedTest")
                        || qname.endsWith(".RepeatedTest"))) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            // Reflection may not work for all element types
        }
        return false;
    }

    private boolean hasTestAnnotationViaText(PsiElement element) {
        // Text-based fallback (catches Kotlin and edge cases)
        PsiElement prev = element.getPrevSibling();
        int depth = 0;
        while (prev != null && depth < 5) {
            // Stop at previous method/class/field declaration (don't look past it)
            if (prev instanceof PsiNamedElement && ToolUtils.classifyElement(prev) != null) break;
            String text = prev.getText().trim();
            if (text.startsWith("@Test") || text.startsWith("@ParameterizedTest")
                || text.startsWith("@RepeatedTest")
                || text.startsWith("@org.junit")) {
                return true;
            }
            prev = prev.getPrevSibling();
            depth++;
        }
        return false;
    }

    private String getContainingClassName(PsiElement element) {
        PsiElement parent = element.getParent();
        while (parent != null) {
            if (parent instanceof PsiNamedElement named) {
                String type = ToolUtils.classifyElement(parent);
                if (ToolUtils.ELEMENT_TYPE_CLASS.equals(type)) return named.getName();
            }
            parent = parent.getParent();
        }
        return "UnknownClass";
    }

    private String parseJunitXmlResults(String basePath, String module) {
        List<Path> reportDirs = findTestReportDirs(basePath, module);
        if (reportDirs.isEmpty()) return "";

        int totalTests = 0;
        int totalFailed = 0;
        int totalErrors = 0;
        int totalSkipped = 0;
        double totalTime = 0;
        List<String> failures = new ArrayList<>();

        for (Path reportDir : reportDirs) {
            try (var xmlFiles = Files.list(reportDir)) {
                for (Path xmlFile : xmlFiles.filter(p -> p.toString().endsWith(".xml")).toList()) {
                    TestSuiteResult result = parseTestSuiteXml(xmlFile);
                    if (result == null) continue;
                    totalTests += result.tests;
                    totalFailed += result.failed;
                    totalErrors += result.errors;
                    totalSkipped += result.skipped;
                    totalTime += result.time;
                    failures.addAll(result.failures);
                }
            } catch (IOException ignored) {
                // IO errors during directory listing are non-fatal
            }
        }

        if (totalTests == 0) return "";
        return formatTestResults(totalTests, totalFailed, totalErrors, totalSkipped, totalTime, failures);
    }

    private List<Path> findTestReportDirs(String basePath, String module) {
        List<Path> reportDirs = new ArrayList<>();
        if (module.isEmpty()) {
            try (var dirs = Files.walk(Path.of(basePath), 4)) {
                dirs.filter(p -> p.endsWith("test-results/test") && Files.isDirectory(p))
                    .forEach(reportDirs::add);
            } catch (IOException ignored) {
                // Directory walk errors are non-fatal
            }
        } else {
            Path dir = Path.of(basePath, module, ToolUtils.BUILD_DIR, "test-results", "test");
            if (Files.isDirectory(dir)) reportDirs.add(dir);
        }
        return reportDirs;
    }

    private record TestSuiteResult(int tests, int failed, int errors, int skipped,
                                   double time, List<String> failures) {
    }

    private TestSuiteResult parseTestSuiteXml(Path xmlFile) {
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            //noinspection HttpUrlsUsage - XML feature URI, not an actual URL
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var doc = dbf.newDocumentBuilder().parse(xmlFile.toFile());
            var suites = doc.getElementsByTagName("testsuite");

            int tests = 0;
            int failed = 0;
            int errors = 0;
            int skipped = 0;
            double time = 0;
            List<String> failures = new ArrayList<>();

            for (int i = 0; i < suites.getLength(); i++) {
                var suite = suites.item(i);
                tests += intAttr(suite, "tests");
                failed += intAttr(suite, "failures");
                errors += intAttr(suite, "errors");
                skipped += intAttr(suite, "skipped");
                time += doubleAttr(suite, "time");
                collectFailureDetails((org.w3c.dom.Element) suite, failures);
            }
            return new TestSuiteResult(tests, failed, errors, skipped, time, failures);
        } catch (Exception ignored) {
            // XML parsing errors are non-fatal
            return null;
        }
    }

    private static void collectFailureDetails(org.w3c.dom.Element suite, List<String> failures) {
        var testcases = suite.getElementsByTagName("testcase");
        for (int j = 0; j < testcases.getLength(); j++) {
            var tc = testcases.item(j);
            var failNodes = ((org.w3c.dom.Element) tc).getElementsByTagName("failure");
            if (failNodes.getLength() > 0) {
                String tcName = tc.getAttributes().getNamedItem("name").getNodeValue();
                String cls = tc.getAttributes().getNamedItem("classname").getNodeValue();
                String msg = failNodes.item(0).getAttributes().getNamedItem(PARAM_MESSAGE).getNodeValue();
                failures.add(String.format("  \u274C %s.%s: %s", cls, tcName, msg));
            }
        }
    }

    private static String formatTestResults(int totalTests, int totalFailed, int totalErrors,
                                            int totalSkipped, double totalTime, List<String> failures) {
        int passed = totalTests - totalFailed - totalErrors - totalSkipped;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Test Results: %d tests, %d passed, %d failed, %d errors, %d skipped (%.1fs)%n",
            totalTests, passed, totalFailed, totalErrors, totalSkipped, totalTime));

        if (!failures.isEmpty()) {
            sb.append("\nFailures:\n");
            failures.forEach(f -> sb.append(f).append("\n"));
        }
        return sb.toString().trim();
    }

    @SuppressWarnings("java:S3518") // division by zero is prevented by Math.max(1, ...)
    private String parseJacocoXml(Path xmlPath, String fileFilter) {
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            //noinspection HttpUrlsUsage - XML feature URI, not an actual URL
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var doc = dbf.newDocumentBuilder().parse(xmlPath.toFile());
            var packages = doc.getElementsByTagName("package");
            List<String> lines = new ArrayList<>();
            int totalLines = 0;
            int coveredLines = 0;

            for (int i = 0; i < packages.getLength(); i++) {
                var pkg = (org.w3c.dom.Element) packages.item(i);
                var classes = pkg.getElementsByTagName(TEST_TYPE_CLASS);
                for (int j = 0; j < classes.getLength(); j++) {
                    var cls = (org.w3c.dom.Element) classes.item(j);
                    String name = cls.getAttribute("name").replace('/', '.');
                    if (!fileFilter.isEmpty() && !name.contains(fileFilter)) continue;

                    var coverage = processClassCoverage(cls);
                    if (coverage != null) {
                        totalLines += coverage.total;
                        coveredLines += coverage.covered;
                        lines.add(String.format("  %s: %.1f%% (%d/%d lines)",
                            name, coverage.percentage, coverage.covered, coverage.total));
                    }
                }
            }

            if (lines.isEmpty()) return "No line coverage data in JaCoCo report";
            double totalPct = coveredLines * 100.0 / Math.max(1, totalLines);
            return String.format("Coverage: %.1f%% overall (%d/%d lines)%n%n%s",
                totalPct, coveredLines, totalLines, String.join("\n", lines));
        } catch (Exception e) {
            return "Error parsing JaCoCo report: " + e.getMessage();
        }
    }

    @SuppressWarnings("java:S3518") // division by zero is prevented by Math.max(1, ...)
    private CoverageData processClassCoverage(org.w3c.dom.Element cls) {
        var counters = cls.getElementsByTagName("counter");
        for (int k = 0; k < counters.getLength(); k++) {
            var counter = counters.item(k);
            if ("LINE".equals(counter.getAttributes().getNamedItem("type").getNodeValue())) {
                int missed = intAttr(counter, "missed");
                int covered = intAttr(counter, "covered");
                int total = missed + covered;
                double pct = covered * 100.0 / Math.max(1, total);
                return new CoverageData(covered, total, pct);
            }
        }
        return null;
    }

    private record CoverageData(int covered, int total, double percentage) {
    }

    private static int intAttr(org.w3c.dom.Node node, String attr) {
        var item = node.getAttributes().getNamedItem(attr);
        return item != null ? Integer.parseInt(item.getNodeValue()) : 0;
    }

    @SuppressWarnings("SameParameterValue") // Utility method mirrors intAttr, kept parameterized for consistency
    private static double doubleAttr(org.w3c.dom.Node node, String attr) {
        var item = node.getAttributes().getNamedItem(attr);
        return item != null ? Double.parseDouble(item.getNodeValue()) : 0.0;
    }
}
