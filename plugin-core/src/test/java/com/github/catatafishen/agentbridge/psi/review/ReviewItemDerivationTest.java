package com.github.catatafishen.agentbridge.psi.review;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the pure derivation logic for building {@link ReviewItem} lists from
 * session state maps (snapshots, newFiles, deletedFiles). Exercises the same
 * logic as {@link AgentEditSession#getReviewItems()} without needing IntelliJ
 * project infrastructure.
 */
class ReviewItemDerivationTest {

    private static final String BASE = "/project";

    /**
     * Exercises the shared derivation logic from AgentEditSession.getReviewItems().
     */
    private static List<ReviewItem> deriveItems(
        Map<String, String> snapshots,
        Set<String> newFiles,
        Map<String, String> deletedFiles
    ) {
        return deriveItems(snapshots, newFiles, deletedFiles, Map.of(), Map.of());
    }

    private static List<ReviewItem> deriveItems(
        Map<String, String> snapshots,
        Set<String> newFiles,
        Map<String, String> deletedFiles,
        Map<String, Integer> linesAdded,
        Map<String, Integer> linesRemoved
    ) {
        return AgentEditSession.buildReviewItems(
            snapshots,
            newFiles,
            deletedFiles,
            new HashMap<>(),
            new HashMap<>(),
            new HashMap<>(linesAdded),
            new HashMap<>(linesRemoved),
            BASE
        );
    }

    @Test
    void emptySession_noItems() {
        List<ReviewItem> items = deriveItems(Map.of(), Set.of(), Map.of());
        assertTrue(items.isEmpty());
    }

    @Test
    void modifiedFile_hasSnapshotAsBeforeContent() {
        Map<String, String> snapshots = new ConcurrentHashMap<>();
        snapshots.put(BASE + "/src/Foo.java", "original");

        List<ReviewItem> items = deriveItems(snapshots, Set.of(), Map.of());
        assertEquals(1, items.size());
        ReviewItem item = items.getFirst();
        assertEquals(ReviewItem.Status.MODIFIED, item.status());
        assertEquals("src/Foo.java", item.relativePath());
        assertEquals("original", item.beforeContent());
    }

    @Test
    void addedFile_hasNullBeforeContent() {
        Set<String> newFiles = ConcurrentHashMap.newKeySet();
        newFiles.add(BASE + "/src/NewFile.java");

        List<ReviewItem> items = deriveItems(Map.of(), newFiles, Map.of());
        assertEquals(1, items.size());
        ReviewItem item = items.getFirst();
        assertEquals(ReviewItem.Status.ADDED, item.status());
        assertNull(item.beforeContent());
    }

    @Test
    void deletedFile_hasOriginalContent() {
        Map<String, String> deletedFiles = new ConcurrentHashMap<>();
        deletedFiles.put(BASE + "/src/Gone.java", "was here");

        List<ReviewItem> items = deriveItems(Map.of(), Set.of(), deletedFiles);
        assertEquals(1, items.size());
        ReviewItem item = items.getFirst();
        assertEquals(ReviewItem.Status.DELETED, item.status());
        assertEquals("was here", item.beforeContent());
    }

    @Test
    void modifiedThenDeleted_appearsAsDeletedWithSnapshotContent() {
        Map<String, String> snapshots = new ConcurrentHashMap<>();
        snapshots.put(BASE + "/src/Both.java", "snapshot content");
        Map<String, String> deletedFiles = new ConcurrentHashMap<>();
        deletedFiles.put(BASE + "/src/Both.java", "deletion-time content");

        List<ReviewItem> items = deriveItems(snapshots, Set.of(), deletedFiles);
        assertEquals(1, items.size(), "File in both snapshots and deletedFiles should appear once");
        ReviewItem item = items.getFirst();
        assertEquals(ReviewItem.Status.DELETED, item.status());
        assertEquals("snapshot content", item.beforeContent(),
            "Should prefer snapshot over deletion-time content");
    }

    @Test
    void mixedChanges_sortedByRelativePath() {
        Map<String, String> snapshots = new ConcurrentHashMap<>();
        snapshots.put(BASE + "/src/Z.java", "z-original");
        Set<String> newFiles = ConcurrentHashMap.newKeySet();
        newFiles.add(BASE + "/src/A.java");
        Map<String, String> deletedFiles = new ConcurrentHashMap<>();
        deletedFiles.put(BASE + "/src/M.java", "m-content");

        List<ReviewItem> items = deriveItems(snapshots, newFiles, deletedFiles);
        assertEquals(3, items.size());
        assertEquals("src/A.java", items.get(0).relativePath());
        assertEquals("src/M.java", items.get(1).relativePath());
        assertEquals("src/Z.java", items.get(2).relativePath());
    }

    @Test
    void pathOutsideProject_usesFileName() {
        Map<String, String> snapshots = new ConcurrentHashMap<>();
        snapshots.put("/other/path/File.java", "content");

        List<ReviewItem> items = deriveItems(snapshots, Set.of(), Map.of());
        assertEquals(1, items.size());
        assertEquals("File.java", items.getFirst().relativePath());
    }

    @Test
    void addedThenEdited_appearsOnceAsAdded() {
        String path = BASE + "/src/NewFile.java";
        Map<String, String> snapshots = new ConcurrentHashMap<>();
        snapshots.put(path, "stale snapshot");
        Set<String> newFiles = ConcurrentHashMap.newKeySet();
        newFiles.add(path);

        List<ReviewItem> items = deriveItems(
            snapshots,
            newFiles,
            Map.of(),
            Map.of(path, 7),
            Map.of(path, 0)
        );

        assertEquals(1, items.size(), "New file with a stale snapshot should still render once");
        ReviewItem item = items.getFirst();
        assertEquals(ReviewItem.Status.ADDED, item.status());
        assertNull(item.beforeContent());
        assertEquals(7, item.linesAdded());
        assertEquals(0, item.linesRemoved());
    }

    @Test
    void createdThenDeleted_sameSession_disappears() {
        String path = BASE + "/src/Transient.java";
        Set<String> newFiles = ConcurrentHashMap.newKeySet();
        newFiles.add(path);
        Map<String, String> deletedFiles = new ConcurrentHashMap<>();
        deletedFiles.put(path, "temporary");

        List<ReviewItem> items = deriveItems(Map.of(), newFiles, deletedFiles);

        assertTrue(items.isEmpty(), "Create-then-delete in one session should leave no review row");
    }

    @Test
    void deletedFile_keepsRemovedLineCount() {
        String path = BASE + "/src/Gone.java";
        Map<String, String> deletedFiles = new ConcurrentHashMap<>();
        deletedFiles.put(path, "a\nb\nc\n");

        List<ReviewItem> items = deriveItems(
            Map.of(),
            Set.of(),
            deletedFiles,
            Map.of(path, 0),
            Map.of(path, 3)
        );

        assertEquals(1, items.size());
        assertEquals(ReviewItem.Status.DELETED, items.getFirst().status());
        assertEquals(3, items.getFirst().linesRemoved());
    }
}
