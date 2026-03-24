package com.github.catatafishen.ideagentforcopilot.services;

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
        // Clear any stale values from previous runs
        PropertiesComponent props = PropertiesComponent.getInstance();
        PropertiesComponent projectProps = PropertiesComponent.getInstance(getProject());
        props.unsetValue("copilot.selectedModel");
        props.unsetValue("copilot.selectedAgent");
        props.unsetValue("copilot.monthlyRequests");
        props.unsetValue("copilot.monthlyCost");
        props.unsetValue("copilot.usageResetMonth");
        props.unsetValue("copilot.tool.perm.edit_text");
        projectProps.unsetValue("copilot.selectedModel");
        projectProps.unsetValue("copilot.selectedAgent");
        projectProps.unsetValue("copilot.monthlyRequests");
        projectProps.unsetValue("copilot.monthlyCost");
        projectProps.unsetValue("copilot.usageResetMonth");
        projectProps.unsetValue("copilot.tool.perm.edit_text");
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
}
