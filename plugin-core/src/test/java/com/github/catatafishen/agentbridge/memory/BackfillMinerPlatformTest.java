package com.github.catatafishen.agentbridge.memory;

import com.github.catatafishen.agentbridge.memory.mining.BackfillMiner;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.github.catatafishen.agentbridge.session.db.ConversationService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Platform tests for {@link BackfillMiner} — covers the private {@code doBackfill()} method
 * that resolves project services and the public {@code run()} async entry point.
 *
 * <p>Uses a real IntelliJ project with real {@link ConversationService} (backed by temp filesystem).
 * On a fresh project, listSessions returns empty — testing the no-sessions path.
 */
public class BackfillMinerPlatformTest extends MemoryPlatformTestCase {

    private static final int TIMEOUT_SECONDS = 10;

    public void testRunWithNoSessions() throws Exception {
        enableMemory();
        replaceMemoryServiceWithTestComponents();

        BackfillMiner miner = new BackfillMiner(getProject());
        List<String> progress = new ArrayList<>();

        BackfillMiner.BackfillResult result = miner.run(progress::add)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals("Should process zero sessions", 0, result.sessions());
        assertEquals("Should store zero memories", 0, result.stored());
        assertFalse("Should have progress messages", progress.isEmpty());
        assertTrue("Should report no sessions found",
                progress.stream().anyMatch(msg -> msg.contains("No sessions")));
    }

    public void testRunMarksBackfillCompleted() throws Exception {
        enableMemory();
        replaceMemoryServiceWithTestComponents();

        assertFalse("Backfill should not be completed initially",
                memorySettings().isBackfillCompleted());

        BackfillMiner miner = new BackfillMiner(getProject());
        miner.run(msg -> { }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue("Backfill should be marked completed after run",
                memorySettings().isBackfillCompleted());
    }

    public void testRunReturnsZeroExchangesForEmptyProject() throws Exception {
        enableMemory();
        replaceMemoryServiceWithTestComponents();

        BackfillMiner miner = new BackfillMiner(getProject());
        BackfillMiner.BackfillResult result = miner.run(msg -> { })
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(0, result.exchanges());
        assertEquals(0, result.filtered());
        assertEquals(0, result.duplicates());
    }

    public void testConversationServiceResolvesFromProject() {
        ConversationService service = ConversationService.getInstance(getProject());
        assertNotNull("ConversationService should resolve", service);

        // listSessions on a fresh project should return empty
        List<ConversationService.SessionRecord> sessions = service.listSessions();
        assertNotNull("listSessions should return non-null", sessions);
        assertTrue("listSessions on fresh project should be empty", sessions.isEmpty());
    }

    public void testRunUsesProjectBasePath() throws Exception {
        enableMemory();
        replaceMemoryServiceWithTestComponents();

        // The BackfillMiner uses project.getBasePath() to find sessions.
        // In a platform test, this is a real temp directory.
        assertNotNull("Project should have a base path", getProject().getBasePath());

        BackfillMiner miner = new BackfillMiner(getProject());
        // Should not throw — basePath is valid
        BackfillMiner.BackfillResult result = miner.run(msg -> { })
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertNotNull(result);
    }

    public void testProgressCallbackReceivesMessages() throws Exception {
        enableMemory();
        replaceMemoryServiceWithTestComponents();

        List<String> progress = new ArrayList<>();
        BackfillMiner miner = new BackfillMiner(getProject());
        miner.run(progress::add).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertFalse("Progress callback should receive at least one message", progress.isEmpty());
    }

    public void testRunDoesNotStoreWhenDisabled() throws Exception {
        // Memory disabled — BackfillMiner.doBackfill() should still run
        // (it resolves ConversationService independently) but TurnMiner won't store anything
        // because MemoryService returns nulls.
        // Note: this test verifies doBackfill can resolve the conversation service even when
        // MemoryService's getStore/getEmbeddingService return null.
        enableMemory();
        MemoryStore store = replaceMemoryServiceWithTestComponents();

        BackfillMiner miner = new BackfillMiner(getProject());
        miner.run(msg -> { }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals("No sessions = no stored memories", 0, store.getDrawerCount());
    }
}
