package com.github.catatafishen.agentbridge.memory.layers;

import com.github.catatafishen.agentbridge.memory.embedding.Embedder;
import com.github.catatafishen.agentbridge.memory.embedding.EmbeddingService;
import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.github.catatafishen.agentbridge.memory.wal.WriteAheadLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for L1 ({@link EssentialStoryLayer}), L2 ({@link OnDemandLayer}), and
 * L3 ({@link DeepSearchLayer}).
 * Uses a real {@link MemoryStore} backed by a temp Lucene index.
 * L0 (IdentityLayer) is tested in {@link IdentityLayerTest}.
 */
class MemoryLayersTest {

    private static final String WING = "test-project";
    private static int vectorCounter;

    @TempDir
    Path tempDir;

    private MemoryStore store;

    @BeforeEach
    void setUp() throws IOException {
        WriteAheadLog wal = new WriteAheadLog(tempDir.resolve("wal"));
        wal.initialize();
        store = new MemoryStore(tempDir.resolve("index"), wal);
        store.initialize();
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.dispose();
    }

    // --- EssentialStoryLayer (L1) ---

    @Test
    void essentialLayerIdAndName() {
        EssentialStoryLayer layer = new EssentialStoryLayer(store);
        assertEquals("L1-essential", layer.layerId());
        assertEquals("Essential Story", layer.displayName());
    }

    @Test
    void essentialReturnsEmptyWhenNoDrawers() {
        EssentialStoryLayer layer = new EssentialStoryLayer(store);
        String result = layer.render(WING, null);
        assertEquals("", result);
    }

    @Test
    void essentialRendersDrawers() throws IOException {
        addDrawer("d1", WING, "coding", "decision", "Decided to use Lucene for vector search");
        addDrawer("d2", WING, "coding", "technical", "Implemented embedding service with pure-Java BERT");

        EssentialStoryLayer layer = new EssentialStoryLayer(store);
        String result = layer.render(WING, null);

        assertTrue(result.startsWith("## Essential Story"));
        assertTrue(result.contains(WING));
        assertTrue(result.contains("[decision]"));
        assertTrue(result.contains("[technical]"));
        assertTrue(result.contains("Lucene"));
        assertTrue(result.contains("BERT"));
    }

    @Test
    void essentialRespectsMaxDrawers() throws IOException {
        for (int i = 0; i < 5; i++) {
            addDrawer("d" + i, WING, "coding", "technical", "Memory " + i);
        }

        EssentialStoryLayer layer = new EssentialStoryLayer(store, 2);
        String result = layer.render(WING, null);
        assertFalse(result.isEmpty());
        // Count the number of list items (- [ lines)
        long lineCount = result.lines().filter(l -> l.startsWith("- [")).count();
        assertEquals(2, lineCount);
    }

    @Test
    void essentialIgnoresOtherWings() throws IOException {
        addDrawer("d1", "other-project", "coding", "decision", "Some other project memory");

        EssentialStoryLayer layer = new EssentialStoryLayer(store);
        String result = layer.render(WING, null);
        assertEquals("", result);
    }

    @Test
    void essentialTruncatesLongContent() throws IOException {
        String longContent = "A".repeat(500);
        addDrawer("d1", WING, "coding", "technical", longContent);

        EssentialStoryLayer layer = new EssentialStoryLayer(store);
        String result = layer.render(WING, null);
        assertTrue(result.contains("…"));
        assertTrue(result.length() < 500);
    }

    // --- OnDemandLayer (L2) ---

    @Test
    void onDemandLayerIdAndName() {
        OnDemandLayer layer = new OnDemandLayer(store);
        assertEquals("L2-on-demand", layer.layerId());
        assertEquals("On-Demand Recall", layer.displayName());
    }

    @Test
    void onDemandReturnsEmptyWhenNoDrawers() {
        OnDemandLayer layer = new OnDemandLayer(store);
        String result = layer.render(WING, "coding");
        assertEquals("", result);
    }

    @Test
    void onDemandFiltersbyRoom() throws IOException {
        addDrawer("d1", WING, "coding", "decision", "Use Gradle for build system");
        addDrawer("d2", WING, "design", "preference", "Prefer dark themes in UI");

        OnDemandLayer layer = new OnDemandLayer(store);
        String resultCoding = layer.render(WING, "coding");
        assertTrue(resultCoding.contains("Gradle"));
        assertFalse(resultCoding.contains("dark themes"));
    }

    @Test
    void onDemandIncludesQueryInHeader() throws IOException {
        addDrawer("d1", WING, "testing", "technical", "Use JUnit 5 for tests");

        OnDemandLayer layer = new OnDemandLayer(store);
        String result = layer.render(WING, "testing");
        assertTrue(result.contains("## On-Demand Recall — testing"));
    }

    @Test
    void onDemandReturnsAllWhenNoRoomFilter() throws IOException {
        addDrawer("d1", WING, "coding", "decision", "Memory one");
        addDrawer("d2", WING, "design", "preference", "Memory two");

        OnDemandLayer layer = new OnDemandLayer(store);
        String result = layer.render(WING, null);
        assertTrue(result.contains("Memory one"));
        assertTrue(result.contains("Memory two"));
    }

    @Test
    void onDemandRespectsLimit() throws IOException {
        for (int i = 0; i < 5; i++) {
            addDrawer("od" + i, WING, "coding", "technical", "On demand memory " + i);
        }

        OnDemandLayer layer = new OnDemandLayer(store, 2);
        String result = layer.render(WING, null);
        assertFalse(result.isEmpty());
        long lineCount = result.lines().filter(l -> l.startsWith("- [")).count();
        assertEquals(2, lineCount);
    }

    // --- DeepSearchLayer (L3) — uses fake Embedder ---

    @Test
    void deepSearchLayerIdAndName() {
        Embedder fake = text -> uniqueVector();
        DeepSearchLayer layer = new DeepSearchLayer(store, fake);
        assertEquals("L3-deep-search", layer.layerId());
        assertEquals("Deep Search", layer.displayName());
    }

    @Test
    void deepSearchNullQueryReturnsEmpty() {
        Embedder fake = text -> uniqueVector();
        DeepSearchLayer layer = new DeepSearchLayer(store, fake);
        String result = layer.render(WING, null);
        assertEquals("", result);
    }

    @Test
    void deepSearchEmptyQueryReturnsEmpty() {
        Embedder fake = text -> uniqueVector();
        DeepSearchLayer layer = new DeepSearchLayer(store, fake);
        String result = layer.render(WING, "");
        assertEquals("", result);
    }

    @Test
    void deepSearchReturnsResultsForMatchingQuery() throws Exception {
        // Use a consistent vector for the stored drawer
        float[] storedVec = new float[EmbeddingService.EMBEDDING_DIM];
        storedVec[0] = 1.0f;

        DrawerDocument drawer = DrawerDocument.builder()
            .id("deep-1")
            .wing(WING)
            .room("coding")
            .content("Authentication using JWT tokens with refresh rotation")
            .memoryType("decision")
            .sourceSession("session-1")
            .agent("test-agent")
            .filedAt(java.time.Instant.now())
            .addedBy("test")
            .build();
        store.addDrawer(drawer, storedVec);

        // Embedder returns the same vector → high cosine similarity
        Embedder sameVector = text -> {
            float[] v = new float[EmbeddingService.EMBEDDING_DIM];
            v[0] = 1.0f;
            return v;
        };

        DeepSearchLayer layer = new DeepSearchLayer(store, sameVector);
        String result = layer.render(WING, "authentication");
        assertTrue(result.startsWith("## Deep Search Results"));
        assertTrue(result.contains("authentication"));
        assertTrue(result.contains("JWT"));
        assertTrue(result.contains("[decision]"));
    }

    @Test
    void deepSearchNoResultsReturnsEmpty() {
        // Empty store → no results
        Embedder fake = text -> uniqueVector();
        DeepSearchLayer layer = new DeepSearchLayer(store, fake);
        String result = layer.render(WING, "anything");
        assertEquals("", result);
    }

    @Test
    void deepSearchEmbedderFailureReturnsEmpty() {
        Embedder failing = text -> {
            throw new RuntimeException("Embedding engine crashed");
        };
        DeepSearchLayer layer = new DeepSearchLayer(store, failing);
        String result = layer.render(WING, "some query");
        assertEquals("", result);
    }

    @Test
    void deepSearchScoreFormatting() throws Exception {
        float[] storedVec = new float[EmbeddingService.EMBEDDING_DIM];
        storedVec[1] = 1.0f;

        DrawerDocument drawer = DrawerDocument.builder()
            .id("deep-score")
            .wing(WING)
            .room("testing")
            .content("Unit testing with JUnit 5 and assertions")
            .memoryType("technical")
            .sourceSession("session-1")
            .agent("test-agent")
            .filedAt(java.time.Instant.now())
            .addedBy("test")
            .build();
        store.addDrawer(drawer, storedVec);

        Embedder sameVector = text -> {
            float[] v = new float[EmbeddingService.EMBEDDING_DIM];
            v[1] = 1.0f;
            return v;
        };

        DeepSearchLayer layer = new DeepSearchLayer(store, sameVector);
        String result = layer.render(WING, "junit testing");
        // Score should be formatted with 2 decimal places
        assertTrue(result.matches("(?s).*\\[\\d\\.\\d{2}].*"));
    }

    @Test
    void deepSearchTruncatesLongContent() throws Exception {
        String longContent = "A".repeat(500);
        float[] storedVec = new float[EmbeddingService.EMBEDDING_DIM];
        storedVec[2] = 1.0f;

        DrawerDocument drawer = DrawerDocument.builder()
            .id("deep-trunc")
            .wing(WING)
            .room("coding")
            .content(longContent)
            .memoryType("technical")
            .sourceSession("session-1")
            .agent("test-agent")
            .filedAt(java.time.Instant.now())
            .addedBy("test")
            .build();
        store.addDrawer(drawer, storedVec);

        Embedder sameVector = text -> {
            float[] v = new float[EmbeddingService.EMBEDDING_DIM];
            v[2] = 1.0f;
            return v;
        };

        DeepSearchLayer layer = new DeepSearchLayer(store, sameVector);
        String result = layer.render(WING, "long content");
        assertTrue(result.contains("…"));
    }

    @Test
    void deepSearchRespectsLimit() throws Exception {
        // Add 5 drawers, use limit 2
        for (int i = 0; i < 5; i++) {
            float[] vec = new float[EmbeddingService.EMBEDDING_DIM];
            vec[3 + i] = 1.0f;
            DrawerDocument drawer = DrawerDocument.builder()
                .id("deep-lim-" + i)
                .wing(WING)
                .room("coding")
                .content("Memory item number " + i + " with sufficient content")
                .memoryType("technical")
                .sourceSession("session-1")
                .agent("test-agent")
                .filedAt(java.time.Instant.now())
                .addedBy("test")
                .build();
            store.addDrawer(drawer, vec);
        }

        // Query vector that has some similarity to all stored vectors
        Embedder queryEmb = text -> {
            float[] v = new float[EmbeddingService.EMBEDDING_DIM];
            for (int i = 3; i < 8; i++) v[i] = 0.5f;
            // Normalize
            float norm = 0;
            for (float f : v) norm += f * f;
            norm = (float) Math.sqrt(norm);
            for (int i = 0; i < v.length; i++) v[i] /= norm;
            return v;
        };

        DeepSearchLayer layer = new DeepSearchLayer(store, queryEmb, 2);
        String result = layer.render(WING, "memory items");
        assertFalse(result.isEmpty());
        long lineCount = result.lines().filter(l -> l.startsWith("- [")).count();
        assertEquals(2, lineCount);
    }

    // --- OnDemandLayer edge cases ---

    @Test
    void onDemandEmptyStringQueryRendersGenericHeader() throws IOException {
        addDrawer("od-empty-q", WING, "coding", "decision", "Some important decision about the codebase");

        OnDemandLayer layer = new OnDemandLayer(store);
        String result = layer.render(WING, "");
        assertTrue(result.startsWith("## On-Demand Recall\n"));
        assertFalse(result.contains(" — "));
    }

    @Test
    void onDemandTruncatesLongContent() throws IOException {
        String longContent = "B".repeat(500);
        addDrawer("od-trunc", WING, "coding", "technical", longContent);

        OnDemandLayer layer = new OnDemandLayer(store);
        String result = layer.render(WING, null);
        assertTrue(result.contains("…"));
    }

    // --- MemoryStack interface contract ---

    @Test
    void allLayersImplementMemoryStack() {
        EssentialStoryLayer l1 = new EssentialStoryLayer(store);
        OnDemandLayer l2 = new OnDemandLayer(store);
        Embedder fake = text -> uniqueVector();
        DeepSearchLayer l3 = new DeepSearchLayer(store, fake);
        // Verify interface contract
        assertInstanceOf(MemoryStack.class, l1);
        assertInstanceOf(MemoryStack.class, l2);
        assertInstanceOf(MemoryStack.class, l3);
    }

    private void addDrawer(String id, String wing, String room, String memoryType, String content) throws IOException {
        DrawerDocument drawer = DrawerDocument.builder()
            .id(id)
            .wing(wing)
            .room(room)
            .content(content)
            .memoryType(memoryType)
            .sourceSession("test-session")
            .agent("test-agent")
            .filedAt(Instant.now())
            .addedBy("test")
            .build();
        store.addDrawer(drawer, uniqueVector());
    }

    private static float[] uniqueVector() {
        float[] v = new float[384];
        v[vectorCounter % 384] = 1.0f;
        vectorCounter++;
        return v;
    }
}
