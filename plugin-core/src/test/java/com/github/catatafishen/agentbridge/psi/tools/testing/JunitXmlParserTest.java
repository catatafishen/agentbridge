package com.github.catatafishen.agentbridge.psi.tools.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JunitXmlParser}.
 *
 * <p>{@code JunitXmlParser} is package-private with all static methods and no IntelliJ
 * dependencies, so this test class lives in the same package and runs as plain JUnit 5.
 */
class JunitXmlParserTest {

    // ── parseTestTarget ──────────────────────────────────────────────────────

    @Test
    void parseTestTarget_lowercaseLastSegment_isMethod() {
        String[] result = JunitXmlParser.parseTestTarget("com.example.Foo.testBar");
        assertEquals("com.example.Foo", result[0], "class FQN");
        assertEquals("testBar", result[1], "method name");
    }

    @Test
    void parseTestTarget_uppercaseLastSegment_isClass() {
        String[] result = JunitXmlParser.parseTestTarget("com.example.Foo.Bar");
        assertEquals("com.example.Foo.Bar", result[0], "entire string is the class FQN");
        assertNull(result[1], "no method when last segment starts uppercase");
    }

    @Test
    void parseTestTarget_noDot_simpleClass() {
        String[] result = JunitXmlParser.parseTestTarget("SimpleClass");
        assertEquals("SimpleClass", result[0]);
        assertNull(result[1]);
    }

    @Test
    void parseTestTarget_singleLowercaseCharMethod() {
        String[] result = JunitXmlParser.parseTestTarget("com.example.Foo.t");
        assertEquals("com.example.Foo", result[0]);
        assertEquals("t", result[1], "single lowercase char is treated as method");
    }

    // ── parseJunitXmlResults: only this run's reports ────────────────────────

    private static void writeReport(Path dir, String name, int tests, long modifiedMillis) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.writeString(file, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<testsuite name=\"" + name + "\" tests=\"" + tests + "\" skipped=\"0\" failures=\"0\" errors=\"0\" "
            + "time=\"0.1\"></testsuite>");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(modifiedMillis));
    }

    @Test
    void parseJunitXmlResults_ignoresReportsFromBeforeTheRun(@TempDir Path tempDir) throws IOException {
        // A module that was not part of this run keeps the report of an earlier run (#1164).
        writeReport(tempDir.resolve("old-module/build/test-results/test"), "TEST-old.xml", 5, 1_000_000L);
        writeReport(tempDir.resolve("new-module/build/test-results/test"), "TEST-new.xml", 2, 9_000_000L);

        assertTrue(JunitXmlParser.parseJunitXmlResults(tempDir.toString(), "", 5_000_000L)
            .startsWith("Test Results: 2 tests, 2 passed"));
    }

    @Test
    void parseJunitXmlResults_withoutCutoffReadsEveryReport(@TempDir Path tempDir) throws IOException {
        writeReport(tempDir.resolve("a/build/test-results/test"), "TEST-a.xml", 5, 1_000_000L);
        writeReport(tempDir.resolve("b/build/test-results/test"), "TEST-b.xml", 2, 9_000_000L);

        assertTrue(JunitXmlParser.parseJunitXmlResults(tempDir.toString(), "")
            .startsWith("Test Results: 7 tests, 7 passed"));
    }

    @Test
    void parseJunitXmlResults_returnsEmptyWhenAllReportsAreOlderThanTheRun(@TempDir Path tempDir) throws IOException {
        writeReport(tempDir.resolve("a/build/test-results/test"), "TEST-a.xml", 5, 1_000_000L);

        assertEquals("", JunitXmlParser.parseJunitXmlResults(tempDir.toString(), "", 5_000_000L));
    }

    @Test
    void isModifiedSince_zeroCutoffAcceptsAnyFileAndMissingFileIsRejected(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("x.xml");
        Files.writeString(file, "<x/>");
        assertTrue(JunitXmlParser.isModifiedSince(file, 0));
        assertFalse(JunitXmlParser.isModifiedSince(tempDir.resolve("missing.xml"), 1));
    }

    // ── formatTestResults ────────────────────────────────────────────────────

    @Test
    void formatTestResults_allPassed() {
        String output = JunitXmlParser.formatTestResults(10, 0, 0, 0, 1.5, List.of());
        assertTrue(output.contains("10 tests"), "should report total");
        assertTrue(output.contains("10 passed"), "all should pass");
        assertTrue(output.contains("0 failed"));
        assertTrue(output.contains("0 errors"));
        assertTrue(output.contains("0 skipped"));
        assertTrue(output.contains("1.5s"));
        assertFalse(output.contains("Failures:"), "no failure section when all pass");
    }

    @Test
    void formatTestResults_withFailures() {
        List<String> failures = List.of("  com.Foo.test1: expected true");
        String output = JunitXmlParser.formatTestResults(5, 2, 0, 0, 0.8, failures);
        assertTrue(output.contains("5 tests"));
        assertTrue(output.contains("3 passed"), "5 - 2 failures = 3 passed");
        assertTrue(output.contains("2 failed"));
        assertTrue(output.contains("Failures:"), "should include failure section");
        assertTrue(output.contains("com.Foo.test1: expected true"));
    }

    @Test
    void formatTestResults_zeroTests() {
        String output = JunitXmlParser.formatTestResults(0, 0, 0, 0, 0.0, List.of());
        assertTrue(output.contains("0 tests"));
        assertTrue(output.contains("0 passed"));
    }

    // ── parseTestSuiteXml ────────────────────────────────────────────────────

    @Test
    void parseTestSuiteXml_validXml(@TempDir Path tempDir) throws IOException {
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.example.FooTest" tests="3" failures="1" errors="0" skipped="0" time="0.5">
              <testcase name="testPass" classname="com.example.FooTest" time="0.1"/>
              <testcase name="testFail" classname="com.example.FooTest" time="0.2">
                <failure message="expected 1 but was 2">assertion trace</failure>
              </testcase>
              <testcase name="testSkip" classname="com.example.FooTest" time="0.0">
                <skipped/>
              </testcase>
            </testsuite>
            """;
        Path xmlFile = tempDir.resolve("TEST-FooTest.xml");
        Files.writeString(xmlFile, xml);

        JunitXmlParser.TestSuiteResult result = JunitXmlParser.parseTestSuiteXml(xmlFile);

        assertNotNull(result);
        assertEquals(3, result.tests());
        assertEquals(1, result.failed());
        assertEquals(0, result.errors());
        assertEquals(0, result.skipped());
        assertEquals(0.5, result.time(), 0.01);
    }

    @Test
    void parseTestSuiteXml_malformedXml_returnsNull(@TempDir Path tempDir) throws IOException {
        Path xmlFile = tempDir.resolve("TEST-bad.xml");
        Files.writeString(xmlFile, "<not-valid-xml><<<<");

        JunitXmlParser.TestSuiteResult result = JunitXmlParser.parseTestSuiteXml(xmlFile);
        assertNull(result, "malformed XML should return null");
    }

    @Test
    void parseTestSuiteXml_failureDetailsPopulated(@TempDir Path tempDir) throws IOException {
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="Suite" tests="2" failures="2" errors="0" skipped="0" time="1.0">
              <testcase name="test1" classname="com.A" time="0.5">
                <failure message="msg1">trace1</failure>
              </testcase>
              <testcase name="test2" classname="com.B" time="0.5">
                <failure message="msg2">trace2</failure>
              </testcase>
            </testsuite>
            """;
        Path xmlFile = tempDir.resolve("TEST-multi.xml");
        Files.writeString(xmlFile, xml);

        JunitXmlParser.TestSuiteResult result = JunitXmlParser.parseTestSuiteXml(xmlFile);

        assertNotNull(result);
        assertEquals(2, result.failures().size());
        assertTrue(result.failures().get(0).contains("com.A.test1"));
        assertTrue(result.failures().get(1).contains("com.B.test2"));
    }

    @Test
    void parseTestSuiteXml_missingAttributes_defaultsToZero(@TempDir Path tempDir) throws IOException {
        // testsuite with no failures/errors/skipped/time attributes
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="Minimal" tests="1">
              <testcase name="test1" classname="com.X" time="0.1"/>
            </testsuite>
            """;
        Path xmlFile = tempDir.resolve("TEST-minimal.xml");
        Files.writeString(xmlFile, xml);

        JunitXmlParser.TestSuiteResult result = JunitXmlParser.parseTestSuiteXml(xmlFile);

        assertNotNull(result);
        assertEquals(1, result.tests());
        assertEquals(0, result.failed(), "missing failures attr defaults to 0");
        assertEquals(0, result.errors(), "missing errors attr defaults to 0");
        assertEquals(0, result.skipped(), "missing skipped attr defaults to 0");
        assertEquals(0.0, result.time(), 0.001, "missing time attr defaults to 0.0");
    }

    // ── intAttr / doubleAttr ─────────────────────────────────────────────────

    @Test
    void intAttr_presentAttribute_returnsParsedValue() throws Exception {
        Element element = createElementWithAttr("count", "42");
        assertEquals(42, JunitXmlParser.intAttr(element, "count"));
    }

    @Test
    void intAttr_missingAttribute_returnsZero() throws Exception {
        Element element = createElementWithAttr("other", "99");
        assertEquals(0, JunitXmlParser.intAttr(element, "missing"));
    }

    @Test
    void doubleAttr_presentAttribute_returnsParsedValue() throws Exception {
        Element element = createElementWithAttr("time", "3.14");
        assertEquals(3.14, JunitXmlParser.doubleAttr(element, "time"), 0.001);
    }

    @Test
    void doubleAttr_missingAttribute_returnsZero() throws Exception {
        Element element = createElementWithAttr("other", "1.0");
        assertEquals(0.0, JunitXmlParser.doubleAttr(element, "missing"), 0.001);
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    /**
     * Creates a minimal DOM element with a single attribute for testing intAttr/doubleAttr.
     */
    private static Element createElementWithAttr(String attrName, String attrValue) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        Document doc = dbf.newDocumentBuilder().newDocument();
        Element element = doc.createElement("testsuite");
        element.setAttribute(attrName, attrValue);
        return element;
    }
}
