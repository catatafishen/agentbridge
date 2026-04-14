package com.github.catatafishen.agentbridge.memory.validation;

import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.github.catatafishen.agentbridge.memory.wal.WriteAheadLog;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link MemoryValidator} — evidence parsing and state determination.
 * PSI-dependent validation is tested in platform tests.
 */
class MemoryValidatorTest {

    @Test
    void parseEvidence_emptyString_returnsEmpty() {
        assertTrue(MemoryValidator.parseEvidence("").isEmpty());
    }

    @Test
    void parseEvidence_validJsonArray() {
        List<String> refs = MemoryValidator.parseEvidence("[\"com.example.Foo\",\"Bar.java:42\"]");
        assertEquals(2, refs.size());
        assertEquals("com.example.Foo", refs.get(0));
        assertEquals("Bar.java:42", refs.get(1));
    }

    @Test
    void parseEvidence_singleElement() {
        List<String> refs = MemoryValidator.parseEvidence("[\"com.example.Service\"]");
        assertEquals(1, refs.size());
        assertEquals("com.example.Service", refs.getFirst());
    }

    @Test
    void parseEvidence_invalidJson_returnsEmpty() {
        assertTrue(MemoryValidator.parseEvidence("not json").isEmpty());
    }

    @Test
    void parseEvidence_jsonObject_returnsEmpty() {
        assertTrue(MemoryValidator.parseEvidence("{\"key\":\"value\"}").isEmpty());
    }

    @Test
    void parseEvidence_emptyArray_returnsEmpty() {
        assertTrue(MemoryValidator.parseEvidence("[]").isEmpty());
    }

    @Test
    void determineState_allValid_verified() {
        assertEquals(DrawerDocument.STATE_VERIFIED,
            MemoryValidator.determineState(3, 3));
    }

    @Test
    void determineState_someInvalid_stale() {
        assertEquals(DrawerDocument.STATE_STALE,
            MemoryValidator.determineState(2, 3));
    }

    @Test
    void determineState_noneValid_stale() {
        assertEquals(DrawerDocument.STATE_STALE,
            MemoryValidator.determineState(0, 3));
    }

    @Test
    void determineState_noRefs_unverified() {
        assertEquals(DrawerDocument.STATE_UNVERIFIED,
            MemoryValidator.determineState(0, 0));
    }

    // ── validateDrawer ────────────────────────────────────────────────────

    @Nested
    @DisplayName("validateDrawer")
    class ValidateDrawerTests {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("returns null when drawer has no evidence")
        void validateDrawer_emptyEvidence_returnsNull() throws IOException {
            Project project = mock(Project.class);
            WriteAheadLog wal = new WriteAheadLog(tempDir.resolve("wal"));
            wal.initialize();
            MemoryStore store = new MemoryStore(tempDir.resolve("lucene"), wal);
            store.initialize();
            try {
                DrawerDocument drawer = DrawerDocument.builder()
                    .id("test-drawer")
                    .wing("proj")
                    .content("Some content")
                    .evidence("")       // empty evidence → parseEvidence returns empty list
                    .build();

                assertNull(MemoryValidator.validateDrawer(project, store, drawer));
            } finally {
                store.dispose();
            }
        }

        @Test
        @DisplayName("returns null when evidence is an empty JSON array")
        void validateDrawer_emptyJsonArrayEvidence_returnsNull() throws IOException {
            Project project = mock(Project.class);
            WriteAheadLog wal = new WriteAheadLog(tempDir.resolve("wal2"));
            wal.initialize();
            MemoryStore store = new MemoryStore(tempDir.resolve("lucene2"), wal);
            store.initialize();
            try {
                DrawerDocument drawer = DrawerDocument.builder()
                    .id("test-drawer-2")
                    .wing("proj")
                    .content("Some content")
                    .evidence("[]")     // empty array → empty refs list
                    .build();

                assertNull(MemoryValidator.validateDrawer(project, store, drawer));
            } finally {
                store.dispose();
            }
        }
    }

    // ── validateByFile ────────────────────────────────────────────────────

    @Nested
    @DisplayName("validateByFile")
    class ValidateByFileTests {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("returns empty list when no drawers match the file")
        void validateByFile_noMatchingDrawers_returnsEmpty() throws IOException {
            Project project = mock(Project.class);
            WriteAheadLog wal = new WriteAheadLog(tempDir.resolve("wal"));
            wal.initialize();
            MemoryStore store = new MemoryStore(tempDir.resolve("lucene"), wal);
            store.initialize();
            try {
                List<MemoryValidator.ValidationOutcome> outcomes =
                    MemoryValidator.validateByFile(project, store, "SomeFile.java");
                assertTrue(outcomes.isEmpty());
            } finally {
                store.dispose();
            }
        }

        @Test
        @DisplayName("returns empty list for an empty store")
        void validateByFile_emptyStore_returnsEmpty() throws IOException {
            Project project = mock(Project.class);
            WriteAheadLog wal = new WriteAheadLog(tempDir.resolve("wal2"));
            wal.initialize();
            MemoryStore store = new MemoryStore(tempDir.resolve("lucene2"), wal);
            store.initialize();
            try {
                List<MemoryValidator.ValidationOutcome> outcomes =
                    MemoryValidator.validateByFile(project, store, "AnyFile.kt");
                assertTrue(outcomes.isEmpty());
            } finally {
                store.dispose();
            }
        }
    }
}
