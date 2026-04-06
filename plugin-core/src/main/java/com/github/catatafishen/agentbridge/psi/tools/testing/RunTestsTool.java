package com.github.catatafishen.agentbridge.psi.tools.testing;

import com.github.catatafishen.agentbridge.psi.ClassResolverUtil;
import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.TestResultRenderer;
import com.google.gson.JsonObject;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.RunManager;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs tests by class, method, or wildcard pattern.
 * Uses IntelliJ's built-in JUnit runner when possible; falls back to Gradle for unresolvable targets.
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
public final class RunTestsTool extends TestingTool {

    private static final Logger LOG = Logger.getInstance(RunTestsTool.class);

    private static final String JSON_MODULE = "module";
    private static final String PARAM_TARGET = "target";
    private static final String PARAM_MESSAGE = "message";
    private static final String TEST_TYPE_METHOD = "method";
    private static final String TEST_TYPE_CLASS = "class";
    private static final String TEST_TYPE_PATTERN = "pattern";
    private static final String JUNIT_TYPE_ID = "junit";
    private static final String LAUNCH_FAILED = "launch_failed";
    private static final String TESTS_PASSED = "Tests PASSED";
    private static final String TESTS_FAILED_PREFIX = "Tests FAILED (exit code ";
    private static final String NO_PROCESS_HANDLE_MSG =
        "\nCould not capture process handle. Check the Run panel for results.";
    private static final String FIELD_TEST_OBJECT = "TEST_OBJECT";
    private static final String ERROR_PROCESS_FAILED_TO_START = "Error: Test process failed to start for ";
    private static final String ERROR_TESTS_TIMED_OUT = "Tests timed out after 120 seconds: ";
    private static final String STARTED_TESTS_MSG = "Started tests via IntelliJ JUnit runner: ";
    private static final String RESULTS_IN_RUNNER_PANEL = "\nResults are visible in the IntelliJ test runner panel.";

    public RunTestsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "run_tests";
    }

    @Override
    public @NotNull String displayName() {
        return "Run Tests";
    }

    @Override
    public @NotNull String description() {
        return "Run tests by class, method, or wildcard pattern. Uses IntelliJ's built-in test runner; falls back to Gradle for unresolvable targets";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Run tests: {target}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {PARAM_TARGET, TYPE_STRING, "Test target: fully qualified class class.method (e.g., 'MyTest.testFoo'), or pattern with wildcards (e.g., '*Test')"},
            {JSON_MODULE, TYPE_STRING, "Optional module name (e.g., 'plugin-core')", ""}
        }, PARAM_TARGET);
    }

    @Override
    public @NotNull Object resultRenderer() {
        return TestResultRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String target = args.get(PARAM_TARGET).getAsString();
        String module = args.has(JSON_MODULE) ? args.get(JSON_MODULE).getAsString() : "";
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;

        String configResult = tryRunTestConfig(target);
        if (configResult != null) return configResult;

        if (target.contains("*")) {
            String patternResult = tryRunJUnitPattern(target);
            if (patternResult != null) return patternResult;

            return runTestsViaGradleConfig(target, module);
        }

        String junitResult = tryRunJUnitNatively(target);
        if (junitResult != null) return junitResult;

        return runTestsViaGradleConfig(target, module);
    }

    // ── Run configuration lookup ─────────────────────────────

    private ConfigurationType findJUnitConfigurationType() {
        return PlatformApiCompat.findConfigurationTypeBySearch(JUNIT_TYPE_ID);
    }

    private String tryRunTestConfig(String target) {
        try {
            var configs = RunManager.getInstance(project).getAllSettings();
            for (var settings : configs) {
                String typeName = settings.getType().getDisplayName().toLowerCase();
                if ((typeName.contains(JUNIT_TYPE_ID) || typeName.contains("test"))
                    && settings.getName().contains(target)) {
                    return runTestConfigAndWait(settings);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("tryRunTestConfig interrupted", e);
        } catch (Exception ignored) {
            // Config lookup errors are non-fatal; fall through to other runners
        }
        return null;
    }

    private String runTestConfigAndWait(com.intellij.execution.RunnerAndConfigurationSettings settings) throws Exception {
        String configName = settings.getName();

        CompletableFuture<ProcessHandler> handlerFuture = new CompletableFuture<>();
        AtomicReference<Runnable> disconnect = new AtomicReference<>(() -> {
        });
        disconnect.set(subscribeToExecution(configName, handlerFuture, disconnect));

        CompletableFuture<String> launchFuture = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                var executor = DefaultRunExecutor.getRunExecutorInstance();
                var envBuilder = ExecutionEnvironmentBuilder.createOrNull(executor, settings);
                if (envBuilder == null) {
                    launchFuture.complete("Cannot create execution environment for: " + configName);
                    return;
                }
                ExecutionManager.getInstance(project).restartRunProfile(envBuilder.build());
                launchFuture.complete(null);
            } catch (Exception e) {
                LOG.warn("Failed to run test config: " + configName, e);
                launchFuture.complete(LAUNCH_FAILED);
            }
        });

        return awaitTestExecution(configName, launchFuture, handlerFuture, disconnect);
    }

    // ── Native JUnit runner ──────────────────────────────────

    private String tryRunJUnitNatively(String target) {
        try {
            var junitType = findJUnitConfigurationType();
            if (junitType == null) return null;

            String[] parsed = parseTestTarget(target);
            String testClass = parsed[0];
            String testMethod = parsed[1];

            ClassResolverUtil.ClassInfo classInfo = ClassResolverUtil.resolveClass(project, testClass);
            if (classInfo.fqn() == null) return null;

            final String resolvedClass = classInfo.fqn();
            final Module resolvedModule = classInfo.module();
            String simpleName = resolvedClass.substring(resolvedClass.lastIndexOf('.') + 1);
            String configName = "Test: " + (testMethod != null
                ? simpleName + "." + testMethod : simpleName);

            CompletableFuture<ProcessHandler> handlerFuture = new CompletableFuture<>();
            AtomicReference<Runnable> disconnect = new AtomicReference<>(() -> {
            });
            disconnect.set(subscribeToExecution(configName, handlerFuture, disconnect));

            CompletableFuture<String> launchFuture = new CompletableFuture<>();
            EdtUtil.invokeLater(() -> {
                try {
                    String error = launchJUnitConfig(
                        junitType, resolvedClass, testMethod, resolvedModule, configName);
                    launchFuture.complete(error);
                } catch (Exception e) {
                    LOG.warn("Failed to run JUnit natively, will fall back to Gradle", e);
                    launchFuture.complete(LAUNCH_FAILED);
                }
            });

            return awaitTestExecution(configName, launchFuture, handlerFuture, disconnect);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("tryRunJUnitNatively failed", e);
            return null;
        } catch (Exception e) {
            LOG.warn("tryRunJUnitNatively failed", e);
            return null;
        }
    }

    // ── JUnit pattern runner ─────────────────────────────────

    private String tryRunJUnitPattern(String target) {
        try {
            var junitType = findJUnitConfigurationType();
            if (junitType == null) return null;

            List<String> matchingClasses = resolveMatchingTestClasses(target);
            if (matchingClasses.isEmpty()) return null;

            String configName = "Test: " + target + " (" + matchingClasses.size() + " classes)";

            CompletableFuture<ProcessHandler> handlerFuture = new CompletableFuture<>();
            AtomicReference<Runnable> disconnect = new AtomicReference<>(() -> {
            });
            disconnect.set(subscribeToExecution(configName, handlerFuture, disconnect));

            CompletableFuture<String> launchFuture = new CompletableFuture<>();
            EdtUtil.invokeLater(() -> launchPatternConfig(
                junitType, configName, matchingClasses, launchFuture));

            return awaitTestExecution(configName, launchFuture, handlerFuture, disconnect);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("tryRunJUnitPattern failed", e);
            return null;
        } catch (Exception e) {
            LOG.warn("tryRunJUnitPattern failed", e);
            return null;
        }
    }

    private List<String> resolveMatchingTestClasses(String target) {
        return ApplicationManager.getApplication().runReadAction((Computable<List<String>>) () -> {
            List<String> classes = new ArrayList<>();
            ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
            fileIndex.iterateContent(vf -> processTestFile(vf, fileIndex, target, classes));
            return classes;
        });
    }

    private boolean processTestFile(com.intellij.openapi.vfs.VirtualFile vf,
                                    ProjectFileIndex fileIndex, String target, List<String> classes) {
        if (!fileIndex.isInTestSourceContent(vf)) return true;
        if (vf.isDirectory()) return true;
        String name = vf.getName();
        if (!name.endsWith(ToolUtils.JAVA_EXTENSION) && !name.endsWith(".kt")) return true;
        String simpleName = name.substring(0, name.lastIndexOf('.'));
        if (ToolUtils.doesNotMatchGlob(simpleName, target)) return true;
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return true;
        String fqn = extractClassFqn(psiFile, simpleName);
        if (fqn != null) classes.add(fqn);
        return classes.size() < 200;
    }

    @SuppressWarnings("java:S3011")
    // Required: accessing internal JUnit run config fields via reflection — no public API exists
    private void launchPatternConfig(ConfigurationType junitType, String configName,
                                     List<String> matchingClasses,
                                     CompletableFuture<String> launchFuture) {
        try {
            RunManager runManager = RunManager.getInstance(project);
            var factory = junitType.getConfigurationFactories()[0];
            var settings = runManager.createConfiguration(configName, factory);
            RunConfiguration config = settings.getConfiguration();

            var getData = config.getClass().getMethod("getPersistentData");
            Object data = getData.invoke(config);
            data.getClass().getField(FIELD_TEST_OBJECT).set(data, TEST_TYPE_PATTERN);
            data.getClass().getField("PATTERNS").set(data,
                new java.util.LinkedHashSet<>(matchingClasses));

            String configError = checkRunConfiguration(config);
            if (configError != null) {
                launchFuture.complete(configError);
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
            launchFuture.complete(LAUNCH_FAILED);
        }
    }

    @Nullable
    private static String checkRunConfiguration(RunConfiguration config) {
        try {
            config.checkConfiguration();
            return null;
        } catch (com.intellij.execution.configurations.RuntimeConfigurationException e) {
            return "Error: Invalid pattern config: " + e.getLocalizedMessage();
        }
    }

    // ── Gradle runner ────────────────────────────────────────

    private String runTestsViaGradleConfig(String target, String module) {
        try {
            String taskPrefix = module.isEmpty() ? "" : ":" + module + ":";
            String configName = "Gradle Test: " + target;

            CompletableFuture<ProcessHandler> handlerFuture = new CompletableFuture<>();
            AtomicReference<Runnable> disconnect = new AtomicReference<>(() -> {
            });
            disconnect.set(subscribeToExecution(configName, handlerFuture, disconnect));

            CompletableFuture<String> launchFuture = new CompletableFuture<>();
            EdtUtil.invokeLater(() -> {
                try {
                    String error = createAndRunGradleTestConfig(configName, taskPrefix, target);
                    launchFuture.complete(error);
                } catch (Exception e) {
                    LOG.warn("Failed to create Gradle test config", e);
                    launchFuture.complete(LAUNCH_FAILED);
                }
            });

            return awaitGradleTestExecution(
                configName, launchFuture, handlerFuture, disconnect, target, module);
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

            ConfigurationType gradleType =
                PlatformApiCompat.findConfigurationTypeBySearch("Gradle");

            if (gradleType == null) {
                return "Error: Gradle run configuration type not available. "
                    + "For non-Gradle projects, use create_run_configuration with the appropriate type "
                    + "(e.g., 'maven' for Maven, 'npm' for Node.js) or run_command to invoke the build tool directly.";
            }

            var factory = gradleType.getConfigurationFactories()[0];
            var settings = runManager.createConfiguration(configName, factory);
            RunConfiguration config = settings.getConfiguration();

            var getSettings = config.getClass().getMethod("getSettings");
            Object gradleSettings = getSettings.invoke(config);

            var setTaskNames = gradleSettings.getClass().getMethod("setTaskNames", List.class);
            setTaskNames.invoke(gradleSettings, List.of(taskPrefix + "test"));

            var setScriptParameters = gradleSettings.getClass().getMethod("setScriptParameters", String.class);
            setScriptParameters.invoke(gradleSettings, "--tests " + target);

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
            return LAUNCH_FAILED;
        }
    }

    // ── Execution lifecycle helpers ──────────────────────────

    private Runnable subscribeToExecution(String configName,
                                          CompletableFuture<ProcessHandler> handlerFuture,
                                          AtomicReference<Runnable> disconnect) {
        return PlatformApiCompat.subscribeExecutionListener(project, new com.intellij.execution.ExecutionListener() {
            @Override
            public void processStarted(@NotNull String executorId,
                                       @NotNull com.intellij.execution.runners.ExecutionEnvironment env,
                                       @NotNull ProcessHandler handler) {
                if (env.getRunnerAndConfigurationSettings() != null
                    && configName.equals(env.getRunnerAndConfigurationSettings().getName())) {
                    handlerFuture.complete(handler);
                    disconnect.get().run();
                }
            }

            @Override
            public void processNotStarted(@NotNull String executorId,
                                          @NotNull com.intellij.execution.runners.ExecutionEnvironment env) {
                if (env.getRunnerAndConfigurationSettings() != null
                    && configName.equals(env.getRunnerAndConfigurationSettings().getName())) {
                    handlerFuture.complete(null);
                    disconnect.get().run();
                }
            }
        });
    }

    private String awaitTestExecution(String configName,
                                      CompletableFuture<String> launchFuture,
                                      CompletableFuture<ProcessHandler> handlerFuture,
                                      AtomicReference<Runnable> disconnect) throws Exception {
        String launchError = launchFuture.get(10, TimeUnit.SECONDS);
        if (launchError != null) {
            disconnect.get().run();
            return LAUNCH_FAILED.equals(launchError) ? null : launchError;
        }

        ProcessHandler handler;
        try {
            handler = handlerFuture.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            disconnect.get().run();
            return STARTED_TESTS_MSG + configName + NO_PROCESS_HANDLE_MSG;
        }

        if (handler == null) return ERROR_PROCESS_FAILED_TO_START + configName;
        if (!handler.waitFor(120_000)) return ERROR_TESTS_TIMED_OUT + configName;

        int exitCode = handler.getExitCode() != null ? handler.getExitCode() : -1;
        String summary = (exitCode == 0 ? TESTS_PASSED : TESTS_FAILED_PREFIX + exitCode + ")")
            + " — " + configName;

        String testOutput = collectTestRunOutput(configName);
        return testOutput.isEmpty()
            ? summary + RESULTS_IN_RUNNER_PANEL
            : summary + "\n" + testOutput;
    }

    private String awaitGradleTestExecution(String configName,
                                            CompletableFuture<String> launchFuture,
                                            CompletableFuture<ProcessHandler> handlerFuture,
                                            AtomicReference<Runnable> disconnect,
                                            String target, String module) throws Exception {
        String launchError = launchFuture.get(10, TimeUnit.SECONDS);
        if (launchError != null) {
            disconnect.get().run();
            if (LAUNCH_FAILED.equals(launchError)) {
                return "Error: Failed to create Gradle test run configuration for: " + target;
            }
            return launchError;
        }

        ProcessHandler handler;
        try {
            handler = handlerFuture.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            disconnect.get().run();
            return "Started tests via Gradle run configuration: " + configName + NO_PROCESS_HANDLE_MSG;
        }

        if (handler == null) return ERROR_PROCESS_FAILED_TO_START + configName;
        if (!handler.waitFor(120_000)) return ERROR_TESTS_TIMED_OUT + configName;

        int exitCode = handler.getExitCode() != null ? handler.getExitCode() : -1;
        String basePath = project.getBasePath();
        if (basePath != null) {
            String xmlResults = parseJunitXmlResults(basePath, module);
            if (!xmlResults.isEmpty()) return xmlResults;
        }

        String summary = (exitCode == 0 ? TESTS_PASSED : TESTS_FAILED_PREFIX + exitCode + ")")
            + " — " + configName;
        String testOutput = collectTestRunOutput(configName);
        return testOutput.isEmpty() ? summary : summary + "\n" + testOutput;
    }

    // ── JUnit config helpers ─────────────────────────────────

    private String launchJUnitConfig(
        ConfigurationType junitType,
        String resolvedClass, String resolvedMethod, Module resolvedModule,
        String configName) throws Exception {
        RunManager runManager = RunManager.getInstance(project);
        var factory = junitType.getConfigurationFactories()[0];
        var settings = runManager.createConfiguration(configName, factory);
        RunConfiguration config = settings.getConfiguration();

        configureJUnitTestData(config, resolvedClass, resolvedMethod, resolvedModule);

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
            data.getClass().getField(FIELD_TEST_OBJECT).set(data, TEST_TYPE_METHOD);
        } else {
            data.getClass().getField(FIELD_TEST_OBJECT).set(data, TEST_TYPE_CLASS);
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

    // ── Result parsing helpers ───────────────────────────────

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

    private String extractClassFqn(PsiFile psiFile, String simpleName) {
        try {
            var getPackageName = psiFile.getClass().getMethod("getPackageName");
            String pkg = (String) getPackageName.invoke(psiFile);
            return pkg != null && !pkg.isEmpty() ? pkg + "." + simpleName : simpleName;
        } catch (NoSuchMethodException e) {
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

            String testResults = tryGetTestResults(console);
            if (testResults != null) return testResults;

            String consoleText = tryGetConsoleText(console);
            if (consoleText != null) return consoleText;
        } catch (Exception e) {
            LOG.debug("Failed to collect test run output", e);
        }
        return "";
    }

    @Nullable
    private String tryGetTestResults(Object console) {
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
        } catch (Exception e) {
            LOG.debug("Failed to get test results viewer", e);
        }
        return null;
    }

    @Nullable
    private static String tryGetConsoleText(Object console) {
        try {
            var getTextMethod = console.getClass().getMethod("getText");
            String text = (String) getTextMethod.invoke(console);
            if (text != null && !text.isBlank()) {
                return "\n=== Console Output ===\n" + ToolUtils.truncateOutput(text);
            }
        } catch (ReflectiveOperationException ignored) {
            // getText not available on this console type
        }
        return null;
    }

    private void appendTestDetail(Object test, StringBuilder sb) throws Exception {
        var getName = test.getClass().getMethod("getPresentableName");
        var isPassed = test.getClass().getMethod("isPassed");
        var isDefect = test.getClass().getMethod("isDefect");
        String name = (String) getName.invoke(test);
        boolean passed = (boolean) isPassed.invoke(test);
        boolean defect = (boolean) isDefect.invoke(test);
        String status;
        if (passed) {
            status = "PASSED";
        } else if (defect) {
            status = "FAILED";
        } else {
            status = "UNKNOWN";
        }
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

    // ── JUnit XML result parsing ─────────────────────────────

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
                failures.add(String.format("  %s.%s: %s", cls, tcName, msg));
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
}
