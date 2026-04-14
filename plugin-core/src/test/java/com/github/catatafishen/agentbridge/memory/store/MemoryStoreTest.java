package com.github.catatafishen.agentbridge.memory.store;

import com.github.catatafishen.agentbridge.memory.wal.WriteAheadLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MemoryStore} using a temporary Lucene index.
 * Tests basic CRUD, duplicate detection, taxonomy, and search with metadata filters.
 * Note: KNN vector search accuracy tests are omitted since they require real embeddings.
 */
class MemoryStoreTest {

    @TempDir
    Path tempDir;

    private MemoryStore store;
    private WriteAheadLog wal;

    @BeforeEach
    void setUp() throws IOException {
        wal = new WriteAheadLog(tempDir.resolve("wal"));
        wal.initialize();
        store = new MemoryStore(tempDir.resolve("lucene-index"), wal);
        store.initialize();
    }

    @AfterEach
    void tearDown() {
        store.dispose();
    }

    @Test
    void addDrawerAndRetrieveCount() throws IOException {
        float[] embedding = randomEmbedding();
        DrawerDocument doc = DrawerDocument.builder()
            .id("test-drawer-1")
            .wing("project-x")
            .room("technical")
            .content("We use Java 21 for this project")
            .memoryType(DrawerDocument.TYPE_CONTEXT)
            .build();

        String id = store.addDrawer(doc, embedding);
        assertEquals("test-drawer-1", id);
        assertEquals(1, store.getDrawerCount());
    }

    @Test
    void addMultipleDrawers() throws IOException {
        for (int i = 0; i < 5; i++) {
            // Use different embeddings so no duplicates
            float[] embedding = randomEmbedding();
            DrawerDocument doc = DrawerDocument.builder()
                .id("drawer-" + i)
                .wing("proj")
                .room("room-" + (i % 3))
                .content("Content for drawer number " + i + " with unique text " + System.nanoTime())
                .build();
            store.addDrawer(doc, embedding);
        }
        assertEquals(5, store.getDrawerCount());
    }

    @Test
    void duplicateDetectionSkipsIdenticalEmbedding() throws IOException {
        float[] embedding = new float[384];
        for (int i = 0; i < 384; i++) embedding[i] = 0.5f;

        DrawerDocument doc1 = DrawerDocument.builder()
            .id("dup-1")
            .wing("proj")
            .content("First document")
            .build();
        store.addDrawer(doc1, embedding);

        // Same embedding should be detected as duplicate
        DrawerDocument doc2 = DrawerDocument.builder()
            .id("dup-2")
            .wing("proj")
            .content("Second document")
            .build();
        String result = store.addDrawer(doc2, embedding);
        assertNull(result, "Expected duplicate to be skipped");
        assertEquals(1, store.getDrawerCount());
    }

    @Test
    void isDuplicateReturnsFalseForEmptyIndex() throws IOException {
        float[] embedding = randomEmbedding();
        assertFalse(store.isDuplicate(embedding));
    }

    @Test
    void getTaxonomyReturnsCorrectCounts() throws IOException {
        addTestDrawer("d1", "project-a", "technical", randomEmbedding());
        addTestDrawer("d2", "project-a", "technical", randomEmbedding());
        addTestDrawer("d3", "project-a", "architecture", randomEmbedding());
        addTestDrawer("d4", "project-b", "planning", randomEmbedding());

        Map<String, Map<String, Integer>> taxonomy = store.getTaxonomy();
        assertEquals(2, taxonomy.size());
        assertEquals(2, taxonomy.get("project-a").get("technical"));
        assertEquals(1, taxonomy.get("project-a").get("architecture"));
        assertEquals(1, taxonomy.get("project-b").get("planning"));
    }

    @Test
    void getTopDrawersFiltersByWing() throws IOException {
        addTestDrawer("d1", "project-a", "tech", randomEmbedding());
        addTestDrawer("d2", "project-b", "tech", randomEmbedding());
        addTestDrawer("d3", "project-a", "arch", randomEmbedding());

        List<DrawerDocument> topA = store.getTopDrawers("project-a", 10);
        assertEquals(2, topA.size());
        assertTrue(topA.stream().allMatch(d -> "project-a".equals(d.wing())));
    }

    @Test
    void getTopDrawersRespectsLimit() throws IOException {
        for (int i = 0; i < 10; i++) {
            addTestDrawer("d-" + i, "proj", "room", randomEmbedding());
        }

        List<DrawerDocument> top = store.getTopDrawers("proj", 3);
        assertEquals(3, top.size());
    }

    @Test
    void getTopDrawersSortedByRecency() throws IOException {
        DrawerDocument old = DrawerDocument.builder()
            .id("old").wing("proj").room("r").content("old " + System.nanoTime())
            .filedAt(Instant.parse("2024-01-01T00:00:00Z"))
            .build();
        store.addDrawer(old, randomEmbedding());

        DrawerDocument recent = DrawerDocument.builder()
            .id("recent").wing("proj").room("r").content("recent " + System.nanoTime())
            .filedAt(Instant.parse("2024-12-31T23:59:59Z"))
            .build();
        store.addDrawer(recent, randomEmbedding());

        List<DrawerDocument> top = store.getTopDrawers("proj", 10);
        assertEquals("recent", top.get(0).id());
        assertEquals("old", top.get(1).id());
    }

    @Test
    void searchWithMetadataFilter() throws IOException {
        float[] e1 = randomEmbedding();
        addTestDrawer("d1", "proj", "technical", e1);
        addTestDrawer("d2", "proj", "architecture", randomEmbedding());

        MemoryQuery q = MemoryQuery.filter().wing("proj").room("technical").build();
        List<DrawerDocument.SearchResult> results = store.search(q, null);
        assertEquals(1, results.size());
        assertEquals("d1", results.get(0).drawer().id());
    }

    @Test
    void searchWithNoFilterReturnsAll() throws IOException {
        addTestDrawer("d1", "proj", "a", randomEmbedding());
        addTestDrawer("d2", "proj", "b", randomEmbedding());

        MemoryQuery q = MemoryQuery.filter().limit(10).build();
        List<DrawerDocument.SearchResult> results = store.search(q, null);
        assertEquals(2, results.size());
    }

    @Test
    void generateDrawerIdIsDeterministic() {
        String id1 = MemoryStore.generateDrawerId("wing", "room", "content");
        String id2 = MemoryStore.generateDrawerId("wing", "room", "content");
        assertEquals(id1, id2);
    }

    @Test
    void generateDrawerIdDiffersForDifferentInput() {
        String id1 = MemoryStore.generateDrawerId("wing1", "room", "content");
        String id2 = MemoryStore.generateDrawerId("wing2", "room", "content");
        assertNotEquals(id1, id2);
    }

    @Test
    void generateDrawerIdHasCorrectPrefix() {
        String id = MemoryStore.generateDrawerId("my-project", "technical", "some content");
        assertTrue(id.startsWith("drawer_my-project_technical_"));
    }

    @Test
    void emptyStoreReturnsZeroCount() throws IOException {
        assertEquals(0, store.getDrawerCount());
    }

    @Test
    void emptyTaxonomy() throws IOException {
        Map<String, Map<String, Integer>> taxonomy = store.getTaxonomy();
        assertTrue(taxonomy.isEmpty());
    }

    @Test
    void traceabilityFieldsAreStoredAndRetrieved() throws IOException {
        DrawerDocument doc = DrawerDocument.builder()
            .id("trace-1")
            .wing("proj")
            .room("codebase")
            .content("Refactored auth module with unique content " + System.nanoTime())
            .sourceTurnIndex("3")
            .sourceCommits("abc1234,def5678")
            .build();
        store.addDrawer(doc, randomEmbedding());

        MemoryQuery q = MemoryQuery.filter().wing("proj").room("codebase").build();
        List<DrawerDocument.SearchResult> results = store.search(q, null);
        assertEquals(1, results.size());
        DrawerDocument retrieved = results.get(0).drawer();
        assertEquals("3", retrieved.sourceTurnIndex());
        assertEquals("abc1234,def5678", retrieved.sourceCommits());
    }

    @Test
    void traceabilityFieldsDefaultToEmptyString() throws IOException {
        DrawerDocument doc = DrawerDocument.builder()
            .id("no-trace-1")
            .wing("proj")
            .room("general")
            .content("No traceability info with unique content " + System.nanoTime())
            .build();
        store.addDrawer(doc, randomEmbedding());

        MemoryQuery q = MemoryQuery.filter().wing("proj").room("general").build();
        List<DrawerDocument.SearchResult> results = store.search(q, null);
        assertEquals(1, results.size());
        DrawerDocument retrieved = results.get(0).drawer();
        assertEquals("", retrieved.sourceTurnIndex());
        assertEquals("", retrieved.sourceCommits());
    }

    private void addTestDrawer(String id, String wing, String room, float[] embedding) throws IOException {
        DrawerDocument doc = DrawerDocument.builder()
            .id(id)
            .wing(wing)
            .room(room)
            .content("Content for " + id + " unique-" + System.nanoTime())
            .build();
        store.addDrawer(doc, embedding);
    }

    // ── findByEvidence ────────────────────────────────────────────────────

    @Test
    void findByEvidence_returnsDrawerWhoseEvidenceContainsRef() throws IOException {
        DrawerDocument doc = DrawerDocument.builder()
            .id("ev-1")
            .wing("proj")
            .room("codebase")
            .content("Uses the auth service with unique token " + System.nanoTime())
            .evidence("[\"com.example.AuthService\",\"AuthService.java:42\"]")
            .build();
        store.addDrawer(doc, randomEmbedding());

        List<DrawerDocument> found = store.findByEvidence("com.example.AuthService");
        assertEquals(1, found.size());
        assertEquals("ev-1", found.get(0).id());
    }

    @Test
    void findByEvidence_partialSubstringMatch_returnsDrawer() throws IOException {
        DrawerDocument doc = DrawerDocument.builder()
            .id("ev-2")
            .wing("proj")
            .room("codebase")
            .content("Some content " + System.nanoTime())
            .evidence("[\"src/main/java/UserService.java\"]")
            .build();
        store.addDrawer(doc, randomEmbedding());

        // Partial substring "UserService.java" should match
        List<DrawerDocument> found = store.findByEvidence("UserService.java");
        assertEquals(1, found.size());
        assertEquals("ev-2", found.get(0).id());
    }

    @Test
    void findByEvidence_noMatchingDrawers_returnsEmpty() throws IOException {
        DrawerDocument doc = DrawerDocument.builder()
            .id("ev-3")
            .wing("proj")
            .room("codebase")
            .content("Some content " + System.nanoTime())
            .evidence("[\"com.example.OtherService\"]")
            .build();
        store.addDrawer(doc, randomEmbedding());

        List<DrawerDocument> found = store.findByEvidence("com.example.MissingClass");
        assertTrue(found.isEmpty());
    }

    @Test
    void findByEvidence_emptyStore_returnsEmpty() throws IOException {
        List<DrawerDocument> found = store.findByEvidence("anything");
        assertTrue(found.isEmpty());
    }

    @Test
    void findByEvidence_evidenceStoredAndRetrieved() throws IOException {
        String evidenceJson = "[\"com.example.Foo\",\"Bar.java:10\"]";
        DrawerDocument doc = DrawerDocument.builder()
            .id("ev-4")
            .wing("proj")
            .room("codebase")
            .content("Evidence retrieval test " + System.nanoTime())
            .evidence(evidenceJson)
            .verificationState(DrawerDocument.STATE_VERIFIED)
            .build();
        store.addDrawer(doc, randomEmbedding());

        List<DrawerDocument> found = store.findByEvidence("com.example.Foo");
        assertEquals(1, found.size());
        assertEquals(evidenceJson, found.get(0).evidence());
        assertEquals(DrawerDocument.STATE_VERIFIED, found.get(0).verificationState());
    }

    // ── updateEvidenceRef ─────────────────────────────────────────────────

    @Test
    void updateEvidenceRef_updatesMatchingDrawers() throws IOException {
        DrawerDocument doc = DrawerDocument.builder()
            .id("upd-1")
            .wing("proj")
            .room("codebase")
            .content("Refactor test " + System.nanoTime())
            .evidence("[\"com.example.OldService\"]")
            .build();
        store.addDrawer(doc, randomEmbedding());

        int updated = store.updateEvidenceRef("com.example.OldService", "com.example.NewService");
        assertEquals(1, updated);

        // Verify the evidence was updated
        List<DrawerDocument> found = store.findByEvidence("com.example.NewService");
        assertEquals(1, found.size());
        assertTrue(found.get(0).evidence().contains("com.example.NewService"));
        assertFalse(found.get(0).evidence().contains("com.example.OldService"));
    }

    @Test
    void updateEvidenceRef_noMatch_returnsZero() throws IOException {
        DrawerDocument doc = DrawerDocument.builder()
            .id("upd-2")
            .wing("proj")
            .room("codebase")
            .content("No match test " + System.nanoTime())
            .evidence("[\"com.example.SomeService\"]")
            .build();
        store.addDrawer(doc, randomEmbedding());

        int updated = store.updateEvidenceRef("com.example.MissingClass", "com.example.NewClass");
        assertEquals(0, updated);
    }

    @Test
    void updateEvidenceRef_marksUpdatedDrawerAsStale() throws IOException {
        DrawerDocument doc = DrawerDocument.builder()
            .id("upd-3")
            .wing("proj")
            .room("codebase")
            .content("Stale test " + System.nanoTime())
            .evidence("[\"com.example.Original\"]")
            .verificationState(DrawerDocument.STATE_VERIFIED)
            .build();
        store.addDrawer(doc, randomEmbedding());

        store.updateEvidenceRef("com.example.Original", "com.example.Renamed");

        List<DrawerDocument> found = store.findByEvidence("com.example.Renamed");
        assertEquals(1, found.size());
        assertEquals(DrawerDocument.STATE_STALE, found.get(0).verificationState());
    }

    @Test
    void updateEvidenceRef_emptyStore_returnsZero() throws IOException {
        int updated = store.updateEvidenceRef("com.example.Foo", "com.example.Bar");
        assertEquals(0, updated);
    }

    // ── updateVerificationState ───────────────────────────────────────────

    @Test
    void updateVerificationState_changesStateToVerified() throws IOException {
        DrawerDocument doc = DrawerDocument.builder()
            .id("vs-1")
            .wing("proj")
            .room("codebase")
            .content("Verification state test " + System.nanoTime())
            .evidence("[\"com.example.Foo\"]")
            .verificationState(DrawerDocument.STATE_UNVERIFIED)
            .build();
        store.addDrawer(doc, randomEmbedding());

        store.updateVerificationState("vs-1", DrawerDocument.STATE_VERIFIED);

        // Retrieve and verify
        MemoryQuery q = MemoryQuery.filter().wing("proj").room("codebase").build();
        List<DrawerDocument.SearchResult> results = store.search(q, null);
        DrawerDocument retrieved = results.stream()
            .filter(r -> "vs-1".equals(r.drawer().id()))
            .findFirst()
            .orElseThrow()
            .drawer();
        assertEquals(DrawerDocument.STATE_VERIFIED, retrieved.verificationState());
    }

    @Test
    void updateVerificationState_changesStateToStale() throws IOException {
        DrawerDocument doc = DrawerDocument.builder()
            .id("vs-2")
            .wing("proj")
            .room("tech")
            .content("Stale state test " + System.nanoTime())
            .evidence("[\"com.example.Bar\"]")
            .verificationState(DrawerDocument.STATE_VERIFIED)
            .build();
        store.addDrawer(doc, randomEmbedding());

        store.updateVerificationState("vs-2", DrawerDocument.STATE_STALE);

        MemoryQuery q = MemoryQuery.filter().wing("proj").room("tech").build();
        List<DrawerDocument.SearchResult> results = store.search(q, null);
        DrawerDocument retrieved = results.stream()
            .filter(r -> "vs-2".equals(r.drawer().id()))
            .findFirst()
            .orElseThrow()
            .drawer();
        assertEquals(DrawerDocument.STATE_STALE, retrieved.verificationState());
    }

    @Test
    void updateVerificationState_setsLastVerifiedAt() throws IOException {
        DrawerDocument doc = DrawerDocument.builder()
            .id("vs-3")
            .wing("proj")
            .room("general")
            .content("LastVerifiedAt test " + System.nanoTime())
            .evidence("[\"com.example.Baz\"]")
            .build();
        store.addDrawer(doc, randomEmbedding());

        assertNull(doc.lastVerifiedAt()); // not set before update

        store.updateVerificationState("vs-3", DrawerDocument.STATE_VERIFIED);

        List<DrawerDocument> found = store.findByEvidence("com.example.Baz");
        assertEquals(1, found.size());
        assertNotNull(found.get(0).lastVerifiedAt());
    }

    /**
     * Generate a random 384-dim embedding for testing purposes.
     * Each call produces a unique vector to avoid duplicate detection.
     */
    private static float[] randomEmbedding() {
        float[] embedding = new float[384];
        for (int i = 0; i < 384; i++) {
            embedding[i] = (float) (Math.random() * 2 - 1);
        }
        // L2 normalize
        float norm = 0;
        for (float v : embedding) norm += v * v;
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < 384; i++) embedding[i] /= norm;
        return embedding;
    }
}
