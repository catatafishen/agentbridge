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
 * Falls back to {@code ExternalSystemUtil.refreshProject(Project, ProjectSystemId,
 * String, boolean, ProgressExecutionMode)} when the {@code ImportSpecBuilder} path
 * is unavailable.
 *
 * <p>All platform calls go through reflection because the external-system jars live
 * in {@code lib/} rather than on the plugin's compile classpath — see the deliberate
 * absence of an external-system entry in {@code plugin-core/build.gradle.kts}. That
 * makes the class and method names above unverifiable at compile time, so they are
 * declared as constants and any failure is reported with its exception type.
 */
public final class ReloadProjectModelTool extends ProjectTool {

    private static final Logger LOG = Logger.getInstance(ReloadProjectModelTool.class);

    private static final String EXTERNAL_SYSTEM_API_UTIL_CLASS =
        "com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil";
    private static final String EXTERNAL_SYSTEM_UTIL_CLASS =
        "com.intellij.openapi.externalSystem.util.ExternalSystemUtil";
    private static final String PROJECT_SYSTEM_ID_CLASS =
        "com.intellij.openapi.externalSystem.model.ProjectSystemId";
    /**
     * {@code ImportSpecBuilder} lives in the {@code ...externalSystem.importing} package, not
     * {@code ...externalSystem.util} where its companion {@code ExternalSystemUtil} lives. Getting
     * this wrong yields a {@code ClassNotFoundException} that looks like an IDE-version problem.
     */
    private static final String IMPORT_SPEC_BUILDER_CLASS =
        "com.intellij.openapi.externalSystem.importing.ImportSpecBuilder";
    private static final String PROGRESS_EXECUTION_MODE_CLASS =
        "com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode";

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
            Class<?> apiUtilClass = Class.forName(EXTERNAL_SYSTEM_API_UTIL_CLASS);
            Method getAllManagers = apiUtilClass.getMethod("getAllManagers");
            Collection<?> managers = (Collection<?>) getAllManagers.invoke(null);

            if (managers.isEmpty()) {
                return "No external build systems registered for this project.";
            }

            Class<?> externalSystemUtilClass = Class.forName(EXTERNAL_SYSTEM_UTIL_CLASS);

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
                        String failure = refresh(externalSystemUtilClass, manager);
                        if (failure == null) {
                            sb.append("✓ ").append(name).append("\n");
                            synced++;
                        } else {
                            sb.append("✗ ").append(name).append(" (").append(failure).append(")\n");
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
                        future.complete("Error: Refresh failed for all configured build system(s).\n" + sb);
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

    /**
     * Refreshes one external system. Returns {@code null} on success, or a human-readable
     * reason describing why every attempted API path failed.
     *
     * <p>Two API paths are tried in order:
     * <ol>
     *   <li>{@code ExternalSystemUtil.refreshProjects(ImportSpecBuilder)} — auto-discovers all
     *       linked project paths, so it handles multi-root setups correctly.</li>
     *   <li>{@code ExternalSystemUtil.refreshProject(Project, ProjectSystemId, String,
     *       boolean, ProgressExecutionMode)} — single base path only, used when the
     *       {@code ImportSpecBuilder} path is unavailable.</li>
     * </ol>
     */
    private @org.jetbrains.annotations.Nullable String refresh(Class<?> externalSystemUtilClass, Object manager) {
        try {
            Object systemId = manager.getClass().getMethod("getSystemId").invoke(manager);
            Class<?> systemIdClass = Class.forName(PROJECT_SYSTEM_ID_CLASS);

            String importSpecFailure = tryImportSpecRefresh(externalSystemUtilClass, systemIdClass, systemId);
            if (importSpecFailure == null) {
                return null;
            }

            String refreshProjectFailure =
                tryRefreshProject(externalSystemUtilClass, systemIdClass, systemId);
            if (refreshProjectFailure == null) {
                return null;
            }

            return "ImportSpecBuilder: " + importSpecFailure + "; refreshProject: " + refreshProjectFailure;

        } catch (Exception e) {
            LOG.warn("Failed to refresh external system: " + e.getMessage(), e);
            return describeFailure(e);
        }
    }

    /**
     * Returns {@code true} if the project has at least one linked project for the given
     * build system, {@code false} if definitely none, or {@code null} if the status could
     * not be determined (settings API threw — manager will be skipped).
     *
     * <p>Package-private for testing.
     */
    @org.jetbrains.annotations.Nullable Boolean hasLinkedProjects(Class<?> apiUtilClass, Object manager) {
        try {
            Object systemId = manager.getClass().getMethod("getSystemId").invoke(manager);
            Class<?> systemIdClass = Class.forName(PROJECT_SYSTEM_ID_CLASS);
            Method getSettings = apiUtilClass.getMethod("getSettings", Project.class, systemIdClass);
            Object settings = getSettings.invoke(null, project, systemId);
            Method getLinked = settings.getClass().getMethod("getLinkedProjectsSettings");
            Collection<?> linked = (Collection<?>) getLinked.invoke(settings);
            return !linked.isEmpty();
        } catch (Throwable e) { // NOSONAR - reflection can also throw Error (e.g. IllegalAccessError)
            LOG.warn("Could not check linked projects for " + getSystemName(manager) + " — will skip", e);
            return null;
        }
    }

    /**
     * Attempts refresh via {@code ImportSpecBuilder} — auto-discovers project paths, works for
     * multi-root setups. Returns {@code null} on success, or a short human-readable reason when
     * this API path is unavailable so the caller can try the next one and surface why.
     */
    private @org.jetbrains.annotations.Nullable String tryImportSpecRefresh(
        Class<?> externalSystemUtilClass, Class<?> systemIdClass, Object systemId) {
        try {
            Class<?> importSpecBuilderClass = Class.forName(IMPORT_SPEC_BUILDER_CLASS);
            Constructor<?> ctor = importSpecBuilderClass.getConstructor(Project.class, systemIdClass);
            Object importSpec = ctor.newInstance(project, systemId);
            externalSystemUtilClass.getMethod("refreshProjects", importSpecBuilderClass)
                .invoke(null, importSpec);
            return null;
        } catch (Exception e) {
            LOG.warn("ImportSpecBuilder refresh failed, will try the refreshProject API", e);
            return describeFailure(e);
        }
    }

    /**
     * Refreshes via {@code ExternalSystemUtil.refreshProject}. Unlike {@code ImportSpecBuilder}
     * this only covers the project base path, so it is a fallback rather than the primary path.
     *
     * <p>Returns {@code null} on success, or a short human-readable reason on failure.
     */
    private @org.jetbrains.annotations.Nullable String tryRefreshProject(
        Class<?> externalSystemUtilClass, Class<?> systemIdClass, Object systemId) {
        try {
            Class<?> progressModeClass = Class.forName(PROGRESS_EXECUTION_MODE_CLASS);
            // Enum constants are public static final fields — a name lookup is unaffected by
            // any toString() override on the enum.
            Object inBackground = progressModeClass.getField("IN_BACKGROUND_ASYNC").get(null);
            externalSystemUtilClass.getMethod("refreshProject",
                    Project.class, systemIdClass, String.class, boolean.class, progressModeClass)
                .invoke(null, project, systemId, project.getBasePath(), false, inBackground);
            return null;
        } catch (Exception e) {
            LOG.warn("refreshProject refresh failed", e);
            return describeFailure(e);
        }
    }

    /**
     * Renders a reflective failure as a short, self-explanatory string.
     *
     * <p>{@code ClassNotFoundException} and {@code NoSuchMethodException} carry only the missing
     * signature as their message, which is meaningless without the exception type — so the type
     * is always included. Reflective invocation wraps the real error in
     * {@code InvocationTargetException}, so the cause is unwrapped first.
     */
    private static String describeFailure(Throwable e) {
        Throwable actual = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null
            ? e.getCause()
            : e;
        String message = actual.getMessage();
        return actual.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }

    /**
     * Attempts to trigger a CMake project reload via {@code CMakeWorkspace.scheduleReload()}.
     * CMake is not an {@code ExternalSystemManager} — it has its own workspace mechanism.
     *
     * <p>Returns a success message if CMake reloaded, an error message if CMake is present
     * but reload failed, or {@code null} if CMake is not available in this IDE installation.
     *
     * <p>Package-private for testing.
     */
    @org.jetbrains.annotations.Nullable String tryCMakeReload() {
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
            return "✓ CMake (reload scheduled)\n\nProject model reload triggered for 1 build system(s). Indexing will run in the background.";
        } catch (Throwable e) { // NOSONAR - reflection can also throw Error (e.g., IllegalAccessError)
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
