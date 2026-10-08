package com.github.catatafishen.agentbridge.psi.tools.testing;

import com.github.catatafishen.agentbridge.psi.ClassResolverUtil;
import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.McpRequestDeadline;
import com.github.catatafishen.agentbridge.psi.tools.RunPanelExecutor;
import com.github.catatafishen.agentbridge.ui.renderers.TestResultRenderer;
import com.google.gson.JsonObject;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.externalSystem.ExternalSystemManager;
import com.intellij.openapi.externalSystem.model.ExternalProjectInfo;
import com.intellij.openapi.externalSystem.model.ProjectKeys;
import com.intellij.openapi.externalSystem.model.task.TaskData;
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
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
import java.util.concurrent.TimeoutException;
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
    private static final String PARAM_TEST_TASK = "test_task";
    private static final String PARAM_TIMEOUT = "timeout";

    private static final String TEST_TYPE_METHOD = "method";
    private static final String TEST_TYPE_CLASS = "class";
    private static final String TEST_TYPE_PATTERN = "pattern";
    private static final String JUNIT_TYPE_ID = "junit";
    private static final String LAUNCH_FAILED = "launch_failed";
    private static final String FIELD_TEST_OBJECT = "TEST_OBJECT";
    private static final String ERROR_PROCESS_FAILED_TO_START = "Error: Test process failed to start for ";
    private static final String ERROR_NO_PROJECT_PATH = "Error: Could not determine project base path";

    /**
     * Default wait for a test run, in seconds. Kept below
     * {@link McpRequestDeadline#MAX_TIMEOUT_SECONDS} — the previous default of 300s could never be
     * honoured, since the MCP client abandons the request long before then and the agent would see
     * a transport error rather than the test results.
     */
    private static final int DEFAULT_TIMEOUT_SECONDS = 150;
    private static final int RESULT_MODEL_POLL_ATTEMPTS = 50;
    private static final long RESULT_MODEL_POLL_DELAY_MILLIS = 100;

    /**
     * Timeout in seconds for {@link #awaitProcessTermination}; set in {@link #execute}.
     */
    private int timeoutSec = DEFAULT_TIMEOUT_SECONDS;

    /**
     * When the current run started, for ignoring report files written by an earlier run; set in {@link #execute}.
     */
    private long runStartedMillis;

    /**
     * Report files can carry a coarser timestamp than the clock that records the start.
     */
    private static final long XML_FRESHNESS_SLACK_MILLIS = 2_000;

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
            "Falls back to the project's build tool for unresolvable targets; use the 'test_task' parameter " +
            "when the project defines a custom test task (e.g., 'unitTest') instead of the standard 'test'. " +
            "Returns pass/fail counts and failure details. Use list_tests to discover available test targets. " +
            "Use the 'timeout' parameter to override the default " + DEFAULT_TIMEOUT_SECONDS
            + "-second wait, up to a maximum of " + McpRequestDeadline.MAX_TIMEOUT_SECONDS + "s.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EXECUTE;
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
            Param.optional(JSON_MODULE, TYPE_STRING, "Optional module name (e.g., 'plugin-core')", ""),
            Param.optional(PARAM_TEST_TASK, TYPE_STRING,
                "Build task name when the project does not use the standard 'test' task "
                    + "(e.g., 'unitTest'). Auto-detected from the project model if not specified.", ""),
            Param.optional(PARAM_TIMEOUT, TYPE_INTEGER,
                "Timeout in seconds (default: " + DEFAULT_TIMEOUT_SECONDS + ", maximum: "
                    + McpRequestDeadline.MAX_TIMEOUT_SECONDS + "). Larger values are reduced to the "
                    + "maximum, because MCP clients abandon a request after roughly 180s and the "
                    + "results would be lost. For a suite that needs longer, run it with "
                    + "run_in_terminal and poll with read_terminal_output.")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return TestResultRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        int requestedTimeout = args.has(PARAM_TIMEOUT)
            ? args.get(PARAM_TIMEOUT).getAsInt() : DEFAULT_TIMEOUT_SECONDS;
        String timeoutError = McpRequestDeadline.rejectNonPositive(requestedTimeout);
        if (timeoutError != null) return timeoutError;
        this.timeoutSec = McpRequestDeadline.clamp(requestedTimeout);
        this.runStartedMillis = System.currentTimeMillis();
        return McpRequestDeadline.prependNotice(
            McpRequestDeadline.clampNotice(requestedTimeout), runResolvedTarget(args));
    }

    private @NotNull String runResolvedTarget(@NotNull JsonObject args) {
        String target = args.get(PARAM_TARGET).getAsString();
        String module = args.has(JSON_MODULE) ? args.get(JSON_MODULE).getAsString() : "";
        String testTask = args.has(PARAM_TEST_TASK) ? args.get(PARAM_TEST_TASK).getAsString() : "";
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;

        String configResult = tryRunTestConfig(target);
        if (configResult != null) return configResult;

        if (target.contains("*")) {
            String patternResult = tryRunJUnitPattern(target);
            if (patternResult != null) return patternResult;

            return runTestsViaGradleConfig(target, module, testTask);
        }

        // Framework-agnostic: resolve the target to a PSI element and use ConfigurationContext
        // to auto-detect the right test framework (JUnit, TestNG, pytest, etc.)
        String contextResult = tryRunViaConfigurationContext(target);
        if (contextResult != null) return contextResult;

        String junitResult = tryRunJUnitNatively(target);
        if (junitResult != null) return junitResult;

        return runTestsViaGradleConfig(target, module, testTask);
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

        TestExecutionTracker tracker = new TestExecutionTracker(project, configName);

        CompletableFuture<String> launchFuture = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                var executor = DefaultRunExecutor.getRunExecutorInstance();
                var envBuilder = ExecutionEnvironmentBuilder.createOrNull(executor, settings);
                if (envBuilder == null) {
                    launchFuture.complete(err("Cannot create execution environment for: " + configName));
                    return;
                }
                tracker.expect(settings.getConfiguration());
                ExecutionManager.getInstance(project).restartRunProfile(envBuilder.build());
                launchFuture.complete(null);
            } catch (Exception e) {
                LOG.warn("Failed to run test config: " + configName, e);
                launchFuture.complete(LAUNCH_FAILED);
            }
        });

        return awaitTestExecution(configName, launchFuture, tracker);
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

            TestExecutionTracker tracker = new TestExecutionTracker(project, configName);

            CompletableFuture<String> launchFuture = new CompletableFuture<>();
            EdtUtil.invokeLater(() -> {
                try {
                    String error = launchJUnitConfig(
                        junitType, resolvedClass, testMethod, resolvedModule, configName, tracker);
                    launchFuture.complete(error);
                } catch (Exception e) {
                    LOG.warn("Failed to run JUnit natively, will fall back to Gradle", e);
                    launchFuture.complete(LAUNCH_FAILED);
                }
            });

            return awaitTestExecution(configName, launchFuture, tracker);
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

            MatchingTestClasses matches = resolveMatchingTestClasses(target);
            List<String> matchingClasses = matches.classes();
            if (matchingClasses.isEmpty()) return null;

            String configName = buildPatternConfigName(target, matchingClasses.size());

            TestExecutionTracker tracker = new TestExecutionTracker(project, configName);

            // The run configuration takes one module, which also decides the Gradle project the tests run in.
            // Use the module that holds the matched classes; with several (or none), fall back to a guess.
            Module patternModule = matches.modules().size() == 1 ? matches.modules().iterator().next() : null;
            CompletableFuture<String> launchFuture = new CompletableFuture<>();
            EdtUtil.invokeLater(() -> launchPatternConfig(
                junitType, configName, matchingClasses, patternModule, launchFuture, tracker));

            return awaitTestExecution(configName, launchFuture, tracker);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("tryRunJUnitPattern failed", e);
            return null;
        } catch (Exception e) {
            LOG.warn("tryRunJUnitPattern failed", e);
            return null;
        }
    }

    /**
     * Test classes matching a wildcard target, and the modules that contain them.
     */
    private record MatchingTestClasses(List<String> classes, java.util.Set<Module> modules) {
    }

    private MatchingTestClasses resolveMatchingTestClasses(String target) {
        return ApplicationManager.getApplication().runReadAction((Computable<MatchingTestClasses>) () -> {
            List<String> classes = new ArrayList<>();
            java.util.Set<Module> modules = new java.util.LinkedHashSet<>();
            ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
            var compiledGlob = target.isEmpty() ? null : ToolUtils.compileGlob(target);
            fileIndex.iterateContent(vf -> processTestFile(vf, fileIndex, target, compiledGlob, classes, modules));
            return new MatchingTestClasses(classes, modules);
        });
    }

    private boolean processTestFile(com.intellij.openapi.vfs.VirtualFile vf,
                                    ProjectFileIndex fileIndex, String target, java.util.regex.Pattern compiledGlob,
                                    List<String> classes, java.util.Set<Module> modules) {
        if (!fileIndex.isInTestSourceContent(vf)) return true;
        if (vf.isDirectory()) return true;
        String name = vf.getName();
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx <= 0) return true;
        String simpleName = name.substring(0, dotIdx);
        // A package-qualified target needs the FQN, which means reading the file's package; a simple-name
        // target can reject most files from the name alone.
        boolean needsFqnToMatch = target.contains(".");
        if (!needsFqnToMatch && !TestConfigBuilder.matchesTestTarget(simpleName, null, target, compiledGlob)) return true;
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return true;
        String fqn = extractClassFqn(psiFile, simpleName);
        if (needsFqnToMatch && !TestConfigBuilder.matchesTestTarget(simpleName, fqn, target, compiledGlob)) return true;
        if (fqn != null) {
            classes.add(fqn);
            Module module = fileIndex.getModuleForFile(vf);
            if (module != null) modules.add(module);
        }
        return classes.size() < 200;
    }

    @SuppressWarnings("java:S3011")
    // Required: accessing internal JUnit run config fields via reflection — no public API exists
    private void launchPatternConfig(ConfigurationType junitType, String configName,
                                     List<String> matchingClasses,
                                     @Nullable Module matchedModule,
                                     CompletableFuture<String> launchFuture,
                                     TestExecutionTracker tracker) {
        try {
            RunManager runManager = RunManager.getInstance(project);
            var factory = junitType.getConfigurationFactories()[0];
            var settings = runManager.createConfiguration(configName, factory);
            RunConfiguration config = settings.getConfiguration();

            var getData = config.getClass().getMethod("getPersistentData");
            Object data = getData.invoke(config);
            TestConfigBuilder.applyPatternData(data, matchingClasses);

            Module patternModule = matchedModule != null ? matchedModule : resolveModuleFallback();
            if (patternModule != null) {
                setModuleIfSupported(config, patternModule);
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
            tracker.expect(config);
            ExecutionManager.getInstance(project).restartRunProfile(envBuilder.build());
            launchFuture.complete(null);
        } catch (Exception e) {
            LOG.warn("Failed to run JUnit pattern config", e);
            launchFuture.complete(LAUNCH_FAILED);
        }
    }

    private static void setModuleIfSupported(RunConfiguration config, Module module)
        throws ReflectiveOperationException {
        try {
            var setModule = config.getClass().getMethod("setModule", Module.class);
            setModule.invoke(config, module);
        } catch (NoSuchMethodException ignored) {
            // Method not available in this version
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

    private String runTestsViaGradleConfig(String target, String module, String testTask) {
        try {
            String taskPrefix = buildGradleTaskPrefix(module);
            String resolvedTask = testTask.isEmpty() ? resolveTestTask(module) : testTask;
            String configName = "Gradle Test: " + target;

            TestExecutionTracker tracker = new TestExecutionTracker(project, configName);

            CompletableFuture<String> launchFuture = new CompletableFuture<>();
            EdtUtil.invokeLater(() -> {
                try {
                    String error = createAndRunGradleTestConfig(
                        configName, taskPrefix, target, resolvedTask, tracker);
                    launchFuture.complete(error);
                } catch (Exception e) {
                    LOG.warn("Failed to create Gradle test config", e);
                    launchFuture.complete(LAUNCH_FAILED);
                }
            });

            return awaitGradleTestExecution(configName, launchFuture, tracker, target, module);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Test execution interrupted";
        } catch (Exception e) {
            LOG.warn("runTestsViaGradleConfig failed", e);
            return "Error: Failed to run tests via Gradle config: " + e.getMessage();
        }
    }

    private String createAndRunGradleTestConfig(String configName, String taskPrefix, String target,
                                                String gradleTask, TestExecutionTracker tracker) {
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
            setTaskNames.invoke(gradleSettings, List.of(taskPrefix + gradleTask));

            var setScriptParameters = gradleSettings.getClass().getMethod("setScriptParameters", String.class);
            setScriptParameters.invoke(gradleSettings, "--tests " + buildGradleTestFilter(target));

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

            tracker.expect(config);
            ExecutionManager.getInstance(project).restartRunProfile(envBuilder.build());
            return null;
        } catch (Exception e) {
            LOG.warn("createAndRunGradleTestConfig failed", e);
            return LAUNCH_FAILED;
        }
    }

    /**
     * Resolves the test task name to use. Falls back to the standard {@code "test"} task
     * if nothing custom is found via the project model or build files.
     */
    private String resolveTestTask(@NotNull String module) {
        String detected = detectTestTask(module);
        return detected != null ? detected : "test";
    }

    /**
     * Detects a non-standard test task registered in the project.
     *
     * <p>Uses IntelliJ's ExternalSystem API as the primary source — works for any build
     * system imported by IntelliJ (Gradle, Maven, etc.) via {@link TaskData#isTest()}.
     * Falls back to scanning Gradle build files when no ExternalSystem data is available.</p>
     *
     * @return the first non-standard test task name found, or {@code null} if only the
     * standard {@code "test"} task is present or nothing could be detected
     */
    @Nullable
    private String detectTestTask(@NotNull String module) {
        String basePath = project.getBasePath();
        if (basePath == null) return null;

        boolean externalDataFound = false;
        List<TestTaskInfo> tasks = new ArrayList<>();
        for (ExternalSystemManager<?, ?, ?, ?, ?> manager : ExternalSystemApiUtil.getAllManagers()) {
            var systemId = manager.getSystemId();
            ExternalProjectInfo info = ProjectDataManager.getInstance()
                .getExternalProjectData(project, systemId, basePath);
            if (info == null || info.getExternalProjectStructure() == null) continue;
            externalDataFound = true;
            var taskNodes = ExternalSystemApiUtil.findAllRecursively(
                info.getExternalProjectStructure(), ProjectKeys.TASK);
            for (var taskNode : taskNodes) {
                TaskData task = taskNode.getData();
                tasks.add(new TestTaskInfo(task.getName(), task.getLinkedExternalProjectPath(), task.isTest()));
            }
        }
        if (externalDataFound) return selectTestTask(tasks, basePath, module);

        return GradleBuildFileScanner.detectTestTask(basePath);
    }

    /**
     * A task from the external-system model together with the project directory that owns it.
     */
    record TestTaskInfo(@NotNull String name, @NotNull String projectPath, boolean markedAsTest) {
    }

    /**
     * Picks the non-standard test task for {@code module}, or {@code null} when the standard
     * {@code test} task should be used.
     *
     * <p>Only tasks owned by the requested module are considered ({@code module} is a Gradle path
     * such as {@code plugin-core} or {@code a:b}; empty means the root project). The model spans
     * every module, so a custom task of another module (e.g. an {@code integrationTest} in a sibling
     * UI-test module) must not be chosen, and another module's {@code test} task must not hide that
     * the requested module only has a custom one (#1113, #1130, #1135).
     */
    static @Nullable String selectTestTask(@NotNull List<TestTaskInfo> tasks,
                                           @NotNull String basePath, @NotNull String module) {
        String scope = normalizeProjectPath(module.isEmpty()
            ? basePath
            : basePath + "/" + module.replaceFirst("^:+", "").replace(':', '/'));
        String custom = null;
        for (TestTaskInfo task : tasks) {
            if (!task.markedAsTest() || !scope.equals(normalizeProjectPath(task.projectPath()))) continue;
            if ("test".equals(task.name())) return null;
            if (custom == null && isCustomTestTask(task.name(), true)) custom = task.name();
        }
        return custom;
    }

    private static String normalizeProjectPath(@NotNull String path) {
        String normalized = path.replace('\\', '/');
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    static boolean isCustomTestTask(@NotNull String name, boolean markedAsTest) {
        if (!markedAsTest || "test".equals(name)) return false;
        return !List.of("check", "build", "assemble", "classes", "testClasses").contains(name);
    }

    /**
     * Delegates to {@link GradleBuildFileScanner#findTestTaskInBuildFile(String)}.
     */
    @Nullable
    static String findTestTaskInBuildFile(@NotNull String content) {
        return GradleBuildFileScanner.findTestTaskInBuildFile(content);
    }

    public String executeFromCommand(@NotNull String command) {
        return executeFromCommand(command, DEFAULT_TIMEOUT_SECONDS);
    }

    public String executeFromCommand(@NotNull String command, int timeoutSec) {
        String target = parseTestsFilterFromCommand(command);
        String module = parseModuleFromCommand(command);
        String taskName = parseTaskFromCommand(command);

        JsonObject args = new JsonObject();
        args.addProperty(PARAM_TARGET, target != null ? target : "*");
        if (!module.isEmpty()) args.addProperty(JSON_MODULE, module);
        if (taskName != null) args.addProperty(PARAM_TEST_TASK, taskName);
        args.addProperty(PARAM_TIMEOUT, timeoutSec);

        try {
            return execute(args);
        } catch (Exception e) {
            LOG.warn("executeFromCommand failed", e);
            return "Error: Failed to run tests: " + e.getMessage();
        }
    }

    @Nullable
    static String parseTestsFilterFromCommand(@NotNull String command) {
        return TestConfigBuilder.parseTestsFilterFromCommand(command);
    }

    static @NotNull String parseModuleFromCommand(@NotNull String command) {
        return TestConfigBuilder.parseModuleFromCommand(command);
    }

    @Nullable
    static String parseTaskFromCommand(@NotNull String command) {
        return TestConfigBuilder.parseTaskFromCommand(command);
    }

    // ── Execution lifecycle helpers ──────────────────────────

    /**
     * Grace period for the platform to report the launched process, capped by the caller's own
     * timeout. A cold Gradle daemon routinely needs more than a few seconds to hand over a process
     * handle, and giving up early made {@code run_tests} return before the tests had even started.
     */
    private static final int HANDLER_WAIT_SECONDS = 60;

    private long handlerWaitSeconds() {
        return handlerWaitSeconds(timeoutSec);
    }

    /**
     * Never waits longer for the process handle than the caller was prepared to wait for the whole
     * run, so a short {@code timeout} still returns promptly.
     */
    static long handlerWaitSeconds(int timeoutSeconds) {
        return Math.clamp(timeoutSeconds, 1, HANDLER_WAIT_SECONDS);
    }

    private String noProcessHandleError(String configName) {
        return "Error: Tests were launched as '" + configName + "' but the IDE did not report a "
            + "process handle within " + handlerWaitSeconds() + "s, so the result could not be "
            + "collected. The run is probably still in progress — use list_run_tabs to find the tab "
            + "and read_run_output to read the outcome.";
    }

    private String awaitTestExecution(String configName,
                                      CompletableFuture<String> launchFuture,
                                      TestExecutionTracker tracker) throws Exception {
        String launchError = launchFuture.get(10, TimeUnit.SECONDS);
        if (launchError != null) {
            tracker.disconnect();
            return LAUNCH_FAILED.equals(launchError) ? null : launchError;
        }

        ProcessHandler handler;
        try {
            handler = tracker.awaitHandler(handlerWaitSeconds());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            tracker.disconnect();
            return noProcessHandleError(configName);
        } catch (TimeoutException e) {
            tracker.disconnect();
            return noProcessHandleError(configName);
        }

        if (handler == null) return ERROR_PROCESS_FAILED_TO_START + configName;
        int exitCode = awaitProcessTermination(handler);
        if (exitCode == Integer.MIN_VALUE) return "Tests timed out after " + timeoutSec + " seconds: " + configName;

        String testOutput = selectTestOutput(
            exitCode, () -> collectTestRunOutput(configName), tracker.capturedOutput());
        if (exitCode == 0 && !TestResultFormatter.hasTestCounts(testOutput)) {
            // The IDE reported no per-test results (Gradle runs often don't): count them from the report files.
            String xmlResults = freshXmlResults("");
            if (!xmlResults.isEmpty()) testOutput = xmlResults;
        }
        return formatTestSummary(exitCode, configName, testOutput);
    }

    private String awaitGradleTestExecution(String configName,
                                            CompletableFuture<String> launchFuture,
                                            TestExecutionTracker tracker,
                                            String target, String module) throws Exception {
        String launchError = launchFuture.get(10, TimeUnit.SECONDS);
        if (launchError != null) {
            tracker.disconnect();
            if (LAUNCH_FAILED.equals(launchError)) {
                return "Error: Failed to create Gradle test run configuration for: " + target;
            }
            return launchError;
        }

        ProcessHandler handler;
        try {
            handler = tracker.awaitHandler(handlerWaitSeconds());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            tracker.disconnect();
            return noProcessHandleError(configName);
        } catch (TimeoutException e) {
            tracker.disconnect();
            return noProcessHandleError(configName);
        }

        if (handler == null) return ERROR_PROCESS_FAILED_TO_START + configName;
        int exitCode = awaitProcessTermination(handler);
        if (exitCode == Integer.MIN_VALUE) return "Tests timed out after " + timeoutSec + " seconds: " + configName;

        String basePath = project.getBasePath();
        if (basePath != null) {
            String xmlResults = parseJunitXmlResults(basePath, module);
            if (shouldUseXmlResults(exitCode, xmlResults)) return xmlResults;
        }

        String testOutput = selectTestOutput(
            exitCode, () -> collectTestRunOutput(configName), tracker.capturedOutput());
        return formatTestSummary(exitCode, configName, testOutput);
    }

    static @NotNull String selectTestOutput(
        int exitCode,
        @NotNull java.util.function.Supplier<String> structuredOutput,
        @NotNull String capturedOutput
    ) {
        if (exitCode != 0) {
            return TestResultFormatter.withConsoleFallback("", capturedOutput);
        }
        return TestResultFormatter.withConsoleFallback(structuredOutput.get(), capturedOutput);
    }

    /**
     * Waits for the given {@link ProcessHandler} to terminate using an event-driven
     * {@link ProcessListener} callback, rather than the polling-based
     * {@link ProcessHandler#waitFor(long)}.
     *
     * <p>{@code waitFor} may not unblock if the handler implementation does not properly
     * implement the termination notification protocol (observed with certain JUnit runner
     * wrappers where the UI shows "complete" but the handler never transitions to terminated).
     * {@code processTerminated} is the definitive event-driven signal and is more reliable.
     *
     * <p>As a safety net, a {@link Process#onExit()} fallback is scheduled via
     * {@link RunPanelExecutor#scheduleHandlerExitFallback} to detect process exit even
     * when {@code processTerminated} never fires (e.g. stuck reader threads, certain
     * Gradle or JUnit runner wrappers).
     *
     * @return the process exit code on normal termination, or
     * {@link Integer#MIN_VALUE} on timeout or interruption
     */
    private int awaitProcessTermination(@NotNull ProcessHandler handler) {
        CompletableFuture<Integer> doneFuture = new CompletableFuture<>();
        handler.addProcessListener(new ProcessListener() {
            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                doneFuture.complete(event.getExitCode());
            }
        });
        // Race guard: the process may have already terminated before the listener was added.
        if (handler.isProcessTerminated()) {
            doneFuture.complete(handler.getExitCode() != null ? handler.getExitCode() : 0);
        }
        // Fallback: detect process exit via the underlying OS process in case
        // processTerminated never fires. Covers stuck reader threads and handlers
        // that don't properly fire processTerminated.
        RunPanelExecutor.scheduleHandlerExitFallback(handler, doneFuture);
        try {
            return doneFuture.get(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Integer.MIN_VALUE;
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
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
        String configName, TestExecutionTracker tracker) throws Exception {
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
        tracker.expect(config);
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

    static String buildFqn(@Nullable String packageName, @NotNull String simpleName) {
        return TestConfigBuilder.buildFqn(packageName, simpleName);
    }

    static String extractFqnFromSourceText(@NotNull String sourceText, @NotNull String simpleName) {
        return TestConfigBuilder.extractFqnFromSourceText(sourceText, simpleName);
    }

    static String formatTestSummary(int exitCode, @NotNull String configName, @NotNull String testOutput) {
        return TestResultFormatter.formatTestSummary(exitCode, configName, testOutput);
    }

    private String collectTestRunOutput(String configName) {
        // Process termination can precede the Run-content model update. Retry the structured model
        // before falling back to console text, which is usually available earlier than test results.
        for (int attempt = 0; attempt < RESULT_MODEL_POLL_ATTEMPTS; attempt++) {
            String testResults = collectTestRunOutputOnce(configName, false);
            if (!testResults.isEmpty()) return testResults;
            try {
                Thread.sleep(RESULT_MODEL_POLL_DELAY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
        }
        return collectTestRunOutputOnce(configName, true);
    }

    private String collectTestRunOutputOnce(String configName, boolean includeConsoleText) {
        try {
            var manager = com.intellij.execution.ui.RunContentManager.getInstance(project);
            var descriptors = new ArrayList<>(manager.getAllDescriptors());
            com.intellij.execution.ui.RunContentDescriptor target = findTestRunDescriptor(descriptors, configName);
            if (target == null || target.getExecutionConsole() == null) return "";

            Object console = target.getExecutionConsole();
            String testResults = tryGetTestResults(console);
            if (testResults != null) return testResults;
            if (!includeConsoleText) return "";

            String consoleText = tryGetConsoleText(console);
            return consoleText != null ? consoleText : "";
        } catch (Exception e) {
            LOG.debug("Failed to collect test run output", e);
            return "";
        }
    }

    @Nullable
    private com.intellij.execution.ui.RunContentDescriptor findTestRunDescriptor(
        List<com.intellij.execution.ui.RunContentDescriptor> descriptors, String configName) {
        com.intellij.execution.ui.RunContentDescriptor substringFallback = null;
        for (var descriptor : descriptors) {
            String displayName = descriptor.getDisplayName();
            if (displayName != null) {
                if (displayName.equals(configName)) return descriptor;
                if (displayName.contains(configName)) substringFallback = descriptor;
            }
        }
        return substringFallback;
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
                    List<TestResultData> testResults = new ArrayList<>();
                    for (var test : tests) {
                        if (isLeafTest(test)) testResults.add(readTestResult(test));
                    }
                    if (testResults.isEmpty()) return null;

                    int passed = 0;
                    int failed = 0;
                    int errors = 0;
                    int skipped = 0;
                    for (var result : testResults) {
                        if (result.ignored()) skipped++;
                        else if (result.passed()) passed++;
                        else if (result.error()) errors++;
                        else if (result.defect()) failed++;
                    }

                    StringBuilder sb = new StringBuilder(TestResultFormatter.formatTestResults(
                        testResults.size(), passed, failed, errors, skipped));
                    sb.append("\n\n=== Test Details ===\n");
                    for (var result : testResults) {
                        sb.append(formatTestDetail(result.name(), result.passed(),
                            result.defect() || result.error(), result.errorMessage(), result.stacktrace()));
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

    private static boolean isLeafTest(Object test) {
        try {
            Object children = test.getClass().getMethod("getChildren").invoke(test);
            return children instanceof java.util.Collection<?> collection && collection.isEmpty();
        } catch (ReflectiveOperationException ignored) {
            // Older result models do not expose their children; treat the result as a test.
            return true;
        }
    }

    private TestResultData readTestResult(Object test) throws Exception {
        var getName = test.getClass().getMethod("getPresentableName");
        var isPassed = test.getClass().getMethod("isPassed");
        var isDefect = test.getClass().getMethod("isDefect");
        String name = (String) getName.invoke(test);
        boolean passed = (boolean) isPassed.invoke(test);
        boolean defect = (boolean) isDefect.invoke(test);
        boolean ignored = optionalBoolean(test, "isIgnored");
        boolean error = optionalBoolean(test, "isError");

        String errorMsg = null;
        String stacktrace = null;
        if (defect || error) {
            try {
                errorMsg = (String) test.getClass().getMethod("getErrorMessage").invoke(test);
                stacktrace = (String) test.getClass().getMethod("getStacktrace").invoke(test);
            } catch (NoSuchMethodException ignoredException) {
                // Method not available on this test result type
            }
        }
        return new TestResultData(name, passed, defect, ignored, error, errorMsg, stacktrace);
    }

    private static boolean optionalBoolean(Object target, String methodName) {
        try {
            return (boolean) target.getClass().getMethod(methodName).invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private record TestResultData(String name, boolean passed, boolean defect, boolean ignored,
                                  boolean error, @Nullable String errorMessage, @Nullable String stacktrace) {
    }

    // ── JUnit XML result parsing ─────────────────────────────

    static boolean shouldUseXmlResults(int exitCode, @NotNull String xmlResults) {
        return exitCode == 0 && !xmlResults.isEmpty();
    }

    private String parseJunitXmlResults(String basePath, String module) {
        return JunitXmlParser.parseJunitXmlResults(basePath, module, runStartedMillis - XML_FRESHNESS_SLACK_MILLIS);
    }

    /**
     * Result of this run read from the report files Gradle wrote, or an empty string when there are none. Used
     * when the IDE's own test-results model has nothing, so a pass is still reported with counts.
     */
    private String freshXmlResults(String module) {
        String basePath = project.getBasePath();
        return basePath == null ? "" : parseJunitXmlResults(basePath, module);
    }

    static String buildJUnitConfigName(@NotNull String simpleName, @Nullable String testMethod) {
        return TestConfigBuilder.buildJUnitConfigName(simpleName, testMethod);
    }

    static String buildPatternConfigName(@NotNull String target, int classCount) {
        return TestConfigBuilder.buildPatternConfigName(target, classCount);
    }

    static String buildGradleTaskPrefix(@NotNull String module) {
        return TestConfigBuilder.buildGradleTaskPrefix(module);
    }

    static String buildGradleTestFilter(@NotNull String target) {
        return TestConfigBuilder.buildGradleTestFilter(target);
    }

    static String determineTestStatus(boolean passed, boolean defect) {
        return TestResultFormatter.determineTestStatus(passed, defect);
    }

    static String formatTestDetail(@NotNull String name, boolean passed, boolean defect,
                                   @Nullable String errorMsg, @Nullable String stacktrace) {
        return TestResultFormatter.formatTestDetail(name, passed, defect, errorMsg, stacktrace);
    }

    @Nullable
    static String formatConsoleSection(@Nullable String text) {
        return TestResultFormatter.formatConsoleSection(text);
    }

}
