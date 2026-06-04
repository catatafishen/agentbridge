package com.github.catatafishen.agentbridge.psi.tools.project;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Triggers a project model reload for every registered external build system
 * (Gradle, Maven, SBT, BSP/Bazel, etc.). Equivalent to clicking "Reload All
 * Gradle Projects" / "Reimport Maven Projects" but framework-agnostic.
 *
 * <p>Uses {@code ExternalSystemApiUtil.getAllManagers()} to discover registered
 * systems and {@code ExternalSystemUtil.refreshProjects(ImportSpecBuilder)} to
 * trigger each sync — the same API path the IDE uses for the toolbar action.
 * Falls back to the older {@code refreshProject} signature when
 * {@code ImportSpecBuilder} is unavailable.
 */
public final class ReloadProjectModelTool extends ProjectTool {

    private static final Logger LOG = Logger.getInstance(ReloadProjectModelTool.class);

    public ReloadProjectModelTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "reload_project_model";
    }

    @Override
    public @NotNull String displayName() {
        return "Reload Project Model";
    }

    @Override
    public @NotNull String description() {
        return """
            Re-sync the project model for every registered external build system \
            (Gradle, Maven, SBT, BSP/Bazel, and any other system the IDE supports). \
            Equivalent to clicking "Reload All Gradle Projects" or "Reimport Maven \
            Projects" in the IDE toolbar, but framework-agnostic — triggers a full \
            project import for all registered systems in one call.

            Use after:
            - Rebasing or merging branches that modify build files
            - Editing build files (build.gradle.kts, pom.xml, etc.) externally
            - Seeing "Unresolved reference" errors that a build-system sync would fix

            Runs in the background; indexing starts after import completes. \
            Returns the list of build systems that were reloaded.""";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public boolean isIdempotent() {
        return true;
    }

    @Override
    public boolean needsWriteLock() {
        return false;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Reload project model";
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        try {
            Class<?> apiUtilClass = Class.forName(
                "com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil");
            Method getAllManagers = apiUtilClass.getMethod("getAllManagers");
            Collection<?> managers = (Collection<?>) getAllManagers.invoke(null);

            if (managers.isEmpty()) {
                return "No external build systems registered for this project.";
            }

            Class<?> externalSystemUtilClass = Class.forName(
                "com.intellij.openapi.externalSystem.util.ExternalSystemUtil");

            CompletableFuture<String> future = new CompletableFuture<>();
            EdtUtil.invokeLater(() -> {
                try {
                    StringBuilder sb = new StringBuilder();
                    int synced = 0;
                    int notConfigured = 0;
                    for (Object manager : managers) {
                        String name = getSystemName(manager);
                        Boolean linked = hasLinkedProjects(apiUtilClass, manager);
                        if (linked != Boolean.TRUE) {
                            String reason = linked == null ? "skipped (status check failed — see IDE log)" : "no linked projects — skipped";
                            sb.append("– ").append(name).append(" (").append(reason).append(")\n");
                            notConfigured++;
                            continue;
                        }
                        if (refresh(externalSystemUtilClass, manager)) {
                            sb.append("✓ ").append(name).append("\n");
                            synced++;
                        } else {
                            sb.append("✗ ").append(name).append(" (refresh failed — see IDE log)\n");
                        }
                    }
                    if (synced == 0 && notConfigured == managers.size()) {
                        // Check if this is a CMake project — CMake is not an ExternalSystemManager
                        String cmakeResult = tryCMakeReload();
                        if (cmakeResult != null) {
                            future.complete(cmakeResult);
                            return;
                        }
                        future.complete("No build systems are configured for this project. "
                            + "Trigger a sync manually from the IDE's build tool window "
                            + "(Gradle, Maven, BSP, etc.) or by opening the relevant project file.");
                        return;
                    }
                    if (synced == 0) {
                        future.complete("Error: Refresh failed for all configured build system(s). "
                            + "See IDE log for details.\n" + sb);
                        return;
                    }
                    sb.append("\nProject model reload triggered for ").append(synced)
                        .append(" build system(s). Indexing will run in the background.");
                    future.complete(sb.toString());
                } catch (Exception e) {
                    LOG.warn("ReloadProjectModelTool refresh error", e);
                    future.complete("Error triggering project model reload: " + e.getMessage());
                }
            });

            return future.get(30, TimeUnit.SECONDS);

        } catch (ClassNotFoundException e) {
            return "External System API not available in this IDE installation. "
                + "Trigger a sync manually: Gradle tool window → Reload, "
                + "or File → Sync Project with Gradle Files.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("reload_project_model interrupted", e);
            return "Error: Operation interrupted";
        } catch (Exception e) {
            LOG.warn("ReloadProjectModelTool error", e);
            return "Error triggering project model reload: " + e.getMessage();
        }
    }

    private boolean refresh(Class<?> externalSystemUtilClass, Object manager) {
        try {
            Object systemId = manager.getClass().getMethod("getSystemId").invoke(manager);
            Class<?> systemIdClass = Class.forName(
                "com.intellij.openapi.externalSystem.model.ProjectSystemId");

            if (tryImportSpecRefresh(externalSystemUtilClass, systemIdClass, systemId)) {
                return true;
            }

            // Legacy: refreshProject(Project, ProjectSystemId, String basePath, boolean preview, boolean reportErrors)
            externalSystemUtilClass.getMethod("refreshProject",
                    Project.class, systemIdClass, String.class, boolean.class, boolean.class)
                .invoke(null, project, systemId, project.getBasePath(), false, true);
            return true;

        } catch (Exception e) {
            LOG.warn("Failed to refresh external system: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Returns {@code true} if the project has at least one linked project for the given
     * build system, {@code false} if definitely none, or {@code null} if the status could
     * not be determined (settings API threw — manager will be skipped).
     */
    private @org.jetbrains.annotations.Nullable Boolean hasLinkedProjects(Class<?> apiUtilClass, Object manager) {
        try {
            Object systemId = manager.getClass().getMethod("getSystemId").invoke(manager);
            Class<?> systemIdClass = Class.forName(
                "com.intellij.openapi.externalSystem.model.ProjectSystemId");
            Method getSettings = apiUtilClass.getMethod("getSettings", Project.class, systemIdClass);
            Object settings = getSettings.invoke(null, project, systemId);
            Method getLinked = settings.getClass().getMethod("getLinkedProjectsSettings");
            Collection<?> linked = (Collection<?>) getLinked.invoke(settings);
            return !linked.isEmpty();
        } catch (Throwable e) { // intentional: must also catch Error (e.g. IllegalAccessError from Java module restrictions)
            LOG.warn("Could not check linked projects for " + getSystemName(manager) + " — will skip", e);
            return null;
        }
    }

    /**
     * Attempts refresh via ImportSpecBuilder — auto-discovers project paths, works for multi-root setups.
     * Returns {@code false} if ImportSpecBuilder is not available in this IDE version.
     */
    private boolean tryImportSpecRefresh(Class<?> externalSystemUtilClass, Class<?> systemIdClass, Object systemId) {
        try {
            Class<?> importSpecBuilderClass = Class.forName(
                "com.intellij.openapi.externalSystem.util.ImportSpecBuilder");
            Constructor<?> ctor = importSpecBuilderClass.getConstructor(Project.class, systemIdClass);
            Object importSpec = ctor.newInstance(project, systemId);
            externalSystemUtilClass.getMethod("refreshProjects", importSpecBuilderClass)
                .invoke(null, importSpec);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (Exception e) {
            LOG.warn("ImportSpecBuilder refresh failed, will try legacy API", e);
            return false;
        }
    }

    /**
     * Attempts to trigger a CMake project reload via {@code CMakeWorkspace.scheduleReload()}.
     * CMake is not an {@code ExternalSystemManager} — it has its own workspace mechanism.
     *
     * <p>Returns a success message if CMake reloaded, an error message if CMake is present
     * but reload failed, or {@code null} if CMake is not available in this IDE installation.
     */
    private @org.jetbrains.annotations.Nullable String tryCMakeReload() {
        Class<?> workspaceClass;
        try {
            workspaceClass = Class.forName("com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace");
        } catch (ClassNotFoundException ignored) {
            return null; // CMake plugin not installed
        }
        try {
            Method getInstance = workspaceClass.getMethod("getInstance", Project.class);
            Object workspace = getInstance.invoke(null, project);
            if (workspace == null) return null; // no CMake project in this IDE instance
            Method scheduleReload = workspaceClass.getMethod("scheduleReload", boolean.class);
            scheduleReload.invoke(workspace, true);
            return "✓ CMake (reload scheduled)\n\nProject model reload triggered for 1 build system(s). "
                + "Indexing will run in the background.";
        } catch (Throwable e) { // intentional: must also catch Error (e.g. linkage errors from module restrictions)
            LOG.warn("CMake reload failed", e);
            return "✗ CMake (reload failed — see IDE log): " + e.getMessage();
        }
    }

    private static String getSystemName(Object manager) {
        try {
            Object systemId = manager.getClass().getMethod("getSystemId").invoke(manager);
            return (String) systemId.getClass().getMethod("getReadableName").invoke(systemId);
        } catch (Exception e) {
            return manager.getClass().getSimpleName();
        }
    }
}
