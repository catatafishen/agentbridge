package com.github.catatafishen.agentbridge.settings;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Application-level settings for where the AgentBridge plugin stores its
 * per-project data files (e.g. the tool-call statistics database and semantic
 * memory files).
 *
 * <p>Resolves to {@code {project}/.agentbridge/} by default. Users can choose
 * the shared {@code ~/.agentbridge/} root or an absolute custom root via the
 * "Storage" settings page. Shared roots namespace each project under
 * {@code <root>/projects/<project-name>-<hash>/}.</p>
 */
@Service(Service.Level.APP)
@State(name = "AgentBridgeStorageSettings",
    storages = @Storage("agentbridgeStorage.xml"))
public final class AgentBridgeStorageSettings
    implements PersistentStateComponent<AgentBridgeStorageSettings.State> {

    private static final String DEFAULT_DIR_NAME = ".agentbridge";
    private static final String PROJECTS_DIR_NAME = "projects";
    private static final String MEMORY_DIR_NAME = "memory";

    private State myState = new State();

    public static AgentBridgeStorageSettings getInstance() {
        return PlatformApiCompat.getApplicationService(AgentBridgeStorageSettings.class);
    }

    public enum StorageLocationMode {
        PROJECT,
        USER_HOME,
        CUSTOM
    }

    /**
     * Returns the user-configured custom root, or {@code null} if the custom
     * root field is empty.
     */
    @Nullable
    public String getCustomStorageRoot() {
        String s = myState.getCustomStorageRoot();
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    public void setCustomStorageRoot(@Nullable String path) {
        myState.setCustomStorageRoot((path == null || path.isBlank()) ? null : path.trim());
    }

    @NotNull
    public StorageLocationMode getStorageLocationMode() {
        StorageLocationMode mode = myState.getStorageLocationMode();
        if (mode != null) {
            return mode;
        }
        return getCustomStorageRoot() == null ? StorageLocationMode.PROJECT : StorageLocationMode.CUSTOM;
    }

    public void setStorageLocationMode(@NotNull StorageLocationMode mode) {
        myState.setStorageLocationMode(mode);
    }

    public boolean isToolStatsEnabled() {
        return myState.isToolStatsEnabled();
    }

    public void setToolStatsEnabled(boolean enabled) {
        myState.setToolStatsEnabled(enabled);
    }

    /**
     * The project-local default storage root: {@code {project}/.agentbridge}.
     */
    @NotNull
    public static Path getProjectDefaultStorageRoot(@NotNull Project project) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            throw new IllegalStateException("Cannot resolve AgentBridge project storage: project has no base path");
        }
        return Paths.get(basePath, DEFAULT_DIR_NAME);
    }

    /**
     * The shared user-home storage root: {@code <user.home>/.agentbridge}.
     */
    @NotNull
    public static Path getUserHomeStorageRoot() {
        return Paths.get(System.getProperty("user.home"), DEFAULT_DIR_NAME);
    }

    /**
     * Returns the per-project data directory for the selected storage mode.
     * Project-local mode uses {@code {project}/.agentbridge}; shared roots use
     * {@code <root>/projects/<project-name>-<hash>/} so projects with
     * identical names do not collide.
     */
    @NotNull
    public Path getProjectStorageDir(@NotNull Project project) {
        return switch (getStorageLocationMode()) {
            case PROJECT -> getProjectDefaultStorageRoot(project);
            case USER_HOME -> getNamespacedProjectStorageDir(getUserHomeStorageRoot(), project);
            case CUSTOM -> getNamespacedProjectStorageDir(getRequiredCustomStorageRoot(), project);
        };
    }

    @NotNull
    private Path getRequiredCustomStorageRoot() {
        String custom = getCustomStorageRoot();
        if (custom == null) {
            throw new IllegalStateException("Custom AgentBridge storage location is selected but no path is configured");
        }
        return Paths.get(custom);
    }

    @NotNull
    private static Path getNamespacedProjectStorageDir(@NotNull Path root, @NotNull Project project) {
        String safeName = sanitize(project.getName());
        String hashSource = project.getBasePath() != null ? project.getBasePath() : project.getName();
        String hash = Integer.toHexString(hashSource.hashCode());
        return root.resolve(PROJECTS_DIR_NAME).resolve(safeName + "-" + hash);
    }

    /**
     * Returns the semantic memory directory for the given project under the
     * shared per-project storage directory.
     */
    @NotNull
    public Path getProjectMemoryDir(@NotNull Project project) {
        return getProjectStorageDir(project).resolve(MEMORY_DIR_NAME);
    }

    private static String sanitize(@NotNull String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String out = sb.toString().toLowerCase(Locale.ROOT);
        return out.isEmpty() ? "unnamed" : out;
    }

    @Override
    public @NotNull State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    public static class State {
        private String customStorageRoot = null;
        private StorageLocationMode storageLocationMode = null;
        private boolean toolStatsEnabled = true;

        public String getCustomStorageRoot() {
            return customStorageRoot;
        }

        public void setCustomStorageRoot(String customStorageRoot) {
            this.customStorageRoot = customStorageRoot;
        }

        public StorageLocationMode getStorageLocationMode() {
            return storageLocationMode;
        }

        public void setStorageLocationMode(StorageLocationMode storageLocationMode) {
            this.storageLocationMode = storageLocationMode;
        }

        public boolean isToolStatsEnabled() {
            return toolStatsEnabled;
        }

        public void setToolStatsEnabled(boolean toolStatsEnabled) {
            this.toolStatsEnabled = toolStatsEnabled;
        }
    }
}
