package com.github.catatafishen.agentbridge.memory.kg;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link KgTriple} — POJO and input validation.
 */
class KgTripleTest {

    @Test
    void builderCreatesTriple() {
        Instant now = Instant.now();
        KgTriple triple = KgTriple.builder()
            .id(1)
            .subject("project")
            .predicate("uses")
            .object("Java 21")
            .validFrom(now)
            .createdAt(now)
            .build();

        assertEquals(1, triple.id());
        assertEquals("project", triple.subject());
        assertEquals("uses", triple.predicate());
        assertEquals("Java 21", triple.object());
        assertEquals(now, triple.validFrom());
        assertNull(triple.validUntil());
        assertEquals(now, triple.createdAt());
    }

    @Test
    void sanitizeNameTrimsAndTruncates() {
        String longName = "a".repeat(200);
        String sanitized = KgTriple.sanitizeName(longName);
        assertEquals(KgTriple.MAX_NAME_LENGTH, sanitized.length());
    }

    @Test
    void sanitizeNameStripsWhitespace() {
        assertEquals("hello", KgTriple.sanitizeName("  hello  "));
    }

    @Test
    void sanitizeNameRemovesNullBytes() {
        assertEquals("hello", KgTriple.sanitizeName("hel\0lo"));
    }

    @Test
    void sanitizeNamePreventsPathTraversal() {
        String sanitized = KgTriple.sanitizeName("../../etc/passwd");
        assertFalse(sanitized.contains(".."));
        assertFalse(sanitized.contains("/"));
    }

    @Test
    void sanitizeNameRemovesBackslash() {
        String sanitized = KgTriple.sanitizeName("path\\to\\file");
        assertFalse(sanitized.contains("\\"));
    }

    @Test
    void sanitizeNameThrowsForEmpty() {
        assertThrows(IllegalArgumentException.class,
            () -> KgTriple.sanitizeName(""));
    }

    @Test
    void sanitizeNameThrowsForOnlyNullBytes() {
        assertThrows(IllegalArgumentException.class,
            () -> KgTriple.sanitizeName("\0\0\0"));
    }

    @Test
    void sanitizeContentRemovesNullBytes() {
        assertEquals("hello world", KgTriple.sanitizeContent("hello\0 world"));
    }

    @Test
    void sanitizeContentTruncatesLongContent() {
        String longContent = "x".repeat(200_000);
        String sanitized = KgTriple.sanitizeContent(longContent);
        assertEquals(KgTriple.MAX_CONTENT_LENGTH, sanitized.length());
    }

    @Test
    void sanitizeContentThrowsForEmpty() {
        assertThrows(IllegalArgumentException.class,
            () -> KgTriple.sanitizeContent(""));
    }

    @Test
    void sanitizeContentThrowsForOnlyWhitespace() {
        assertThrows(IllegalArgumentException.class,
            () -> KgTriple.sanitizeContent("   "));
    }

    @Test
    void isSafeNameAcceptsValidNames() {
        assertTrue(KgTriple.isSafeName("project-name"));
        assertTrue(KgTriple.isSafeName("my_variable"));
        assertTrue(KgTriple.isSafeName("version 2.0"));
        assertTrue(KgTriple.isSafeName("Java21"));
    }

    @Test
    void isSafeNameRejectsUnsafeCharacters() {
        assertFalse(KgTriple.isSafeName("foo;bar"));
        assertFalse(KgTriple.isSafeName("hello\nworld"));
        assertFalse(KgTriple.isSafeName("path/to/file"));
        assertFalse(KgTriple.isSafeName(""));
    }

    @Test
    void builderSanitizesInputs() {
        KgTriple triple = KgTriple.builder()
            .subject("  my project  ")
            .predicate("uses")
            .object("Java\0 21")
            .build();

        assertEquals("my project", triple.subject());
        assertEquals("uses", triple.predicate());
        assertEquals("Java 21", triple.object());
    }

    @Test
    void tripleWithNullOptionalFields() {
        KgTriple triple = KgTriple.builder()
            .subject("test")
            .predicate("is")
            .object("value")
            .build();

        assertNull(triple.validFrom());
        assertNull(triple.validUntil());
        assertNull(triple.sourceDrawer());
    }

    @Test
    void maxNameLength() {
        assertEquals(128, KgTriple.MAX_NAME_LENGTH);
    }

    @Test
    void maxContentLength() {
        assertEquals(100_000, KgTriple.MAX_CONTENT_LENGTH);
    }
}
