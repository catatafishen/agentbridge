package com.github.catatafishen.agentbridge.memory.mining;

import com.github.catatafishen.agentbridge.memory.embedding.Embedder;
import com.github.catatafishen.agentbridge.memory.embedding.EmbeddingService;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.github.catatafishen.agentbridge.memory.wal.WriteAheadLog;
import com.github.catatafishen.agentbridge.ui.EntryData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for {@link TurnMiner} mining pipeline.
 * Uses a real {@link MemoryStore} backed by a temp Lucene index and a fake {@link Embedder}.
 */
class TurnMinerTest {

    private static final String WING = "test-project";
    private static final String SESSION_ID = "session-001";
    private static final String AGENT = "test-agent";
    private static int vectorCounter;

    @TempDir
    Path tempDir;

    private MemoryStore store;
    private QualityFilter filter;

    /**
     * Deterministic fake embedder that produces unique unit vectors.
     */
    private final Embedder fakeEmbedder = text -> uniqueVector();

    @BeforeEach
    void setUp() throws IOException {
        vectorCounter = 0;
        WriteAheadLog wal = new WriteAheadLog(tempDir.resolve("wal"));
        wal.initialize();
        store = new MemoryStore(tempDir.resolve("index"), wal);
        store.initialize();
        filter = new QualityFilter(50);
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.dispose();
    }

    private TurnMiner.PipelineContext pipelineContext(int maxDrawers) {
        return pipelineContext(maxDrawers, fakeEmbedder);
    }

    private TurnMiner.PipelineContext pipelineContext(int maxDrawers, Embedder embedder) {
        return new TurnMiner.PipelineContext(store, embedder, filter, maxDrawers, WING, null, null);
    }

    // --- MineResult record ---

    @Test
    void mineResultFields() {
        TurnMiner.MineResult r = new TurnMiner.MineResult(3, 2, 1, 6);
        assertEquals(3, r.stored());
        assertEquals(2, r.filtered());
        assertEquals(1, r.duplicates());
        assertEquals(6, r.total());
    }

    @Test
    void mineResultEmpty() {
        assertEquals(0, TurnMiner.MineResult.EMPTY.stored());
        assertEquals(0, TurnMiner.MineResult.EMPTY.filtered());
        assertEquals(0, TurnMiner.MineResult.EMPTY.duplicates());
        assertEquals(0, TurnMiner.MineResult.EMPTY.total());
    }

    @Test
    void mineResultEquality() {
        TurnMiner.MineResult a = new TurnMiner.MineResult(1, 2, 3, 4);
        TurnMiner.MineResult b = new TurnMiner.MineResult(1, 2, 3, 4);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // --- Pipeline tests ---

    @Test
    void emptyEntriesReturnsEmpty() {
        TurnMiner miner = new TurnMiner();
        TurnMiner.MineResult result = miner.executePipeline(
            Collections.emptyList(), SESSION_ID, AGENT, pipelineContext(10));
        assertEquals(TurnMiner.MineResult.EMPTY, result);
    }

    @Test
    void singleQualityExchangeIsStored() {
        List<EntryData> entries = List.of(
            prompt("How should we structure the authentication module for maximum security?"),
            response("I recommend using JWT tokens with refresh token rotation. " +
                "The auth module should have a service layer for token validation and middleware " +
                "for request interception. This keeps concerns separated.")
        );

        TurnMiner miner = new TurnMiner();
        TurnMiner.MineResult result = miner.executePipeline(
            entries, SESSION_ID, AGENT, pipelineContext(10));

        assertEquals(1, result.stored());
        assertEquals(0, result.filtered());
        assertEquals(0, result.duplicates());
        assertEquals(1, result.total());
    }

    @Test
    void shortExchangeIsFiltered() {
        List<EntryData> entries = List.of(
            prompt("ok"),
            response("Done")
        );

        TurnMiner miner = new TurnMiner();
        TurnMiner.MineResult result = miner.executePipeline(
            entries, SESSION_ID, AGENT, pipelineContext(10));

        assertEquals(0, result.stored());
        assertEquals(1, result.filtered());
        assertEquals(1, result.total());
    }

    @Test
    void maxDrawersLimitRespected() {
        List<EntryData> entries = List.of(
            prompt("How should we handle authentication in the microservices architecture?"),
            response("Use OAuth 2.0 with a centralized auth server. " +
                "Each service validates tokens independently via JWT verification."),
            prompt("What about the database design for the user management service?"),
            response("Use PostgreSQL with proper indexing on email and username columns. " +
                "Implement soft deletes for GDPR compliance and audit trail."),
            prompt("How do we handle rate limiting across the API gateway?"),
            response("Implement a sliding window counter using Redis. " +
                "Set different limits per endpoint and authenticate users via API keys.")
        );

        TurnMiner miner = new TurnMiner();
        TurnMiner.MineResult result = miner.executePipeline(
            entries, SESSION_ID, AGENT, pipelineContext(1));

        assertEquals(1, result.stored());
        assertEquals(3, result.total());
    }

    @Test
    void pipelineReportsTotalExchangeCount() {
        // 3 prompt-response pairs = 3 exchanges
        List<EntryData> entries = List.of(
            prompt("How should we handle authentication?"),
            response("Use OAuth 2.0 with JWT tokens for stateless auth across services."),
            prompt("What about the database design for user management?"),
            response("Use PostgreSQL with indexing on email. Implement soft deletes for GDPR."),
            prompt("How do we implement rate limiting?"),
            response("Use a sliding window counter with Redis. Set per-endpoint limits.")
        );

        TurnMiner miner = new TurnMiner();
        TurnMiner.MineResult result = miner.executePipeline(
            entries, SESSION_ID, AGENT, pipelineContext(10));

        assertEquals(3, result.total());
        // All should be stored (no duplicates with unique vectors)
        assertEquals(3, result.stored());
        assertEquals(0, result.filtered());
    }

    @Test
    void embedderExceptionHandledGracefully() {
        Embedder failingEmbedder = text -> {
            throw new RuntimeException("Embedding engine crash");
        };
        List<EntryData> entries = List.of(
            prompt("How should we structure the caching layer for our application?"),
            response("Use a multi-level cache: L1 in-memory (Caffeine), " +
                "L2 distributed (Redis). Set appropriate TTLs per entity type.")
        );

        TurnMiner miner = new TurnMiner();
        TurnMiner.MineResult result = miner.executePipeline(
            entries, SESSION_ID, AGENT, pipelineContext(10, failingEmbedder));

        assertEquals(0, result.stored());
        assertEquals(0, result.filtered());
        assertEquals(0, result.duplicates());
        assertEquals(1, result.total());
    }

    @Test
    void multipleExchangesMixedResults() {
        List<EntryData> entries = List.of(
            prompt("ok"),
            response("Got it"),
            prompt("How should we implement the logging framework for distributed tracing?"),
            response("Use structured logging with correlation IDs. " +
                "Each request gets a unique trace ID propagated through all services. " +
                "Use ELK stack for centralized log aggregation.")
        );

        TurnMiner miner = new TurnMiner();
        TurnMiner.MineResult result = miner.executePipeline(
            entries, SESSION_ID, AGENT, pipelineContext(10));

        assertEquals(1, result.stored());
        assertEquals(1, result.filtered());
        assertEquals(2, result.total());
    }

    @Test
    void mineResultToStringContainsValues() {
        TurnMiner.MineResult result = new TurnMiner.MineResult(5, 3, 2, 10);
        String str = result.toString();
        assertNotNull(str);
    }

    private static EntryData prompt(String text) {
        return new EntryData.Prompt(text);
    }

    private static EntryData response(String text) {
        EntryData.Text t = new EntryData.Text();
        t.setRaw(text);
        return t;
    }

    private static float[] uniqueVector() {
        float[] v = new float[EmbeddingService.EMBEDDING_DIM];
        v[vectorCounter % EmbeddingService.EMBEDDING_DIM] = 1.0f;
        vectorCounter++;
        return v;
    }
}
