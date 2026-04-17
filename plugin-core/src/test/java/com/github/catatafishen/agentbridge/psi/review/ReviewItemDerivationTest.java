package com.github.catatafishen.agentbridge.psi.review;

import org.junit.jupiter.api.Test;

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
     * Mirrors the derivation logic from AgentEditSession.getReviewItems().
     */
    private static List<ReviewItem> deriveItems(
        Map<String, String> snapshots,
        Set<String> newFiles,
        Map<String, String> deletedFiles
    ) {
        var items = new java.util.ArrayList<ReviewItem>();

        for (var entry : snapshots.entrySet()) {
            String path = entry.getKey();
            if (deletedFiles.containsKey(path)) continue;
            items.add(new ReviewItem(path, relativize(path),
                ReviewItem.Status.MODIFIED, entry.getValue()));
        }
        for (String path : newFiles) {
            items.add(new ReviewItem(path, relativize(path),
                ReviewItem.Status.ADDED, null));
        }
        for (var entry : deletedFiles.entrySet()) {
            String path = entry.getKey();
            String beforeContent = snapshots.getOrDefault(path, entry.getValue());
            items.add(new ReviewItem(path, relativize(path),
                ReviewItem.Status.DELETED, beforeContent));
        }

        items.sort((a, b) -> a.relativePath().compareToIgnoreCase(b.relativePath()));
        return items;
    }

    private static String relativize(String path) {
        if (path.startsWith(BASE + "/")) {
            return path.substring(BASE.length() + 1);
        }
        return new java.io.File(path).getName();
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
}
