package com.github.catatafishen.agentbridge.memory;

import com.github.catatafishen.agentbridge.session.db.ConversationService;

/**
 * Verifies that all memory-related project services resolve correctly from the service container.
 *
 * <p>These tests catch plugin.xml registration errors and service wiring issues
 * that only manifest with a real IntelliJ project.
 */
public class ServiceRegistrationPlatformTest extends MemoryPlatformTestCase {

    public void testMemoryServiceResolves() {
        MemoryService service = MemoryService.getInstance(getProject());
        assertNotNull("MemoryService should resolve from project service container", service);
    }

    public void testMemorySettingsResolves() {
        MemorySettings settings = MemorySettings.getInstance(getProject());
        assertNotNull("MemorySettings should resolve from project service container", settings);
    }

    public void testConversationServiceResolves() {
        ConversationService service = ConversationService.getInstance(getProject());
        assertNotNull("ConversationService should resolve from project service container", service);
    }

    public void testMemoryServiceReturnsSameInstance() {
        MemoryService first = MemoryService.getInstance(getProject());
        MemoryService second = MemoryService.getInstance(getProject());
        assertSame("Project service should return the same instance", first, second);
    }

    public void testMemorySettingsReturnsSameInstance() {
        MemorySettings first = MemorySettings.getInstance(getProject());
        MemorySettings second = MemorySettings.getInstance(getProject());
        assertSame("Project service should return the same instance", first, second);
    }
}
