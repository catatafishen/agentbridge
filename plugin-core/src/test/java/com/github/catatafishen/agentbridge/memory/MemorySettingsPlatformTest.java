package com.github.catatafishen.agentbridge.memory;

/**
 * Platform tests for {@link MemorySettings} — verifies defaults, persistence,
 * and state management with a real IntelliJ project.
 */
public class MemorySettingsPlatformTest extends MemoryPlatformTestCase {

    public void testDefaultDisabled() {
        assertFalse("Memory should be disabled by default", memorySettings().isEnabled());
    }

    public void testDefaultAutoMineOnTurnComplete() {
        assertTrue("Auto-mine on turn complete should default to true",
                memorySettings().isAutoMineOnTurnComplete());
    }

    public void testDefaultAutoMineOnSessionArchive() {
        assertTrue("Auto-mine on session archive should default to true",
                memorySettings().isAutoMineOnSessionArchive());
    }

    public void testDefaultMinChunkLength() {
        assertEquals(200, memorySettings().getMinChunkLength());
    }

    public void testDefaultMaxDrawersPerTurn() {
        assertEquals(10, memorySettings().getMaxDrawersPerTurn());
    }

    public void testDefaultPalaceWingEmpty() {
        assertEquals("", memorySettings().getPalaceWing());
    }

    public void testDefaultBackfillNotCompleted() {
        assertFalse("Backfill should not be marked as completed by default",
                memorySettings().isBackfillCompleted());
    }

    public void testSetEnabled() {
        memorySettings().setEnabled(true);
        assertTrue(memorySettings().isEnabled());

        memorySettings().setEnabled(false);
        assertFalse(memorySettings().isEnabled());
    }

    public void testSetMinChunkLength() {
        memorySettings().setMinChunkLength(500);
        assertEquals(500, memorySettings().getMinChunkLength());
    }

    public void testSetMaxDrawersPerTurn() {
        memorySettings().setMaxDrawersPerTurn(25);
        assertEquals(25, memorySettings().getMaxDrawersPerTurn());
    }

    public void testSetPalaceWing() {
        memorySettings().setPalaceWing("my-project-wing");
        assertEquals("my-project-wing", memorySettings().getPalaceWing());
    }

    public void testSetBackfillCompleted() {
        memorySettings().setBackfillCompleted(true);
        assertTrue(memorySettings().isBackfillCompleted());
    }

    public void testSettingsAreSameAcrossCalls() {
        MemorySettings first = MemorySettings.getInstance(getProject());
        first.setEnabled(true);
        first.setMinChunkLength(300);

        MemorySettings second = MemorySettings.getInstance(getProject());
        assertTrue("Settings should persist across getInstance calls", second.isEnabled());
        assertEquals(300, second.getMinChunkLength());
    }

    public void testStateRoundTrip() {
        MemorySettings settings = memorySettings();
        settings.setEnabled(true);
        settings.setAutoMineOnTurnComplete(false);
        settings.setAutoMineOnSessionArchive(false);
        settings.setMinChunkLength(150);
        settings.setMaxDrawersPerTurn(5);
        settings.setPalaceWing("test-wing");
        settings.setBackfillCompleted(true);

        MemorySettings.State state = settings.getState();
        assertNotNull(state);

        MemorySettings fresh = MemorySettings.getInstance(getProject());
        fresh.loadState(state);

        assertTrue(fresh.isEnabled());
        assertFalse(fresh.isAutoMineOnTurnComplete());
        assertFalse(fresh.isAutoMineOnSessionArchive());
        assertEquals(150, fresh.getMinChunkLength());
        assertEquals(5, fresh.getMaxDrawersPerTurn());
        assertEquals("test-wing", fresh.getPalaceWing());
        assertTrue(fresh.isBackfillCompleted());
    }
}
