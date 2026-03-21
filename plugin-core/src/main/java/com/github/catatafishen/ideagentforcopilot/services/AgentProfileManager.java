package com.github.catatafishen.ideagentforcopilot.services;

import com.github.catatafishen.ideagentforcopilot.agent.claude.AnthropicDirectClient;
import com.github.catatafishen.ideagentforcopilot.agent.claude.ClaudeCliClient;
import com.github.catatafishen.ideagentforcopilot.agent.claude.ClaudeCliCredentials;
import com.github.catatafishen.ideagentforcopilot.bridge.TransportType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages all built-in agent profiles. Profiles are static defaults; no persistence.
 *
 * <p>Thread-safe: all reads are synchronized on this instance.</p>
 */
public final class AgentProfileManager {

    public static final String COPILOT_PROFILE_ID = "copilot";
    public static final String OPENCODE_PROFILE_ID = "opencode";
    public static final String CLAUDE_CODE_PROFILE_ID = AnthropicDirectClient.PROFILE_ID;
    public static final String CLAUDE_CLI_PROFILE_ID = ClaudeCliClient.PROFILE_ID;
    public static final String JUNIE_PROFILE_ID = "junie";
    public static final String KIRO_PROFILE_ID = "kiro";

    private final Map<String, AgentProfile> profiles = new LinkedHashMap<>();

    public AgentProfileManager() {
        ensureDefaults();
    }

    private static final AgentProfileManager INSTANCE = new AgentProfileManager();

    @NotNull
    public static AgentProfileManager getInstance() {
        return INSTANCE;
    }

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

    private void ensureDefaults() {
        for (String id : List.of(COPILOT_PROFILE_ID, OPENCODE_PROFILE_ID, CLAUDE_CODE_PROFILE_ID,
                                  CLAUDE_CLI_PROFILE_ID, JUNIE_PROFILE_ID, KIRO_PROFILE_ID)) {
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
            case CLAUDE_CODE_PROFILE_ID -> AnthropicDirectClient.createDefaultProfile();
            case CLAUDE_CLI_PROFILE_ID -> ClaudeCliClient.createDefaultProfile();
            case JUNIE_PROFILE_ID -> buildJunieProfile();
            case KIRO_PROFILE_ID -> buildKiroProfile();
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
        return p;
    }

    private static AgentProfile buildKiroProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(KIRO_PROFILE_ID);
        p.setDisplayName("Kiro");
        p.setBuiltIn(true);
        p.setExperimental(true);
        p.setTransportType(TransportType.ACP);
        p.setBinaryName("kiro-cli");
        p.setAlternateNames(List.of("kiro"));
        p.setInstallHint("Install Kiro CLI and ensure it's available on your PATH.");
        p.setInstallUrl("https://kiro.dev/docs/cli/acp/");
        return p;
    }

    private static AgentProfile buildOpenCodeProfile() {
        AgentProfile p = new AgentProfile();
        p.setId(OPENCODE_PROFILE_ID);
        p.setDisplayName("OpenCode");
        p.setBuiltIn(true);
        p.setExperimental(true);
        p.setBinaryName(OPENCODE_PROFILE_ID);
        p.setInstallHint("Install with: npm i -g opencode-ai");
        return p;
    }

}
