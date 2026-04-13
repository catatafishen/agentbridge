package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentScratchTrackerTest {

    @Test
    void emptyMap_returnsEmptyList() {
        Map<String, Long> trackedFiles = new LinkedHashMap<>();

        List<String> result = AgentScratchTracker.findExpiredEntries(trackedFiles, 5000L);

        assertTrue(result.isEmpty());
    }

    @Test
    void allExpired_returnsAll() {
        Map<String, Long> trackedFiles = new LinkedHashMap<>();
        trackedFiles.put("/path/to/file1.txt", 1000L);
        trackedFiles.put("/path/to/file2.txt", 2000L);

        List<String> result = AgentScratchTracker.findExpiredEntries(trackedFiles, 5000L);

        assertEquals(2, result.size());
        assertTrue(result.contains("/path/to/file1.txt"));
        assertTrue(result.contains("/path/to/file2.txt"));
    }

    @Test
    void noneExpired_returnsEmpty() {
        Map<String, Long> trackedFiles = new LinkedHashMap<>();
        trackedFiles.put("/path/to/file1.txt", 1000L);
        trackedFiles.put("/path/to/file2.txt", 2000L);

        List<String> result = AgentScratchTracker.findExpiredEntries(trackedFiles, 500L);

        assertTrue(result.isEmpty());
    }

    @Test
    void mixedExpiration_returnsOnlyExpired() {
        Map<String, Long> trackedFiles = new LinkedHashMap<>();
        trackedFiles.put("/path/to/file1.txt", 1000L);
        trackedFiles.put("/path/to/file2.txt", 2000L);
        trackedFiles.put("/path/to/file3.txt", 5000L);

        List<String> result = AgentScratchTracker.findExpiredEntries(trackedFiles, 3000L);

        assertEquals(2, result.size());
        assertTrue(result.contains("/path/to/file1.txt"));
        assertTrue(result.contains("/path/to/file2.txt"));
        assertFalse(result.contains("/path/to/file3.txt"));
    }

    @Test
    void exactlyAtCutoff_notExpired() {
        Map<String, Long> trackedFiles = new LinkedHashMap<>();
        trackedFiles.put("/path/to/file1.txt", 3000L);

        List<String> result = AgentScratchTracker.findExpiredEntries(trackedFiles, 3000L);

        assertTrue(result.isEmpty());
    }

    @Test
    void singleEntry_expired() {
        Map<String, Long> trackedFiles = new LinkedHashMap<>();
        trackedFiles.put("/path/to/file1.txt", 1000L);

        List<String> result = AgentScratchTracker.findExpiredEntries(trackedFiles, 5000L);

        assertEquals(1, result.size());
        assertEquals("/path/to/file1.txt", result.get(0));
    }

    @Test
    void singleEntry_notExpired() {
        Map<String, Long> trackedFiles = new LinkedHashMap<>();
        trackedFiles.put("/path/to/file1.txt", 5000L);

        List<String> result = AgentScratchTracker.findExpiredEntries(trackedFiles, 3000L);

        assertTrue(result.isEmpty());
    }

    @Test
    void zeroCutoff_noneExpired() {
        Map<String, Long> trackedFiles = new LinkedHashMap<>();
        trackedFiles.put("/path/to/file1.txt", 1000L);
        trackedFiles.put("/path/to/file2.txt", 2000L);

        List<String> result = AgentScratchTracker.findExpiredEntries(trackedFiles, 0L);

        assertTrue(result.isEmpty());
    }

    @Test
    void preservesOriginalMap() {
        Map<String, Long> trackedFiles = new LinkedHashMap<>();
        trackedFiles.put("/path/to/file1.txt", 1000L);
        trackedFiles.put("/path/to/file2.txt", 2000L);

        Map<String, Long> snapshot = new LinkedHashMap<>(trackedFiles);

        AgentScratchTracker.findExpiredEntries(trackedFiles, 5000L);

        assertEquals(snapshot, trackedFiles);
    }

    @Test
    void multipleExpiredPreservesOrder() {
        Map<String, Long> trackedFiles = new LinkedHashMap<>();
        trackedFiles.put("/path/to/file1.txt", 1000L);
        trackedFiles.put("/path/to/file2.txt", 2000L);
        trackedFiles.put("/path/to/file3.txt", 3000L);

        List<String> result = AgentScratchTracker.findExpiredEntries(trackedFiles, 5000L);

        assertEquals(List.of("/path/to/file1.txt", "/path/to/file2.txt", "/path/to/file3.txt"), result);
    }
}
