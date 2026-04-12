package com.github.catatafishen.agentbridge.psi;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the AccessType merge logic in {@link FileAccessTracker}.
 * Uses reflection since the method is private static.
 */
class FileAccessTrackerTest {

    // ── merge ──────────────────────────────────────────────

    @Test
    void merge_readPlusReadStaysRead() throws Exception {
        assertEquals(FileAccessTracker.AccessType.READ,
            invokeMerge(FileAccessTracker.AccessType.READ, FileAccessTracker.AccessType.READ));
    }

    @Test
    void merge_writePlusWriteStaysWrite() throws Exception {
        assertEquals(FileAccessTracker.AccessType.WRITE,
            invokeMerge(FileAccessTracker.AccessType.WRITE, FileAccessTracker.AccessType.WRITE));
    }

    @Test
    void merge_readPlusWriteBecomesReadWrite() throws Exception {
        assertEquals(FileAccessTracker.AccessType.READ_WRITE,
            invokeMerge(FileAccessTracker.AccessType.READ, FileAccessTracker.AccessType.WRITE));
    }

    @Test
    void merge_writePlusReadBecomesReadWrite() throws Exception {
        assertEquals(FileAccessTracker.AccessType.READ_WRITE,
            invokeMerge(FileAccessTracker.AccessType.WRITE, FileAccessTracker.AccessType.READ));
    }

    @Test
    void merge_readWritePlusReadStaysReadWrite() throws Exception {
        assertEquals(FileAccessTracker.AccessType.READ_WRITE,
            invokeMerge(FileAccessTracker.AccessType.READ_WRITE, FileAccessTracker.AccessType.READ));
    }

    @Test
    void merge_readWritePlusWriteStaysReadWrite() throws Exception {
        assertEquals(FileAccessTracker.AccessType.READ_WRITE,
            invokeMerge(FileAccessTracker.AccessType.READ_WRITE, FileAccessTracker.AccessType.WRITE));
    }

    @Test
    void merge_readPlusReadWriteStaysReadWrite() throws Exception {
        assertEquals(FileAccessTracker.AccessType.READ_WRITE,
            invokeMerge(FileAccessTracker.AccessType.READ, FileAccessTracker.AccessType.READ_WRITE));
    }

    @Test
    void merge_readWritePlusReadWriteStaysReadWrite() throws Exception {
        assertEquals(FileAccessTracker.AccessType.READ_WRITE,
            invokeMerge(FileAccessTracker.AccessType.READ_WRITE, FileAccessTracker.AccessType.READ_WRITE));
    }

    // ── Reflection helper ──────────────────────────────────

    private static FileAccessTracker.AccessType invokeMerge(
        FileAccessTracker.AccessType existing,
        FileAccessTracker.AccessType incoming
    ) throws Exception {
        Method m = FileAccessTracker.class.getDeclaredMethod(
            "merge", FileAccessTracker.AccessType.class, FileAccessTracker.AccessType.class);
        m.setAccessible(true);
        return (FileAccessTracker.AccessType) m.invoke(null, existing, incoming);
    }
}
