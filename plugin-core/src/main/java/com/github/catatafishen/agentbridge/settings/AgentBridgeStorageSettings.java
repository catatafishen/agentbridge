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
 * <p>Resolves to {@code ~/.agentbridge/} by default. Users can override the
 * root via the "Storage" settings page. Per-project data lives under
 * {@code <root>/projects/<project-name>-<hash>/}, keeping stats scoped to
 * individual projects while moving them out of the project tree (see issue
 * #351).</p>
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

    /**
     * Returns the user-configured custom root, or {@code null} if the default
     * should be used.
     */
    @Nullable
    public String getCustomStorageRoot() {
        String s = myState.customStorageRoot;
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    public void setCustomStorageRoot(@Nullable String path) {
        myState.customStorageRoot = (path == null || path.isBlank()) ? null : path.trim();
    }

    public boolean isToolStatsEnabled() {
        return myState.toolStatsEnabled;
    }

    public void setToolStatsEnabled(boolean enabled) {
        myState.toolStatsEnabled = enabled;
    }

    /**
     * The default storage root: {@code <user.home>/.agentbridge}. This matches
     * the location already used by the embedding model cache, so all plugin
     * data lives in one user-visible directory.
     */
    @NotNull
    public static Path getDefaultStorageRoot() {
        return Paths.get(System.getProperty("user.home"), DEFAULT_DIR_NAME);
    }

    /**
     * Returns the effective storage root — the user override if set, otherwise
     * the default.
     */
    @NotNull
    public Path getEffectiveStorageRoot() {
        String custom = getCustomStorageRoot();
        return custom != null ? Paths.get(custom) : getDefaultStorageRoot();
    }

    /**
     * Returns the per-project data directory under the effective storage root.
     * The directory is namespaced by project name plus a hash of the project
     * base path so that projects with identical names do not collide.
     */
    @NotNull
    public Path getProjectStorageDir(@NotNull Project project) {
        String safeName = sanitize(project.getName());
        String hashSource = project.getBasePath() != null ? project.getBasePath() : project.getName();
        String hash = Integer.toHexString(hashSource.hashCode());
        return getEffectiveStorageRoot().resolve(PROJECTS_DIR_NAME).resolve(safeName + "-" + hash);
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
        private boolean toolStatsEnabled = true;

        public String getCustomStorageRoot() {
            return customStorageRoot;
        }

        public void setCustomStorageRoot(String customStorageRoot) {
            this.customStorageRoot = customStorageRoot;
        }

        public boolean isToolStatsEnabled() {
            return toolStatsEnabled;
        }

        public void setToolStatsEnabled(boolean toolStatsEnabled) {
            this.toolStatsEnabled = toolStatsEnabled;
        }
    }
}
