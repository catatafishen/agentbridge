package com.github.catatafishen.agentbridge.psi.tools.project;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.options.advanced.AdvancedSettings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.vfs.VirtualFile;
import com.github.catatafishen.agentbridge.ui.renderers.SimpleStatusRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Downloads library sources to enable source navigation and debugging.
 */
@SuppressWarnings("java:S112")
public final class DownloadSourcesTool extends ProjectTool {

    private static final Logger LOG = Logger.getInstance(DownloadSourcesTool.class);
    private static final String PARAM_LIBRARY = "library";
    private static final String GET_INSTANCE_METHOD = "getInstance";

    public DownloadSourcesTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "download_sources";
    }

    @Override
    public @NotNull String displayName() {
        return "Download Sources";
    }

    @Override
    public @NotNull String description() {
        return "Download library sources to enable source navigation and debugging";
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
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {PARAM_LIBRARY, TYPE_STRING, "Optional library name filter (e.g. 'junit')"}
        });
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
    }

    @Override
    @SuppressWarnings({"JavaReflectionMemberAccess", "RedundantSuppression"})
    public @NotNull String execute(@NotNull JsonObject args) {
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

    private static void collectModuleLibrarySources(Module module, String library,
                                                    List<String> withSources, List<String> withoutSources) {
        ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
        for (var entry : rootManager.getOrderEntries()) {
            if (entry instanceof com.intellij.openapi.roots.LibraryOrderEntry libEntry
                && libEntry.getLibrary() != null) {
                String entryName = entry.getPresentableName();
                if (library.isEmpty() || entryName.toLowerCase().contains(library.toLowerCase())) {
                    VirtualFile[] sources = libEntry.getLibrary().getFiles(OrderRootType.SOURCES);
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

            Collection<?> linkedSettings = (Collection<?>)
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
}
