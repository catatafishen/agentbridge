package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AgentProfileManager}.
 * The constructor is pure Java — no IntelliJ platform needed.
 */
class AgentProfileManagerTest {

    private AgentProfileManager manager;

    @BeforeEach
    void setUp() {
        manager = new AgentProfileManager();
    }

    // ── Default profiles ─────────────────────────────────────────────────────

    @Test
    @DisplayName("getAllProfiles returns 6 built-in profiles")
    void getAllProfilesReturnsDefaults() {
        List<AgentProfile> profiles = manager.getAllProfiles();
        assertEquals(6, profiles.size());
    }

    @Test
    @DisplayName("All default profiles have non-empty display names")
    void allProfilesHaveDisplayNames() {
        for (AgentProfile p : manager.getAllProfiles()) {
            assertNotNull(p.getDisplayName(), "Profile " + p.getId() + " should have a display name");
            assertFalse(p.getDisplayName().isEmpty(), "Profile " + p.getId() + " display name should not be empty");
        }
    }

    @Test
    @DisplayName("All known profile IDs are present")
    void allKnownProfileIdsPresent() {
        for (String id : List.of(
            AgentProfileManager.COPILOT_PROFILE_ID,
            AgentProfileManager.OPENCODE_PROFILE_ID,
            AgentProfileManager.CLAUDE_CLI_PROFILE_ID,
            AgentProfileManager.JUNIE_PROFILE_ID,
            AgentProfileManager.KIRO_PROFILE_ID,
            AgentProfileManager.CODEX_PROFILE_ID)) {
            assertNotNull(manager.getProfile(id), "Profile not found: " + id);
        }
    }

    @Test
    @DisplayName("getProfile returns null for unknown ID")
    void getProfileUnknownId() {
        assertNull(manager.getProfile("does-not-exist"));
    }

    @Test
    @DisplayName("Copilot profile has expected defaults")
    void copilotProfileDefaults() {
        AgentProfile p = manager.getProfile(AgentProfileManager.COPILOT_PROFILE_ID);
        assertNotNull(p);
        assertEquals("copilot", p.getId());
        assertEquals("GitHub Copilot", p.getDisplayName());
        assertTrue(p.isBuiltIn());
        assertTrue(p.isSupportsOAuthSignIn());
        assertEquals(".github/copilot-instructions.md", p.getPrependInstructionsTo());
    }

    @Test
    @DisplayName("createDefaultCopilotProfile static method returns correct profile")
    void createDefaultCopilotProfileStatic() {
        AgentProfile p = AgentProfileManager.createDefaultCopilotProfile();
        assertNotNull(p);
        assertEquals("copilot", p.getId());
        assertEquals("GitHub Copilot", p.getDisplayName());
    }

    // ── Binary path CRUD ─────────────────────────────────────────────────────

    @Test
    @DisplayName("loadBinaryPath returns null for default profile (no custom path)")
    void loadBinaryPathDefaultNull() {
        assertNull(manager.loadBinaryPath(AgentProfileManager.COPILOT_PROFILE_ID));
    }

    @Test
    @DisplayName("saveBinaryPath + loadBinaryPath roundtrip")
    void saveBinaryPathRoundtrip() {
        manager.saveBinaryPath(AgentProfileManager.COPILOT_PROFILE_ID, "/usr/local/bin/copilot");
        assertEquals("/usr/local/bin/copilot", manager.loadBinaryPath(AgentProfileManager.COPILOT_PROFILE_ID));
    }

    @Test
    @DisplayName("saveBinaryPath with null clears the override")
    void saveBinaryPathNullClears() {
        manager.saveBinaryPath(AgentProfileManager.COPILOT_PROFILE_ID, "/some/path");
        manager.saveBinaryPath(AgentProfileManager.COPILOT_PROFILE_ID, null);
        assertNull(manager.loadBinaryPath(AgentProfileManager.COPILOT_PROFILE_ID));
    }

    @Test
    @DisplayName("saveBinaryPath with blank clears the override")
    void saveBinaryPathBlankClears() {
        manager.saveBinaryPath(AgentProfileManager.COPILOT_PROFILE_ID, "/some/path");
        manager.saveBinaryPath(AgentProfileManager.COPILOT_PROFILE_ID, "  ");
        assertNull(manager.loadBinaryPath(AgentProfileManager.COPILOT_PROFILE_ID));
    }

    @Test
    @DisplayName("saveBinaryPath for unknown agentId does nothing")
    void saveBinaryPathUnknownId() {
        assertDoesNotThrow(() -> manager.saveBinaryPath("unknown-agent", "/some/path"));
    }

    @Test
    @DisplayName("loadBinaryPath for unknown agentId returns null")
    void loadBinaryPathUnknownId() {
        assertNull(manager.loadBinaryPath("unknown-agent"));
    }

    // ── Snapshot / override persistence ─────────────────────────────────────

    @Test
    @DisplayName("getState with unchanged profiles returns empty overrides list")
    void getStateDefaultIsEmpty() {
        AgentProfileManager.PersistedState state = manager.getState();
        assertTrue(state.overrides.isEmpty(), "Unchanged profiles should produce no overrides");
    }

    @Test
    @DisplayName("getState persists customised binary path as override")
    void getStatePersistsBinaryPath() {
        manager.saveBinaryPath(AgentProfileManager.COPILOT_PROFILE_ID, "/custom/copilot");
        AgentProfileManager.PersistedState state = manager.getState();
        AgentProfileManager.ProfileOverride saved = state.overrides.stream()
            .filter(o -> AgentProfileManager.COPILOT_PROFILE_ID.equals(o.profileId))
            .findFirst()
            .orElse(null);
        assertNotNull(saved, "Expected override for copilot");
        assertEquals("/custom/copilot", saved.customBinaryPath);
    }

    @Test
    @DisplayName("getAllProfiles returns a snapshot (modifying result does not affect manager)")
    void getAllProfilesIsSnapshot() {
        List<AgentProfile> profiles = manager.getAllProfiles();
        int original = profiles.size();
        profiles.clear();
        assertEquals(original, manager.getAllProfiles().size(), "getAllProfiles should return a new list each time");
    }
}
