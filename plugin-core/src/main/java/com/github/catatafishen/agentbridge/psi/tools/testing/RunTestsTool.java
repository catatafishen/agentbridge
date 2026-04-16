package com.github.catatafishen.agentbridge.psi.tools.testing;

import com.github.catatafishen.agentbridge.psi.ClassResolverUtil;
import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.TestResultRenderer;
import com.google.gson.JsonObject;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.actions.ConfigurationContext;
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
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.UsageSearchContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs tests by class, method, or wildcard pattern.
 * <p>
 * Uses IntelliJ's {@link ConfigurationContext} for framework-agnostic test detection,
 * falling back to JUnit-specific configuration and Gradle for unresolvable targets.
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
public final class RunTestsTool extends TestingTool {

    private static final Logger LOG = Logger.getInstance(RunTestsTool.class);

    private static final String JSON_MODULE = "module";
    private static final String PARAM_TARGET = "target";
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
        return "Run tests by class, method, or wildcard pattern. Uses IntelliJ's built-in test runner — " +
            "auto-detects the test framework (JUnit, TestNG, pytest, etc.) via ConfigurationContext. " +
            "Falls back to Gradle for unresolvable targets. " +
            "Returns pass/fail counts and failure details. Use list_tests to discover available test targets.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public boolean needsWriteLock() {
        return false;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Run tests: {target}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_TARGET, TYPE_STRING, "Test target: fully qualified class class.method (e.g., 'MyTest.testFoo'), or pattern with wildcards (e.g., '*Test')"),
            Param.optional(JSON_MODULE, TYPE_STRING, "Optional module name (e.g., 'plugin-core')", "")
        );
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

        // Framework-agnostic: resolve the target to a PSI element and use ConfigurationContext
        // to auto-detect the right test framework (JUnit, TestNG, pytest, etc.)
        String contextResult = tryRunViaConfigurationContext(target);
        if (contextResult != null) return contextResult;

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

    // ── Framework-agnostic runner via ConfigurationContext ────

    private String tryRunViaConfigurationContext(String target) {
        try {
            PsiElement testElement = resolveTestPsiElement(target);
            if (testElement == null) return null;

            RunnerAndConfigurationSettings settings = ApplicationManager.getApplication()
                .runReadAction((Computable<RunnerAndConfigurationSettings>) () -> {
                    ConfigurationContext context = new ConfigurationContext(testElement);
                    var configs = context.createConfigurationsFromContext();
                    if (configs == null || configs.isEmpty()) return null;
                    return configs.getFirst().getConfigurationSettings();
                });
            if (settings == null) return null;

            settings.setTemporary(true);
            RunManager.getInstance(project).addConfiguration(settings);
            return runTestConfigAndWait(settings);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("tryRunViaConfigurationContext interrupted", e);
            return null;
        } catch (Exception e) {
            LOG.warn("tryRunViaConfigurationContext failed, falling through to other runners", e);
            return null;
        }
    }

    /**
     * Resolves a test target string (e.g., "MyTest.testFoo" or "MyTest") to the corresponding
     * PSI element in the project. Searches for the class by name, then optionally finds the
     * specified method within it.
     */
    private PsiElement resolveTestPsiElement(String target) {
        return ApplicationManager.getApplication().runReadAction((Computable<PsiElement>) () -> {
            String[] parsed = parseTestTarget(target);
            String testClass = parsed[0];
            String testMethod = parsed[1];
            String searchName = testClass.contains(".")
                ? testClass.substring(testClass.lastIndexOf('.') + 1) : testClass;

            AtomicReference<PsiElement> result = new AtomicReference<>();
            PsiSearchHelper.getInstance(project).processElementsWithWord(
                (element, offset) -> matchTestElement(element, searchName, testMethod, result),
                GlobalSearchScope.projectScope(project),
                searchName,
                UsageSearchContext.IN_CODE,
                true
            );
            return result.get();
        });
    }

    /**
     * Checks if a PSI element matches the searched test class name, and optionally resolves
     * a method within it. Returns false (stop iteration) when a match is found.
     */
    private static boolean matchTestElement(PsiElement element, String searchName, String testMethod,
                                            AtomicReference<PsiElement> result) {
        if (!ToolUtils.ELEMENT_TYPE_CLASS.equals(ToolUtils.classifyElement(element))) return true;
        if (!(element instanceof PsiNamedElement named) || !searchName.equals(named.getName())) return true;

        if (testMethod != null) {
            PsiElement method = findMethodByName(element, testMethod);
            if (method != null) {
                result.set(method);
                return false;
            }
        } else {
            result.set(element);
            return false;
        }
        return true;
    }

    /**
     * Walks the children of a class element to find a method with the given name.
     */
    private static PsiElement findMethodByName(PsiElement classElement, String methodName) {
        for (PsiElement child : classElement.getChildren()) {
            if (child instanceof PsiNamedElement named
                && methodName.equals(named.getName())
                && ToolUtils.ELEMENT_TYPE_METHOD.equals(ToolUtils.classifyElement(child))) {
                return child;
            }
        }
        return null;
    }

    private String runTestConfigAndWait(RunnerAndConfigurationSettings settings) throws Exception {
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
            String configName = buildJUnitConfigName(simpleName, testMethod);

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

            String configName = buildPatternConfigName(target, matchingClasses.size());

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
            var compiledGlob = target.isEmpty() ? null : ToolUtils.compileGlob(target);
            fileIndex.iterateContent(vf -> processTestFile(vf, fileIndex, target, compiledGlob, classes));
            return classes;
        });
    }

    private boolean processTestFile(com.intellij.openapi.vfs.VirtualFile vf,
                                    ProjectFileIndex fileIndex, String target, java.util.regex.Pattern compiledGlob, List<String> classes) {
        if (!fileIndex.isInTestSourceContent(vf)) return true;
        if (vf.isDirectory()) return true;
        String name = vf.getName();
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx <= 0) return true;
        String simpleName = name.substring(0, dotIdx);
        if (ToolUtils.doesNotMatchGlob(simpleName, target, compiledGlob)) return true;
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

            Module fallbackModule = resolveModuleFallback();
            if (fallbackModule != null) {
                try {
                    var setModule = config.getClass().getMethod("setModule", Module.class);
                    setModule.invoke(config, fallbackModule);
                } catch (NoSuchMethodException ignored) {
                    // Method not available in this version
                }
            }

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
            String taskPrefix = buildGradleTaskPrefix(module);
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
        String testOutput = collectTestRunOutput(configName);
        return formatTestSummary(exitCode, configName, testOutput);
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

        String testOutput = collectTestRunOutput(configName);
        return formatTestSummary(exitCode, configName, testOutput);
    }

    // ── JUnit config helpers ─────────────────────────────────

    /**
     * Falls back to find a suitable module when class resolution didn't provide one.
     * For single-module projects, returns the only module. For multi-module projects,
     * returns the first module with a non-empty test scope.
     */
    @Nullable
    private Module resolveModuleFallback() {
        var moduleManager = com.intellij.openapi.module.ModuleManager.getInstance(project);
        Module[] modules = moduleManager.getModules();
        if (modules.length == 0) return null;
        if (modules.length == 1) return modules[0];

        for (Module mod : modules) {
            var scope = mod.getModuleScope(true);
            if (!scope.equals(GlobalSearchScope.EMPTY_SCOPE)) {
                return mod;
            }
        }
        return modules[0];
    }

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
    private void configureJUnitTestData(RunConfiguration config, String resolvedClass,
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

        Module moduleToSet = resolvedModule != null ? resolvedModule : resolveModuleFallback();
        if (moduleToSet != null) {
            try {
                var setModule = config.getClass().getMethod("setModule", Module.class);
                setModule.invoke(config, moduleToSet);
            } catch (NoSuchMethodException ignored) {
                // Method not available in this version
            }
        }
    }

    // ── Result parsing helpers ───────────────────────────────

    private String[] parseTestTarget(String target) {
        return JunitXmlParser.parseTestTarget(target);
    }

    private String extractClassFqn(PsiFile psiFile, String simpleName) {
        try {
            var getPackageName = psiFile.getClass().getMethod("getPackageName");
            String pkg = (String) getPackageName.invoke(psiFile);
            return buildFqn(pkg, simpleName);
        } catch (NoSuchMethodException e) {
            return extractFqnFromSourceText(psiFile.getText(), simpleName);
        } catch (Exception e) {
            return simpleName;
        }
    }

    /**
     * Builds a fully-qualified class name from package and simple name.
     * Pure function — no IDE dependency.
     */
    static String buildFqn(@Nullable String packageName, @NotNull String simpleName) {
        return packageName != null && !packageName.isEmpty() ? packageName + "." + simpleName : simpleName;
    }

    /**
     * Extracts the FQN from raw source text by parsing the {@code package} declaration via regex.
     * Fallback for when PSI reflection fails.
     * Pure function — no IDE dependency.
     */
    static String extractFqnFromSourceText(@NotNull String sourceText, @NotNull String simpleName) {
        var matcher = java.util.regex.Pattern.compile("^package\\s+([\\w.]+)").matcher(sourceText);
        if (matcher.find()) {
            return matcher.group(1) + "." + simpleName;
        }
        return simpleName;
    }

    /**
     * Formats a test execution summary from exit code, config name, and test output.
     * Pure function — no IDE dependency.
     */
    static String formatTestSummary(int exitCode, @NotNull String configName, @NotNull String testOutput) {
        String summary = (exitCode == 0 ? TESTS_PASSED : TESTS_FAILED_PREFIX + exitCode + ")")
            + " — " + configName;
        return testOutput.isEmpty()
            ? summary + RESULTS_IN_RUNNER_PANEL
            : summary + "\n" + testOutput;
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
            return formatConsoleSection(text);
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

        String errorMsg = null;
        String stacktrace = null;
        if (defect) {
            try {
                errorMsg = (String) test.getClass().getMethod("getErrorMessage").invoke(test);
                stacktrace = (String) test.getClass().getMethod("getStacktrace").invoke(test);
            } catch (NoSuchMethodException ignored) {
                // Method not available on this test result type
            }
        }
        sb.append(formatTestDetail(name, passed, defect, errorMsg, stacktrace));
    }

    // ── JUnit XML result parsing ─────────────────────────────

    private String parseJunitXmlResults(String basePath, String module) {
        return JunitXmlParser.parseJunitXmlResults(basePath, module);
    }

    // ── Extracted pure helpers ────────────────────────────────

    /**
     * Builds a JUnit run configuration name from the test class simple name and optional method.
     * Pure function — no IDE dependency.
     */
    static String buildJUnitConfigName(@NotNull String simpleName, @Nullable String testMethod) {
        return "Test: " + (testMethod != null ? simpleName + "." + testMethod : simpleName);
    }

    /**
     * Builds a pattern-based run configuration name from the glob target and match count.
     * Pure function — no IDE dependency.
     */
    static String buildPatternConfigName(@NotNull String target, int classCount) {
        return "Test: " + target + " (" + classCount + " classes)";
    }

    /**
     * Builds a Gradle task prefix from the module name.
     * Returns an empty string for no module, or {@code ":module:"} for a named module.
     * Pure function — no IDE dependency.
     */
    static String buildGradleTaskPrefix(@NotNull String module) {
        return module.isEmpty() ? "" : ":" + module + ":";
    }

    /**
     * Determines the test status label from passed/defect flags.
     * Pure function — no IDE dependency.
     */
    static String determineTestStatus(boolean passed, boolean defect) {
        if (passed) return "PASSED";
        if (defect) return "FAILED";
        return "UNKNOWN";
    }

    /**
     * Formats a single test detail line with optional failure information.
     * Pure function — no IDE dependency.
     */
    static String formatTestDetail(@NotNull String name, boolean passed, boolean defect,
                                   @Nullable String errorMsg, @Nullable String stacktrace) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(determineTestStatus(passed, defect)).append(" ").append(name).append("\n");
        if (defect) {
            if (errorMsg != null && !errorMsg.isEmpty()) {
                sb.append("    Error: ").append(errorMsg).append("\n");
            }
            if (stacktrace != null && !stacktrace.isEmpty()) {
                sb.append("    Stacktrace:\n").append(stacktrace).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Formats raw console text into a labelled console output section.
     * Returns {@code null} if text is null or blank.
     * Pure function — no IDE dependency.
     */
    @Nullable
    static String formatConsoleSection(@Nullable String text) {
        if (text == null || text.isBlank()) return null;
        return "\n=== Console Output ===\n" + ToolUtils.truncateOutput(text);
    }

}
