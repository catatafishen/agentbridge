package com.github.catatafishen.agentbridge.ui;

import kotlin.Pair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("FileNavigator.parsePathAndLine")
class FileNavigatorTest {

    private Pair<String, Integer> invoke(String input) {
        return FileNavigator.parsePathAndLine(input);
    }

    @Test
    void simplePathWithLineNumber() {
        Pair<String, Integer> result = invoke("foo/bar.java:42");
        assertEquals("foo/bar.java", result.getFirst());
        assertEquals(42, result.getSecond());
    }

    @Test
    void pathWithoutLineNumberReturnsZero() {
        Pair<String, Integer> result = invoke("foo/bar.java");
        assertEquals("foo/bar.java", result.getFirst());
        assertEquals(0, result.getSecond());
    }

    @Test
    void windowsPathWithLineNumber() {
        Pair<String, Integer> result = invoke("C:\\Users\\foo\\bar.java:10");
        assertEquals("C:\\Users\\foo\\bar.java", result.getFirst());
        assertEquals(10, result.getSecond());
    }

    @Test
    void colonAtStartTreatsWholeStringAsPath() {
        // ":42" — lastColon index is 0, condition `lastColon > 0` is false,
        // so the entire string is returned as the path with line 0.
        Pair<String, Integer> result = invoke(":42");
        assertEquals(":42", result.getFirst());
        assertEquals(0, result.getSecond());
    }

    @Test
    void multipleColonsUsesLastOneForLineNumber() {
        Pair<String, Integer> result = invoke("foo:bar:42");
        assertEquals("foo:bar", result.getFirst());
        assertEquals(42, result.getSecond());
    }

    @Test
    void emptyStringReturnsEmptyPathAndZero() {
        Pair<String, Integer> result = invoke("");
        assertEquals("", result.getFirst());
        assertEquals(0, result.getSecond());
    }
}
