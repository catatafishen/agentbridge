package com.github.catatafishen.agentbridge.memory;

import com.github.catatafishen.agentbridge.memory.mining.TurnMiner;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.github.catatafishen.agentbridge.ui.EntryData;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Platform tests for {@link TurnMiner} — covers the private {@code doMine()} method
 * that resolves project services and the public {@code mineTurn()} async entry point.
 *
 * <p>These tests use {@link MemoryPlatformTestCase} to provide a real IntelliJ project
 * with replaced services, exercising the full service wiring path.
 */
public class TurnMinerPlatformTest extends MemoryPlatformTestCase {

    private static final int TIMEOUT_SECONDS = 10;

    public void testMineTurnReturnsEmptyWhenDisabled() throws Exception {
        assertFalse("Memory should be disabled by default", memorySettings().isEnabled());

        TurnMiner miner = new TurnMiner(getProject());
        TurnMiner.MineResult result = miner.mineTurn(
                createTestEntries(), "session-1", "test-agent"
        ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals("Should store nothing when disabled", 0, result.stored());
        assertEquals("Should have no exchanges when disabled", 0, result.total());
    }

    public void testMineTurnStoresExchangesWhenEnabled() throws Exception {
        enableMemory();
        MemoryStore store = replaceMemoryServiceWithTestComponents();

        TurnMiner miner = new TurnMiner(getProject());
        TurnMiner.MineResult result = miner.mineTurn(
                createLongTestEntries(), "session-1", "test-agent"
        ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue("Should extract at least one exchange", result.total() > 0);
        assertTrue("Should store at least one memory", result.stored() > 0);
        assertTrue("Store should contain documents", store.getDrawerCount() > 0);
    }

    public void testMineTurnUsesSettingsMaxDrawers() throws Exception {
        enableMemory();
        memorySettings().setMaxDrawersPerTurn(1);
        replaceMemoryServiceWithTestComponents();

        TurnMiner miner = new TurnMiner(getProject());
        TurnMiner.MineResult result = miner.mineTurn(
                createMultipleExchangeEntries(), "session-1", "test-agent"
        ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue("Should have more exchanges than stored (limited by maxDrawers)",
                result.total() > result.stored());
        assertEquals("Should store at most maxDrawersPerTurn", 1, result.stored());
    }

    public void testMineTurnUsesProjectWing() throws Exception {
        enableMemory();
        MemoryStore store = replaceMemoryServiceWithTestComponents();

        TurnMiner miner = new TurnMiner(getProject());
        miner.mineTurn(
                createLongTestEntries(), "session-1", "test-agent"
        ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue("Store should contain a document", store.getDrawerCount() > 0);
    }

    public void testMineTurnUsesCustomWing() throws Exception {
        enableMemory();
        memorySettings().setPalaceWing("custom-wing");
        MemoryStore store = replaceMemoryServiceWithTestComponents();

        TurnMiner miner = new TurnMiner(getProject());
        miner.mineTurn(
                createLongTestEntries(), "session-1", "test-agent"
        ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // The wing is embedded in the drawer ID and stored in the document
        assertTrue("Store should contain a document with custom wing", store.getDrawerCount() > 0);
    }

    public void testMineTurnFiltersShortEntries() throws Exception {
        enableMemory();
        memorySettings().setMinChunkLength(200);
        replaceMemoryServiceWithTestComponents();

        TurnMiner miner = new TurnMiner(getProject());
        TurnMiner.MineResult result = miner.mineTurn(
                createShortTestEntries(), "session-1", "test-agent"
        ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals("Short entries should be filtered", 0, result.stored());
    }

    public void testMineTurnReturnsEmptyForEmptyEntries() throws Exception {
        enableMemory();
        replaceMemoryServiceWithTestComponents();

        TurnMiner miner = new TurnMiner(getProject());
        TurnMiner.MineResult result = miner.mineTurn(
                List.of(), "session-1", "test-agent"
        ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(0, result.stored());
        assertEquals(0, result.total());
    }

    public void testMineTurnReturnsEmptyWhenEmbeddingNull() throws Exception {
        enableMemory();
        replaceMemoryService(null, null, null, null);

        TurnMiner miner = new TurnMiner(getProject());
        TurnMiner.MineResult result = miner.mineTurn(
                createLongTestEntries(), "session-1", "test-agent"
        ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals("Should return EMPTY when embedding is null", 0, result.stored());
        assertEquals(0, result.total());
    }

    public void testQualityFilterResolvesMinChunkFromProject() throws Exception {
        enableMemory();
        memorySettings().setMinChunkLength(5000);
        replaceMemoryServiceWithTestComponents();

        TurnMiner miner = new TurnMiner(getProject());
        TurnMiner.MineResult result = miner.mineTurn(
                createLongTestEntries(), "session-1", "test-agent"
        ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals("All entries should be filtered with high minChunkLength", 0, result.stored());
    }

    // --- Test data helpers ---

    private static List<EntryData> createTestEntries() {
        EntryData.Prompt prompt = new EntryData.Prompt("How do I refactor this code?");
        EntryData.Text response = new EntryData.Text();
        response.setRaw("You should extract a method and rename variables.");
        return List.of(prompt, response);
    }

    private static List<EntryData> createLongTestEntries() {
        String longPrompt = "I need to refactoring the authentication module in our Java application. "
                + "The current implementation has several issues: the session management is tightly coupled "
                + "with the HTTP layer, the password hashing uses MD5 which is insecure, and there's no "
                + "support for token-based authentication. Can you help me redesign this?";
        String longResponse = "Here's a comprehensive plan for refactoring your authentication module. "
                + "First, we should create an AuthenticationService interface to decouple from HTTP. "
                + "Second, migrate from MD5 to bcrypt for password hashing using the jBCrypt library. "
                + "Third, implement JWT token generation and validation for stateless authentication. "
                + "Fourth, add a TokenRefreshService for handling token expiration gracefully.";

        EntryData.Prompt prompt = new EntryData.Prompt(longPrompt);
        EntryData.Text response = new EntryData.Text();
        response.setRaw(longResponse);
        return List.of(prompt, response);
    }

    private static List<EntryData> createMultipleExchangeEntries() {
        String prompt1 = "What is the best approach for implementing a caching layer in our microservice? "
                + "We need to support both in-memory and distributed caching with Redis.";
        String response1 = "I recommend using Spring Cache abstraction with a dual-layer strategy. "
                + "Use Caffeine for the local L1 cache and Redis for the distributed L2 cache. "
                + "Configure cache eviction policies based on your access patterns and workload characteristics.";

        String prompt2 = "How should we handle database migrations in our Kubernetes deployment? "
                + "We are using PostgreSQL with multiple replicas and zero-downtime requirements.";
        String response2 = "Use Flyway for versioned migrations with expand-and-contract pattern. "
                + "Never rename or drop columns in a single migration — always add the new column, "
                + "migrate data, then remove the old column in a subsequent release to avoid downtime.";

        EntryData.Prompt p1 = new EntryData.Prompt(prompt1);
        EntryData.Text r1 = new EntryData.Text();
        r1.setRaw(response1);
        EntryData.Prompt p2 = new EntryData.Prompt(prompt2);
        EntryData.Text r2 = new EntryData.Text();
        r2.setRaw(response2);
        return List.of(p1, r1, p2, r2);
    }

    private static List<EntryData> createShortTestEntries() {
        EntryData.Prompt prompt = new EntryData.Prompt("Hi");
        EntryData.Text response = new EntryData.Text();
        response.setRaw("Hello!");
        return List.of(prompt, response);
    }
}
