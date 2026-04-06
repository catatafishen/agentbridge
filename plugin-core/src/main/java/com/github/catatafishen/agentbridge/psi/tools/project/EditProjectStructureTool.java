package com.github.catatafishen.agentbridge.psi.tools.project;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkType;
import com.intellij.openapi.roots.DependencyScope;
import com.intellij.openapi.roots.JdkOrderEntry;
import com.intellij.openapi.roots.LibraryOrderEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleOrderEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Views and modifies module dependencies, libraries, SDKs, and project structure.
 */
@SuppressWarnings("java:S112")
public final class EditProjectStructureTool extends ProjectTool {

    private static final String PARAM_ACTION = "action";
    private static final String PARAM_DEPENDENCY_NAME = "dependency_name";
    private static final String PARAM_DEPENDENCY_TYPE = "dependency_type";
    private static final String PARAM_LIBRARY = "library";
    private static final String PARAM_SCOPE = "scope";
    private static final String PARAM_JAR_PATH = "jar_path";
    private static final String PARAM_SDK_NAME = "sdk_name";
    private static final String PARAM_SDK_TYPE = "sdk_type";
    private static final String PARAM_HOME_PATH = "home_path";
    private static final String JSON_MODULE = "module";
    private static final String MSG_MODULE_PREFIX = "Module '";
    private static final String MSG_NOT_FOUND = "' not found";

    public EditProjectStructureTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "edit_project_structure";
    }

    @Override
    public @NotNull String displayName() {
        return "Edit Project Structure";
    }

    @Override
    public @NotNull String description() {
        return "View and modify module dependencies, libraries, SDKs, and project structure";
    }



    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }
@Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {PARAM_ACTION, TYPE_STRING, "Action: 'list_modules', 'list_dependencies', 'add_dependency', 'remove_dependency', 'list_sdks', 'add_sdk', 'remove_sdk'"},
            {JSON_MODULE, TYPE_STRING, "Module name (required for list_dependencies, add_dependency, remove_dependency)"},
            {PARAM_DEPENDENCY_NAME, TYPE_STRING, "Name of the dependency to add or remove"},
            {PARAM_DEPENDENCY_TYPE, TYPE_STRING, "Type of dependency to add: 'library' (default) or 'module'"},
            {PARAM_SCOPE, TYPE_STRING, "Dependency scope: 'COMPILE' (default), 'TEST', 'RUNTIME', 'PROVIDED'"},
            {PARAM_JAR_PATH, TYPE_STRING, "Path to JAR file (absolute or project-relative). Required when adding a library dependency"},
            {PARAM_SDK_TYPE, TYPE_STRING, "SDK type name for add_sdk (e.g., 'Python SDK', 'JavaSDK'). Use list_sdks to see available types"},
            {PARAM_SDK_NAME, TYPE_STRING, "SDK name for remove_sdk. Use list_sdks to see configured SDK names"},
            {PARAM_HOME_PATH, TYPE_STRING, "Home path for add_sdk. Use list_sdks to see suggested paths for each SDK type"}
        }, PARAM_ACTION);
    }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
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

    private static void appendModuleDependencySummary(StringBuilder sb, Module module) {
        ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
        int libCount = 0;
        int modDepCount = 0;
        List<String> moduleDepNames = new ArrayList<>();

        for (var entry : rootManager.getOrderEntries()) {
            if (entry instanceof LibraryOrderEntry) {
                libCount++;
            } else if (entry instanceof ModuleOrderEntry modEntry) {
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
            if (entry instanceof JdkOrderEntry jdkEntry) {
                sb.append("\n").append(++index).append(". [SDK] ").append(jdkEntry.getPresentableName()).append("\n");
            } else if (entry instanceof LibraryOrderEntry libEntry) {
                appendLibraryDetail(sb, libEntry, ++index);
            } else if (entry instanceof ModuleOrderEntry modEntry) {
                index = appendModuleDependencyDetail(sb, modEntry, index);
            }
        }
        return index;
    }

    private static int appendModuleDependencyDetail(StringBuilder sb, ModuleOrderEntry modEntry, int index) {
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

    private static void appendLibraryDetail(StringBuilder sb, LibraryOrderEntry libEntry, int index) {
        sb.append("\n").append(index).append(". [Library] ").append(libEntry.getPresentableName()).append("\n");
        sb.append("   scope: ").append(libEntry.getScope().name()).append("\n");
        if (libEntry.isExported()) {
            sb.append("   exported: true\n");
        }
        var library = libEntry.getLibrary();
        if (library != null) {
            VirtualFile[] classFiles = library.getFiles(OrderRootType.CLASSES);
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
        DependencyScope scope = parseDependencyScope(scopeStr);
        if (scope == null) {
            return ToolUtils.ERROR_PREFIX + "Invalid scope '" + scopeStr
                + "'. Must be one of: COMPILE, TEST, RUNTIME, PROVIDED";
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                String result = WriteAction.compute(
                    () -> doAddModuleDependency(moduleName, depModuleName, scope));
                future.complete(result);
            } catch (Exception e) {
                future.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
            }
        });
        return future.get(10, TimeUnit.SECONDS);
    }

    private String doAddModuleDependency(String moduleName, String depModuleName, DependencyScope scope) {
        Module module = ModuleManager.getInstance(project).findModuleByName(moduleName);
        if (module == null) {
            return ToolUtils.ERROR_PREFIX + MSG_MODULE_PREFIX + moduleName + MSG_NOT_FOUND;
        }
        Module depModule = ModuleManager.getInstance(project).findModuleByName(depModuleName);
        if (depModule == null) {
            return ToolUtils.ERROR_PREFIX + "Dependency module '" + depModuleName + MSG_NOT_FOUND;
        }

        ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
        for (var entry : rootManager.getOrderEntries()) {
            if (entry instanceof ModuleOrderEntry modEntry
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
        DependencyScope scope = parseDependencyScope(scopeStr);
        if (scope == null) {
            return ToolUtils.ERROR_PREFIX + "Invalid scope '" + scopeStr
                + "'. Must be one of: COMPILE, TEST, RUNTIME, PROVIDED";
        }

        Path resolved = Path.of(jarPath);
        if (!resolved.isAbsolute() && project.getBasePath() != null) {
            resolved = Path.of(project.getBasePath()).resolve(resolved);
        }
        if (!Files.exists(resolved)) {
            return ToolUtils.ERROR_PREFIX + "JAR file not found: " + resolved;
        }
        String absoluteJarPath = resolved.toAbsolutePath().toString();

        String effectiveLibName = (libName == null || libName.isEmpty())
            ? resolved.getFileName().toString().replaceFirst("\\.jar$", "")
            : libName;

        CompletableFuture<String> future = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                String result = WriteAction.compute(
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
                                          DependencyScope scope) {
        Module module = ModuleManager.getInstance(project).findModuleByName(moduleName);
        if (module == null) {
            return ToolUtils.ERROR_PREFIX + MSG_MODULE_PREFIX + moduleName + MSG_NOT_FOUND;
        }

        VirtualFile jarFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(absoluteJarPath);
        if (jarFile == null) {
            return ToolUtils.ERROR_PREFIX + "Could not find JAR in VFS: " + absoluteJarPath;
        }

        String jarUrl = VfsUtilCore.pathToUrl(absoluteJarPath);
        if (absoluteJarPath.endsWith(".jar")) {
            jarUrl = "jar://" + absoluteJarPath + "!/";
        }

        var libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project);
        var existingLib = libraryTable.getLibraryByName(libName);

        Library library;
        if (existingLib != null) {
            library = existingLib;
        } else {
            var tableModel = libraryTable.getModifiableModel();
            library = tableModel.createLibrary(libName);
            var libModel = library.getModifiableModel();
            libModel.addRoot(jarUrl, OrderRootType.CLASSES);
            libModel.commit();
            tableModel.commit();
        }

        ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
        for (var entry : rootManager.getOrderEntries()) {
            if (entry instanceof LibraryOrderEntry libEntry
                && libEntry.getLibrary() != null
                && libName.equals(libEntry.getLibrary().getName())) {
                return "Library '" + libName + "' is already a dependency of module '" + moduleName + "'";
            }
        }

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
                String result = WriteAction.compute(
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
                String entryName = null;
                if (entry instanceof LibraryOrderEntry libEntry) {
                    entryName = libEntry.getPresentableName();
                } else if (entry instanceof ModuleOrderEntry modEntry) {
                    entryName = modEntry.getModuleName();
                }
                if (depName.equals(entryName)) {
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

    private String listSdks() {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            var jdkTable = ProjectJdkTable.getInstance();
            Sdk[] sdks = jdkTable.getAllJdks();

            StringBuilder sb = new StringBuilder();

            Sdk projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();
            sb.append("Project SDK: ");
            if (projectSdk != null) {
                sb.append(projectSdk.getName()).append(" (").append(projectSdk.getSdkType().getName()).append(")\n");
            } else {
                sb.append("(none)\n");
            }

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

        SdkType sdkType = PlatformApiCompat.findSdkTypeByName(sdkTypeName);
        if (sdkType == null) {
            return ToolUtils.ERROR_PREFIX + "SDK type '" + sdkTypeName + "' not found. Use list_sdks to see available types.";
        }

        String adjustedHome = sdkType.adjustSelectedSdkHome(homePath);
        if (!sdkType.isValidSdkHome(adjustedHome)) {
            return ToolUtils.ERROR_PREFIX + "'" + homePath + "' is not a valid home path for SDK type '" + sdkType.getPresentableName() + "'.";
        }

        String sdkName = sdkType.suggestSdkName(null, adjustedHome);

        var jdkTable = ProjectJdkTable.getInstance();
        if (jdkTable.findJdk(sdkName) != null) {
            return "SDK '" + sdkName + "' already exists.";
        }

        final SdkType finalSdkType = sdkType;
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

        var jdkTable = ProjectJdkTable.getInstance();
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

    private static DependencyScope parseDependencyScope(String scopeStr) {
        return switch (scopeStr.toUpperCase()) {
            case "COMPILE" -> DependencyScope.COMPILE;
            case "TEST" -> DependencyScope.TEST;
            case "RUNTIME" -> DependencyScope.RUNTIME;
            case "PROVIDED" -> DependencyScope.PROVIDED;
            default -> null;
        };
    }
}
