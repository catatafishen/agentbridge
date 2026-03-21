package com.github.catatafishen.ideagentforcopilot.psi.tools.editor;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.ScratchFileRenderer;
import com.google.gson.JsonObject;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.scratch.JavaScratchConfiguration;
import com.intellij.execution.scratch.JavaScratchConfigurationType;
import com.intellij.ide.scratch.ScratchFileService;
import com.intellij.ide.scratch.ScratchRootType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs a scratch file using an appropriate run configuration.
 */
public final class RunScratchFileTool extends EditorTool {

    private static final Logger LOG = Logger.getInstance(RunScratchFileTool.class);
    private static final String PARAM_MODULE = "module";
    private static final String PARAM_INTERACTIVE = "interactive";

    public RunScratchFileTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "run_scratch_file";
    }

    @Override
    public @NotNull String displayName() {
        return "Run Scratch File";
    }

    @Override
    public @NotNull String description() {
        return "Run a scratch file using an appropriate run configuration";
    }

    

    @Override
    public @NotNull String kind() {
        return "edit";
    }
@Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"name", TYPE_STRING, "Scratch file name with extension (e.g., 'test.kts', 'MyApp.java', 'hello.js')"},
            {PARAM_MODULE, TYPE_STRING, "Optional: module name for classpath (e.g., 'plugin-core')"},
            {PARAM_INTERACTIVE, TYPE_BOOLEAN, "Optional: enable interactive/REPL mode (Kotlin scripts)"}
        }, "name");
    }

    @Override
    public @NotNull Object resultRenderer() {
        return ScratchFileRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("name")) {
            return "Error: 'name' parameter is required (scratch file name, e.g. 'test.kts')";
        }
        String name = args.get("name").getAsString();
        String moduleName = args.has(PARAM_MODULE) ? args.get(PARAM_MODULE).getAsString() : "";
        boolean interactive = args.has(PARAM_INTERACTIVE) && args.get(PARAM_INTERACTIVE).getAsBoolean();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                executeScratchFile(name, moduleName, interactive, resultFuture);
            } catch (Exception e) {
                LOG.warn("Failed to run scratch file", e);
                resultFuture.complete("Error running scratch file: " + e.getMessage());
            }
        });

        return resultFuture.get(15, TimeUnit.SECONDS);
    }

    private void executeScratchFile(String name, String moduleName, boolean interactive,
                                    CompletableFuture<String> resultFuture) {
        VirtualFile scratchFile = findScratchFile(name);
        if (scratchFile == null) {
            resultFuture.complete("Error: Scratch file not found: '" + name
                + "'. Use list_scratch_files to see available files.");
            return;
        }

        FileEditorManager.getInstance(project).openFile(scratchFile, false);

        String extension = scratchFile.getExtension();
        var configType = findScratchConfigType(extension);
        if (configType == null) {
            resultFuture.complete("Error: No run configuration type found for ."
                + extension + " files. Available types: " + listAvailableConfigTypes());
            return;
        }

        var factories = configType.getConfigurationFactories();
        if (factories.length == 0) {
            resultFuture.complete("Error: No configuration factory for " + configType.getDisplayName());
            return;
        }

        var runManager = RunManager.getInstance(project);
        var settings = runManager.createConfiguration("Scratch: " + scratchFile.getName(), factories[0]);
        var config = settings.getConfiguration();

        boolean pathSet = configureScratchPath(config, scratchFile);
        boolean needsModule = config instanceof JavaScratchConfiguration;

        if ("py".equalsIgnoreCase(extension)) {
            trySetPythonSdkHome(config);
        }
        trySetWorkingDirectory(config, scratchFile.getParent().getPath());

        String moduleStatus = configureScratchModule(config, moduleName, needsModule);
        String interactiveStatus = configureScratchInteractive(config, interactive);

        String launchResult = launchScratchConfig(settings, configType, runManager);
        if (launchResult != null) {
            resultFuture.complete(launchResult);
            return;
        }

        resultFuture.complete("Started: " + scratchFile.getName()
            + " [" + configType.getDisplayName() + "]"
            + (pathSet ? "" : "\nWarning: Could not set script path on config")
            + moduleStatus + interactiveStatus
            + "\nOutput will appear in the Run panel. Use read_run_output to read it.");
    }

    private boolean configureScratchPath(RunConfiguration config, VirtualFile scratchFile) {
        if (config instanceof JavaScratchConfiguration scratchConfig) {
            scratchConfig.setScratchFileUrl(scratchFile.getUrl());
            scratchConfig.setMainClassName(scratchFile.getNameWithoutExtension());
            return true;
        }
        return setScriptPath(config, scratchFile.getPath());
    }

    private String configureScratchModule(RunConfiguration config, String moduleName, boolean needsModule) {
        if (!moduleName.isEmpty()) {
            var module = ModuleManager.getInstance(project).findModuleByName(moduleName);
            if (module != null) {
                trySetModule(config, module);
                return "\nClasspath: " + moduleName;
            }
            return "\nWarning: Module '" + moduleName + "' not found";
        }
        if (needsModule) {
            var modules = ModuleManager.getInstance(project).getModules();
            if (modules.length > 0) {
                trySetModule(config, modules[0]);
                return "\nClasspath: " + modules[0].getName() + " (auto-detected)";
            }
        }
        return "";
    }

    private static String configureScratchInteractive(RunConfiguration config, boolean interactive) {
        if (!interactive) return "";
        boolean set = trySetInteractiveMode(config, true);
        return set ? "\nInteractive mode: ON"
            : "\nWarning: Interactive mode not supported for this config type";
    }

    @Nullable
    private String launchScratchConfig(RunnerAndConfigurationSettings settings,
                                       ConfigurationType configType,
                                       RunManager runManager) {
        settings.setTemporary(true);
        settings.setEditBeforeRun(false);
        runManager.addConfiguration(settings);
        runManager.setSelectedConfiguration(settings);

        var executor = DefaultRunExecutor.getRunExecutorInstance();
        var envBuilder = ExecutionEnvironmentBuilder.createOrNull(executor, settings);
        if (envBuilder == null) {
            return "Error: Cannot create execution environment for " + configType.getDisplayName();
        }
        ExecutionManager.getInstance(project).restartRunProfile(envBuilder.build());
        return null;
    }

    private VirtualFile findScratchFile(String name) {
        VirtualFile asPath = resolveVirtualFile(name);
        if (asPath != null && !asPath.isDirectory()) return asPath;

        try {
            var scratchRoot = ScratchRootType.getInstance();
            VirtualFile file = scratchRoot.findFile(null, name,
                ScratchFileService.Option.existing_only);
            if (file != null && !file.isDirectory()) return file;
        } catch (Exception ignored) {
            // Fall through to directory search
        }

        try {
            var scratchService = ScratchFileService.getInstance();
            var scratchRoot = ScratchRootType.getInstance();
            VirtualFile dir = scratchService.getVirtualFile(scratchRoot);
            if (dir != null) return findFileByName(dir, name, 0);
        } catch (Exception ignored) {
            // Fall through
        }

        return null;
    }

    private static VirtualFile findFileByName(VirtualFile dir, String name, int depth) {
        if (depth > 3) return null;
        for (VirtualFile child : dir.getChildren()) {
            if (child.isDirectory()) {
                VirtualFile found = findFileByName(child, name, depth + 1);
                if (found != null) return found;
            } else if (child.getName().equals(name)) {
                return child;
            }
        }
        return null;
    }

    private static ConfigurationType findScratchConfigType(String extension) {
        if (extension == null) return null;

        if ("java".equalsIgnoreCase(extension)) {
            return JavaScratchConfigurationType.getInstance();
        }

        String searchTerm = switch (extension.toLowerCase()) {
            case "kts" -> "kotlin script";
            case "groovy", "gvy" -> "groovy";
            case "py" -> "id:PythonConfigurationType";
            case "scala" -> "scala";
            case "js", "mjs", "ts", "mts" -> "id:NodeJSConfigurationType";
            default -> extension;
        };

        if (searchTerm.startsWith("id:")) {
            return ConfigurationTypeUtil.findConfigurationType(searchTerm.substring(3));
        }

        return PlatformApiCompat.findConfigurationTypeBySearch(searchTerm);
    }

    private static String listAvailableConfigTypes() {
        return String.join(", ", PlatformApiCompat.listConfigurationTypeNames());
    }

    @SuppressWarnings("java:S3011") // reflection needed for cross-plugin config API
    private static boolean setScriptPath(RunConfiguration config, String path) {
        for (String method : List.of("setupFilePath", "setFilePath", "setScriptName", "setScriptPath",
            "setScriptFile", "setMainScriptFilePath", "setMainClassName")) {
            try {
                config.getClass().getMethod(method, String.class).invoke(config, path);
                return true;
            } catch (Exception ignored) {
                // Try next method name
            }
        }
        return false;
    }

    @SuppressWarnings("java:S3011") // reflection needed for cross-plugin config API
    private static void trySetModule(RunConfiguration config, Module module) {
        try {
            config.getClass().getMethod("setModule", Module.class).invoke(config, module);
        } catch (Exception ignored) {
            // Config type may not support module setting
        }
    }

    @SuppressWarnings("java:S3011") // reflection needed for cross-plugin config API
    private static void trySetWorkingDirectory(RunConfiguration config, String workingDir) {
        for (String method : List.of("setWorkingDirectory", "setWorkDir", "setWorkingDir")) {
            try {
                config.getClass().getMethod(method, String.class).invoke(config, workingDir);
                return;
            } catch (Exception ignored) {
                // Try next method name
            }
        }
    }

    @SuppressWarnings("java:S3011") // reflection needed for cross-plugin config API
    private static boolean trySetInteractiveMode(RunConfiguration config, boolean interactive) {
        for (String method : List.of("setInteractiveMode", "setIsInteractive", "setMakeBeforeRun")) {
            try {
                config.getClass().getMethod(method, boolean.class).invoke(config, interactive);
                return true;
            } catch (Exception ignored) {
                // Try next method name
            }
        }
        return false;
    }

    @SuppressWarnings("java:S3011") // reflection needed for cross-plugin config API
    private static void trySetPythonSdkHome(RunConfiguration config) {
        var sdkTable = ProjectJdkTable.getInstance();
        for (var sdk : sdkTable.getAllJdks()) {
            if ("Python SDK".equals(sdk.getSdkType().getName())) {
                try {
                    config.getClass().getMethod("setSdkHome", String.class)
                        .invoke(config, sdk.getHomePath());
                    return;
                } catch (Exception ignored) {
                    // Config type may not support setSdkHome
                }
            }
        }
    }
}
