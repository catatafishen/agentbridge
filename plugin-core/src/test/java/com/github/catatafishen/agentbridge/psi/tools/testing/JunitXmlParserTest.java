package com.github.catatafishen.agentbridge.psi.tools.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JunitXmlParser}.
 *
 * <p>{@code JunitXmlParser} is a package-private utility class with all static methods
 * and no IntelliJ dependencies. Tests run as plain JUnit 5 without platform test case.
 */
class JunitXmlParserTest {

    // ── parseTestTarget ──────────────────────────────────────────────────────

    @Test
    void parseTestTarget_fullyQualifiedClassAndMethod() {
        String[] result = JunitXmlParser.parseTestTarget("com.example.Foo.testMethod");
        assertEquals("com.example.Foo", result[0], "class FQN");
        assertEquals("testMethod", result[1], "method name");
    }

    @Test
    void parseTestTarget_classOnlyNoMethod() {
        String[] result = JunitXmlParser.parseTestTarget("com.example.Foo");
        assertEquals("com.example.Foo", result[0], "class FQN when last segment starts uppercase");
        assertNull(result[1], "method should be null when last segment is uppercase");
    }

    @Test
    void parseTestTarget_simpleClassName() {
        String[] result = JunitXmlParser.parseTestTarget("Foo");
        assertEquals("Foo", result[0], "simple class name");
        assertNull(result[1], "no method for simple name");
    }

    @Test
    void parseTestTarget_classWithLowercaseMethod() {
        String[] result = JunitXmlParser.parseTestTarget("Foo.testBar");
        assertEquals("Foo", result[0]);
        assertEquals("testBar", result[1]);
    }

    @Test
    void parseTestTarget_deeplyNestedPackage() {
        String[] result = JunitXmlParser.parseTestTarget("com.a.b.c.MyTest.shouldWork");
        assertEquals("com.a.b.c.MyTest", result[0]);
        assertEquals("shouldWork", result[1]);
    }

    @Test
    void parseTestTarget_lastSegmentUppercase_treatedAsClass() {
        // "com.example.FooBar" — last segment FooBar starts with uppercase → no method
        String[] result = JunitXmlParser.parseTestTarget("com.example.FooBar");
        assertEquals("com.example.FooBar", result[0]);
        assertNull(result[1]);
    }

    @Test
    void parseTestTarget_singleCharMethod() {
        // "com.Foo.x" — 'x' is lowercase so treated as method
        String[] result = JunitXmlParser.parseTestTarget("com.Foo.x");
        assertEquals("com.Foo", result[0]);
        assertEquals("x", result[1]);
    }

    // ── parseTestSuiteXml ────────────────────────────────────────────────────

    @Test
    void parseTestSuiteXml_validXmlWithFailure(@TempDir Path tempDir) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="TestSuite" tests="3" failures="1" errors="0" skipped="0" time="1.23">
                  <testcase classname="com.Foo" name="test1" time="0.5"/>
                  <testcase classname="com.Foo" name="test2" time="0.3">
                    <failure message="expected true">AssertionError: expected true</failure>
                  </testcase>
                  <testcase classname="com.Foo" name="test3" time="0.4"/>
                </testsuite>
                """;
        Path xmlFile = tempDir.resolve("TEST-suite.xml");
        Files.writeString(xmlFile, xml);

        JunitXmlParser.TestSuiteResult result = JunitXmlParser.parseTestSuiteXml(xmlFile);

        assertNotNull(result);
        assertEquals(3, result.tests());
        assertEquals(1, result.failed());
        assertEquals(0, result.errors());
        assertEquals(0, result.skipped());
        assertEquals(1.23, result.time(), 0.01);
        assertEquals(1, result.failures().size());
        assertTrue(result.failures().get(0).contains("com.Foo.test2"));
        assertTrue(result.failures().get(0).contains("expected true"));
    }

    @Test
    void parseTestSuiteXml_allPassingTests(@TempDir Path tempDir) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="AllPass" tests="2" failures="0" errors="0" skipped="0" time="0.5">
                  <testcase classname="com.Bar" name="testA" time="0.2"/>
                  <testcase classname="com.Bar" name="testB" time="0.3"/>
                </testsuite>
                """;
        Path xmlFile = tempDir.resolve("TEST-allpass.xml");
        Files.writeString(xmlFile, xml);

        JunitXmlParser.TestSuiteResult result = JunitXmlParser.parseTestSuiteXml(xmlFile);

        assertNotNull(result);
        assertEquals(2, result.tests());
        assertEquals(0, result.failed());
        assertEquals(0, result.errors());
        assertEquals(0, result.skipped());
        assertTrue(result.failures().isEmpty());
    }

    @Test
    void parseTestSuiteXml_withErrorsAndSkipped(@TempDir Path tempDir) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="Mixed" tests="5" failures="1" errors="2" skipped="1" time="3.0">
                  <testcase classname="com.Mix" name="pass1" time="0.1"/>
                  <testcase classname="com.Mix" name="fail1" time="0.2">
                    <failure message="oops">fail detail</failure>
                  </testcase>
                  <testcase classname="com.Mix" name="err1" time="0.3"/>
                  <testcase classname="com.Mix" name="err2" time="0.4"/>
                  <testcase classname="com.Mix" name="skip1" time="0.0"/>
                </testsuite>
                """;
        Path xmlFile = tempDir.resolve("TEST-mixed.xml");
        Files.writeString(xmlFile, xml);

        JunitXmlParser.TestSuiteResult result = JunitXmlParser.parseTestSuiteXml(xmlFile);

        assertNotNull(result);
        assertEquals(5, result.tests());
        assertEquals(1, result.failed());
        assertEquals(2, result.errors());
        assertEquals(1, result.skipped());
        assertEquals(3.0, result.time(), 0.01);
    }

    @Test
    void parseTestSuiteXml_missingAttributes(@TempDir Path tempDir) throws IOException {
        // tests attribute present, but failures/errors/skipped/time missing
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="Minimal" tests="1">
                  <testcase classname="com.Min" name="t1"/>
                </testsuite>
                """;
        Path xmlFile = tempDir.resolve("TEST-minimal.xml");
        Files.writeString(xmlFile, xml);

        JunitXmlParser.TestSuiteResult result = JunitXmlParser.parseTestSuiteXml(xmlFile);

        assertNotNull(result);
        assertEquals(1, result.tests());
        assertEquals(0, result.failed());
        assertEquals(0, result.errors());
        assertEquals(0, result.skipped());
        assertEquals(0.0, result.time(), 0.001);
    }

    @Test
    void parseTestSuiteXml_invalidXmlReturnsNull(@TempDir Path tempDir) throws IOException {
        Path xmlFile = tempDir.resolve("TEST-bad.xml");
        Files.writeString(xmlFile, "this is not xml at all");

        JunitXmlParser.TestSuiteResult result = JunitXmlParser.parseTestSuiteXml(xmlFile);

        assertNull(result, "Invalid XML should return null");
    }

    @Test
    void parseTestSuiteXml_nonExistentFileReturnsNull(@TempDir Path tempDir) {
        Path nonExistent = tempDir.resolve("does-not-exist.xml");

        JunitXmlParser.TestSuiteResult result = JunitXmlParser.parseTestSuiteXml(nonExistent);

        assertNull(result, "Non-existent file should return null");
    }

    @Test
    void parseTestSuiteXml_multipleFailures(@TempDir Path tempDir) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="Multi" tests="3" failures="2" errors="0" skipped="0" time="1.0">
                  <testcase classname="com.Multi" name="failA" time="0.1">
                    <failure message="msg A">detail A</failure>
                  </testcase>
                  <testcase classname="com.Multi" name="failB" time="0.2">
                    <failure message="msg B">detail B</failure>
                  </testcase>
                  <testcase classname="com.Multi" name="pass1" time="0.7"/>
                </testsuite>
                """;
        Path xmlFile = tempDir.resolve("TEST-multi.xml");
        Files.writeString(xmlFile, xml);

        JunitXmlParser.TestSuiteResult result = JunitXmlParser.parseTestSuiteXml(xmlFile);

        assertNotNull(result);
        assertEquals(2, result.failures().size());
        assertTrue(result.failures().get(0).contains("com.Multi.failA"));
        assertTrue(result.failures().get(0).contains("msg A"));
        assertTrue(result.failures().get(1).contains("com.Multi.failB"));
        assertTrue(result.failures().get(1).contains("msg B"));
    }

    // ── collectFailureDetails ────────────────────────────────────────────────

    @Test
    void collectFailureDetails_extractsFailureFromTestcase() throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element suite = doc.createElement("testsuite");
        doc.appendChild(suite);

        Element tc = doc.createElement("testcase");
        tc.setAttribute("classname", "com.example.MyTest");
        tc.setAttribute("name", "shouldFail");
        suite.appendChild(tc);

        Element failure = doc.createElement("failure");
        failure.setAttribute("message", "assertion failed");
        failure.setTextContent("java.lang.AssertionError");
        tc.appendChild(failure);

        List<String> failures = new ArrayList<>();
        JunitXmlParser.collectFailureDetails(suite, failures);

        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("com.example.MyTest.shouldFail"));
        assertTrue(failures.get(0).contains("assertion failed"));
    }

    @Test
    void collectFailureDetails_noFailuresProducesEmptyList() throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element suite = doc.createElement("testsuite");
        doc.appendChild(suite);

        Element tc = doc.createElement("testcase");
        tc.setAttribute("classname", "com.example.Pass");
        tc.setAttribute("name", "shouldPass");
        suite.appendChild(tc);

        List<String> failures = new ArrayList<>();
        JunitXmlParser.collectFailureDetails(suite, failures);

        assertTrue(failures.isEmpty(), "No failures should produce empty list");
    }

    @Test
    void collectFailureDetails_multipleTestcasesWithMixedResults() throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element suite = doc.createElement("testsuite");
        doc.appendChild(suite);

        // Passing test
        Element tcPass = doc.createElement("testcase");
        tcPass.setAttribute("classname", "com.ex.T");
        tcPass.setAttribute("name", "pass1");
        suite.appendChild(tcPass);

        // Failing test
        Element tcFail = doc.createElement("testcase");
        tcFail.setAttribute("classname", "com.ex.T");
        tcFail.setAttribute("name", "fail1");
        suite.appendChild(tcFail);
        Element fail = doc.createElement("failure");
        fail.setAttribute("message", "expected 42");
        tcFail.appendChild(fail);

        // Another passing test
        Element tcPass2 = doc.createElement("testcase");
        tcPass2.setAttribute("classname", "com.ex.T");
        tcPass2.setAttribute("name", "pass2");
        suite.appendChild(tcPass2);

        List<String> failures = new ArrayList<>();
        JunitXmlParser.collectFailureDetails(suite, failures);

        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("com.ex.T.fail1"));
        assertTrue(failures.get(0).contains("expected 42"));
    }

    // ── formatTestResults ────────────────────────────────────────────────────

    @Test
    void formatTestResults_allPassing() {
        String result = JunitXmlParser.formatTestResults(5, 0, 0, 0, 1.5, List.of());

        assertTrue(result.contains("5 tests"));
        assertTrue(result.contains("5 passed"));
        assertTrue(result.contains("0 failed"));
        assertTrue(result.contains("0 errors"));
        assertTrue(result.contains("0 skipped"));
        assertTrue(result.contains("1.5s"));
        assertFalse(result.contains("Failures:"), "No failures section when all pass");
    }

    @Test
    void formatTestResults_withFailures() {
        List<String> failures = List.of(
                "  com.Foo.testA: expected true",
                "  com.Foo.testB: index out of bounds"
        );
        String result = JunitXmlParser.formatTestResults(5, 2, 0, 0, 2.0, failures);

        assertTrue(result.contains("5 tests"));
        assertTrue(result.contains("3 passed"));
        assertTrue(result.contains("2 failed"));
        assertTrue(result.contains("Failures:"));
        assertTrue(result.contains("com.Foo.testA: expected true"));
        assertTrue(result.contains("com.Foo.testB: index out of bounds"));
    }

    @Test
    void formatTestResults_withErrors() {
        String result = JunitXmlParser.formatTestResults(4, 0, 2, 0, 0.8, List.of());

        assertTrue(result.contains("4 tests"));
        assertTrue(result.contains("2 passed"));
        assertTrue(result.contains("2 errors"));
    }

    @Test
    void formatTestResults_withSkipped() {
        String result = JunitXmlParser.formatTestResults(10, 1, 1, 3, 5.0, List.of());

        assertTrue(result.contains("10 tests"));
        assertTrue(result.contains("5 passed")); // 10 - 1 - 1 - 3 = 5
        assertTrue(result.contains("1 failed"));
        assertTrue(result.contains("1 errors"));
        assertTrue(result.contains("3 skipped"));
    }

    @Test
    void formatTestResults_zeroTests() {
        String result = JunitXmlParser.formatTestResults(0, 0, 0, 0, 0.0, List.of());

        assertTrue(result.contains("0 tests"));
        assertTrue(result.contains("0 passed"));
    }

    @Test
    void formatTestResults_timeFormattedCorrectly() {
        String result = JunitXmlParser.formatTestResults(1, 0, 0, 0, 12.345, List.of());

        // Time should be formatted to 1 decimal place
        assertTrue(result.contains("12.3s"));
    }

    // ── findTestReportDirs ───────────────────────────────────────────────────

    @Test
    void findTestReportDirs_withModule(@TempDir Path tempDir) throws IOException {
        // Create: <tempDir>/mymodule/build/test-results/test/
        Path reportDir = tempDir.resolve("mymodule/build/test-results/test");
        Files.createDirectories(reportDir);

        List<Path> dirs = JunitXmlParser.findTestReportDirs(tempDir.toString(), "mymodule");

        assertEquals(1, dirs.size());
        assertEquals(reportDir, dirs.get(0));
    }

    @Test
    void findTestReportDirs_withModuleDirDoesNotExist(@TempDir Path tempDir) {
        List<Path> dirs = JunitXmlParser.findTestReportDirs(tempDir.toString(), "nonexistent");

        assertTrue(dirs.isEmpty(), "Should return empty list when module dir doesn't exist");
    }

    @Test
    void findTestReportDirs_emptyModuleScansAll(@TempDir Path tempDir) throws IOException {
        // Create two report dirs at different levels
        Path dir1 = tempDir.resolve("mod1/build/test-results/test");
        Path dir2 = tempDir.resolve("mod2/build/test-results/test");
        Files.createDirectories(dir1);
        Files.createDirectories(dir2);

        List<Path> dirs = JunitXmlParser.findTestReportDirs(tempDir.toString(), "");

        assertEquals(2, dirs.size());
    }

    @Test
    void findTestReportDirs_emptyModuleNoMatchingDirs(@TempDir Path tempDir) throws IOException {
        // Create dirs that don't match the pattern
        Files.createDirectories(tempDir.resolve("some/other/dir"));

        List<Path> dirs = JunitXmlParser.findTestReportDirs(tempDir.toString(), "");

        assertTrue(dirs.isEmpty());
    }

    @Test
    void findTestReportDirs_nonExistentBasePath() {
        List<Path> dirs = JunitXmlParser.findTestReportDirs("/nonexistent/path/xyz", "");

        assertTrue(dirs.isEmpty(), "Non-existent base path should return empty list");
    }

    // ── parseJunitXmlResults ─────────────────────────────────────────────────

    @Test
    void parseJunitXmlResults_aggregatesMultipleFiles(@TempDir Path tempDir) throws IOException {
        Path reportDir = tempDir.resolve("mod/build/test-results/test");
        Files.createDirectories(reportDir);

        String xml1 = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="Suite1" tests="2" failures="0" errors="0" skipped="0" time="1.0">
                  <testcase classname="com.A" name="t1" time="0.5"/>
                  <testcase classname="com.A" name="t2" time="0.5"/>
                </testsuite>
                """;
        String xml2 = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="Suite2" tests="3" failures="1" errors="0" skipped="0" time="2.0">
                  <testcase classname="com.B" name="t1" time="0.5"/>
                  <testcase classname="com.B" name="t2" time="1.0">
                    <failure message="wrong">AssertionError</failure>
                  </testcase>
                  <testcase classname="com.B" name="t3" time="0.5"/>
                </testsuite>
                """;
        Files.writeString(reportDir.resolve("TEST-Suite1.xml"), xml1);
        Files.writeString(reportDir.resolve("TEST-Suite2.xml"), xml2);

        String result = JunitXmlParser.parseJunitXmlResults(tempDir.toString(), "mod");

        assertTrue(result.contains("5 tests"));
        assertTrue(result.contains("4 passed"));
        assertTrue(result.contains("1 failed"));
        assertTrue(result.contains("Failures:"));
        assertTrue(result.contains("com.B.t2"));
    }

    @Test
    void parseJunitXmlResults_noReportDirsReturnsEmpty(@TempDir Path tempDir) {
        String result = JunitXmlParser.parseJunitXmlResults(tempDir.toString(), "nope");

        assertEquals("", result, "No report dirs should return empty string");
    }

    @Test
    void parseJunitXmlResults_emptyReportDirReturnsEmpty(@TempDir Path tempDir) throws IOException {
        // Create the report dir but put no XML files in it
        Path reportDir = tempDir.resolve("mod/build/test-results/test");
        Files.createDirectories(reportDir);

        String result = JunitXmlParser.parseJunitXmlResults(tempDir.toString(), "mod");

        assertEquals("", result, "Empty report dir should return empty string");
    }

    @Test
    void parseJunitXmlResults_ignoresNonXmlFiles(@TempDir Path tempDir) throws IOException {
        Path reportDir = tempDir.resolve("mod/build/test-results/test");
        Files.createDirectories(reportDir);

        Files.writeString(reportDir.resolve("not-xml.txt"), "hello");
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="S" tests="1" failures="0" errors="0" skipped="0" time="0.1">
                  <testcase classname="com.C" name="t1" time="0.1"/>
                </testsuite>
                """;
        Files.writeString(reportDir.resolve("TEST-S.xml"), xml);

        String result = JunitXmlParser.parseJunitXmlResults(tempDir.toString(), "mod");

        assertTrue(result.contains("1 tests"));
        assertTrue(result.contains("1 passed"));
    }

    @Test
    void parseJunitXmlResults_skipsInvalidXmlFiles(@TempDir Path tempDir) throws IOException {
        Path reportDir = tempDir.resolve("mod/build/test-results/test");
        Files.createDirectories(reportDir);

        Files.writeString(reportDir.resolve("TEST-bad.xml"), "not valid xml");
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="Good" tests="2" failures="0" errors="0" skipped="0" time="0.5">
                  <testcase classname="com.D" name="t1" time="0.25"/>
                  <testcase classname="com.D" name="t2" time="0.25"/>
                </testsuite>
                """;
        Files.writeString(reportDir.resolve("TEST-good.xml"), xml);

        String result = JunitXmlParser.parseJunitXmlResults(tempDir.toString(), "mod");

        // Should still parse the good file
        assertTrue(result.contains("2 tests"));
        assertTrue(result.contains("2 passed"));
    }

    @Test
    void parseJunitXmlResults_emptyModuleScansAllModules(@TempDir Path tempDir) throws IOException {
        Path dir1 = tempDir.resolve("a/build/test-results/test");
        Path dir2 = tempDir.resolve("b/build/test-results/test");
        Files.createDirectories(dir1);
        Files.createDirectories(dir2);

        String xml1 = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="A" tests="1" failures="0" errors="0" skipped="0" time="0.1">
                  <testcase classname="com.A" name="t1" time="0.1"/>
                </testsuite>
                """;
        String xml2 = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="B" tests="1" failures="0" errors="0" skipped="0" time="0.2">
                  <testcase classname="com.B" name="t1" time="0.2"/>
                </testsuite>
                """;
        Files.writeString(dir1.resolve("TEST-A.xml"), xml1);
        Files.writeString(dir2.resolve("TEST-B.xml"), xml2);

        String result = JunitXmlParser.parseJunitXmlResults(tempDir.toString(), "");

        assertTrue(result.contains("2 tests"));
        assertTrue(result.contains("2 passed"));
    }

    // ── intAttr / doubleAttr ─────────────────────────────────────────────────

    @Test
    void intAttr_presentAttribute() throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element el = doc.createElement("suite");
        el.setAttribute("tests", "42");

        assertEquals(42, JunitXmlParser.intAttr(el, "tests"));
    }

    @Test
    void intAttr_missingAttributeReturnsZero() throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element el = doc.createElement("suite");

        assertEquals(0, JunitXmlParser.intAttr(el, "nonexistent"));
    }

    @Test
    void doubleAttr_presentAttribute() throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element el = doc.createElement("suite");
        el.setAttribute("time", "3.14");

        assertEquals(3.14, JunitXmlParser.doubleAttr(el, "time"), 0.001);
    }

    @Test
    void doubleAttr_missingAttributeReturnsZero() throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element el = doc.createElement("suite");

        assertEquals(0.0, JunitXmlParser.doubleAttr(el, "nonexistent"), 0.001);
    }
}
