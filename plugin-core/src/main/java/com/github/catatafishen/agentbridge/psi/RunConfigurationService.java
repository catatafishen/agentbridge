package com.github.catatafishen.agentbridge.psi;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.RunManager;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service for managing IntelliJ run configurations.
 * Handles creation, editing, execution, and listing of run configurations.
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
public final class RunConfigurationService {
    private static final Logger LOG = Logger.getInstance(RunConfigurationService.class);

    // Common Parameters
    private static final String PARAM_JVM_ARGS = "jvm_args";
    private static final String PARAM_PROGRAM_ARGS = "program_args";
    private static final String PARAM_WORKING_DIR = "working_dir";
    private static final String PARAM_MAIN_CLASS = "main_class";
    private static final String PARAM_TEST_CLASS = "test_class";
    private static final String PARAM_TEST_METHOD = "test_method";
    private static final String PARAM_MODULE_NAME = "module";

    // Reflection Field/Method Names
    private static final String FIELD_TEST_OBJECT = "TEST_OBJECT";
    private static final String FIELD_METHOD_NAME = "METHOD_NAME";
    private static final String METHOD_SET_MODULE = "setModule";

    // Test Type Values
    private static final String TEST_TYPE_METHOD = "method";
    private static final String TEST_TYPE_CLASS = "class";

    private static final String PARAM_ENV = "env";
    private static final String PARAM_FACTORY_NAME = "factory_name";
    private static final String PARAM_CONFIG = "config";
    private static final String XML_ELEM_OPTION = "option";
    private static final String XML_ATTR_VALUE = "value";
    private static final String PARAM_SHARED = "shared";
    private static final String PARAM_TASKS = "tasks";
    private static final String METHOD_SET_WORKING_DIR = "setWorkingDirectory";

    // JSON Schema key/type constants (used by xmlElementToJsonSchema and helpers)
    private static final String JSON_KEY_TYPE = "type";
    private static final String JSON_KEY_DEFAULT = "default";
    private static final String JSON_KEY_PROPERTIES = "properties";
    private static final String JSON_KEY_ITEMS = "items";
    private static final String JSON_TYPE_OBJECT = "object";
    private static final String JSON_TYPE_STRING = "string";
    private static final String JSON_TYPE_ARRAY = "array";
    private static final String JSON_TYPE_BOOLEAN = "boolean";
    private static final String PARAM_SCRIPT_PATH = "script_path";
    private static final String PARAM_SCRIPT_PARAMETERS = "script_parameters";
    private static final String PARAM_INTERPRETER_PATH = "interpreter_path";
    private static final String PARAM_EXECUTE_IN_TERMINAL = "execute_in_terminal";
    private static final String PARAM_SCRIPT_TEXT = "script_text";
    private static final String PARAM_SCRIPT_OPTIONS = "script_options";
    private static final String ERROR_CONFIG_NOT_FOUND = "Run configuration not found: '";
    private static final String ERROR_CONFIG_LIST_HINT = "'. Use list_run_configurations to see available configs.";

    // Config types that produce empty writeExternal output and need special handling
    private static final String TYPE_JS_DEBUG = "JavascriptDebugType";
    private static final String TYPE_CHROMIUM_REMOTE = "ChromiumRemoteDebugType";
    private static final Map<String, JsonObject> KNOWN_EMPTY_TYPE_SCHEMAS = buildKnownEmptyTypeSchemas();

    private final Project project;
    private final ClassResolverUtil.ClassResolver classResolver;

    public RunConfigurationService(Project project, ClassResolverUtil.ClassResolver classResolver) {
        this.project = project;
        this.classResolver = classResolver;
    }

    public String listRunConfigurations() {
        // Cast required: disambiguates Computable<T> vs ThrowableComputable<T,E> overloads at compile time.
        // The IDE falsely reports this as redundant; Gradle fails without it.
        Computable<String> action = () -> {
            try {
                var configs = RunManager.getInstance(project).getAllSettings();
                if (configs.isEmpty()) return "No run configurations found";

                List<String> results = new ArrayList<>();
                for (var config : configs) {
                    String entry = String.format("%s [%s]%s",
                        config.getName(),
                        config.getType().getDisplayName(),
                        config.isTemporary() ? " (temporary)" : "");
                    results.add(entry);
                }
                return results.size() + " run configurations:\n" + String.join("\n", results);
            } catch (Exception e) {
                return "Error listing run configurations: " + e.getMessage();
            }
        };
        return ApplicationManager.getApplication().runReadAction(action);
    }

    private com.intellij.execution.runners.ExecutionEnvironment buildExecutionEnv(
        com.intellij.execution.RunnerAndConfigurationSettings settings) {
        var executor = DefaultRunExecutor.getRunExecutorInstance();
        var envBuilder = ExecutionEnvironmentBuilder.createOrNull(executor, settings);
        if (envBuilder == null) {
            throw new IllegalStateException("Cannot create execution environment for: " + settings.getName());
        }
        return envBuilder.build();
    }

    public String runConfiguration(JsonObject args) throws Exception {
        String name = args.get("name").getAsString();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                var settings = RunManager.getInstance(project).findConfigurationByName(name);
                if (settings == null) {
                    resultFuture.complete(ERROR_CONFIG_NOT_FOUND + name + ERROR_CONFIG_LIST_HINT);
                    return;
                }

                ExecutionManager.getInstance(project).restartRunProfile(buildExecutionEnv(settings));
                resultFuture.complete("Started run configuration: " + name
                    + " [" + settings.getType().getDisplayName() + "]"
                    + "\nResults will appear in the IntelliJ Run panel.");
            } catch (Exception e) {
                resultFuture.complete("Error running configuration: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    public String runConfigurationAndWait(JsonObject args) throws Exception {
        String name = args.get("name").getAsString();
        int waitSeconds = args.has("wait_seconds") ? args.get("wait_seconds").getAsInt() : 30;

        var settingsRef = new java.util.concurrent.atomic.AtomicReference<com.intellij.execution.RunnerAndConfigurationSettings>();
        CompletableFuture<Void> launchFuture = new CompletableFuture<>();
        var doneLatch = new java.util.concurrent.CountDownLatch(1);
        var exitCodeRef = new java.util.concurrent.atomic.AtomicInteger(-1);

        // Subscribe before launching so we don't miss the processStarted event.
        Runnable disconnect = PlatformApiCompat.subscribeExecutionListener(project,
            new com.intellij.execution.ExecutionListener() {
                @Override
                public void processStarted(@org.jetbrains.annotations.NotNull String executorId,
                                           @org.jetbrains.annotations.NotNull com.intellij.execution.runners.ExecutionEnvironment env,
                                           @org.jetbrains.annotations.NotNull com.intellij.execution.process.ProcessHandler handler) {
                    var s = settingsRef.get();
                    var envSettings = env.getRunnerAndConfigurationSettings();
                    if (s != null && envSettings != null && s.getName().equals(envSettings.getName())) {
                        handler.addProcessListener(new com.intellij.execution.process.ProcessListener() {
                            @Override
                            public void startNotified(@org.jetbrains.annotations.NotNull com.intellij.execution.process.ProcessEvent e) {
                                // we wait for termination only
                            }

                            @Override
                            public void onTextAvailable(@org.jetbrains.annotations.NotNull com.intellij.execution.process.ProcessEvent e,
                                                        @org.jetbrains.annotations.NotNull com.intellij.openapi.util.Key outputType) {
                                // output is read via read_run_output after termination
                            }

                            @Override
                            public void processTerminated(@org.jetbrains.annotations.NotNull com.intellij.execution.process.ProcessEvent event) {
                                exitCodeRef.set(event.getExitCode());
                                doneLatch.countDown();
                            }
                        });
                    }
                }
            });

        EdtUtil.invokeLater(() -> {
            try {
                var settings = RunManager.getInstance(project).findConfigurationByName(name);
                if (settings == null) {
                    launchFuture.completeExceptionally(new IllegalArgumentException(
                        ERROR_CONFIG_NOT_FOUND + name + ERROR_CONFIG_LIST_HINT));
                    return;
                }
                settingsRef.set(settings);
                ExecutionManager.getInstance(project).restartRunProfile(buildExecutionEnv(settings));
                launchFuture.complete(null);
            } catch (Exception e) {
                launchFuture.completeExceptionally(e);
            }
        });

        try {
            launchFuture.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            disconnect.run();
            return e.getCause().getMessage();
        }

        boolean finished = doneLatch.await(waitSeconds, TimeUnit.SECONDS);
        disconnect.run();

        if (!finished) {
            return formatRunTimeoutMessage(name, waitSeconds);
        }

        int exitCode = exitCodeRef.get();
        return formatRunCompletionMessage(name, exitCode);
    }

    public String createRunConfiguration(JsonObject args) throws Exception {
        String name = args.get("name").getAsString();

        if (!args.has("type")) {
            return "Error: 'type' is required. "
                + "Use list_run_configuration_types to see available types, "
                + "then get_run_configuration_template to get the JSON schema for your chosen type.";
        }
        String type = args.get("type").getAsString().toLowerCase(java.util.Locale.ROOT);

        // Shell Script: use the deterministic XML builder instead of the IntelliJ API path.
        // ShRunConfiguration has no stable public Java API; the XML builder produces correct output.
        if (ShellScriptRunConfigXmlBuilder.isShellScriptType(type)) {
            return createShellScriptRunConfig(name, args);
        }

        // Abuse detection on program_args — same rules as run_command
        String abuseError = checkProgramArgsAbuse(args, type);
        if (abuseError != null) return abuseError;

        return createRunConfigWithOptions(name, type, args);
    }

    private String createShellScriptRunConfig(String name, JsonObject args) throws Exception {
        boolean hasPath = args.has(PARAM_SCRIPT_PATH) && !args.get(PARAM_SCRIPT_PATH).getAsString().isBlank();
        boolean hasText = args.has(PARAM_SCRIPT_TEXT) && !args.get(PARAM_SCRIPT_TEXT).getAsString().isBlank();
        if (hasPath && hasText) {
            return "Error: 'script_path' and 'script_text' are mutually exclusive — provide exactly one.";
        }
        var config = new ShellScriptRunConfigXmlBuilder.ShellScriptConfig(
            args.has(PARAM_SCRIPT_PATH) ? args.get(PARAM_SCRIPT_PATH).getAsString() : null,
            args.has(PARAM_SCRIPT_TEXT) ? args.get(PARAM_SCRIPT_TEXT).getAsString() : null,
            args.has(PARAM_SCRIPT_OPTIONS) ? args.get(PARAM_SCRIPT_OPTIONS).getAsString() : null,
            args.has(PARAM_INTERPRETER_PATH) ? args.get(PARAM_INTERPRETER_PATH).getAsString() : null,
            null,
            args.has(PARAM_WORKING_DIR) ? args.get(PARAM_WORKING_DIR).getAsString() : null,
            !args.has(PARAM_EXECUTE_IN_TERMINAL) || args.get(PARAM_EXECUTE_IN_TERMINAL).getAsBoolean()
        );
        String xml = ShellScriptRunConfigXmlBuilder.build(name, config, project.getBasePath());
        return createRunConfigFromXml(name, xml);
    }

    private String createRunConfigFromXml(String name, String rawXml) throws Exception {
        String basePath = project.getBasePath();
        if (basePath == null) return "Error: project base path is not available";

        String fileName = sanitizeConfigFileName(name) + ".xml";
        java.io.File dir = new java.io.File(basePath + "/.idea/runConfigurations");

        CompletableFuture<String> result = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                if (!dir.exists() && !dir.mkdirs()) {
                    result.complete("Error: could not create .idea/runConfigurations directory");
                    return;
                }
                var vDir = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .refreshAndFindFileByIoFile(dir);
                if (vDir == null) {
                    result.complete("Error: could not find .idea/runConfigurations in VFS");
                    return;
                }
                ApplicationManager.getApplication().runWriteAction(() -> {
                    try {
                        var vFile = vDir.findOrCreateChildData(this, fileName);
                        vFile.setBinaryContent(rawXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } catch (java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                result.complete("Created run configuration: '" + name + "' [Shell Script]"
                    + "\nUse run_configuration to execute it.");
            } catch (Exception e) {
                result.complete("Error creating run configuration: " + e.getMessage());
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    public String editRunConfiguration(JsonObject args) throws Exception {
        String name = args.get("name").getAsString();

        String abuseError = checkProgramArgsAbuse(args, null);
        if (abuseError != null) return abuseError;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                var settings = RunManager.getInstance(project).findConfigurationByName(name);
                if (settings == null) {
                    resultFuture.complete(ERROR_CONFIG_NOT_FOUND + name + "'");
                    return;
                }
                List<String> changes = applyEditProperties(settings.getConfiguration(), args);
                applySharedStorageChange(settings, args, changes);
                if (changes.isEmpty()) {
                    resultFuture.complete("No changes applied. Available properties: "
                        + "env (object), jvm_args, program_args, working_dir, "
                        + "main_class, test_class, test_method, tasks, script_parameters, shared");
                } else {
                    resultFuture.complete("Updated run configuration '" + name + "': "
                        + String.join(", ", changes));
                }
            } catch (Exception e) {
                resultFuture.complete("Error editing run configuration: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private List<String> applyEditProperties(RunConfiguration config, JsonObject args) {
        List<String> changes = new ArrayList<>();

        if (args.has(PARAM_ENV)) {
            applyEnvVars(config, args.getAsJsonObject(PARAM_ENV), changes);
        }
        if (args.has(PARAM_JVM_ARGS)) {
            setViaReflection(config, "setVMParameters",
                args.get(PARAM_JVM_ARGS).getAsString(), changes, "JVM args");
        }
        if (args.has(PARAM_PROGRAM_ARGS)) {
            setViaReflection(config, "setProgramParameters",
                args.get(PARAM_PROGRAM_ARGS).getAsString(), changes, "program args");
        }
        if (args.has(PARAM_WORKING_DIR)) {
            setViaReflection(config, METHOD_SET_WORKING_DIR,
                args.get(PARAM_WORKING_DIR).getAsString(), changes, "working directory");
        }

        applyTypeSpecificProperties(config, args);
        if (args.has(PARAM_MAIN_CLASS)) changes.add("main class");
        if (args.has(PARAM_TEST_CLASS)) changes.add("test class");
        if (args.has(PARAM_TASKS)) changes.add("Gradle tasks");
        if (args.has(PARAM_SCRIPT_PARAMETERS)) changes.add("script parameters");
        if (args.has(PARAM_SCRIPT_PATH)) changes.add("script path");

        return changes;
    }

    public String deleteRunConfiguration(JsonObject args) throws Exception {
        String name = args.get("name").getAsString();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                RunManager runManager = RunManager.getInstance(project);
                var settings = runManager.findConfigurationByName(name);
                if (settings == null) {
                    resultFuture.complete(ERROR_CONFIG_NOT_FOUND + name + ERROR_CONFIG_LIST_HINT);
                    return;
                }

                String typeName = settings.getType().getDisplayName();
                runManager.removeConfiguration(settings);
                resultFuture.complete("Deleted run configuration: " + name + " [" + typeName + "]");
            } catch (Exception e) {
                resultFuture.complete("Error deleting run configuration: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    // ---- Helper Methods ----

    /**
     * Check program_args for abuse patterns (same detection as run_command).
     * Also blocks Gradle configs with test task args.
     *
     * @param args       the tool arguments
     * @param configType the config type (e.g. "gradle"), or null if unknown/edit
     * @return error message if blocked, null if allowed
     */
    private static String checkProgramArgsAbuse(JsonObject args, String configType) {
        if (!args.has(PARAM_PROGRAM_ARGS)) return null;
        String progArgs = args.get(PARAM_PROGRAM_ARGS).getAsString();

        // General abuse detection (git, cat, sed, grep, find, test commands)
        String abuseType = ToolUtils.detectCommandAbuseType(progArgs);
        if (abuseType != null) {
            return ToolUtils.getCommandAbuseMessage(abuseType);
        }

        // Gradle-specific: block test tasks (bare "test" won't match general patterns)
        if ("gradle".equals(configType) && progArgs.toLowerCase().contains("test")) {
            return "Error: Use the run_tests tool to run tests, "
                + "not create_run_configuration with Gradle test tasks.";
        }

        return null;
    }

    private void applyTypeSpecificProperties(RunConfiguration config, JsonObject args) {
        List<String> ignore = new ArrayList<>();
        if (args.has(PARAM_MAIN_CLASS))
            setViaReflection(config, "setMainClassName", args.get(PARAM_MAIN_CLASS).getAsString(), ignore, null);

        // JUnit: test class/method via getPersistentData()
        if (args.has(PARAM_TEST_CLASS) || args.has(PARAM_TEST_METHOD)) {
            applyJUnitTestProperties(config, args);
        }

        // Gradle: tasks and script parameters via ExternalSystemRunConfiguration
        applyGradleProperties(config, args);

        // Shell Script: script path via ShRunConfiguration
        if (args.has(PARAM_SCRIPT_PATH)) {
            applyShellScriptProperties(config, args);
        }

        if (args.has(PARAM_MODULE_NAME)) {
            applyModuleProperty(config, args);
        }
    }

    private void applyJUnitTestProperties(RunConfiguration config, JsonObject args) {
        try {
            Object data = getJUnitPersistentData(config);

            if (args.has(PARAM_TEST_CLASS)) {
                applyTestClass(config, args, data);
            }
            if (args.has(PARAM_TEST_METHOD)) {
                setJUnitField(data, FIELD_METHOD_NAME, args.get(PARAM_TEST_METHOD).getAsString());
                setJUnitField(data, FIELD_TEST_OBJECT, TEST_TYPE_METHOD);
            }
        } catch (Exception e) {
            LOG.warn("Failed to set JUnit test class/method via getPersistentData", e);
            // Fallback: try direct setter
            List<String> ignore = new ArrayList<>();
            setViaReflection(config, "setMainClassName",
                args.has(PARAM_TEST_CLASS) ? args.get(PARAM_TEST_CLASS).getAsString() : "", ignore, null);
        }
    }

    private Object getJUnitPersistentData(RunConfiguration config) throws ReflectiveOperationException {
        var getData = config.getClass().getMethod("getPersistentData");
        return getData.invoke(config);
    }

    @SuppressWarnings("java:S3011") // reflection needed to set JUnit PersistentData fields
    private void setJUnitField(Object data, String fieldName, Object value) throws ReflectiveOperationException {
        data.getClass().getField(fieldName).set(data, value);
    }

    private void applyTestClass(RunConfiguration config, JsonObject args, Object data) throws ReflectiveOperationException {
        String testClass = args.get(PARAM_TEST_CLASS).getAsString();
        ClassResolverUtil.ClassInfo classInfo = classResolver.resolveClass(testClass);
        setJUnitField(data, "MAIN_CLASS_NAME", classInfo.fqn());
        setJUnitField(data, FIELD_TEST_OBJECT,
            args.has(PARAM_TEST_METHOD) ? TEST_TYPE_METHOD : TEST_TYPE_CLASS);

        // Auto-set module if not explicitly provided
        if (!args.has(PARAM_MODULE_NAME) && classInfo.module() != null) {
            trySetModuleOnConfig(config, classInfo.module());
        }
    }

    private void trySetModuleOnConfig(RunConfiguration config, Module module) {
        try {
            var setModule = config.getClass().getMethod(METHOD_SET_MODULE, Module.class);
            setModule.invoke(config, module);
        } catch (NoSuchMethodException e) {
            LOG.warn("Cannot set module on config: " + config.getClass().getName(), e);
        } catch (Exception e) {
            LOG.warn("Failed to set module on config", e);
        }
    }

    private void applyModuleProperty(RunConfiguration config, JsonObject args) {
        Module module = ModuleManager.getInstance(project)
            .findModuleByName(args.get(PARAM_MODULE_NAME).getAsString());
        if (module != null) {
            trySetModuleOnConfig(config, module);
        }
    }

    private void applyShellScriptProperties(RunConfiguration config, JsonObject args) {
        String scriptPath = args.get(PARAM_SCRIPT_PATH).getAsString();
        setViaReflection(config, "setScriptPath", scriptPath, new ArrayList<>(), null);
        // Ensure the config runs the file, not a stdin script snippet.
        try {
            var method = config.getClass().getMethod("setExecuteScriptFile", boolean.class);
            method.invoke(config, true);
        } catch (Exception ignored) {
            // ShRunConfiguration not available (Shell Script plugin not installed)
        }
    }

    private void applyGradleProperties(RunConfiguration config, JsonObject args) {
        if (!args.has(PARAM_TASKS) && !args.has(PARAM_SCRIPT_PARAMETERS) && !args.has(PARAM_JVM_ARGS)) return;
        try {
            Object rawSettings = config.getClass().getMethod("getSettings").invoke(config);
            if (!(rawSettings instanceof com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings settings))
                return;

            // externalProjectPath must be set; without it the Gradle config is non-functional.
            String basePath = project.getBasePath();
            if ((settings.getExternalProjectPath() == null || settings.getExternalProjectPath().isEmpty())
                && basePath != null && !basePath.isEmpty()) {
                settings.setExternalProjectPath(basePath);
            }
            if (settings.getExternalSystemIdString().isEmpty()) {
                settings.setExternalSystemIdString("GRADLE");
            }

            if (args.has(PARAM_TASKS)) {
                settings.setTaskNames(parseTaskNames(args.get(PARAM_TASKS)));
            }
            if (args.has(PARAM_SCRIPT_PARAMETERS)) {
                settings.setScriptParameters(args.get(PARAM_SCRIPT_PARAMETERS).getAsString());
            }
            // jvm_args for Gradle live on ExternalSystemTaskExecutionSettings.vmOptions —
            // setVMParameters on the config object itself would silently do nothing.
            if (args.has(PARAM_JVM_ARGS)) {
                settings.setVmOptions(args.get(PARAM_JVM_ARGS).getAsString());
            }
        } catch (Exception e) {
            LOG.warn("Failed to apply Gradle properties (config may not be a Gradle type)", e);
        }
    }

    private static List<String> parseTaskNames(com.google.gson.JsonElement tasksElem) {
        List<String> taskNames = new ArrayList<>();
        if (tasksElem.isJsonArray()) {
            for (var t : tasksElem.getAsJsonArray()) {
                taskNames.add(t.getAsString());
            }
        } else {
            for (String t : tasksElem.getAsString().split("\\s+")) {
                if (!t.isEmpty()) taskNames.add(t);
            }
        }
        return taskNames;
    }

    private static void applySharedStorageChange(
        com.intellij.execution.RunnerAndConfigurationSettings settings,
        JsonObject args, List<String> changes) {
        if (!args.has(PARAM_SHARED)) return;
        boolean shared = args.get(PARAM_SHARED).getAsBoolean();
        if (shared) {
            settings.storeInDotIdeaFolder();
        } else {
            settings.storeInLocalWorkspace();
        }
        changes.add(shared ? "stored as shared" : "stored in workspace");
    }

    private void applyEnvVars(RunConfiguration config, JsonObject envObj, List<String> changes) {
        try {
            Map<String, String> envs = getConfigEnvVars(config);
            mergeEnvVars(envs, envObj, changes);
            setConfigEnvVars(config, envs);
        } catch (Exception e) {
            changes.add("env vars (failed: " + e.getMessage() + ")");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> getConfigEnvVars(RunConfiguration config) {
        try {
            var getEnvs = config.getClass().getMethod("getEnvs");
            return new HashMap<>((Map<String, String>) getEnvs.invoke(config));
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private void setConfigEnvVars(RunConfiguration config, Map<String, String> envs)
        throws ReflectiveOperationException {
        var setEnvs = config.getClass().getMethod("setEnvs", Map.class);
        setEnvs.invoke(config, envs);
    }

    private void setViaReflection(Object target, String methodName, String value,
                                  List<String> changes, String label) {
        try {
            var method = target.getClass().getMethod(methodName, String.class);
            method.invoke(target, value);
            if (label != null) changes.add(label);
        } catch (Exception ignored) {
            // Reflection method may not exist on this config type
        }
    }

    // ── Testable pure-logic helpers ──────────────────────────

    /**
     * Sanitizes a run-configuration name into a safe filename for
     * {@code .idea/runConfigurations/}.
     */
    static String sanitizeConfigFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Merges environment variable overrides from a {@link JsonObject} into an existing map.
     * A {@code null} JSON value removes the key; any other value sets it.
     * Change descriptions are appended to {@code changes}.
     */
    static void mergeEnvVars(Map<String, String> envs, JsonObject envObj, List<String> changes) {
        for (var entry : envObj.entrySet()) {
            if (entry.getValue().isJsonNull()) {
                envs.remove(entry.getKey());
                changes.add("removed env " + entry.getKey());
            } else {
                envs.put(entry.getKey(), entry.getValue().getAsString());
                changes.add("env " + entry.getKey());
            }
        }
    }

    // ---- Dynamic Type Discovery & Template-based Creation ----

    public String listRunConfigurationTypes() {
        var descriptors = PlatformApiCompat.listAllConfigTypeDescriptors();
        if (descriptors.isEmpty()) return "No run configuration types found.";
        var sb = new StringBuilder("Available run configuration types (")
            .append(descriptors.size()).append("):\n\n");
        for (var d : descriptors) {
            sb.append("  id=").append(d.id())
                .append("  display=\"").append(d.displayName()).append("\"");
            if (d.factoryNames().size() > 1) {
                sb.append("  factories=[").append(String.join(", ", d.factoryNames())).append("]");
            }
            sb.append("\n");
        }
        sb.append("\nUse get_run_configuration_template to see available options for any type.");
        return sb.toString();
    }

    public String getRunConfigTemplate(JsonObject args) throws Exception {
        String typeName = args.get("type").getAsString();
        String factoryName = args.has(PARAM_FACTORY_NAME) ? args.get(PARAM_FACTORY_NAME).getAsString() : null;

        CompletableFuture<String> result = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                var configType = PlatformApiCompat.findConfigurationType(typeName);
                if (configType == null) {
                    result.complete("Error: Unknown type '" + typeName + "'. "
                        + "Use list_run_configuration_types to see available types.");
                    return;
                }
                var factory = PlatformApiCompat.findFactory(configType, factoryName);
                var config = factory.createTemplateConfiguration(project);
                config.setName("Example");

                var element = buildConfigElement("Example", configType.getId(), factory.getName());
                config.writeExternal(element);

                var schema = xmlElementToJsonSchema(element);

                // Some types (e.g. JavascriptDebugType, ChromiumRemoteDebugType) use
                // XmlSerializer-based state rather than writeExternal and produce empty XML.
                // Fall back to a hardcoded schema so agents know what options to pass.
                if (schema.getAsJsonObject(JSON_KEY_PROPERTIES).entrySet().isEmpty()) {
                    var knownSchema = KNOWN_EMPTY_TYPE_SCHEMAS.get(configType.getId());
                    if (knownSchema != null) {
                        schema = knownSchema.deepCopy();
                        schema.addProperty("note",
                            "Schema inferred from known properties (writeExternal produces nothing for this type). "
                                + "Options are applied via XmlSerializer deserialization.");
                    }
                }

                schema.addProperty("description",
                    configType.getDisplayName() + " (type id: " + configType.getId()
                        + ", factory: " + factory.getName() + ")");

                var gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
                result.complete("JSON schema for '" + configType.getDisplayName()
                    + "' (type id: " + configType.getId()
                    + ", factory: " + factory.getName() + ")\n\n"
                    + gson.toJson(schema)
                    + "\n\nCreate with:\n  create_run_configuration(name=\"My Config\", type=\""
                    + configType.getId() + "\", config={...from schema...})");
            } catch (Exception e) {
                result.complete("Error generating template for '" + typeName + "': " + e.getMessage());
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    private String createRunConfigWithOptions(String name, String typeName, JsonObject args) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                var configType = PlatformApiCompat.findConfigurationType(typeName);
                if (configType == null) {
                    result.complete("Error: Unknown type '" + typeName + "'. "
                        + "Use list_run_configuration_types to see available types.");
                    return;
                }
                var factory = PlatformApiCompat.findFactory(configType,
                    args.has(PARAM_FACTORY_NAME) ? args.get(PARAM_FACTORY_NAME).getAsString() : null);
                var runManager = RunManager.getInstance(project);
                var settings = runManager.createConfiguration(name, factory);
                applyConfigFromOptions(settings.getConfiguration(), configType.getId(), factory.getName(), args);

                String validationError = PlatformApiCompat.checkRunConfigForError(settings.getConfiguration());
                if (validationError != null) {
                    result.complete("Error: Configuration validation failed — " + validationError
                        + "\nUse get_run_configuration_template with type=\"" + configType.getId()
                        + "\" to see required options.");
                    return;
                }
                saveNewConfig(runManager, settings, args);
                result.complete("Created run configuration: '" + name + "' ["
                    + configType.getDisplayName() + "]"
                    + (isSharedConfig(args) ? " (shared)" : " (workspace-local)")
                    + "\nUse run_configuration to execute it.");
            } catch (Exception e) {
                result.complete("Error creating run configuration: " + e.getMessage());
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    private void applyConfigFromOptions(RunConfiguration config, String typeId,
                                        String factoryName, JsonObject args) {
        // writeExternal/readExternal throw checked exceptions (WriteExternalException, InvalidDataException).
        // The IDE daemon cannot see these declarations due to EP cascade, but Gradle compiles correctly.
        try {
            var element = buildConfigElement(config.getName(), typeId, factoryName);
            config.writeExternal(element);

            if (args.has(PARAM_CONFIG)) {
                var configJson = args.getAsJsonObject(PARAM_CONFIG);
                var schema = xmlElementToJsonSchema(element);
                // Only validate when schema has known properties. Types that use XmlSerializer
                // rather than writeExternal produce an empty schema — skip validation so agents
                // can still pass options (mergeJsonConfigIntoXml will add them to the element).
                var schemaProperties = schema.getAsJsonObject(JSON_KEY_PROPERTIES);
                if (!schemaProperties.entrySet().isEmpty()) {
                    var validationError = validateJsonAgainstSchema(configJson, schema);
                    if (validationError != null) throw new IllegalArgumentException(validationError);
                }
                mergeJsonConfigIntoXml(element, configJson);
            }

            config.readExternal(element);

            // Secondary deserialization for configs (e.g. JavascriptDebugType) that use
            // XmlSerializer / @State annotations rather than readExternal to read their fields.
            tryXmlSerializerDeserialize(config, element);

            // Apply legacy top-level parameters (env, jvm_args, program_args, main_class, etc.)
            // for backward compatibility with agents using the pre-template workflow.
            applyEditProperties(config, args);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply config: " + e.getMessage(), e);
        }
    }

    /**
     * Attempts to apply config options via XmlSerializer for config types that use
     * {@code @State} annotations rather than {@code readExternal} (e.g. JavascriptDebugType).
     * Silently ignored for types that don't support XmlSerializer.
     */
    private static void tryXmlSerializerDeserialize(RunConfiguration config, org.jdom.Element element) {
        try {
            com.intellij.util.xmlb.XmlSerializer.deserializeInto(config, element);
        } catch (Exception ignored) {
            // Not all config types support XmlSerializer; readExternal is the primary path.
        }
    }

    private static boolean isSharedConfig(JsonObject args) {
        return !args.has(PARAM_SHARED) || args.get(PARAM_SHARED).getAsBoolean();
    }

    private static void saveNewConfig(RunManager runManager,
                                      com.intellij.execution.RunnerAndConfigurationSettings settings,
                                      JsonObject args) {
        if (isSharedConfig(args)) settings.storeInDotIdeaFolder();
        else settings.storeInLocalWorkspace();
        runManager.addConfiguration(settings);
        runManager.setSelectedConfiguration(settings);
    }

    private static org.jdom.Element buildConfigElement(String name, String typeId, String factoryName) {
        var element = new org.jdom.Element("configuration");
        element.setAttribute(JSON_KEY_DEFAULT, "false");
        element.setAttribute("name", name);
        element.setAttribute(JSON_KEY_TYPE, typeId);
        element.setAttribute("factoryName", factoryName);
        return element;
    }

    /**
     * Returns hardcoded JSON schemas for run configuration types that produce empty XML from
     * {@code writeExternal} (typically Kotlin configs using {@code @State} / XmlSerializer).
     * This enables {@link #getRunConfigTemplate} to return useful documentation for those types.
     */
    private static Map<String, JsonObject> buildKnownEmptyTypeSchemas() {
        Map<String, JsonObject> schemas = new HashMap<>();

        // JavaScript Debug: opens a URL in a browser with JS debugging attached.
        JsonObject jsDebugProps = new JsonObject();
        jsDebugProps.add("url", schemaPrimitive("http://localhost:3000/"));
        jsDebugProps.add("browser", schemaPrimitive(""));
        jsDebugProps.add("browserPath", schemaPrimitive(""));
        JsonObject jsDebug = new JsonObject();
        jsDebug.addProperty(JSON_KEY_TYPE, JSON_TYPE_OBJECT);
        jsDebug.add(JSON_KEY_PROPERTIES, jsDebugProps);
        schemas.put(TYPE_JS_DEBUG, jsDebug);

        // Chromium Remote Debug: attaches to a running Chrome/Chromium instance.
        JsonObject chromeProps = new JsonObject();
        chromeProps.add("host", schemaPrimitive("localhost"));
        chromeProps.add("port", schemaPrimitive("9222"));
        chromeProps.add("localRoot", schemaPrimitive(""));
        chromeProps.add("remoteRoot", schemaPrimitive(""));
        JsonObject chromeAttach = new JsonObject();
        chromeAttach.addProperty(JSON_KEY_TYPE, JSON_TYPE_OBJECT);
        chromeAttach.add(JSON_KEY_PROPERTIES, chromeProps);
        schemas.put(TYPE_CHROMIUM_REMOTE, chromeAttach);

        return Collections.unmodifiableMap(schemas);
    }

    static JsonObject xmlElementToJsonSchema(org.jdom.Element element) {
        var schema = new JsonObject();
        schema.addProperty(JSON_KEY_TYPE, JSON_TYPE_OBJECT);
        var properties = new JsonObject();
        for (org.jdom.Element child : element.getChildren()) {
            String key;
            JsonObject childSchema;
            if (XML_ELEM_OPTION.equals(child.getName()) && child.getAttributeValue("name") != null) {
                key = child.getAttributeValue("name");
                childSchema = inferOptionSchema(child);
            } else {
                key = child.getName();
                childSchema = inferElementSchema(child);
            }
            properties.add(key, childSchema);
        }
        schema.add(JSON_KEY_PROPERTIES, properties);
        return schema;
    }

    /**
     * Recursively merges a JSON config object into a JDOM element.
     * String values update/create {@code <option name="K" value="V"/>} (Pattern A) by default.
     * Array values build {@code <option name="K"><list><option value="x"/></list></option>}.
     * Object values recurse into the matching child element; "envs" gets special env-var handling.
     */
    static void mergeJsonConfigIntoXml(org.jdom.Element element, JsonObject config) {
        for (var entry : config.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            org.jdom.Element target = findOptionChild(element, key);
            if (target == null) target = element.getChild(key);
            if (value.isJsonPrimitive()) {
                mergeStringIntoXml(element, key, value.getAsString(), target);
            } else if (value.isJsonArray()) {
                mergeArrayIntoXml(element, key, value.getAsJsonArray(), target);
            } else if (value.isJsonObject()) {
                mergeObjectIntoXml(element, key, value.getAsJsonObject(), target);
            }
        }
    }

    private static org.jdom.Element findOptionChild(org.jdom.Element parent, String name) {
        for (var child : parent.getChildren(XML_ELEM_OPTION)) {
            if (name.equals(child.getAttributeValue("name"))) return child;
        }
        return null;
    }

    private static void mergeStringIntoXml(org.jdom.Element parent, String key, String value,
                                           org.jdom.Element existing) {
        if (existing != null) {
            if (existing.getAttributeValue(XML_ATTR_VALUE) != null) {
                existing.setAttribute(XML_ATTR_VALUE, value);
            } else {
                existing.setText(value);
            }
        } else {
            var opt = new org.jdom.Element(XML_ELEM_OPTION);
            opt.setAttribute("name", key);
            opt.setAttribute(XML_ATTR_VALUE, value);
            parent.addContent(opt);
        }
    }

    private static void mergeArrayIntoXml(org.jdom.Element parent, String key, JsonArray array,
                                          org.jdom.Element existing) {
        var container = existing;
        if (container == null) {
            container = new org.jdom.Element(XML_ELEM_OPTION);
            container.setAttribute("name", key);
            parent.addContent(container);
        }
        var list = container.getChild("list");
        if (list == null) {
            list = new org.jdom.Element("list");
            container.addContent(list);
        }
        list.removeChildren(XML_ELEM_OPTION);
        for (var item : array) {
            var opt = new org.jdom.Element(XML_ELEM_OPTION);
            opt.setAttribute(XML_ATTR_VALUE, item.getAsString());
            list.addContent(opt);
        }
    }

    private static void mergeObjectIntoXml(org.jdom.Element parent, String key, JsonObject obj,
                                           org.jdom.Element existing) {
        if ("envs".equals(key)) {
            var envsElem = parent.getChild("envs");
            if (envsElem == null) {
                envsElem = new org.jdom.Element("envs");
                parent.addContent(envsElem);
            }
            // Detect the child tag used in this envs element (usually "env")
            String childTag = envsElem.getChildren().isEmpty() ? "env"
                : envsElem.getChildren().getFirst().getName();
            for (var entry : obj.entrySet()) {
                var envVar = new org.jdom.Element(childTag);
                envVar.setAttribute("name", entry.getKey());
                envVar.setAttribute(XML_ATTR_VALUE, entry.getValue().getAsString());
                envsElem.addContent(envVar);
            }
            return;
        }
        var target = existing != null ? existing : parent.getChild(key);
        if (target == null) {
            target = new org.jdom.Element(key);
            parent.addContent(target);
        }
        mergeJsonConfigIntoXml(target, obj);
    }

    private static JsonObject inferOptionSchema(org.jdom.Element option) {
        var children = option.getChildren();
        String value = option.getAttributeValue(XML_ATTR_VALUE);
        if (children.isEmpty()) return schemaPrimitive(value != null ? value : "");
        if (children.size() == 1 && "list".equals(children.getFirst().getName())) {
            return schemaArray(children.getFirst());
        }
        return xmlElementToJsonSchema(option);
    }

    private static JsonObject inferElementSchema(org.jdom.Element element) {
        if ("envs".equals(element.getName())) {
            return schemaDict();
        }
        var children = element.getChildren();
        String valueAttr = element.getAttributeValue(XML_ATTR_VALUE);
        if (children.isEmpty() && valueAttr != null) return schemaPrimitive(valueAttr);
        String text = element.getTextTrim();
        if (children.isEmpty() && !text.isEmpty()) return schemaPrimitive(text);
        if (children.size() == 1 && "list".equals(children.getFirst().getName())) {
            return schemaArray(children.getFirst());
        }
        if (children.isEmpty()) return schemaPrimitive("");
        return xmlElementToJsonSchema(element);
    }

    private static JsonObject schemaPrimitive(String defaultValue) {
        var prop = new JsonObject();
        if ("true".equalsIgnoreCase(defaultValue) || "false".equalsIgnoreCase(defaultValue)) {
            prop.addProperty(JSON_KEY_TYPE, JSON_TYPE_BOOLEAN);
            prop.addProperty(JSON_KEY_DEFAULT, Boolean.parseBoolean(defaultValue));
        } else {
            prop.addProperty(JSON_KEY_TYPE, JSON_TYPE_STRING);
            prop.addProperty(JSON_KEY_DEFAULT, defaultValue);
        }
        return prop;
    }

    private static JsonObject schemaArray(org.jdom.Element listElement) {
        var prop = new JsonObject();
        prop.addProperty(JSON_KEY_TYPE, JSON_TYPE_ARRAY);
        var items = new JsonObject();
        items.addProperty(JSON_KEY_TYPE, JSON_TYPE_STRING);
        prop.add(JSON_KEY_ITEMS, items);
        var defaults = new JsonArray();
        for (var item : listElement.getChildren()) {
            String v = item.getAttributeValue(XML_ATTR_VALUE);
            if (v != null) defaults.add(v);
        }
        if (!defaults.isEmpty()) prop.add(JSON_KEY_DEFAULT, defaults);
        return prop;
    }

    private static JsonObject schemaDict() {
        var prop = new JsonObject();
        prop.addProperty(JSON_KEY_TYPE, JSON_TYPE_OBJECT);
        prop.addProperty("description", "Environment variables as key-value pairs");
        var additionalProps = new JsonObject();
        additionalProps.addProperty(JSON_KEY_TYPE, JSON_TYPE_STRING);
        prop.add("additionalProperties", additionalProps);
        return prop;
    }

    static String validateJsonAgainstSchema(JsonObject config, JsonObject schema) {
        if (!schema.has(JSON_KEY_PROPERTIES)) return null;
        var properties = schema.getAsJsonObject(JSON_KEY_PROPERTIES);
        var errors = new ArrayList<String>();
        for (var entry : config.entrySet()) {
            String key = entry.getKey();
            if (!properties.has(key)) {
                errors.add("Unknown option '" + key + "'");
                continue;
            }
            collectTypeErrors(key, entry.getValue(), properties.getAsJsonObject(key), errors);
        }
        return errors.isEmpty() ? null
            : "Schema validation failed:\n"
              + errors.stream().map(e -> "  - " + e).collect(Collectors.joining("\n"));
    }

    private static void collectTypeErrors(String key, JsonElement value, JsonObject propSchema,
                                          List<String> errors) {
        String expectedType = propSchema.has(JSON_KEY_TYPE)
            ? propSchema.get(JSON_KEY_TYPE).getAsString() : JSON_TYPE_STRING;
        if (JSON_TYPE_ARRAY.equals(expectedType)) {
            if (!value.isJsonArray()) errors.add("'" + key + "' must be an array");
        } else if (JSON_TYPE_OBJECT.equals(expectedType)) {
            collectObjectTypeErrors(key, value, propSchema, errors);
        } else if (JSON_TYPE_STRING.equals(expectedType) && !value.isJsonPrimitive()) {
            errors.add("'" + key + "' must be a string, got "
                + (value.isJsonArray() ? JSON_TYPE_ARRAY : JSON_TYPE_OBJECT));
        } else if (JSON_TYPE_BOOLEAN.equals(expectedType) && !value.isJsonPrimitive()) {
            errors.add("'" + key + "' must be a boolean, got "
                + (value.isJsonArray() ? JSON_TYPE_ARRAY : JSON_TYPE_OBJECT));
        }
    }

    private static void collectObjectTypeErrors(String key, JsonElement value, JsonObject propSchema,
                                                List<String> errors) {
        if (!value.isJsonObject()) {
            errors.add("'" + key + "' must be an object");
            return;
        }
        if (propSchema.has(JSON_KEY_PROPERTIES)) {
            String nested = validateJsonAgainstSchema(value.getAsJsonObject(), propSchema);
            if (nested != null) errors.add("In '" + key + "': " + nested);
        }
    }

    /**
     * Formats the message returned when a run configuration completes.
     */
    static String formatRunCompletionMessage(String name, int exitCode) {
        String status = exitCode == 0 ? "PASSED" : "FAILED (exit code " + exitCode + ")";
        return "Run configuration '" + name + "' " + status + ". "
            + "Use read_run_output with tab_name='" + name + "' to see full output.";
    }

    /**
     * Formats the message returned when a run configuration times out.
     */
    static String formatRunTimeoutMessage(String name, int waitSeconds) {
        return "Run configuration '" + name + "' did not complete within " + waitSeconds + "s. "
            + "Use read_run_output with tab_name='" + name + "' to see current output.";
    }
}
