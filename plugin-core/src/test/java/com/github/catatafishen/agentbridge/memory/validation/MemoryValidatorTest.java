package com.github.catatafishen.agentbridge.memory.validation;

import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
}
