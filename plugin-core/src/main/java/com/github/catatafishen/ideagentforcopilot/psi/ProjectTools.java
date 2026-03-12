package com.github.catatafishen.ideagentforcopilot.psi;

import com.google.gson.JsonObject;
import com.intellij.execution.RunManager;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.options.advanced.AdvancedSettings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ExcludeFolder;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.SourceFolder;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles project environment tools: get_project_info, build_project, get_indexing_status, download_sources.
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
public final class ProjectTools extends AbstractToolHandler {
    private static final Logger LOG = Logger.getInstance(ProjectTools.class);

    private static final String PARAM_TIMEOUT = "timeout";
    private static final String JSON_MODULE = "module";
    private static final String OS_NAME_PROPERTY = "os.name";
    private static final String GET_INSTANCE_METHOD = "getInstance";

    private final AtomicBoolean buildInProgress = new AtomicBoolean(false);

    ProjectTools(Project project) {
        super(project);
    }

    // ---- get_project_info ----

    @SuppressWarnings("unused") // ToolHandler interface requires JsonObject parameter
    public String getProjectInfo(JsonObject args) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            StringBuilder sb = new StringBuilder();
            String basePath = project.getBasePath();
            sb.append("Project: ").append(project.getName()).append("\n");
            sb.append("Path: ").append(basePath).append("\n");
            sb.append("Agent Workspace: ").append(basePath).append("/.agent-work/ (for temp/working files)\n");

            appendIdeAndOsInfo(sb);
            appendSdkInfo(sb);
            appendModulesInfo(sb);
            appendBuildSystemInfo(sb, basePath);
            appendRunConfigsInfo(sb);

            return sb.toString().trim();
        });
    }

    private static void appendIdeAndOsInfo(StringBuilder sb) {
        try {
            ApplicationInfo appInfo = ApplicationInfo.getInstance();
            sb.append("IDE: ").append(appInfo.getFullApplicationName()).append("\n");
            sb.append("IDE Build: ").append(appInfo.getBuild().asString()).append("\n");
        } catch (Exception e) {
            sb.append("IDE: unavailable\n");
        }
        try {
            String pluginInfo = PlatformApiCompat.getPluginVersionInfo("com.github.catatafishen.ideagentforcopilot");
            if (pluginInfo != null) {
                sb.append("Plugin: ").append(pluginInfo).append("\n");
            }
        } catch (Exception e) {
            sb.append("Plugin version: unavailable\n");
        }
        sb.append("OS: ").append(System.getProperty(OS_NAME_PROPERTY))
            .append(" ").append(System.getProperty("os.version"))
            .append(" (").append(System.getProperty("os.arch")).append(")\n");
        sb.append("Java: ").append(System.getProperty("java.version"))
            .append(" (").append(System.getProperty("java.vendor")).append(")\n");
    }

    private void appendSdkInfo(StringBuilder sb) {
        try {
            Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
            if (sdk != null) {
                sb.append("SDK: ").append(sdk.getName()).append("\n");
                sb.append("SDK Path: ").append(sdk.getHomePath()).append("\n");
                sb.append("SDK Version: ").append(sdk.getVersionString()).append("\n");
            }
        } catch (Exception e) {
            sb.append("SDK: unavailable (").append(e.getMessage()).append(")\n");
        }
    }

    private void appendModulesInfo(StringBuilder sb) {
        try {
            Module[] modules = ModuleManager.getInstance(project).getModules();
            sb.append("\nModules (").append(modules.length).append("):\n");
            for (Module module : modules) {
                sb.append("  - ").append(module.getName());
                appendModuleDetails(sb, module);
                sb.append("\n");
            }
        } catch (Exception e) {
            sb.append("Modules: unavailable\n");
        }
    }

    private void appendBuildSystemInfo(StringBuilder sb, String basePath) {
        if (basePath == null) return;
        if (Files.exists(Path.of(basePath, "build.gradle.kts"))
            || Files.exists(Path.of(basePath, "build.gradle"))) {
            sb.append("\nBuild System: Gradle\n");
            Path gradlew = Path.of(basePath,
                System.getProperty(OS_NAME_PROPERTY).contains("Win") ? "gradlew.bat" : "gradlew");
            sb.append("Gradle Wrapper: ").append(gradlew).append("\n");
        } else if (Files.exists(Path.of(basePath, "pom.xml"))) {
            sb.append("\nBuild System: Maven\n");
        }
    }

    private void appendRunConfigsInfo(StringBuilder sb) {
        try {
            var configs = RunManager.getInstance(project).getAllSettings();
            if (!configs.isEmpty()) {
                sb.append("\nRun Configurations (").append(configs.size()).append("):\n");
                for (var config : configs) {
                    sb.append("  - ").append(config.getName())
                        .append(" [").append(config.getType().getDisplayName()).append("]\n");
                }
            }
        } catch (Exception e) {
            sb.append("Run Configurations: unavailable\n");
        }
    }

    private void appendModuleDetails(StringBuilder sb, Module module) {
        try {
            Sdk moduleSdk = ModuleRootManager.getInstance(module).getSdk();
            if (moduleSdk != null) {
                sb.append(" [SDK: ").append(moduleSdk.getName()).append("]");
            }
            VirtualFile[] sourceRoots = ModuleRootManager.getInstance(module)
                .getSourceRoots(false);
            if (sourceRoots.length > 0) {
                sb.append(" (").append(sourceRoots.length).append(" source roots)");
            }
        } catch (Exception ignored) {
            // Module may not support source roots
        }
    }

    // ---- build_project ----

    public String buildProject(JsonObject args) throws Exception {
        if (!buildInProgress.compareAndSet(false, true)) {
            return "Build already in progress. Please wait for the current build to complete before requesting another.";
        }

        String moduleName = args.has(JSON_MODULE) ? args.get(JSON_MODULE).getAsString() : "";

        return com.github.catatafishen.ideagentforcopilot.psi.java.ProjectBuildSupport.buildProject(project, moduleName, buildInProgress);
    }

    // ---- get_indexing_status ----

    public String getIndexingStatus(JsonObject args) throws Exception {
        boolean wait = args.has("wait") && args.get("wait").getAsBoolean();
        int timeoutSec = args.has(PARAM_TIMEOUT) ? args.get(PARAM_TIMEOUT).getAsInt() : 60;

        var dumbService = com.intellij.openapi.project.DumbService.getInstance(project);
        boolean indexing = dumbService.isDumb();

        if (indexing && wait) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            dumbService.runWhenSmart(() -> done.complete(null));
            try {
                done.get(timeoutSec, TimeUnit.SECONDS);
                return "Indexing finished. IDE is ready.";
            } catch (java.util.concurrent.TimeoutException e) {
                return "Indexing still in progress after " + timeoutSec + "s timeout. Try again later.";
            }
        }

        if (indexing) {
            return "Indexing is in progress. Use wait=true to block until finished. " +
                "Some tools (inspections, find_references, search_symbols) may return incomplete results while indexing.";
        }
        return "IDE is ready. Indexing is complete.";
    }

    // ---- mark_directory ----

    public String markDirectory(JsonObject args) throws Exception {
        String pathStr = args.get("path").getAsString();
        String type = args.get("type").getAsString();

        // Validate type
        List<String> validTypes = List.of("sources", "test_sources", "resources",
            "test_resources", "generated_sources", "excluded", "unmark");
        if (!validTypes.contains(type)) {
            return "Error: invalid type '" + type + "'. Must be one of: " + String.join(", ", validTypes);
        }

        // Resolve the path
        String basePath = project.getBasePath();
        Path dirPath = Path.of(pathStr);
        if (!dirPath.isAbsolute() && basePath != null) {
            dirPath = Path.of(basePath).resolve(dirPath);
        }
        String absolutePath = dirPath.toAbsolutePath().toString();

        // Ensure the directory exists on disk
        if (!Files.isDirectory(dirPath)) {
            Files.createDirectories(dirPath);
        }

        // Refresh VFS to make IntelliJ aware of the directory
        VirtualFile vDir = LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath);
        if (vDir == null) {
            return "Error: could not find directory in VFS: " + absolutePath;
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                String result = ApplicationManager.getApplication().runWriteAction(
                    (com.intellij.openapi.util.Computable<String>) () ->
                        applyDirectoryMarking(absolutePath, vDir, type));
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future.get(10, TimeUnit.SECONDS);
    }

    private String applyDirectoryMarking(String absolutePath, VirtualFile vDir, String type) {
        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            ModifiableRootModel model = ModuleRootManager.getInstance(module).getModifiableModel();
            try {
                for (ContentEntry entry : model.getContentEntries()) {
                    VirtualFile contentRoot = entry.getFile();
                    if (contentRoot == null) continue;
                    if (!absolutePath.startsWith(contentRoot.getPath())) continue;

                    if ("unmark".equals(type)) {
                        return unmarkDirectory(entry, model, module, vDir.getUrl(), absolutePath);
                    }

                    if ("excluded".equals(type)) {
                        entry.addExcludeFolder(vDir);
                        model.commit();
                        return "Marked '" + absolutePath + "' as excluded in module '" + module.getName() + "'";
                    }

                    return addSourceRoot(entry, model, module, vDir, absolutePath, type);
                }
            } finally {
                if (!model.isDisposed() && model.isWritable()) {
                    model.dispose();
                }
            }
        }
        return "Error: directory '" + absolutePath + "' is not under any module's content root";
    }

    private static String addSourceRoot(ContentEntry entry, ModifiableRootModel model,
                                        Module module, VirtualFile vDir, String absolutePath, String type) {
        PlatformApiCompat.addSourceFolder(entry, vDir, type);
        model.commit();
        return "Marked '" + absolutePath + "' as " + type + " in module '" + module.getName() + "'";
    }

    private static String unmarkDirectory(ContentEntry entry, ModifiableRootModel model,
                                          Module module, String url, String absolutePath) {
        boolean found = false;
        for (SourceFolder sf : entry.getSourceFolders()) {
            if (url.equals(sf.getUrl())) {
                entry.removeSourceFolder(sf);
                found = true;
            }
        }
        for (ExcludeFolder ef : entry.getExcludeFolders()) {
            if (url.equals(ef.getUrl())) {
                entry.removeExcludeFolder(ef);
                found = true;
            }
        }
        if (found) {
            model.commit();
            return "Unmarked '" + absolutePath + "' in module '" + module.getName() + "'";
        }
        model.dispose();
        return "Directory '" + absolutePath + "' was not marked in module '" + module.getName() + "'";
    }

    // ---- download_sources ----

    @SuppressWarnings({"JavaReflectionMemberAccess", "RedundantSuppression"})
    public String downloadSources(JsonObject args) {
        String library = args.has(PARAM_LIBRARY) ? args.get(PARAM_LIBRARY).getAsString() : "";

        try {
            CompletableFuture<String> future = new CompletableFuture<>();

            EdtUtil.invokeLater(() -> {
                try {
                    StringBuilder sb = new StringBuilder();
                    boolean settingChanged = enableDownloadSources(sb);
                    scanLibrarySources(library, sb);
                    if (settingChanged) {
                        triggerProjectResync(sb);
                    }
                    future.complete(ToolUtils.truncateOutput(sb.toString()));
                } catch (Exception e) {
                    future.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
                }
            });

            return future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("download_sources interrupted", e);
            return "Error: Operation interrupted";
        } catch (Exception e) {
            LOG.warn("download_sources error", e);
            return ToolUtils.ERROR_PREFIX + e.getMessage();
        }
    }

    private void scanLibrarySources(String library, StringBuilder sb) {
        List<String> withSources = new ArrayList<>();
        List<String> withoutSources = new ArrayList<>();

        for (Module module : ModuleManager.getInstance(project).getModules()) {
            collectModuleLibrarySources(module, library, withSources, withoutSources);
        }

        sb.append("Libraries with sources: ").append(withSources.size()).append("\n");
        sb.append("Libraries without sources: ").append(withoutSources.size()).append("\n");

        if (!withoutSources.isEmpty()) {
            sb.append("\nMissing sources:\n");
            for (String lib : withoutSources) {
                sb.append("  - ").append(lib).append("\n");
            }
        }

        if (!withSources.isEmpty() && !library.isEmpty()) {
            sb.append("\nWith sources:\n");
            for (String lib : withSources) {
                sb.append("  ✓ ").append(lib).append("\n");
            }
        }
    }

    private void collectModuleLibrarySources(Module module, String library,
                                             List<String> withSources, List<String> withoutSources) {
        ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
        for (var entry : rootManager.getOrderEntries()) {
            if (entry instanceof com.intellij.openapi.roots.LibraryOrderEntry libEntry
                && libEntry.getLibrary() != null) {
                String entryName = entry.getPresentableName();
                if (library.isEmpty() || entryName.toLowerCase().contains(library.toLowerCase())) {
                    VirtualFile[] sources = libEntry.getLibrary().getFiles(com.intellij.openapi.roots.OrderRootType.SOURCES);
                    if (sources.length > 0) {
                        withSources.add(entryName);
                    } else {
                        withoutSources.add(entryName);
                    }
                }
            }
        }
    }

    private boolean enableDownloadSources(StringBuilder sb) {
        try {
            Class<?> gradleSettingsClass = Class.forName(
                "org.jetbrains.plugins.gradle.settings.GradleSettings");
            Object gradleSettings = gradleSettingsClass.getMethod(GET_INSTANCE_METHOD, Project.class)
                .invoke(null, project);

            java.util.Collection<?> linkedSettings = (java.util.Collection<?>)
                gradleSettingsClass.getMethod("getLinkedProjectsSettings").invoke(gradleSettings);

            if (linkedSettings == null || linkedSettings.isEmpty()) {
                sb.append("No Gradle project settings found.\n");
                return false;
            }

            boolean anyChanged = false;
            Class<?> gradleProjectSettingsClass = Class.forName(
                "org.jetbrains.plugins.gradle.settings.GradleProjectSettings");

            for (Object projectSettings : linkedSettings) {
                anyChanged |= enableExternalAnnotations(gradleProjectSettingsClass, projectSettings, sb);
                anyChanged |= enableDownloadSourcesSetting(gradleProjectSettingsClass, projectSettings, sb);
            }
            return anyChanged;
        } catch (ClassNotFoundException e) {
            sb.append("Gradle plugin not available. ");
            return enableMavenDownloadSources(sb);
        } catch (Exception e) {
            LOG.warn("enableDownloadSources error", e);
            sb.append("Error enabling download sources: ").append(e.getMessage()).append("\n");
            return false;
        }
    }

    private static boolean enableExternalAnnotations(Class<?> settingsClass, Object projectSettings, StringBuilder sb) {
        try {
            Method getResolve = settingsClass.getMethod("isResolveExternalAnnotations");
            boolean currentValue = (boolean) getResolve.invoke(projectSettings);
            if (!currentValue) {
                Method setResolve = settingsClass.getMethod("setResolveExternalAnnotations", boolean.class);
                setResolve.invoke(projectSettings, true);
                sb.append("Enabled 'Resolve external annotations' for Gradle project.\n");
                return true;
            }
        } catch (NoSuchMethodException ignored) {
            // Method not available in this IDE version
        } catch (Exception e) {
            LOG.warn("enableExternalAnnotations error", e);
        }
        return false;
    }

    private boolean enableDownloadSourcesSetting(Class<?> gradleProjectSettingsClass,
                                                 Object projectSettings, StringBuilder sb) {
        try {
            boolean downloadOnSync = AdvancedSettings.getBoolean("gradle.download.sources.on.sync");
            if (!downloadOnSync) {
                AdvancedSettings.setBoolean("gradle.download.sources.on.sync", true);
                sb.append("Enabled 'Download sources on sync' in Advanced Settings.\n");
                return true;
            } else {
                sb.append("'Download sources on sync' is already enabled.\n");
            }
        } catch (Exception e) {
            LOG.info("AdvancedSettings download sources not available: " + e.getMessage());
            return enableDownloadSourcesLegacy(gradleProjectSettingsClass, projectSettings, sb);
        }
        return false;
    }

    private static boolean enableDownloadSourcesLegacy(Class<?> settingsClass,
                                                       Object projectSettings, StringBuilder sb) {
        try {
            Method getDownload = settingsClass.getMethod("isDownloadSources");
            Method setDownload = settingsClass.getMethod("setDownloadSources", boolean.class);
            boolean current = (boolean) getDownload.invoke(projectSettings);
            if (!current) {
                setDownload.invoke(projectSettings, true);
                sb.append("Enabled 'Download sources' for Gradle project.\n");
                return true;
            } else {
                sb.append("'Download sources' is already enabled.\n");
            }
        } catch (NoSuchMethodException ex) {
            sb.append("Download sources setting not found in this IntelliJ version.\n");
        } catch (Exception e) {
            LOG.warn("enableDownloadSourcesLegacy error", e);
        }
        return false;
    }

    private boolean enableMavenDownloadSources(StringBuilder sb) {
        try {
            Class<?> mavenSettingsClass = Class.forName(
                "org.jetbrains.idea.maven.project.MavenImportingSettings");
            Class<?> mavenProjectsManagerClass = Class.forName(
                "org.jetbrains.idea.maven.project.MavenProjectsManager");
            Object manager = mavenProjectsManagerClass.getMethod(GET_INSTANCE_METHOD, Project.class)
                .invoke(null, project);
            Object importingSettings = mavenProjectsManagerClass.getMethod("getImportingSettings")
                .invoke(manager);

            Method setDownloadSources = mavenSettingsClass.getMethod("setDownloadSourcesAutomatically", boolean.class);
            Method getDownloadSources = mavenSettingsClass.getMethod("isDownloadSourcesAutomatically");
            Method setDownloadDocs = mavenSettingsClass.getMethod("setDownloadDocsAutomatically", boolean.class);

            boolean current = (boolean) getDownloadSources.invoke(importingSettings);
            if (!current) {
                setDownloadSources.invoke(importingSettings, true);
                setDownloadDocs.invoke(importingSettings, true);
                sb.append("Enabled 'Download sources and docs automatically' for Maven project.\n");
                return true;
            } else {
                sb.append("Maven 'Download sources automatically' is already enabled.\n");
                return false;
            }
        } catch (ClassNotFoundException e) {
            sb.append("Neither Gradle nor Maven plugin available.\n");
            return false;
        } catch (Exception e) {
            LOG.warn("enableMavenDownloadSources error", e);
            sb.append("Error enabling Maven source download: ").append(e.getMessage()).append("\n");
            return false;
        }
    }

    @SuppressWarnings({"JavaReflectionMemberAccess", "JavaReflectionInvocation"})
    private void triggerProjectResync(StringBuilder sb) {
        try {
            Class<?> gradleConstantsClass = Class.forName(
                "org.jetbrains.plugins.gradle.util.GradleConstants");
            Object gradleSystemId = gradleConstantsClass.getField("SYSTEM_ID").get(null);

            Class<?> externalSystemUtil = Class.forName(
                "com.intellij.openapi.externalSystem.util.ExternalSystemUtil");
            externalSystemUtil.getMethod("refreshProject", Project.class,
                    Class.forName("com.intellij.openapi.externalSystem.model.ProjectSystemId"),
                    String.class, boolean.class, boolean.class)
                .invoke(null, project, gradleSystemId, project.getBasePath(), false, true);

            sb.append("\nTriggered Gradle project re-sync to download sources.\n");
            sb.append("Sources will be downloaded in the background. Check back shortly.\n");
        } catch (Exception e) {
            LOG.info("Auto-resync not available: " + e.getMessage());
            sb.append("\nTo download sources, please re-sync the project:\n");
            sb.append("  Gradle: click 'Reload All Gradle Projects' in the Gradle tool window\n");
            sb.append("  Or: File → Reload All from Disk\n");
        }
    }

    // ---- edit_project_structure ----

    private static final String PARAM_ACTION = "action";
    private static final String PARAM_DEPENDENCY_NAME = "dependency_name";
    private static final String PARAM_DEPENDENCY_TYPE = "dependency_type";
    private static final String PARAM_LIBRARY = "library";
    private static final String PARAM_SCOPE = "scope";
    private static final String PARAM_JAR_PATH = "jar_path";
    private static final String MSG_MODULE_PREFIX = "Module '";
    private static final String MSG_NOT_FOUND = "' not found";

    public String editProjectStructure(JsonObject args) throws Exception {
        String action = args.has(PARAM_ACTION) ? args.get(PARAM_ACTION).getAsString() : "";
        return switch (action) {
            case "list_modules" -> listModules();
            case "list_dependencies" -> listDependencies(args);
            case "add_dependency" -> addDependency(args);
            case "remove_dependency" -> removeDependency(args);
            case "list_sdks" -> listSdks();
            case "add_sdk" -> addSdk(args);
            case "remove_sdk" -> removeSdk(args);
            default -> ToolUtils.ERROR_PREFIX + "Unknown action '" + action
                + "'. Must be one of: list_modules, list_dependencies, add_dependency, remove_dependency, list_sdks, add_sdk, remove_sdk";
        };
    }

    private String listModules() {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            Module[] modules = ModuleManager.getInstance(project).getModules();
            if (modules.length == 0) {
                return "No modules found in the project.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Modules (").append(modules.length).append("):\n");
            for (Module module : modules) {
                sb.append("\n• ").append(module.getName()).append("\n");
                appendModuleDependencySummary(sb, module);
            }
            return sb.toString().trim();
        });
    }

    private void appendModuleDependencySummary(StringBuilder sb, Module module) {
        ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
        int libCount = 0;
        int modDepCount = 0;
        List<String> moduleDepNames = new ArrayList<>();

        for (var entry : rootManager.getOrderEntries()) {
            if (entry instanceof com.intellij.openapi.roots.LibraryOrderEntry) {
                libCount++;
            } else if (entry instanceof com.intellij.openapi.roots.ModuleOrderEntry modEntry) {
                String depName = modEntry.getModuleName();
                if (!depName.isEmpty()) {
                    modDepCount++;
                    moduleDepNames.add(depName);
                }
            }
        }

        sb.append("  Libraries: ").append(libCount).append("\n");
        sb.append("  Module dependencies: ").append(modDepCount).append("\n");
        if (!moduleDepNames.isEmpty()) {
            sb.append("  Depends on: ").append(String.join(", ", moduleDepNames)).append("\n");
        }
    }

    private String listDependencies(JsonObject args) {
        String moduleName = args.has(JSON_MODULE) ? args.get(JSON_MODULE).getAsString() : "";
        if (moduleName.isEmpty()) {
            return ToolUtils.ERROR_PREFIX + "'module' parameter is required for list_dependencies";
        }

        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            Module module = ModuleManager.getInstance(project).findModuleByName(moduleName);
            if (module == null) {
                return ToolUtils.ERROR_PREFIX + MSG_MODULE_PREFIX + moduleName + MSG_NOT_FOUND;
            }

            ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
            StringBuilder sb = new StringBuilder();
            sb.append("Dependencies for module '").append(moduleName).append("':\n");

            int index = formatDependencyEntries(sb, rootManager);

            if (index == 0) {
                sb.append("  (no dependencies)\n");
            }
            return sb.toString().trim();
        });
    }

    private static int formatDependencyEntries(StringBuilder sb, ModuleRootManager rootManager) {
        int index = 0;
        for (var entry : rootManager.getOrderEntries()) {
            if (entry instanceof com.intellij.openapi.roots.JdkOrderEntry jdkEntry) {
                sb.append("\n").append(++index).append(". [SDK] ").append(jdkEntry.getPresentableName()).append("\n");
            } else if (entry instanceof com.intellij.openapi.roots.LibraryOrderEntry libEntry) {
                appendLibraryDetail(sb, libEntry, ++index);
            } else if (entry instanceof com.intellij.openapi.roots.ModuleOrderEntry modEntry) {
                index = appendModuleDependencyDetail(sb, modEntry, index);
            }
        }
        return index;
    }

    private static int appendModuleDependencyDetail(StringBuilder sb,
                                                    com.intellij.openapi.roots.ModuleOrderEntry modEntry, int index) {
        String depName = modEntry.getModuleName();
        if (!depName.isEmpty()) {
            sb.append("\n").append(++index).append(". [Module] ").append(depName);
            sb.append("  (scope: ").append(modEntry.getScope().name()).append(")\n");
            if (modEntry.isExported()) {
                sb.append("   exported: true\n");
            }
        }
        return index;
    }

    private static void appendLibraryDetail(StringBuilder sb,
                                            com.intellij.openapi.roots.LibraryOrderEntry libEntry, int index) {
        sb.append("\n").append(index).append(". [Library] ").append(libEntry.getPresentableName()).append("\n");
        sb.append("   scope: ").append(libEntry.getScope().name()).append("\n");
        if (libEntry.isExported()) {
            sb.append("   exported: true\n");
        }
        var library = libEntry.getLibrary();
        if (library != null) {
            VirtualFile[] classFiles = library.getFiles(com.intellij.openapi.roots.OrderRootType.CLASSES);
            if (classFiles.length > 0) {
                sb.append("   JARs:\n");
                for (VirtualFile jar : classFiles) {
                    sb.append("     - ").append(jar.getPresentableUrl()).append("\n");
                }
            }
        }
    }

    private String addDependency(JsonObject args) throws Exception {
        String moduleName = args.has(JSON_MODULE) ? args.get(JSON_MODULE).getAsString() : "";
        if (moduleName.isEmpty()) {
            return ToolUtils.ERROR_PREFIX + "'module' parameter is required for add_dependency";
        }

        String depType = args.has(PARAM_DEPENDENCY_TYPE) ? args.get(PARAM_DEPENDENCY_TYPE).getAsString() : PARAM_LIBRARY;
        String depName = args.has(PARAM_DEPENDENCY_NAME) ? args.get(PARAM_DEPENDENCY_NAME).getAsString() : "";
        String scopeStr = args.has(PARAM_SCOPE) ? args.get(PARAM_SCOPE).getAsString() : "COMPILE";
        String jarPath = args.has(PARAM_JAR_PATH) ? args.get(PARAM_JAR_PATH).getAsString() : "";

        if (JSON_MODULE.equals(depType)) {
            if (depName.isEmpty()) {
                return ToolUtils.ERROR_PREFIX + "'dependency_name' is required when dependency_type is 'module'";
            }
            return addModuleDependency(moduleName, depName, scopeStr);
        } else {
            if (jarPath.isEmpty()) {
                return ToolUtils.ERROR_PREFIX + "'jar_path' is required when adding a library dependency";
            }
            return addLibraryDependency(moduleName, depName, jarPath, scopeStr);
        }
    }

    private String addModuleDependency(String moduleName, String depModuleName, String scopeStr) throws Exception {
        com.intellij.openapi.roots.DependencyScope scope = parseDependencyScope(scopeStr);
        if (scope == null) {
            return ToolUtils.ERROR_PREFIX + "Invalid scope '" + scopeStr
                + "'. Must be one of: COMPILE, TEST, RUNTIME, PROVIDED";
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                String result = ApplicationManager.getApplication().runWriteAction((Computable<String>)
                    () -> doAddModuleDependency(moduleName, depModuleName, scope));
                future.complete(result);
            } catch (Exception e) {
                future.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
            }
        });
        return future.get(10, TimeUnit.SECONDS);
    }

    private String doAddModuleDependency(String moduleName, String depModuleName,
                                         com.intellij.openapi.roots.DependencyScope scope) {
        Module module = ModuleManager.getInstance(project).findModuleByName(moduleName);
        if (module == null) {
            return ToolUtils.ERROR_PREFIX + MSG_MODULE_PREFIX + moduleName + MSG_NOT_FOUND;
        }
        Module depModule = ModuleManager.getInstance(project).findModuleByName(depModuleName);
        if (depModule == null) {
            return ToolUtils.ERROR_PREFIX + "Dependency module '" + depModuleName + MSG_NOT_FOUND;
        }

        // Check for duplicate
        ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
        for (var entry : rootManager.getOrderEntries()) {
            if (entry instanceof com.intellij.openapi.roots.ModuleOrderEntry modEntry
                && depModuleName.equals(modEntry.getModuleName())) {
                return "Module dependency '" + depModuleName + "' already exists in module '" + moduleName + "'";
            }
        }

        ModifiableRootModel model = rootManager.getModifiableModel();
        try {
            var modEntry = model.addModuleOrderEntry(depModule);
            modEntry.setScope(scope);
            model.commit();
            return "Added module dependency '" + depModuleName + "' to module '" + moduleName
                + "' (scope: " + scope.name() + ")";
        } catch (Exception e) {
            if (!model.isDisposed()) {
                model.dispose();
            }
            return ToolUtils.ERROR_PREFIX + e.getMessage();
        }
    }

    private String addLibraryDependency(String moduleName, String libName, String jarPath,
                                        String scopeStr) throws Exception {
        com.intellij.openapi.roots.DependencyScope scope = parseDependencyScope(scopeStr);
        if (scope == null) {
            return ToolUtils.ERROR_PREFIX + "Invalid scope '" + scopeStr
                + "'. Must be one of: COMPILE, TEST, RUNTIME, PROVIDED";
        }

        // Resolve and validate the JAR path
        Path resolved = Path.of(jarPath);
        if (!resolved.isAbsolute() && project.getBasePath() != null) {
            resolved = Path.of(project.getBasePath()).resolve(resolved);
        }
        if (!Files.exists(resolved)) {
            return ToolUtils.ERROR_PREFIX + "JAR file not found: " + resolved;
        }
        String absoluteJarPath = resolved.toAbsolutePath().toString();

        // Auto-generate library name from JAR filename if not provided
        String effectiveLibName = (libName == null || libName.isEmpty())
            ? resolved.getFileName().toString().replaceFirst("\\.jar$", "")
            : libName;

        CompletableFuture<String> future = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                String result = ApplicationManager.getApplication().runWriteAction((Computable<String>)
                    () -> doAddLibraryDependency(
                        moduleName, effectiveLibName, absoluteJarPath, scope));
                future.complete(result);
            } catch (Exception e) {
                future.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
            }
        });
        return future.get(10, TimeUnit.SECONDS);
    }

    private String doAddLibraryDependency(String moduleName, String libName, String absoluteJarPath,
                                          com.intellij.openapi.roots.DependencyScope scope) {
        Module module = ModuleManager.getInstance(project).findModuleByName(moduleName);
        if (module == null) {
            return ToolUtils.ERROR_PREFIX + MSG_MODULE_PREFIX + moduleName + MSG_NOT_FOUND;
        }

        VirtualFile jarFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(absoluteJarPath);
        if (jarFile == null) {
            return ToolUtils.ERROR_PREFIX + "Could not find JAR in VFS: " + absoluteJarPath;
        }

        // Create JAR URL (IntelliJ uses jar:// protocol)
        String jarUrl = com.intellij.openapi.vfs.VfsUtilCore.pathToUrl(absoluteJarPath);
        if (absoluteJarPath.endsWith(".jar")) {
            jarUrl = "jar://" + absoluteJarPath + "!/";
        }

        // Create or find the project-level library
        var libraryTable = com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
            .getInstance().getLibraryTable(project);
        var existingLib = libraryTable.getLibraryByName(libName);

        com.intellij.openapi.roots.libraries.Library library;
        if (existingLib != null) {
            library = existingLib;
        } else {
            var tableModel = libraryTable.getModifiableModel();
            library = tableModel.createLibrary(libName);
            var libModel = library.getModifiableModel();
            libModel.addRoot(jarUrl, com.intellij.openapi.roots.OrderRootType.CLASSES);
            libModel.commit();
            tableModel.commit();
        }

        // Check for duplicate dependency on the module
        ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
        for (var entry : rootManager.getOrderEntries()) {
            if (entry instanceof com.intellij.openapi.roots.LibraryOrderEntry libEntry
                && libEntry.getLibrary() != null
                && libName.equals(libEntry.getLibrary().getName())) {
                return "Library '" + libName + "' is already a dependency of module '" + moduleName + "'";
            }
        }

        // Add library to module
        ModifiableRootModel model = rootManager.getModifiableModel();
        try {
            var libEntry = model.addLibraryEntry(library);
            libEntry.setScope(scope);
            model.commit();
            return "Added library '" + libName + "' (" + absoluteJarPath + ") to module '"
                + moduleName + "' (scope: " + scope.name() + ")";
        } catch (Exception e) {
            if (!model.isDisposed()) {
                model.dispose();
            }
            return ToolUtils.ERROR_PREFIX + e.getMessage();
        }
    }

    private String removeDependency(JsonObject args) throws Exception {
        String moduleName = args.has(JSON_MODULE) ? args.get(JSON_MODULE).getAsString() : "";
        String depName = args.has(PARAM_DEPENDENCY_NAME) ? args.get(PARAM_DEPENDENCY_NAME).getAsString() : "";

        if (moduleName.isEmpty()) {
            return ToolUtils.ERROR_PREFIX + "'module' parameter is required for remove_dependency";
        }
        if (depName.isEmpty()) {
            return ToolUtils.ERROR_PREFIX + "'dependency_name' parameter is required for remove_dependency";
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                String result = ApplicationManager.getApplication().runWriteAction((Computable<String>)
                    () -> doRemoveDependency(moduleName, depName));
                future.complete(result);
            } catch (Exception e) {
                future.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
            }
        });
        return future.get(10, TimeUnit.SECONDS);
    }

    private String doRemoveDependency(String moduleName, String depName) {
        Module module = ModuleManager.getInstance(project).findModuleByName(moduleName);
        if (module == null) {
            return ToolUtils.ERROR_PREFIX + MSG_MODULE_PREFIX + moduleName + MSG_NOT_FOUND;
        }

        ModifiableRootModel model = ModuleRootManager.getInstance(module).getModifiableModel();
        try {
            boolean found = false;
            for (var entry : model.getOrderEntries()) {
                if (entry instanceof com.intellij.openapi.roots.LibraryOrderEntry libEntry
                    && depName.equals(libEntry.getPresentableName())) {
                    model.removeOrderEntry(entry);
                    found = true;
                    break;
                }
                if (entry instanceof com.intellij.openapi.roots.ModuleOrderEntry modEntry
                    && depName.equals(modEntry.getModuleName())) {
                    model.removeOrderEntry(entry);
                    found = true;
                    break;
                }
            }

            if (found) {
                model.commit();
                return "Removed dependency '" + depName + "' from module '" + moduleName + "'";
            } else {
                model.dispose();
                return ToolUtils.ERROR_PREFIX + "Dependency '" + depName + "' not found in module '" + moduleName + "'";
            }
        } catch (Exception e) {
            if (!model.isDisposed()) {
                model.dispose();
            }
            return ToolUtils.ERROR_PREFIX + e.getMessage();
        }
    }

    // ---- SDK management ----

    private static final String PARAM_SDK_NAME = "sdk_name";
    private static final String PARAM_SDK_TYPE = "sdk_type";
    private static final String PARAM_HOME_PATH = "home_path";

    private String listSdks() {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            var jdkTable = com.intellij.openapi.projectRoots.ProjectJdkTable.getInstance();
            com.intellij.openapi.projectRoots.Sdk[] sdks = jdkTable.getAllJdks();

            StringBuilder sb = new StringBuilder();

            // Show project SDK
            Sdk projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();
            sb.append("Project SDK: ");
            if (projectSdk != null) {
                sb.append(projectSdk.getName()).append(" (").append(projectSdk.getSdkType().getName()).append(")\n");
            } else {
                sb.append("(none)\n");
            }

            // List all configured SDKs
            sb.append("\nConfigured SDKs (").append(sdks.length).append("):\n");
            for (Sdk sdk : sdks) {
                sb.append("\n• ").append(sdk.getName()).append("\n");
                sb.append("  Type: ").append(sdk.getSdkType().getName()).append("\n");
                sb.append("  Home: ").append(sdk.getHomePath() != null ? sdk.getHomePath() : "(not set)").append("\n");
                String version = sdk.getVersionString();
                if (version != null) {
                    sb.append("  Version: ").append(version).append("\n");
                }
            }

            // List available SDK types for adding
            sb.append(PlatformApiCompat.listSdkTypes(project));

            return sb.toString().trim();
        });
    }

    private String addSdk(JsonObject args) throws Exception {
        String sdkTypeName = args.has(PARAM_SDK_TYPE) ? args.get(PARAM_SDK_TYPE).getAsString() : "";
        String homePath = args.has(PARAM_HOME_PATH) ? args.get(PARAM_HOME_PATH).getAsString() : "";

        if (sdkTypeName.isEmpty()) {
            return ToolUtils.ERROR_PREFIX + "'sdk_type' parameter is required. Use list_sdks to see available types.";
        }
        if (homePath.isEmpty()) {
            return ToolUtils.ERROR_PREFIX + "'home_path' parameter is required. Use list_sdks to see suggested paths.";
        }

        // Find SDK type by name (case-insensitive)
        com.intellij.openapi.projectRoots.SdkType sdkType = PlatformApiCompat.findSdkTypeByName(sdkTypeName);
        if (sdkType == null) {
            return ToolUtils.ERROR_PREFIX + "SDK type '" + sdkTypeName + "' not found. Use list_sdks to see available types.";
        }

        // Validate home path
        String adjustedHome = sdkType.adjustSelectedSdkHome(homePath);
        if (!sdkType.isValidSdkHome(adjustedHome)) {
            return ToolUtils.ERROR_PREFIX + "'" + homePath + "' is not a valid home path for SDK type '" + sdkType.getPresentableName() + "'.";
        }

        // Generate SDK name
        String sdkName = sdkType.suggestSdkName(null, adjustedHome);

        // Check if an SDK with this name already exists
        var jdkTable = com.intellij.openapi.projectRoots.ProjectJdkTable.getInstance();
        if (jdkTable.findJdk(sdkName) != null) {
            return "SDK '" + sdkName + "' already exists.";
        }

        // Resolve version outside write action (may run external process)
        final com.intellij.openapi.projectRoots.SdkType finalSdkType = sdkType;
        final String finalHome = adjustedHome;
        final String finalName = sdkName;
        String version = sdkType.getVersionString(finalHome);

        PlatformApiCompat.writeActionRunAndWait(() -> {
            Sdk sdk = jdkTable.createSdk(finalName, finalSdkType);
            var modificator = sdk.getSdkModificator();
            modificator.setHomePath(finalHome);
            if (version != null) {
                modificator.setVersionString(version);
            }
            modificator.commitChanges();
            jdkTable.addJdk(sdk);
        });

        return "Added SDK '" + finalName + "' (" + sdkType.getPresentableName() + ") at " + adjustedHome;
    }

    private String removeSdk(JsonObject args) {
        String sdkName = args.has(PARAM_SDK_NAME) ? args.get(PARAM_SDK_NAME).getAsString() : "";
        if (sdkName.isEmpty()) {
            return ToolUtils.ERROR_PREFIX + "'sdk_name' parameter is required. Use list_sdks to see configured SDKs.";
        }

        var jdkTable = com.intellij.openapi.projectRoots.ProjectJdkTable.getInstance();
        Sdk sdk = jdkTable.findJdk(sdkName);
        if (sdk == null) {
            return ToolUtils.ERROR_PREFIX + "SDK '" + sdkName + "' not found. Use list_sdks to see configured SDKs.";
        }

        try {
            PlatformApiCompat.writeActionRunAndWait(() -> jdkTable.removeJdk(sdk));
        } catch (Exception e) {
            return ToolUtils.ERROR_PREFIX + "Failed to remove SDK: " + e.getMessage();
        }
        return "Removed SDK '" + sdkName + "'.";
    }

    private static com.intellij.openapi.roots.DependencyScope parseDependencyScope(String scopeStr) {
        return switch (scopeStr.toUpperCase()) {
            case "COMPILE" -> com.intellij.openapi.roots.DependencyScope.COMPILE;
            case "TEST" -> com.intellij.openapi.roots.DependencyScope.TEST;
            case "RUNTIME" -> com.intellij.openapi.roots.DependencyScope.RUNTIME;
            case "PROVIDED" -> com.intellij.openapi.roots.DependencyScope.PROVIDED;
            default -> null;
        };
    }

}
