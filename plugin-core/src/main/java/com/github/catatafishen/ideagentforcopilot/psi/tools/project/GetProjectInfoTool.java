package com.github.catatafishen.ideagentforcopilot.psi.tools.project;

import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.ProjectInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.execution.RunManager;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Gets project name, SDK, modules, and overall structure.
 */
public final class GetProjectInfoTool extends ProjectTool {

    private static final String OS_NAME_PROPERTY = "os.name";

    public GetProjectInfoTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_project_info";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Project Info";
    }

    @Override
    public @NotNull String description() {
        return "Get project name, SDK, modules, and overall structure";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return ProjectInfoRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
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

    private static void appendBuildSystemInfo(StringBuilder sb, String basePath) {
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

    private static void appendModuleDetails(StringBuilder sb, Module module) {
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
}
