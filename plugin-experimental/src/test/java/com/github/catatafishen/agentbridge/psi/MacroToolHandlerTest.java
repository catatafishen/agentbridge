package com.github.catatafishen.agentbridge.psi;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for private static helper methods in {@link MacroToolHandler}.
 * Uses reflection to access private methods. Pure unit tests — no IntelliJ platform context required.
 */
class MacroToolHandlerTest {

    private static Method appendChangeReportMethod;
    private static Method appendFilePathsMethod;
    private static Method appendDiffStatsMethod;

    @BeforeAll
    static void makeMethodsAccessible() throws Exception {
        appendChangeReportMethod = MacroToolHandler.class.getDeclaredMethod(
                "appendChangeReport", StringBuilder.class,
                String.class, String.class, String.class, String.class);
        appendChangeReportMethod.setAccessible(true);

        appendFilePathsMethod = MacroToolHandler.class.getDeclaredMethod(
                "appendFilePaths", StringBuilder.class, boolean.class,
                String.class, String.class);
        appendFilePathsMethod.setAccessible(true);

        appendDiffStatsMethod = MacroToolHandler.class.getDeclaredMethod(
                "appendDiffStats", StringBuilder.class, String.class, String.class);
        appendDiffStatsMethod.setAccessible(true);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String invokeAppendFilePaths(boolean sameFile,
                                                String beforePath,
                                                String afterPath) throws Exception {
        StringBuilder sb = new StringBuilder();
        appendFilePathsMethod.invoke(null, sb, sameFile, beforePath, afterPath);
        return sb.toString();
    }

    private static String invokeAppendDiffStats(String before, String after) throws Exception {
        StringBuilder sb = new StringBuilder();
        appendDiffStatsMethod.invoke(null, sb, before, after);
        return sb.toString();
    }

    private static String invokeAppendChangeReport(String beforePath, String beforeContent,
                                                    String afterPath, String afterContent) throws Exception {
        StringBuilder sb = new StringBuilder();
        appendChangeReportMethod.invoke(null, sb, beforePath, beforeContent, afterPath, afterContent);
        return sb.toString();
    }

    // ── appendFilePaths ─────────────────────────────────────────────────────

    @Nested
    class AppendFilePaths {

        @Test
        void sameFile_singleFileLine() throws Exception {
            String result = invokeAppendFilePaths(true, "/project/src/Main.java", "/project/src/Main.java");
            assertEquals("\nFile: /project/src/Main.java", result);
        }

        @Test
        void differentFiles_beforeAndAfterLines() throws Exception {
            String result = invokeAppendFilePaths(false, "/project/A.java", "/project/B.java");
            assertTrue(result.contains("\nBefore: /project/A.java"), "Should contain Before path");
            assertTrue(result.contains("\nAfter: /project/B.java"), "Should contain After path");
            assertTrue(result.contains("editor switched to a different file"),
                    "Should note the editor switch");
        }

        @Test
        void differentFiles_nullBeforePath_onlyAfterShown() throws Exception {
            String result = invokeAppendFilePaths(false, null, "/project/B.java");
            assertTrue(!result.contains("Before:"), "Should not contain Before when null");
            assertTrue(result.contains("\nAfter: /project/B.java"), "Should contain After path");
        }

        @Test
        void differentFiles_nullAfterPath_onlyBeforeShown() throws Exception {
            String result = invokeAppendFilePaths(false, "/project/A.java", null);
            assertTrue(result.contains("\nBefore: /project/A.java"), "Should contain Before path");
            assertTrue(!result.contains("After:"), "Should not contain After when null");
        }
    }

    // ── appendDiffStats ─────────────────────────────────────────────────────

    @Nested
    class AppendDiffStats {

        @Test
        void linesAdded_showsPositiveDelta() throws Exception {
            String before = "line1\nline2";
            String after = "line1\nline2\nline3\nline4";
            String result = invokeAppendDiffStats(before, after);

            assertTrue(result.contains("Lines: 2 \u2192 4"), "Should show line count change");
            assertTrue(result.contains("(+2)"), "Should show positive line delta");
        }

        @Test
        void linesRemoved_showsNegativeDelta() throws Exception {
            String before = "line1\nline2\nline3";
            String after = "line1";
            String result = invokeAppendDiffStats(before, after);

            assertTrue(result.contains("Lines: 3 \u2192 1"), "Should show line count change");
            assertTrue(result.contains("(-2)"), "Should show negative line delta");
        }

        @Test
        void characterDelta_showsPositiveCharChange() throws Exception {
            String before = "abc";
            String after = "abcdef";
            String result = invokeAppendDiffStats(before, after);

            assertTrue(result.contains("Chars: 3 \u2192 6"), "Should show char count change");
            assertTrue(result.contains("(+3)"), "Should show positive char delta");
        }

        @Test
        void characterDelta_showsNegativeCharChange() throws Exception {
            String before = "abcdef";
            String after = "ab";
            String result = invokeAppendDiffStats(before, after);

            assertTrue(result.contains("Chars: 6 \u2192 2"), "Should show char count change");
            assertTrue(result.contains("(-4)"), "Should show negative char delta");
        }

        @Test
        void sameLineCount_noDeltaShown() throws Exception {
            String before = "aaa\nbbb";
            String after = "xxx\nyyy";
            String result = invokeAppendDiffStats(before, after);

            assertTrue(result.contains("Lines: 2 \u2192 2"), "Should show line counts");
            String linesLine = result.lines()
                    .filter(l -> l.contains("Lines:"))
                    .findFirst()
                    .orElse("");
            assertTrue(!linesLine.contains("(+") && !linesLine.contains("(-"),
                    "Should not show line delta when zero");
        }

        @Test
        void sameCharCount_noDeltaShown() throws Exception {
            String before = "abc";
            String after = "xyz";
            String result = invokeAppendDiffStats(before, after);

            assertTrue(result.contains("Chars: 3 \u2192 3"), "Should show char counts");
            String charsLine = result.lines()
                    .filter(l -> l.contains("Chars:"))
                    .findFirst()
                    .orElse("");
            assertTrue(!charsLine.contains("(+") && !charsLine.contains("(-"),
                    "Should not show char delta when zero");
        }
    }

    // ── appendChangeReport ──────────────────────────────────────────────────

    @Nested
    class AppendChangeReport {

        @Test
        void bothContentsNull_noEditorMessage() throws Exception {
            String result = invokeAppendChangeReport("/a.java", null, "/b.java", null);
            assertTrue(result.contains("No editor was active during macro execution"),
                    "Should report no active editor");
        }

        @Test
        void sameFileSameContent_noChangesMessage() throws Exception {
            String path = "/project/File.java";
            String content = "class Foo {}";
            String result = invokeAppendChangeReport(path, content, path, content);

            assertTrue(result.contains("File: " + path), "Should show the file path");
            assertTrue(result.contains("No content changes detected"),
                    "Should report no changes");
        }

        @Test
        void sameFileDifferentContent_includesDiffStats() throws Exception {
            String path = "/project/File.java";
            String before = "line1\nline2";
            String after = "line1\nline2\nline3";
            String result = invokeAppendChangeReport(path, before, path, after);

            assertTrue(result.contains("File: " + path), "Should show the file path");
            assertTrue(result.contains("Lines:"), "Should contain line stats");
            assertTrue(result.contains("Chars:"), "Should contain char stats");
        }

        @Test
        void differentFiles_showsBeforeAndAfterPaths() throws Exception {
            String result = invokeAppendChangeReport(
                    "/project/A.java", "content A",
                    "/project/B.java", "content B");

            assertTrue(result.contains("Before: /project/A.java"), "Should show before path");
            assertTrue(result.contains("After: /project/B.java"), "Should show after path");
            assertTrue(!result.contains("Lines:"), "Should not show diff stats for different files");
        }

        @Test
        void beforeContentNull_afterContentPresent_differentFiles() throws Exception {
            String result = invokeAppendChangeReport(
                    null, null,
                    "/project/B.java", "new content");

            assertTrue(result.contains("After: /project/B.java"), "Should show after path");
        }

        @Test
        void sameFileDifferentContent_showsLineDelta() throws Exception {
            String path = "/project/Test.java";
            String before = "a";
            String after = "a\nb\nc";
            String result = invokeAppendChangeReport(path, before, path, after);

            assertTrue(result.contains("Lines: 1 \u2192 3"), "Should show line change");
            assertTrue(result.contains("(+2)"), "Should show positive delta");
        }
    }
}
