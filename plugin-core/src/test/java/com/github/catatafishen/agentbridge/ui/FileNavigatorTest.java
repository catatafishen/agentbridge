package com.github.catatafishen.agentbridge.ui;

import com.intellij.openapi.project.Project;
import kotlin.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DisplayName("FileNavigator.parsePathAndLine")
class FileNavigatorTest {

    private Method parsePathAndLine;
    private FileNavigator navigator;

    @BeforeEach
    void setUp() throws Exception {
        Project project = mock(Project.class);
        navigator = new FileNavigator(project);
        parsePathAndLine = FileNavigator.class.getDeclaredMethod("parsePathAndLine", String.class);
        parsePathAndLine.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private Pair<String, Integer> invoke(String input) throws Exception {
        return (Pair<String, Integer>) parsePathAndLine.invoke(navigator, input);
    }

    @Test
    void simplePathWithLineNumber() throws Exception {
        Pair<String, Integer> result = invoke("foo/bar.java:42");
        assertEquals("foo/bar.java", result.getFirst());
        assertEquals(42, result.getSecond());
    }

    @Test
    void pathWithoutLineNumberReturnsZero() throws Exception {
        Pair<String, Integer> result = invoke("foo/bar.java");
        assertEquals("foo/bar.java", result.getFirst());
        assertEquals(0, result.getSecond());
    }

    @Test
    void windowsPathWithLineNumber() throws Exception {
        Pair<String, Integer> result = invoke("C:\\Users\\foo\\bar.java:10");
        assertEquals("C:\\Users\\foo\\bar.java", result.getFirst());
        assertEquals(10, result.getSecond());
    }

    @Test
    void colonAtStartTreatsWholeStringAsPath() throws Exception {
        // ":42" — lastColon index is 0, condition `lastColon > 0` is false,
        // so the entire string is returned as the path with line 0.
        Pair<String, Integer> result = invoke(":42");
        assertEquals(":42", result.getFirst());
        assertEquals(0, result.getSecond());
    }

    @Test
    void multipleColonsUsesLastOneForLineNumber() throws Exception {
        Pair<String, Integer> result = invoke("foo:bar:42");
        assertEquals("foo:bar", result.getFirst());
        assertEquals(42, result.getSecond());
    }

    @Test
    void emptyStringReturnsEmptyPathAndZero() throws Exception {
        Pair<String, Integer> result = invoke("");
        assertEquals("", result.getFirst());
        assertEquals(0, result.getSecond());
    }
}
