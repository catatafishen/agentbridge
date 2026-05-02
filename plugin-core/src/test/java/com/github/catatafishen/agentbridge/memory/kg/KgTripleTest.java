package com.github.catatafishen.agentbridge.memory.kg;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link KgTriple} — static sanitization methods, safety checks, and builder.
 */
class KgTripleTest {

    // ── sanitizeName ─────────────────────────────────────────────────────────

    @Test
    void sanitizeName_normalName_unchanged() {
        assertEquals("hello-world", KgTriple.sanitizeName("hello-world"));
    }

    @Test
    void sanitizeName_leadingAndTrailingWhitespace_stripped() {
        assertEquals("hello", KgTriple.sanitizeName("  hello  "));
    }

    @Test
    void sanitizeName_nullBytesRemoved() {
        assertEquals("hello", KgTriple.sanitizeName("he\0llo"));
    }

    @Test
    void sanitizeName_pathTraversalRemoved() {
        String sanitized = KgTriple.sanitizeName("../etc/passwd");
        assertEquals("etcpasswd", sanitized);
    }

    @Test
    void sanitizeName_backslashRemoved() {
        assertEquals("foobar", KgTriple.sanitizeName("foo\\bar"));
    }

    @Test
    void sanitizeName_truncatesAt128Chars() {
        String longName = "a".repeat(200);
        String sanitized = KgTriple.sanitizeName(longName);
        assertEquals(KgTriple.MAX_NAME_LENGTH, sanitized.length());
        assertEquals(128, sanitized.length());
    }

    @Test
    void sanitizeName_emptyAfterSanitization_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> KgTriple.sanitizeName("   "));
    }

    @Test
    void sanitizeName_onlySpecialChars_throws() {
        // "../..//" → after removing "..", "/", "\\" → empty
        assertThrows(IllegalArgumentException.class,
            () -> KgTriple.sanitizeName("../..//"));
    }

    // ── sanitizeContent ──────────────────────────────────────────────────────

    @Test
    void sanitizeContent_normalContent_unchanged() {
        assertEquals("some content here", KgTriple.sanitizeContent("some content here"));
    }

    @Test
    void sanitizeContent_nullBytesRemoved() {
        assertEquals("hello world", KgTriple.sanitizeContent("hello\0 world"));
    }

    @Test
    void sanitizeContent_truncatesAt100000Chars() {
        String longContent = "x".repeat(200_000);
        String sanitized = KgTriple.sanitizeContent(longContent);
        assertEquals(KgTriple.MAX_CONTENT_LENGTH, sanitized.length());
        assertEquals(100_000, sanitized.length());
    }

    @Test
    void sanitizeContent_emptyAfterSanitization_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> KgTriple.sanitizeContent(""));
    }

    @Test
    void sanitizeContent_whitespaceOnlyAfterSanitization_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> KgTriple.sanitizeContent("   \t\n  "));
    }

    // ── isSafeName ───────────────────────────────────────────────────────────

    @Test
    void isSafeName_safeNames_returnTrue() {
        assertTrue(KgTriple.isSafeName("hello-world"));
        assertTrue(KgTriple.isSafeName("foo_bar"));
        assertTrue(KgTriple.isSafeName("test.txt"));
        assertTrue(KgTriple.isSafeName("A B C 123"));
    }

    @Test
    void isSafeName_unsafeNames_returnFalse() {
        assertFalse(KgTriple.isSafeName("hello@world"));
        assertFalse(KgTriple.isSafeName("foo/bar"));
        assertFalse(KgTriple.isSafeName("test\0"));
        assertFalse(KgTriple.isSafeName(""));
    }

    // ── Builder ──────────────────────────────────────────────────────────────

    @Test
    void builder_sanitizesSubjectOnSet() {
        KgTriple triple = KgTriple.builder()
            .subject("  hello  ")
            .predicate("uses")
            .object("value")
            .build();

        assertEquals("hello", triple.subject(), "builder should strip whitespace from subject");
    }

    @Test
    void builder_throwsOnEmptySubject() {
        KgTriple.Builder builder = KgTriple.builder();
        assertThrows(IllegalArgumentException.class, () -> builder.subject("   "));
    }

    @Test
    void builder_fullRoundTrip() {
        Instant now = Instant.now();
        KgTriple triple = KgTriple.builder()
            .id(42)
            .subject("project")
            .predicate("uses")
            .object("Java 21")
            .validFrom(now)
            .validUntil(now.plusSeconds(3600))
            .sourceDrawer("code")
            .createdAt(now)
            .build();

        assertEquals(42, triple.id());
        assertEquals("project", triple.subject());
        assertEquals("uses", triple.predicate());
        assertEquals("Java 21", triple.object());
        assertEquals(now, triple.validFrom());
        assertEquals(now.plusSeconds(3600), triple.validUntil());
        assertEquals("code", triple.sourceDrawer());
        assertEquals(now, triple.createdAt());
    }

    @Test
    void builder_evidenceDefaultsToEmpty() {
        KgTriple triple = KgTriple.builder()
            .subject("TestSubject")
            .predicate("has-method")
            .object("doSomething")
            .build();
        assertEquals("", triple.evidence());
    }

    @Test
    void builder_evidenceSetAndRetrieved() {
        String evidenceJson = "[\"com.example.Foo\",\"Bar.java:42\"]";
        KgTriple triple = KgTriple.builder()
            .subject("TestSubject")
            .predicate("has-method")
            .object("doSomething")
            .evidence(evidenceJson)
            .build();
        assertEquals(evidenceJson, triple.evidence());
    }
}
