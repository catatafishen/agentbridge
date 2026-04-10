package com.github.catatafishen.agentbridge.memory;

import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.github.catatafishen.agentbridge.memory.wal.WriteAheadLog;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Platform tests for {@link MemoryService} lifecycle — lazy initialization,
 * disabled/enabled behavior, disposal, and wing detection.
 */
public class MemoryServicePlatformTest extends MemoryPlatformTestCase {

    public void testGettersReturnNullWhenDisabled() {
        assertFalse("Memory should be disabled by default", memorySettings().isEnabled());

        MemoryService service = MemoryService.getInstance(getProject());
        assertNull("getStore() should return null when disabled", service.getStore());
        assertNull("getEmbeddingService() should return null when disabled", service.getEmbeddingService());
        assertNull("getWriteAheadLog() should return null when disabled", service.getWriteAheadLog());
        assertNull("getKnowledgeGraph() should return null when disabled", service.getKnowledgeGraph());
    }

    public void testIsActiveReturnsFalseWhenDisabled() {
        MemoryService service = MemoryService.getInstance(getProject());
        assertFalse("isActive() should be false when disabled", service.isActive());
    }

    public void testGettersReturnComponentsFromTestConstructor() throws IOException {
        enableMemory();

        MemoryStore store = replaceMemoryServiceWithTestComponents();
        MemoryService service = MemoryService.getInstance(getProject());

        assertNotNull("getStore() should return the test store", service.getStore());
        assertSame(store, service.getStore());
        assertNotNull("getEmbeddingService() should return the test embedding", service.getEmbeddingService());
    }

    public void testIsActiveReturnsTrueWhenEnabledAndInitialized() throws IOException {
        enableMemory();
        replaceMemoryServiceWithTestComponents();

        MemoryService service = MemoryService.getInstance(getProject());
        assertTrue("isActive() should be true when enabled and initialized", service.isActive());
    }

    public void testEffectiveWingDerivesFromProjectName() {
        MemoryService service = MemoryService.getInstance(getProject());
        String wing = service.getEffectiveWing();
        assertNotNull("effectiveWing should not be null", wing);
        assertFalse("effectiveWing should not be empty", wing.isEmpty());
    }

    public void testEffectiveWingUsesSettingsWhenSet() {
        memorySettings().setPalaceWing("custom-wing");

        MemoryService service = MemoryService.getInstance(getProject());
        assertEquals("custom-wing", service.getEffectiveWing());
    }

    public void testEffectiveWingSanitizesProjectName() {
        MemoryService service = MemoryService.getInstance(getProject());
        String wing = service.getEffectiveWing();
        assertTrue("Wing should only contain [a-z0-9_-]", wing.matches("[a-z0-9_-]+"));
    }

    public void testDispose() throws IOException {
        enableMemory();
        replaceMemoryServiceWithTestComponents();

        MemoryService service = MemoryService.getInstance(getProject());
        assertTrue("Should be active before dispose", service.isActive());

        service.dispose();
        assertFalse("Should not be active after dispose", service.isActive());
    }

    public void testReplacedWalIsAccessible() throws IOException {
        enableMemory();
        Path walDir = getTempMemoryDir().resolve("wal-test");
        WriteAheadLog wal = new WriteAheadLog(walDir);
        wal.initialize();
        // Provide WAL along with a store to avoid ensureInitialized() running
        MemoryStore store = replaceMemoryServiceWithTestComponents();
        replaceMemoryService(store, null, wal, null);

        MemoryService service = MemoryService.getInstance(getProject());
        assertSame("getWriteAheadLog() should return replaced WAL", wal, service.getWriteAheadLog());
    }
}
