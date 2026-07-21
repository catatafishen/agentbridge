package com.github.catatafishen.agentbridge.services;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Tests for GenericSettings — persistent storage via PropertiesComponent.
 * Uses the "copilot" prefix to mirror the production Copilot profile.
 * Extends BasePlatformTestCase to get a real IntelliJ application context.
 */
public class CopilotSettingsTest extends BasePlatformTestCase {

    private GenericSettings settings;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        settings = new GenericSettings("copilot");
        PropertiesComponent props = PropertiesComponent.getInstance();
        PropertiesComponent projectProps = PropertiesComponent.getInstance(getProject());
        // Clear app-level keys touched by these tests
        for (String key : new String[]{
            "copilot.selectedModel", "copilot.selectedAgent",
            "copilot.monthlyRequests", "copilot.monthlyCost", "copilot.usageResetMonth",
            "copilot.contextHistoryLimit", "copilot.maxToolCallsPerTurn",
            "copilot.resumeSessionId", "copilot.sessionOpt.effort"}) {
            props.unsetValue(key);
        }
        // Tool permission keys are NOT prefixed — they're global across all profiles
        for (String toolId : new String[]{"edit_text", "tool_in_fallback", "tool_in_set",
            "tool_out_fallback", "tool_out_set", "tool_to_clear"}) {
            props.unsetValue("tool.perm." + toolId);
            props.unsetValue("tool.perm.in." + toolId);
            props.unsetValue("tool.perm.out." + toolId);
        }
        // Clear project-level keys
        projectProps.unsetValue("copilot.selectedModel");
        projectProps.unsetValue("tool.perm.edit_text");
    }

    public void testSelectedModelDefaultNull() {
        assertNull(settings.getSelectedModel());
    }

    public void testSetAndGetSelectedModel() {
        settings.setSelectedModel("gpt-4.1");
        assertEquals("gpt-4.1", settings.getSelectedModel());
    }

    public void testSelectedModelOverwrite() {
        settings.setSelectedModel("gpt-4.1");
        settings.setSelectedModel("claude-sonnet-4.5");
        assertEquals("claude-sonnet-4.5", settings.getSelectedModel());
    }

    public void testSelectedAgentDefault() {
        assertEquals("", settings.getSelectedAgent());
    }

    public void testSetAndGetSelectedAgent() {
        settings.setSelectedAgent("ide-explore");
        assertEquals("ide-explore", settings.getSelectedAgent());
    }

    public void testMonthlyRequestsDefault() {
        assertEquals(0, settings.getMonthlyRequests());
    }

    public void testSetAndGetMonthlyRequests() {
        settings.setMonthlyRequests(42);
        assertEquals(42, settings.getMonthlyRequests());
    }

    public void testMonthlyCostDefault() {
        assertEquals(0.0, settings.getMonthlyCost(), 0.001);
    }

    public void testSetAndGetMonthlyCost() {
        settings.setMonthlyCost(12.50);
        assertEquals(12.50, settings.getMonthlyCost(), 0.001);
    }

    public void testUsageResetMonthDefault() {
        assertEquals("", settings.getUsageResetMonth());
    }

    public void testSetAndGetUsageResetMonth() {
        settings.setUsageResetMonth("2026-02");
        assertEquals("2026-02", settings.getUsageResetMonth());
    }

    public void testProjectScopedToolPermissionPersistsSeparatelyFromApplicationScope() {
        GenericSettings appSettings = new GenericSettings("copilot");
        GenericSettings projectSettings = new GenericSettings("copilot", getProject());

        appSettings.setToolPermission("edit_text", ToolPermission.DENY);
        projectSettings.setToolPermission("edit_text", ToolPermission.ASK);

        assertEquals(ToolPermission.DENY, appSettings.getToolPermission("edit_text"));
        assertEquals(ToolPermission.ASK, projectSettings.getToolPermission("edit_text"));
    }

    public void testContextHistoryLimitDefaultUsesProvided() {
        assertEquals(5, settings.getContextHistoryLimit(5));
        assertEquals(0, settings.getContextHistoryLimit(0));
    }

    public void testSetAndGetContextHistoryLimit() {
        settings.setContextHistoryLimit(10);
        assertEquals(10, settings.getContextHistoryLimit(999));
    }

    public void testMaxToolCallsDefault() {
        assertEquals(0, settings.getMaxToolCallsPerTurn());
    }

    public void testSetAndGetMaxToolCalls() {
        settings.setMaxToolCallsPerTurn(50);
        assertEquals(50, settings.getMaxToolCallsPerTurn());
    }

    public void testToolPermissionDefaultAllow() {
        assertEquals(ToolPermission.ALLOW, settings.getToolPermission("unknown_tool"));
    }

    public void testOutsideProjectAccessDefaultAllow() {
        assertEquals(ToolPermission.ALLOW, settings.getOutsideProjectAccess());
    }

    public void testSetAndGetOutsideProjectAccess() {
        settings.setOutsideProjectAccess(ToolPermission.ASK);
        assertEquals(ToolPermission.ASK, settings.getOutsideProjectAccess());
        // Restore the default: this is a single global key shared across tests (unlike the old
        // per-tool-id sub-permission keys), so leaving it set would pollute the default-value test.
        settings.setOutsideProjectAccess(ToolPermission.ALLOW);
    }

    public void testResolveEffectivePermissionWithDenied() {
        ToolRegistry registry = new ToolRegistry(getProject());
        settings.setToolPermission("denied_tool", ToolPermission.DENY);
        // DENY at top level → always returns DENY regardless of sub-permissions
        assertEquals(ToolPermission.DENY, settings.resolveEffectivePermission("denied_tool", true, registry));
        assertEquals(ToolPermission.DENY, settings.resolveEffectivePermission("denied_tool", false, registry));
    }

    public void testResolveEffectivePermissionToolNotInRegistry() {
        ToolRegistry registry = new ToolRegistry(getProject());
        // Tool not registered → no sub-permissions possible → returns global ALLOW
        assertEquals(ToolPermission.ALLOW, settings.resolveEffectivePermission("unknown_tool_x", true, registry));
        assertEquals(ToolPermission.ALLOW, settings.resolveEffectivePermission("unknown_tool_x", false, registry));
    }

    public void testResumeSessionIdDefaultNull() {
        assertNull(settings.getResumeSessionId());
    }

    public void testSetAndGetResumeSessionId() {
        settings.setResumeSessionId("session-abc-123");
        assertEquals("session-abc-123", settings.getResumeSessionId());
    }

    public void testClearResumeSessionIdWithNull() {
        settings.setResumeSessionId("session-abc-123");
        settings.setResumeSessionId(null);
        assertNull(settings.getResumeSessionId());
    }

    public void testClearResumeSessionIdWithEmpty() {
        settings.setResumeSessionId("session-abc-123");
        settings.setResumeSessionId("");
        assertNull(settings.getResumeSessionId());
    }

    public void testSessionOptionValueDefault() {
        assertEquals("", settings.getSessionOptionValue("effort"));
    }

    public void testSetAndGetSessionOptionValue() {
        settings.setSessionOptionValue("effort", "high");
        assertEquals("high", settings.getSessionOptionValue("effort"));
    }

    public void testActiveAgentLabelDefaultNull() {
        assertNull(settings.getActiveAgentLabel());
    }

    public void testSetAndGetActiveAgentLabel() {
        settings.setActiveAgentLabel("intellij-explore");
        assertEquals("intellij-explore", settings.getActiveAgentLabel());
    }

    public void testClearActiveAgentLabel() {
        settings.setActiveAgentLabel("intellij-explore");
        settings.setActiveAgentLabel(null);
        assertNull(settings.getActiveAgentLabel());
    }

    public void testActiveAgentLabelUsedAsModelFallback() {
        settings.setActiveAgentLabel("claude-sonnet-4.5");
        // When no selectedModel is persisted, getSelectedModel() falls back to activeAgentLabel
        assertNull(PropertiesComponent.getInstance().getValue("copilot.selectedModel"));
        assertEquals("claude-sonnet-4.5", settings.getSelectedModel());
    }

    public void testGetPrefix() {
        assertEquals("copilot.", settings.getPrefix());
    }
}
