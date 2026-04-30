package com.github.catatafishen.agentbridge.psi.tools.quality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextFingerprintTest {

    private static ContextFingerprint fp(String project, String file, long stamp, String action) {
        return new ContextFingerprint(project, file, stamp, action);
    }

    @Test
    void matchesIdenticalFingerprint() {
        ContextFingerprint a = fp("p", "/a/b.java", 100, "act|insp");
        ContextFingerprint b = fp("p", "/a/b.java", 100, "act|insp");
        assertTrue(a.matches(b));
    }

    @Test
    void rejectsDifferentProject() {
        ContextFingerprint a = fp("p1", "/x", 1, "act");
        ContextFingerprint b = fp("p2", "/x", 1, "act");
        assertFalse(a.matches(b));
    }

    @Test
    void rejectsDifferentAction() {
        ContextFingerprint a = fp("p", "/x", 1, "act1");
        ContextFingerprint b = fp("p", "/x", 1, "act2");
        assertFalse(a.matches(b));
    }

    @Test
    void rejectsDifferentFilePathWhenBothPresent() {
        ContextFingerprint a = fp("p", "/x", 1, "act");
        ContextFingerprint b = fp("p", "/y", 1, "act");
        assertFalse(a.matches(b));
    }

    @Test
    void toleratesNullFilePathOnEitherSide() {
        ContextFingerprint a = fp("p", null, 1, "act");
        ContextFingerprint b = fp("p", "/x", 1, "act");
        assertTrue(a.matches(b));
        assertTrue(b.matches(a));
    }

    @Test
    void rejectsDifferentDocumentModStamp() {
        ContextFingerprint a = fp("p", "/x", 100, "act");
        ContextFingerprint b = fp("p", "/x", 101, "act");
        assertFalse(a.matches(b));
    }

    @Test
    void toleratesNegativeModStampOnEitherSide() {
        ContextFingerprint a = fp("p", "/x", -1, "act");
        ContextFingerprint b = fp("p", "/x", 999, "act");
        assertTrue(a.matches(b));
        assertTrue(b.matches(a));
    }

    @Test
    void describeMismatchReportsSpecificDifference() {
        ContextFingerprint a = fp("p", "/x", 100, "act");
        ContextFingerprint b = fp("p", "/x", 200, "act");
        String desc = a.describeMismatch(b);
        assertTrue(desc.contains("document modified"), () -> "got: " + desc);
        assertTrue(desc.contains("100"));
        assertTrue(desc.contains("200"));
    }

    @Test
    void describeMismatchOnIdenticalReturnsGeneric() {
        ContextFingerprint a = fp("p", "/x", 1, "act");
        assertEquals("fingerprint mismatch", a.describeMismatch(a));
    }
}
