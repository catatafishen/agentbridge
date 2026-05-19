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
        return "Re-sync the project model for every registered external build system "
            + "(Gradle, Maven, SBT, BSP/Bazel, and any other system the IDE supports). "
            + "Equivalent to clicking \"Reload All Gradle Projects\" or \"Reimport Maven "
            + "Projects\" in the IDE toolbar, but framework-agnostic — triggers a full "
            + "project import for all registered systems in one call.\n\n"
            + "Use after:\n"
            + "- Rebasing or merging branches that modify build files\n"
            + "- Editing build files (build.gradle.kts, pom.xml, etc.) externally\n"
            + "- Seeing \"Unresolved reference\" errors that a build-system sync would fix\n\n"
            + "Runs in the background; indexing starts after import completes. "
            + "Returns the list of build systems that were reloaded.";
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
                    for (Object manager : managers) {
                        String name = getSystemName(manager);
                        if (refresh(externalSystemUtilClass, manager)) {
                            sb.append("✓ ").append(name).append("\n");
                            synced++;
                        } else {
                            sb.append("✗ ").append(name).append(" (refresh failed — see IDE log)\n");
                        }
                    }
                    if (synced == 0) {
                        future.complete("Error: Refresh failed for all " + managers.size()
                            + " build system(s). See IDE log for details.");
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

            // Prefer ImportSpecBuilder — auto-discovers project paths, works for multi-root setups.
            try {
                Class<?> importSpecBuilderClass = Class.forName(
                    "com.intellij.openapi.externalSystem.util.ImportSpecBuilder");
                Constructor<?> ctor = importSpecBuilderClass.getConstructor(Project.class, systemIdClass);
                Object importSpec = ctor.newInstance(project, systemId);
                externalSystemUtilClass.getMethod("refreshProjects", importSpecBuilderClass)
                    .invoke(null, importSpec);
                return true;
            } catch (NoSuchMethodException ignored) {
                // ImportSpecBuilder not available in this IDE version — fall through to legacy API.
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

    private static String getSystemName(Object manager) {
        try {
            Object systemId = manager.getClass().getMethod("getSystemId").invoke(manager);
            return (String) systemId.getClass().getMethod("getReadableName").invoke(systemId);
        } catch (Exception e) {
            return manager.getClass().getSimpleName();
        }
    }
}
