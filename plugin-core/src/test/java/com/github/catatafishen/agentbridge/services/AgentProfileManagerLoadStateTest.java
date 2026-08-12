package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.client.acp.CopilotClient;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Platform tests for {@link AgentProfileManager#loadState} — requires IntelliJ
 * application context because {@code loadState} calls {@code migrateFromPropertiesComponent}
 * which reads from {@code PropertiesComponent.getInstance()}.
 */
public class AgentProfileManagerLoadStateTest extends BasePlatformTestCase {

    public void testLoadStateRestoresBinaryPath() {
        AgentProfileManager.ProfileOverride override = new AgentProfileManager.ProfileOverride();
        override.profileId = AgentProfileManager.COPILOT_PROFILE_ID;
        override.customBinaryPath = "/restored/copilot";
        AgentProfileManager.PersistedState state = new AgentProfileManager.PersistedState();
        state.overrides.add(override);

        AgentProfileManager fresh = new AgentProfileManager();
        fresh.loadState(state);

        assertEquals("/restored/copilot", fresh.loadBinaryPath(AgentProfileManager.COPILOT_PROFILE_ID));
    }

    public void testLoadStateEmptyBinaryPathOverrideNoOp() {
        AgentProfileManager.ProfileOverride override = new AgentProfileManager.ProfileOverride();
        override.profileId = AgentProfileManager.COPILOT_PROFILE_ID;
        override.customBinaryPath = "";
        AgentProfileManager.PersistedState state = new AgentProfileManager.PersistedState();
        state.overrides.add(override);

        AgentProfileManager fresh = new AgentProfileManager();
        fresh.loadState(state);
        assertNull(fresh.loadBinaryPath(AgentProfileManager.COPILOT_PROFILE_ID));
    }

    public void testLoadStateUnknownProfileIgnored() {
        AgentProfileManager.ProfileOverride override = new AgentProfileManager.ProfileOverride();
        override.profileId = "non-existent-profile";
        override.customBinaryPath = "/some/path";
        AgentProfileManager.PersistedState state = new AgentProfileManager.PersistedState();
        state.overrides.add(override);

        AgentProfileManager fresh = new AgentProfileManager();
        fresh.loadState(state); // Should not throw
        assertNull(fresh.loadBinaryPath("non-existent-profile"));
    }

    public void testLoadStateRestoresCustomCliModels() {
        AgentProfileManager.ProfileOverride override = new AgentProfileManager.ProfileOverride();
        override.profileId = AgentProfileManager.COPILOT_PROFILE_ID;
        override.customCliModels.add("my-model-1");
        override.customCliModels.add("my-model-2");
        AgentProfileManager.PersistedState state = new AgentProfileManager.PersistedState();
        state.overrides.add(override);

        AgentProfileManager fresh = new AgentProfileManager();
        fresh.loadState(state);

        AgentProfile p = fresh.getProfile(AgentProfileManager.COPILOT_PROFILE_ID);
        assertNotNull(p);
        assertEquals(2, p.getCustomCliModels().size());
        assertTrue(p.getCustomCliModels().contains("my-model-1"));
    }

    public void testLoadStateRestoresPrependInstructionsTo() {
        AgentProfileManager.ProfileOverride override = new AgentProfileManager.ProfileOverride();
        override.profileId = AgentProfileManager.COPILOT_PROFILE_ID;
        override.prependInstructionsTo = "custom/instructions.md";
        AgentProfileManager.PersistedState state = new AgentProfileManager.PersistedState();
        state.overrides.add(override);

        AgentProfileManager fresh = new AgentProfileManager();
        fresh.loadState(state);

        AgentProfile p = fresh.getProfile(AgentProfileManager.COPILOT_PROFILE_ID);
        assertNotNull(p);
        assertEquals("custom/instructions.md", p.getPrependInstructionsTo());
    }

    public void testLoadStateRestoresExcludedBuiltInTools() {
        AgentProfileManager.ProfileOverride override = new AgentProfileManager.ProfileOverride();
        override.profileId = AgentProfileManager.COPILOT_PROFILE_ID;
        override.excludedBuiltInTools = "view,edit,create,bash,glob,grep,rg,apply_patch";
        AgentProfileManager.PersistedState state = new AgentProfileManager.PersistedState();
        state.overrides.add(override);

        AgentProfileManager fresh = new AgentProfileManager();
        fresh.loadState(state);

        AgentProfile p = fresh.getProfile(AgentProfileManager.COPILOT_PROFILE_ID);
        assertNotNull(p);
        assertEquals("view,edit,create,bash,glob,grep,rg,apply_patch", p.getExcludedBuiltInTools());
    }

    public void testLoadStateEmptyExcludedBuiltInToolsOverrideNoOp() {
        AgentProfileManager.ProfileOverride override = new AgentProfileManager.ProfileOverride();
        override.profileId = AgentProfileManager.COPILOT_PROFILE_ID;
        override.excludedBuiltInTools = "";
        AgentProfileManager.PersistedState state = new AgentProfileManager.PersistedState();
        state.overrides.add(override);

        AgentProfileManager fresh = new AgentProfileManager();
        fresh.loadState(state);

        AgentProfile p = fresh.getProfile(AgentProfileManager.COPILOT_PROFILE_ID);
        assertNotNull(p);
        assertEquals(CopilotClient.DEFAULT_EXCLUDED_BUILT_IN_TOOLS, p.getExcludedBuiltInTools());
    }
}
