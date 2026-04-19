package com.github.catatafishen.agentbridge.psi.tools.testing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// appended below the outer class — injected via insert_before_symbol on next nested class boundary

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

    // ── buildJUnitConfigName ─────────────────────────────────────────────────

    @Nested
    @DisplayName("buildJUnitConfigName")
    class BuildJUnitConfigName {

        @Test
        @DisplayName("class only produces 'Test: ClassName'")
        void classOnly() {
            assertEquals("Test: MyTest", RunTestsTool.buildJUnitConfigName("MyTest", null));
        }

        @Test
        @DisplayName("class with method produces 'Test: ClassName.methodName'")
        void classAndMethod() {
            assertEquals("Test: MyTest.testFoo", RunTestsTool.buildJUnitConfigName("MyTest", "testFoo"));
        }

        @Test
        @DisplayName("simple name with single-char method")
        void singleCharMethod() {
            assertEquals("Test: A.x", RunTestsTool.buildJUnitConfigName("A", "x"));
        }
    }

    // ── buildPatternConfigName ───────────────────────────────────────────────

    @Nested
    @DisplayName("buildPatternConfigName")
    class BuildPatternConfigName {

        @Test
        @DisplayName("wildcard pattern with class count")
        void wildcardPattern() {
            assertEquals("Test: *Test (5 classes)",
                RunTestsTool.buildPatternConfigName("*Test", 5));
        }

        @Test
        @DisplayName("single matching class")
        void singleClass() {
            assertEquals("Test: *Integration* (1 classes)",
                RunTestsTool.buildPatternConfigName("*Integration*", 1));
        }

        @Test
        @DisplayName("zero matching classes")
        void zeroClasses() {
            assertEquals("Test: *Nothing* (0 classes)",
                RunTestsTool.buildPatternConfigName("*Nothing*", 0));
        }
    }

    // ── buildGradleTaskPrefix ────────────────────────────────────────────────

    @Nested
    @DisplayName("buildGradleTaskPrefix")
    class BuildGradleTaskPrefix {

        @Test
        @DisplayName("empty module returns empty prefix")
        void emptyModule() {
            assertEquals("", RunTestsTool.buildGradleTaskPrefix(""));
        }

        @Test
        @DisplayName("named module returns ':module:' prefix")
        void namedModule() {
            assertEquals(":plugin-core:", RunTestsTool.buildGradleTaskPrefix("plugin-core"));
        }

        @Test
        @DisplayName("simple module name")
        void simpleModule() {
            assertEquals(":app:", RunTestsTool.buildGradleTaskPrefix("app"));
        }
    }

    // ── determineTestStatus ──────────────────────────────────────────────────

    @Nested
    @DisplayName("determineTestStatus")
    class DetermineTestStatus {

        @Test
        @DisplayName("passed=true returns PASSED regardless of defect flag")
        void passedTrue() {
            assertEquals("PASSED", RunTestsTool.determineTestStatus(true, false));
        }

        @Test
        @DisplayName("passed=true and defect=true still returns PASSED (passed takes priority)")
        void passedTrueDefectTrue() {
            assertEquals("PASSED", RunTestsTool.determineTestStatus(true, true));
        }

        @Test
        @DisplayName("passed=false and defect=true returns FAILED")
        void defectTrue() {
            assertEquals("FAILED", RunTestsTool.determineTestStatus(false, true));
        }

        @Test
        @DisplayName("passed=false and defect=false returns UNKNOWN")
        void unknown() {
            assertEquals("UNKNOWN", RunTestsTool.determineTestStatus(false, false));
        }
    }

    // ── formatTestDetail ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("formatTestDetail")
    class FormatTestDetail {

        @Test
        @DisplayName("passed test shows PASSED status with name")
        void passedTest() {
            String result = RunTestsTool.formatTestDetail("testFoo", true, false, null, null);
            assertEquals("  PASSED testFoo\n", result);
        }

        @Test
        @DisplayName("failed test with error message and stacktrace")
        void failedWithErrorAndStack() {
            String result = RunTestsTool.formatTestDetail(
                "testBar", false, true, "expected 1 but was 2", "at com.Foo.testBar(Foo.java:10)");
            assertTrue(result.startsWith("  FAILED testBar\n"), "should start with FAILED status line");
            assertTrue(result.contains("    Error: expected 1 but was 2\n"), "should contain error message");
            assertTrue(result.contains("    Stacktrace:\nat com.Foo.testBar(Foo.java:10)\n"),
                "should contain stacktrace");
        }

        @Test
        @DisplayName("failed test with null error message and null stacktrace")
        void failedNullMessages() {
            String result = RunTestsTool.formatTestDetail("testBaz", false, true, null, null);
            assertEquals("  FAILED testBaz\n", result, "should only have status line when messages are null");
        }

        @Test
        @DisplayName("failed test with empty error message is omitted")
        void failedEmptyErrorMsg() {
            String result = RunTestsTool.formatTestDetail("testEmpty", false, true, "", "");
            assertEquals("  FAILED testEmpty\n", result, "should omit empty error and stacktrace");
        }

        @Test
        @DisplayName("failed test with error only (no stacktrace)")
        void failedErrorOnly() {
            String result = RunTestsTool.formatTestDetail("testErr", false, true, "assertion failed", null);
            assertTrue(result.contains("    Error: assertion failed\n"), "should contain error");
            assertFalse(result.contains("Stacktrace"), "should not contain stacktrace when null");
        }

        @Test
        @DisplayName("failed test with stacktrace only (no error message)")
        void failedStacktraceOnly() {
            String result = RunTestsTool.formatTestDetail("testStack", false, true, null, "at X.y(X.java:5)");
            assertFalse(result.contains("Error:"), "should not contain Error when null");
            assertTrue(result.contains("    Stacktrace:\nat X.y(X.java:5)\n"), "should contain stacktrace");
        }

        @Test
        @DisplayName("unknown status test ignores error/stacktrace even if provided")
        void unknownStatus() {
            String result = RunTestsTool.formatTestDetail("testUnknown", false, false, "some error", "some stack");
            assertEquals("  UNKNOWN testUnknown\n", result,
                "should not include error/stacktrace when defect=false");
        }
    }

    // ── buildGradleTestFilter ────────────────────────────────────────────────

    @Nested
    @DisplayName("buildGradleTestFilter")
    class BuildGradleTestFilter {

        @Test
        @DisplayName("simple class name gets wildcard package prefix")
        void simpleClassName() {
            assertEquals("*.FormattingTest", RunTestsTool.buildGradleTestFilter("FormattingTest"));
        }

        @Test
        @DisplayName("wildcard class suffix gets wildcard package prefix")
        void wildcardSuffix() {
            assertEquals("*.*Test", RunTestsTool.buildGradleTestFilter("*Test"));
        }

        @Test
        @DisplayName("double wildcard is unchanged")
        void doubleWildcard() {
            assertEquals("*.*Test", RunTestsTool.buildGradleTestFilter("*.*Test"));
        }

        @Test
        @DisplayName("fully qualified class name is unchanged")
        void fullyQualifiedClass() {
            assertEquals("com.example.FormattingTest",
                RunTestsTool.buildGradleTestFilter("com.example.FormattingTest"));
        }

        @Test
        @DisplayName("class.method with no package gets wildcard package prefix")
        void classMethodNoPackage() {
            assertEquals("*.FormattingTest.testFoo",
                RunTestsTool.buildGradleTestFilter("FormattingTest.testFoo"));
        }

        @Test
        @DisplayName("fully qualified class.method is unchanged")
        void fullyQualifiedClassMethod() {
            assertEquals("com.example.FormattingTest.testFoo",
                RunTestsTool.buildGradleTestFilter("com.example.FormattingTest.testFoo"));
        }

        @Test
        @DisplayName("wildcard with package qualifier is unchanged")
        void wildcardWithPackage() {
            assertEquals("*.FormattingTest", RunTestsTool.buildGradleTestFilter("*.FormattingTest"));
        }

        @Test
        @DisplayName("single wildcard gets prefix")
        void singleWildcard() {
            assertEquals("*.*", RunTestsTool.buildGradleTestFilter("*"));
        }
    }

    // ── formatConsoleSection ─────────────────────────────────────────────────

    @Nested
    @DisplayName("formatConsoleSection")
    class FormatConsoleSection {

        @Test
        @DisplayName("null text returns null")
        void nullText() {
            assertNull(RunTestsTool.formatConsoleSection(null));
        }

        @Test
        @DisplayName("empty text returns null")
        void emptyText() {
            assertNull(RunTestsTool.formatConsoleSection(""));
        }

        @Test
        @DisplayName("blank text (whitespace only) returns null")
        void blankText() {
            assertNull(RunTestsTool.formatConsoleSection("   \n  \t  "));
        }

        @Test
        @DisplayName("non-blank text is wrapped with console header")
        void normalText() {
            String result = RunTestsTool.formatConsoleSection("some test output");
            assertNotNull(result);
            assertTrue(result.startsWith("\n=== Console Output ===\n"),
                "should start with console header");
            assertTrue(result.contains("some test output"), "should contain original text");
        }

        @Test
        @DisplayName("long text is truncated via ToolUtils.truncateOutput")
        void longTextIsTruncated() {
            // Create text longer than 8000 chars (default truncation limit)
            String longText = "x".repeat(10000);
            String result = RunTestsTool.formatConsoleSection(longText);
            assertNotNull(result);
            assertTrue(result.startsWith("\n=== Console Output ===\n"),
                "should start with console header");
            assertTrue(result.contains("truncated"), "long text should be truncated");
        }
    }

    // ── findTestTaskInBuildFile ───────────────────────────────────────────────

    @Nested
    @DisplayName("findTestTaskInBuildFile")
    class FindTestTaskInBuildFile {

        @Test
        @DisplayName("returns null for empty content")
        void emptyContent() {
            assertNull(RunTestsTool.findTestTaskInBuildFile(""));
        }

        @Test
        @DisplayName("returns null when only standard 'test' task is present")
        void standardTestTask() {
            assertNull(RunTestsTool.findTestTaskInBuildFile(
                "tasks.register(\"test\", Test::class)"));
        }

        @Test
        @DisplayName("detects Kotlin DSL register(\"name\", Test::class)")
        void kotlinDslRegister() {
            assertEquals("unitTest", RunTestsTool.findTestTaskInBuildFile(
                "tasks.register(\"unitTest\", Test::class)"));
        }

        @Test
        @DisplayName("detects Kotlin DSL register<Test>(\"name\")")
        void kotlinDslRegisterGeneric() {
            assertEquals("unitTest", RunTestsTool.findTestTaskInBuildFile(
                "tasks.register<Test>(\"unitTest\")"));
        }

        @Test
        @DisplayName("detects Kotlin DSL val name by tasks.registering(Test...)")
        void kotlinDslRegistering() {
            assertEquals("unitTest", RunTestsTool.findTestTaskInBuildFile(
                "val unitTest by tasks.registering(Test::class)"));
        }

        @Test
        @DisplayName("detects Groovy DSL task name(type: Test)")
        void groovyDslTaskType() {
            assertEquals("unitTest", RunTestsTool.findTestTaskInBuildFile(
                "task unitTest(type: Test)"));
        }

        @Test
        @DisplayName("detects Groovy DSL tasks.register('name', Test)")
        void groovyDslRegister() {
            assertEquals("unitTest", RunTestsTool.findTestTaskInBuildFile(
                "tasks.register('unitTest', Test)"));
        }
    }

    // ── parseTestsFilterFromCommand ───────────────────────────────────────────

    @Nested
    @DisplayName("parseTestsFilterFromCommand")
    class ParseTestsFilterFromCommand {

        @Test
        @DisplayName("extracts --tests argument from Gradle command")
        void gradleTests() {
            assertEquals("com.example.MyTest",
                RunTestsTool.parseTestsFilterFromCommand("./gradlew test --tests com.example.MyTest"));
        }

        @Test
        @DisplayName("extracts --tests with quoted argument")
        void gradleTestsQuoted() {
            assertEquals("com.example.MyTest",
                RunTestsTool.parseTestsFilterFromCommand("./gradlew test --tests \"com.example.MyTest\""));
        }

        @Test
        @DisplayName("extracts -Dtest argument from Maven command")
        void mavenTests() {
            assertEquals("MyTest",
                RunTestsTool.parseTestsFilterFromCommand("mvn test -Dtest=MyTest"));
        }

        @Test
        @DisplayName("returns null when no test filter present")
        void noFilter() {
            assertNull(RunTestsTool.parseTestsFilterFromCommand("./gradlew test"));
        }
    }

    // ── parseModuleFromCommand ────────────────────────────────────────────────

    @Nested
    @DisplayName("parseModuleFromCommand")
    class ParseModuleFromCommand {

        @Test
        @DisplayName("extracts module from :module:task notation")
        void moduleAndTask() {
            assertEquals("plugin-core",
                RunTestsTool.parseModuleFromCommand("./gradlew :plugin-core:test"));
        }

        @Test
        @DisplayName("returns empty string for no module prefix")
        void noModule() {
            assertEquals("",
                RunTestsTool.parseModuleFromCommand("./gradlew test"));
        }

        @Test
        @DisplayName("returns empty string for bare task name")
        void bareTask() {
            assertEquals("",
                RunTestsTool.parseModuleFromCommand("./gradlew unitTest --tests Foo"));
        }
    }

    // ── parseTaskFromCommand ──────────────────────────────────────────────────

    @Nested
    @DisplayName("parseTaskFromCommand")
    class ParseTaskFromCommand {

        @Test
        @DisplayName("extracts task name from gradlew command")
        void gradlewTask() {
            assertEquals("unitTest",
                RunTestsTool.parseTaskFromCommand("./gradlew unitTest"));
        }

        @Test
        @DisplayName("extracts task name with module prefix")
        void taskWithModule() {
            assertEquals("unitTest",
                RunTestsTool.parseTaskFromCommand("./gradlew :plugin-core:unitTest"));
        }

        @Test
        @DisplayName("extracts task name with following flags")
        void taskWithFlags() {
            assertEquals("unitTest",
                RunTestsTool.parseTaskFromCommand("./gradlew unitTest --tests com.example.Foo"));
        }

        @Test
        @DisplayName("returns null for non-Gradle command")
        void nonGradleCommand() {
            assertNull(RunTestsTool.parseTaskFromCommand("mvn test"));
        }
    }
}
