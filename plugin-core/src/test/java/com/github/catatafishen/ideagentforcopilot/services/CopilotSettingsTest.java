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
        props.unsetValue("copilot.selectedModel");
        props.unsetValue("copilot.sessionMode");
        props.unsetValue("copilot.monthlyRequests");
        props.unsetValue("copilot.monthlyCost");
        props.unsetValue("copilot.usageResetMonth");
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

    public void testSessionModeDefaultAgent() {
        assertEquals("agent", settings.getSessionMode());
    }

    public void testSetAndGetSessionMode() {
        settings.setSessionMode("plan");
        assertEquals("plan", settings.getSessionMode());
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
}
