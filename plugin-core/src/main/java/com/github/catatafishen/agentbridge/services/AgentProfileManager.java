package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.agent.claude.ClaudeCliClient;
import com.github.catatafishen.agentbridge.agent.claude.ClaudeCliCredentials;
import com.github.catatafishen.agentbridge.agent.codex.CodexAppServerClient;
import com.github.catatafishen.agentbridge.bridge.TransportType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages all built-in agent profiles with delta persistence.
 *
 * <p>On startup, profiles are built from hardcoded defaults.  Any user
 * customisations (binary path, custom models, instructions file) that were
 * saved in a previous session are then overlaid.  This ensures new defaults
 * from plugin updates take effect for fields the user has not touched.</p>
 *
 * <p>Thread-safe: all public reads are synchronized on this instance.</p>
 */
@Service(Service.Level.APP)
@State(name = "AgentProfileOverrides", storages = @Storage("ideAgentProfiles.xml"))
public final class AgentProfileManager implements PersistentStateComponent<AgentProfileManager.PersistedState> {

    public static final String COPILOT_PROFILE_ID = "copilot";
    public static final String OPENCODE_PROFILE_ID = "opencode";
    public static final String CLAUDE_CLI_PROFILE_ID = ClaudeCliClient.PROFILE_ID;
    public static final String JUNIE_PROFILE_ID = "junie";
    public static final String KIRO_PROFILE_ID = "kiro";
    public static final String CODEX_PROFILE_ID = CodexAppServerClient.PROFILE_ID;

    private final Map<String, AgentProfile> profiles = new LinkedHashMap<>();
    private PersistedState persistedState = new PersistedState();

    public AgentProfileManager() {
        ensureDefaults();
    }

    @NotNull
    public static AgentProfileManager getInstance() {
        return ApplicationManager.getApplication().getService(AgentProfileManager.class);
    }

    // ── Persistence ────────────────────────────────────────────────

    /**
     * User-customisable fields for a single profile.
     *
     * <p>Only fields that users can change through Settings UI are persisted.
     * All other profile fields are always rebuilt from hardcoded defaults so
     * that plugin updates can modify them freely.</p>
     */
    public static class ProfileOverride {
        public String profileId = "";
        public String customBinaryPath = "";
        public String prependInstructionsTo = "";
        public List<String> customCliModels = new ArrayList<>();
    }

    /**
     * Root state object serialised to {@code ideAgentProfiles.xml}.
     */
    public static class PersistedState {
        public List<ProfileOverride> overrides = new ArrayList<>();
    }

    @Override
    public @NotNull PersistedState getState() {
        snapshotOverrides();
        return persistedState;
    }

    @Override
    public void loadState(@NotNull PersistedState state) {
        this.persistedState = state;
        migrateFromPropertiesComponent();
        applyOverrides();
    }

    /**
     * Captures current user-customisable values from in-memory profiles into
     * {@link #persistedState} so they survive a restart.  Only fields that
     * differ from the hardcoded default are persisted, so plugin updates can
     * change defaults without being overridden by stale saved data.
     */
    private synchronized void snapshotOverrides() {
        persistedState.overrides.clear();
        for (AgentProfile profile : profiles.values()) {
            AgentProfile defaults = createDefaultProfile(profile.getId());
            if (defaults == null) continue;
            ProfileOverride o = toDelta(profile, defaults);
            if (hasUserData(o)) {
                persistedState.overrides.add(o);
            }
        }
    }

    /**
     * Overlays previously persisted user customisations onto the in-memory
     * profiles that were already built from hardcoded defaults.
     */
    private synchronized void applyOverrides() {
        for (ProfileOverride o : persistedState.overrides) {
            AgentProfile profile = profiles.get(o.profileId);
            if (profile == null) continue;
            if (o.customBinaryPath != null && !o.customBinaryPath.isEmpty()) {
                profile.setCustomBinaryPath(o.customBinaryPath);
            }
            if (o.prependInstructionsTo != null && !o.prependInstructionsTo.isEmpty()) {
                profile.setPrependInstructionsTo(o.prependInstructionsTo);
            }
            if (o.customCliModels != null && !o.customCliModels.isEmpty()) {
                profile.setCustomCliModels(new ArrayList<>(o.customCliModels));
            }
        }
    }

    private static ProfileOverride toDelta(AgentProfile current, AgentProfile defaults) {
        ProfileOverride o = new ProfileOverride();
        o.profileId = current.getId();
        String cbp = nullToEmpty(current.getCustomBinaryPath());
        o.customBinaryPath = cbp.equals(nullToEmpty(defaults.getCustomBinaryPath())) ? "" : cbp;
        String pit = nullToEmpty(current.getPrependInstructionsTo());
        o.prependInstructionsTo = pit.equals(nullToEmpty(defaults.getPrependInstructionsTo())) ? "" : pit;
        List<String> models = current.getCustomCliModels();
        o.customCliModels = models.equals(defaults.getCustomCliModels()) ? new ArrayList<>() : new ArrayList<>(models);
        return o;
    }

    private static boolean hasUserData(ProfileOverride o) {
        return !o.customBinaryPath.isEmpty()
            || !o.prependInstructionsTo.isEmpty()
            || !o.customCliModels.isEmpty();
    }

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    /**
     * Returns the user-configured binary path for the given agent, or {@code null}
     * if not set (auto-detect will be used instead).  This is the single source of
     * truth for all agents — both ACP and profile-based.
     */
    @Nullable
    public synchronized String loadBinaryPath(@NotNull String agentId) {
        ensureDefaults();
        AgentProfile profile = profiles.get(agentId);
        if (profile == null) return null;
        String path = profile.getCustomBinaryPath();
        return path.isEmpty() ? null : path;
    }

    /**
     * Persists a custom binary path for the given agent.
     * Pass {@code null} or blank to clear the override and use auto-detection.
     */
    public synchronized void saveBinaryPath(@NotNull String agentId, @Nullable String path) {
        ensureDefaults();
        AgentProfile profile = profiles.get(agentId);
        if (profile == null) return;
        profile.setCustomBinaryPath(path != null ? path.trim() : "");
    }

    /**
     * One-time migration: reads binary paths stored in the legacy
     * {@code PropertiesComponent} keys ({@code agentbridge.<id>.customBinary})
     * and copies them into the corresponding {@link AgentProfile}.
     * Called once during {@link #loadState} before applying XML overrides,
     * so that XML-persisted values (if any) win over legacy values.
     */
    private void migrateFromPropertiesComponent() {
        var props = com.intellij.ide.util.PropertiesComponent.getInstance();
        for (String id : List.of(COPILOT_PROFILE_ID, OPENCODE_PROFILE_ID,
            JUNIE_PROFILE_ID, KIRO_PROFILE_ID, CODEX_PROFILE_ID)) {
            String key = "agentbridge." + id + ".customBinary";
            String legacy = props.getValue(key, "").trim();
            if (legacy.isEmpty()) continue;
            AgentProfile profile = profiles.get(id);
            if (profile != null && profile.getCustomBinaryPath().isEmpty()) {
                profile.setCustomBinaryPath(legacy);
            }
            props.unsetValue(key);
        }
    }

    // ── Public API ─────────────────────────────────────────────────

    /**
     * Returns a human-readable Claude CLI authentication status string for display in settings UI.
     * Returns {@code null} if the credentials file cannot be read.
     */
    @Nullable
    public static String getClaudeCliAuthStatus() {
        ClaudeCliCredentials creds = ClaudeCliCredentials.read();
        if (creds.isLoggedIn()) {
            String name = creds.getDisplayName();
            return "✓ Logged in" + (name != null ? " as " + name : "");
        } else {
            return null;
        }
    }

    @NotNull
    public synchronized List<AgentProfile> getAllProfiles() {
        ensureDefaults();
        return new ArrayList<>(profiles.values());
    }

    @Nullable
    public synchronized AgentProfile getProfile(@NotNull String id) {
        ensureDefaults();
        return profiles.get(id);
    }

    // ── Defaults ───────────────────────────────────────────────────

    private void ensureDefaults() {
        for (String id : List.of(COPILOT_PROFILE_ID, OPENCODE_PROFILE_ID,
            CLAUDE_CLI_PROFILE_ID, JUNIE_PROFILE_ID, KIRO_PROFILE_ID, CODEX_PROFILE_ID)) {
            if (!profiles.containsKey(id)) {
                AgentProfile profile = createDefaultProfile(id);
                if (profile != null) profiles.put(id, profile);
            }
        }
    }

    @Nullable
    private AgentProfile createDefaultProfile(@NotNull String id) {
        return switch (id) {
            case COPILOT_PROFILE_ID -> buildCopilotProfile();
            case OPENCODE_PROFILE_ID -> buildOpenCodeProfile();
            case CLAUDE_CLI_PROFILE_ID -> ClaudeCliClient.createDefaultProfile();
            case JUNIE_PROFILE_ID -> buildJunieProfile();
            case KIRO_PROFILE_ID -> buildKiroProfile();
            case CODEX_PROFILE_ID -> CodexAppServerClient.createDefaultProfile();
            default -> null;
        };
    }

    /**
     * Creates the default Copilot profile. Public for use in tests.
     */
    @NotNull
    public static AgentProfile createDefaultCopilotProfile() {
        return buildCopilotProfile();
    }

    private static AgentProfile buildCopilotProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(COPILOT_PROFILE_ID);
        p.setDisplayName("GitHub Copilot");
        p.setBuiltIn(true);
        p.setBinaryName(COPILOT_PROFILE_ID);
        p.setAlternateNames(List.of("copilot-cli"));
        p.setInstallHint("Install with: npm install -g @github/copilot-cli");
        p.setInstallUrl("https://github.com/github/copilot-cli#installation");
        p.setSupportsOAuthSignIn(true);
        p.setPrependInstructionsTo(".github/copilot-instructions.md");
        return p;
    }

    private static AgentProfile buildJunieProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(JUNIE_PROFILE_ID);
        p.setDisplayName("Junie");
        p.setBuiltIn(true);
        p.setTransportType(TransportType.ACP);
        p.setBinaryName(JUNIE_PROFILE_ID);
        p.setInstallHint("Install from junie.jetbrains.com and run 'junie' to authenticate.");
        p.setInstallUrl("https://junie.jetbrains.com/docs/junie-cli.html");
        p.setSendResourceReferences(false);
        return p;
    }

    private static AgentProfile buildKiroProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(KIRO_PROFILE_ID);
        p.setDisplayName("Kiro");
        p.setBuiltIn(true);
        p.setTransportType(TransportType.ACP);
        p.setBinaryName("kiro-cli");
        p.setAlternateNames(List.of("kiro"));
        p.setInstallHint("Install Kiro CLI and ensure it's available on your PATH.");
        p.setInstallUrl("https://kiro.dev/docs/cli/acp/");
        p.setAgentsDirectory(System.getProperty("user.home") + "/.kiro/agents");
        p.setBundledAgentFiles(List.of("kiro-intellij-explore.json", "kiro-intellij-task.json"));
        return p;
    }

    private static AgentProfile buildOpenCodeProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(OPENCODE_PROFILE_ID);
        p.setDisplayName("OpenCode");
        p.setBuiltIn(true);
        p.setTransportType(TransportType.ACP);
        p.setBinaryName(OPENCODE_PROFILE_ID);
        p.setInstallHint("Install with: npm i -g opencode-ai");
        p.setInstallUrl("https://opencode.ai/docs");
        return p;
    }
}
