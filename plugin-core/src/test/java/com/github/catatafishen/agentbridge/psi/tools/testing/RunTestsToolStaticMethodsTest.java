package com.github.catatafishen.agentbridge.psi.tools.testing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the package-private static helper methods in {@link RunTestsTool}.
 *
 * <p>All methods under test are pure functions with no IntelliJ dependencies,
 * so this test class lives in the same package and runs as plain JUnit 5.
 */
class RunTestsToolStaticMethodsTest {

    // ── buildFqn ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildFqn")
    class BuildFqn {

        @Test
        @DisplayName("non-empty package prepends package with dot")
        void nonEmptyPackage() {
            assertEquals("com.example.MyTest", RunTestsTool.buildFqn("com.example", "MyTest"));
        }

        @Test
        @DisplayName("null package returns simple name only")
        void nullPackage() {
            assertEquals("MyTest", RunTestsTool.buildFqn(null, "MyTest"));
        }

        @Test
        @DisplayName("empty package returns simple name only")
        void emptyPackage() {
            assertEquals("MyTest", RunTestsTool.buildFqn("", "MyTest"));
        }

        @Test
        @DisplayName("deeply nested package builds correct FQN")
        void deeplyNestedPackage() {
            assertEquals("a.b.c.d.Foo", RunTestsTool.buildFqn("a.b.c.d", "Foo"));
        }
    }

    // ── extractFqnFromSourceText ─────────────────────────────────────────────

    @Nested
    @DisplayName("extractFqnFromSourceText")
    class ExtractFqnFromSourceText {

        @Test
        @DisplayName("Java source with package and semicolon")
        void javaSourceWithPackage() {
            String source = "package com.example;\npublic class Foo {}";
            assertEquals("com.example.Foo", RunTestsTool.extractFqnFromSourceText(source, "Foo"));
        }

        @Test
        @DisplayName("Kotlin source with package (no semicolon)")
        void kotlinSourceNoSemicolon() {
            String source = "package com.example\nclass Foo";
            assertEquals("com.example.Foo", RunTestsTool.extractFqnFromSourceText(source, "Foo"));
        }

        @Test
        @DisplayName("no package declaration returns simple name")
        void noPackageDeclaration() {
            String source = "public class Foo {}";
            assertEquals("Foo", RunTestsTool.extractFqnFromSourceText(source, "Foo"));
        }

        @Test
        @DisplayName("empty source returns simple name")
        void emptySource() {
            assertEquals("Foo", RunTestsTool.extractFqnFromSourceText("", "Foo"));
        }

        @Test
        @DisplayName("extra whitespace between keyword and package name")
        void extraWhitespace() {
            String source = "package   com.foo;\npublic class Foo {}";
            assertEquals("com.foo.Foo", RunTestsTool.extractFqnFromSourceText(source, "Foo"));
        }

        @Test
        @DisplayName("package not on first line is not matched (regex uses ^)")
        void packageNotOnFirstLine() {
            String source = "// comment\npackage com.foo;\npublic class Foo {}";
            assertEquals("Foo", RunTestsTool.extractFqnFromSourceText(source, "Foo"));
        }

        @Test
        @DisplayName("single-segment package name")
        void singleSegmentPackage() {
            String source = "package mypackage;\nclass Bar {}";
            assertEquals("mypackage.Bar", RunTestsTool.extractFqnFromSourceText(source, "Bar"));
        }
    }

    // ── formatTestSummary ────────────────────────────────────────────────────

    @Nested
    @DisplayName("formatTestSummary")
    class FormatTestSummary {

        @Test
        @DisplayName("exit code 0 with empty output shows PASSED and runner panel message")
        void passedEmptyOutput() {
            String result = RunTestsTool.formatTestSummary(0, "MyTestConfig", "");

            assertTrue(result.contains("Tests PASSED"), "should contain PASSED marker");
            assertTrue(result.contains("MyTestConfig"), "should contain config name");
            assertTrue(result.contains("Results are visible in the IntelliJ test runner panel."),
                    "should contain runner panel message when output is empty");
            assertFalse(result.contains("FAILED"), "should not contain FAILED");
        }

        @Test
        @DisplayName("exit code 0 with output shows PASSED and appends output")
        void passedWithOutput() {
            String result = RunTestsTool.formatTestSummary(0, "MyTestConfig", "5 tests, 5 passed");

            assertTrue(result.contains("Tests PASSED"), "should contain PASSED marker");
            assertTrue(result.contains("MyTestConfig"), "should contain config name");
            assertTrue(result.contains("\n5 tests, 5 passed"), "should append test output after newline");
            assertFalse(result.contains("Results are visible in the IntelliJ test runner panel."),
                    "should not contain runner panel message when output is present");
        }

        @Test
        @DisplayName("exit code 1 with empty output shows FAILED and runner panel message")
        void failedEmptyOutput() {
            String result = RunTestsTool.formatTestSummary(1, "FailConfig", "");

            assertTrue(result.contains("Tests FAILED (exit code 1)"), "should contain FAILED with exit code");
            assertTrue(result.contains("FailConfig"), "should contain config name");
            assertTrue(result.contains("Results are visible in the IntelliJ test runner panel."),
                    "should contain runner panel message when output is empty");
            assertFalse(result.contains("Tests PASSED"), "should not contain PASSED");
        }

        @Test
        @DisplayName("exit code 1 with output shows FAILED and appends output")
        void failedWithOutput() {
            String result = RunTestsTool.formatTestSummary(1, "FailConfig", "2 tests, 1 failed");

            assertTrue(result.contains("Tests FAILED (exit code 1)"), "should contain FAILED with exit code");
            assertTrue(result.contains("FailConfig"), "should contain config name");
            assertTrue(result.contains("\n2 tests, 1 failed"), "should append test output after newline");
            assertFalse(result.contains("Results are visible in the IntelliJ test runner panel."),
                    "should not contain runner panel message when output is present");
        }

        @Test
        @DisplayName("negative exit code is formatted correctly")
        void negativeExitCode() {
            String result = RunTestsTool.formatTestSummary(-1, "CrashConfig", "");

            assertTrue(result.contains("Tests FAILED (exit code -1)"), "should handle negative exit code");
            assertTrue(result.contains("CrashConfig"), "should contain config name");
        }

        @Test
        @DisplayName("summary uses em dash separator between status and config name")
        void emDashSeparator() {
            String result = RunTestsTool.formatTestSummary(0, "DashTest", "");

            assertTrue(result.contains("Tests PASSED — DashTest"),
                    "should use em dash separator between status and config name");
        }
    }
}
