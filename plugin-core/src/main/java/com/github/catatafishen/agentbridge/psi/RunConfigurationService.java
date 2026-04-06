package com.github.catatafishen.agentbridge.psi;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
    private static final String PARAM_MODULE_NAME = "module_name";

    // Reflection Field/Method Names
    private static final String FIELD_TEST_OBJECT = "TEST_OBJECT";
    private static final String FIELD_METHOD_NAME = "METHOD_NAME";
    private static final String METHOD_SET_MODULE = "setModule";

    // Test Type Values
    private static final String TEST_TYPE_METHOD = "method";
    private static final String TEST_TYPE_CLASS = "class";

    private static final String PARAM_SHARED = "shared";
    private static final String PARAM_TASKS = "tasks";
    private static final String PARAM_SCRIPT_PARAMETERS = "script_parameters";
    private static final String PARAM_SCRIPT_PATH = "script_path";
    private static final String ERROR_CONFIG_NOT_FOUND = "Run configuration not found: '";
    private static final String ERROR_CONFIG_LIST_HINT = "'. Use list_run_configurations to see available configs.";

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
            return "Run configuration '" + name + "' did not complete within " + waitSeconds + "s. "
                + "Use read_run_output with tab_name='" + name + "' to see current output.";
        }

        int exitCode = exitCodeRef.get();
        String status = exitCode == 0 ? "PASSED" : "FAILED (exit code " + exitCode + ")";
        return "Run configuration '" + name + "' " + status + ". "
            + "Use read_run_output with tab_name='" + name + "' to see full output.";
    }

    public String createRunConfiguration(JsonObject args) throws Exception {
        String name = args.get("name").getAsString();

        // raw_xml path: write the XML to .idea/runConfigurations/ and let the IDE pick it up.
        if (args.has("raw_xml")) {
            return createRunConfigFromXml(name, args.get("raw_xml").getAsString());
        }

        if (!args.has("type")) {
            return "Error: 'type' is required unless 'raw_xml' is provided. "
                + "Available types: " + String.join(", ", PlatformApiCompat.listConfigurationTypeNames());
        }
        String type = args.get("type").getAsString().toLowerCase();

        // Abuse detection on program_args — same rules as run_command
        String abuseError = checkProgramArgsAbuse(args, type);
        if (abuseError != null) return abuseError;

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                RunManager runManager = RunManager.getInstance(project);

                // Find the configuration type
                var configType = PlatformApiCompat.findConfigurationType(type);
                if (configType == null) {
                    resultFuture.complete("Unknown configuration type: '" + type
                        + "'. Available types: " + String.join(", ", PlatformApiCompat.listConfigurationTypeNames()));
                    return;
                }

                var factory = configType.getConfigurationFactories()[0];
                var settings = runManager.createConfiguration(name, factory);
                RunConfiguration config = settings.getConfiguration();

                // Apply common properties
                applyConfigProperties(config, args);

                // Apply type-specific properties (includes Gradle tasks)
                applyTypeSpecificProperties(config, args);

                // Store as shared (project file) by default
                boolean shared = !args.has(PARAM_SHARED) || args.get(PARAM_SHARED).getAsBoolean();
                if (shared) {
                    settings.storeInDotIdeaFolder();
                } else {
                    settings.storeInLocalWorkspace();
                }

                runManager.addConfiguration(settings);
                runManager.setSelectedConfiguration(settings);

                String storage = shared ? " (shared/project file)" : " (workspace-local)";
                resultFuture.complete("Created run configuration: " + name
                    + " [" + configType.getDisplayName() + "]" + storage
                    + "\nUse run_configuration to execute it, or edit_run_configuration to modify it.");
            } catch (Exception e) {
                resultFuture.complete("Error creating run configuration: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private String createRunConfigFromXml(String name, String rawXml) {
        String basePath = project.getBasePath();
        if (basePath == null) return "Error: project base path is not available";

        String filename = name.replaceAll("[^a-zA-Z0-9._-]", "_") + ".xml";
        java.io.File runConfigsDir = new java.io.File(basePath, ".idea/runConfigurations");
        java.io.File xmlFile = new java.io.File(runConfigsDir, filename);

        if (!runConfigsDir.exists() && !runConfigsDir.mkdirs()) {
            return "Error: could not create directory " + runConfigsDir.getPath();
        }
        try (var writer = new java.io.FileWriter(xmlFile, java.nio.charset.StandardCharsets.UTF_8)) {
            writer.write(rawXml);
        } catch (java.io.IOException e) {
            return "Error writing run configuration XML: " + e.getMessage();
        }

        // Refresh the VFS so IntelliJ's RunManager picks up the new file immediately.
        var vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(xmlFile);
        if (vf != null) vf.refresh(false, false);

        return "Created run configuration '" + name + "' from XML → " + xmlFile.getPath()
            + "\nUse run_configuration to execute it.";
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

        if (args.has("env")) {
            applyEnvVars(config, args.getAsJsonObject("env"), changes);
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
            setViaReflection(config, "setWorkingDirectory",
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

    private void applyConfigProperties(RunConfiguration config, JsonObject args) {
        List<String> ignore = new ArrayList<>();
        if (args.has("env")) applyEnvVars(config, args.getAsJsonObject("env"), ignore);
        if (args.has(PARAM_JVM_ARGS))
            setViaReflection(config, "setVMParameters", args.get(PARAM_JVM_ARGS).getAsString(), ignore, null);
        if (args.has(PARAM_PROGRAM_ARGS))
            setViaReflection(config, "setProgramParameters", args.get(PARAM_PROGRAM_ARGS).getAsString(), ignore, null);
        if (args.has(PARAM_WORKING_DIR))
            setViaReflection(config, "setWorkingDirectory", args.get(PARAM_WORKING_DIR).getAsString(), ignore, null);
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
        if (!args.has(PARAM_TASKS) && !args.has(PARAM_SCRIPT_PARAMETERS)) return;
        try {
            var getSettings = config.getClass().getMethod("getSettings");
            var settings = getSettings.invoke(config);

            if (args.has(PARAM_TASKS)) {
                List<String> taskNames = parseTaskNames(args.get(PARAM_TASKS));
                var setTaskNames = settings.getClass().getMethod("setTaskNames", List.class);
                setTaskNames.invoke(settings, taskNames);
            }
            if (args.has(PARAM_SCRIPT_PARAMETERS)) {
                var setScriptParams = settings.getClass().getMethod("setScriptParameters", String.class);
                setScriptParams.invoke(settings, args.get(PARAM_SCRIPT_PARAMETERS).getAsString());
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

            // Merge new values (null value removes the key)
            for (var entry : envObj.entrySet()) {
                if (entry.getValue().isJsonNull()) {
                    envs.remove(entry.getKey());
                    changes.add("removed env " + entry.getKey());
                } else {
                    envs.put(entry.getKey(), entry.getValue().getAsString());
                    changes.add("env " + entry.getKey());
                }
            }

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
}
