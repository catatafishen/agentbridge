package com.github.catatafishen.agentbridge.psi.tools.quality;

import org.jdom.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the static helper methods extracted from {@link RunInspectionsTool}.
 *
 * <p>Pure unit tests — no IntelliJ platform context required. Uses package-private
 * access to reach {@link RunInspectionsTool.InspectionContext} and the static methods.
 */
class RunInspectionsToolTest {

    // ── formatInspectionPage ────────────────────────────────────────────────

    @Test
    void formatInspectionPage_emptyProblems() {
        String result = RunInspectionsTool.formatInspectionPage(
            List.of(), 0, "Default", 0, 100);
        assertEquals("No inspection problems found (cached result).", result);
    }

    @Test
    void formatInspectionPage_singlePage() {
        List<String> problems = List.of("problem1", "problem2", "problem3");
        String result = RunInspectionsTool.formatInspectionPage(
            problems, 2, "MyProfile", 0, 10);

        assertTrue(result.contains("Found 3 total problems across 2 files (profile: MyProfile)."));
        assertTrue(result.contains("Showing 1-3 of 3."));
        assertFalse(result.contains("WARNING"));
        assertTrue(result.contains("problem1"));
        assertTrue(result.contains("problem2"));
        assertTrue(result.contains("problem3"));
    }

    @Test
    void formatInspectionPage_paginatedFirstPage() {
        List<String> problems = List.of("p1", "p2", "p3", "p4", "p5");
        String result = RunInspectionsTool.formatInspectionPage(
            problems, 3, "Default", 0, 2);

        assertTrue(result.contains("Showing 1-2 of 5."));
        assertTrue(result.contains("WARNING: 3 more problems not shown!"));
        assertTrue(result.contains("offset=2"));
        assertTrue(result.contains("p1"));
        assertTrue(result.contains("p2"));
        assertFalse(result.contains("p3"));
    }

    @Test
    void formatInspectionPage_paginatedSecondPage() {
        List<String> problems = List.of("p1", "p2", "p3", "p4", "p5");
        String result = RunInspectionsTool.formatInspectionPage(
            problems, 3, "Default", 2, 2);

        assertTrue(result.contains("Showing 3-4 of 5."));
        assertTrue(result.contains("WARNING: 1 more problems not shown!"));
        assertTrue(result.contains("offset=4"));
        assertFalse(result.contains("p1"));
        assertFalse(result.contains("p2"));
        assertTrue(result.contains("p3"));
        assertTrue(result.contains("p4"));
        assertFalse(result.contains("p5"));
    }

    @Test
    void formatInspectionPage_offsetBeyondTotal() {
        List<String> problems = List.of("p1", "p2");
        String result = RunInspectionsTool.formatInspectionPage(
            problems, 1, "Default", 10, 5);

        // effectiveOffset = min(10, 2) = 2, end = min(2+5,2) = 2 → empty page
        assertTrue(result.contains("Showing 3-2 of 2."));
        assertFalse(result.contains("p1"));
        assertFalse(result.contains("p2"));
    }

    @Test
    void formatInspectionPage_offsetEqualTotal() {
        List<String> problems = List.of("p1", "p2", "p3");
        String result = RunInspectionsTool.formatInspectionPage(
            problems, 1, "Default", 3, 5);

        // effectiveOffset = min(3, 3) = 3, end = min(3+5,3) = 3 → empty page
        assertTrue(result.contains("Showing 4-3 of 3."));
        assertFalse(result.contains("p1"));
    }

    // ── formatExportedElement ───────────────────────────────────────────────

    @Test
    void formatExportedElement_noDescription() {
        Element root = new Element("problem");
        root.addContent(new Element("file").setText("file:///some/path.java"));

        String result = RunInspectionsTool.formatExportedElement(
            root, "MyInspection", "/base", new HashSet<>());
        assertNull(result);
    }

    @Test
    void formatExportedElement_emptyDescription() {
        Element root = new Element("problem");
        root.addContent(new Element("description").setText(""));
        root.addContent(new Element("file").setText("file:///some/path.java"));

        String result = RunInspectionsTool.formatExportedElement(
            root, "MyInspection", "/base", new HashSet<>());
        assertNull(result);
    }

    @Test
    void formatExportedElement_noFile() {
        Element root = new Element("problem");
        root.addContent(new Element("description").setText("Some issue"));

        String result = RunInspectionsTool.formatExportedElement(
            root, "MyInspection", "/base", new HashSet<>());
        assertEquals("", result);
    }

    @Test
    void formatExportedElement_projectDirUrl() {
        Element root = new Element("problem");
        root.addContent(new Element("description").setText("Unused variable"));
        root.addContent(new Element("file").setText("file://$PROJECT_DIR$/src/Main.java"));
        root.addContent(new Element("line").setText("10"));
        addProblemClass(root, "WARNING");

        Set<String> filesSet = new HashSet<>();
        String result = RunInspectionsTool.formatExportedElement(
            root, "UnusedVar", "/project", filesSet);

        assertEquals("src/Main.java:10 [WARNING/UnusedVar] Unused variable", result);
        assertTrue(filesSet.contains("src/Main.java"));
    }

    @Test
    void formatExportedElement_fileProtocolUrl() {
        Element root = new Element("problem");
        root.addContent(new Element("description").setText("Issue here"));
        root.addContent(new Element("file").setText("file:///project/src/Foo.java"));
        root.addContent(new Element("line").setText("5"));
        addProblemClass(root, "ERROR");

        Set<String> filesSet = new HashSet<>();
        String result = RunInspectionsTool.formatExportedElement(
            root, "SomeCheck", "/project", filesSet);

        // file:// stripped → /project/src/Foo.java, relativized with basePath /project → src/Foo.java
        assertEquals("src/Foo.java:5 [ERROR/SomeCheck] Issue here", result);
        assertTrue(filesSet.contains("src/Foo.java"));
    }

    @Test
    void formatExportedElement_fileProtocolUrlNoBasePath() {
        Element root = new Element("problem");
        root.addContent(new Element("description").setText("Issue"));
        root.addContent(new Element("file").setText("file:///absolute/path/File.java"));
        root.addContent(new Element("line").setText("1"));
        addProblemClass(root, "WARNING");

        Set<String> filesSet = new HashSet<>();
        String result = RunInspectionsTool.formatExportedElement(
            root, "Check", null, filesSet);

        // No basePath → no relativization, just strip file://
        assertEquals("/absolute/path/File.java:1 [WARNING/Check] Issue", result);
        assertTrue(filesSet.contains("/absolute/path/File.java"));
    }

    @Test
    void formatExportedElement_lineNumber() {
        Element root = new Element("problem");
        root.addContent(new Element("description").setText("Bug"));
        root.addContent(new Element("file").setText("file://$PROJECT_DIR$/A.java"));
        root.addContent(new Element("line").setText("42"));
        addProblemClass(root, "ERROR");

        String result = RunInspectionsTool.formatExportedElement(
            root, "BugCheck", "/p", new HashSet<>());
        assertTrue(result.contains("A.java:42 "));
    }

    @Test
    void formatExportedElement_invalidLineNumber() {
        Element root = new Element("problem");
        root.addContent(new Element("description").setText("Bug"));
        root.addContent(new Element("file").setText("file://$PROJECT_DIR$/A.java"));
        root.addContent(new Element("line").setText("not_a_number"));
        addProblemClass(root, "ERROR");

        String result = RunInspectionsTool.formatExportedElement(
            root, "BugCheck", "/p", new HashSet<>());
        assertTrue(result.contains("A.java:0 "));
    }

    @Test
    void formatExportedElement_noLineElement() {
        Element root = new Element("problem");
        root.addContent(new Element("description").setText("Bug"));
        root.addContent(new Element("file").setText("file://$PROJECT_DIR$/A.java"));
        addProblemClass(root, "ERROR");

        String result = RunInspectionsTool.formatExportedElement(
            root, "BugCheck", "/p", new HashSet<>());
        assertTrue(result.contains("A.java:0 "));
    }

    @Test
    void formatExportedElement_htmlStripping() {
        Element root = new Element("problem");
        root.addContent(new Element("description")
            .setText("Use &lt;String&gt; instead of <code>Object</code> &amp; fix"));
        root.addContent(new Element("file").setText("file://$PROJECT_DIR$/X.java"));
        root.addContent(new Element("line").setText("1"));
        addProblemClass(root, "WARNING");

        String result = RunInspectionsTool.formatExportedElement(
            root, "T", "/p", new HashSet<>());
        // HTML tags stripped, entities decoded
        assertTrue(result.contains("Use <String> instead of Object & fix"));
    }

    @Test
    void formatExportedElement_addsToFilesSet() {
        Element root = new Element("problem");
        root.addContent(new Element("description").setText("Issue"));
        root.addContent(new Element("file").setText("file://$PROJECT_DIR$/src/Bar.java"));
        root.addContent(new Element("line").setText("1"));

        Set<String> filesSet = new HashSet<>();
        RunInspectionsTool.formatExportedElement(root, "X", "/p", filesSet);

        assertEquals(1, filesSet.size());
        assertTrue(filesSet.contains("src/Bar.java"));
    }

    @Test
    void formatExportedElement_severityFromProblemClass() {
        Element root = new Element("problem");
        root.addContent(new Element("description").setText("Issue"));
        root.addContent(new Element("file").setText("file://$PROJECT_DIR$/F.java"));
        root.addContent(new Element("line").setText("7"));
        addProblemClass(root, "WEAK_WARNING");

        String result = RunInspectionsTool.formatExportedElement(
            root, "Chk", "/p", new HashSet<>());
        assertTrue(result.contains("[WEAK_WARNING/Chk]"));
    }

    // ── extractSeverityFromElement ──────────────────────────────────────────

    @Test
    void extractSeverityFromElement_hasSeverityAttribute() {
        Element root = new Element("problem");
        addProblemClass(root, "ERROR");

        assertEquals("ERROR", RunInspectionsTool.extractSeverityFromElement(root));
    }

    @Test
    void extractSeverityFromElement_noProblemClass() {
        Element root = new Element("problem");

        assertEquals("WARNING", RunInspectionsTool.extractSeverityFromElement(root));
    }

    @Test
    void extractSeverityFromElement_noSeverityAttribute() {
        Element root = new Element("problem");
        root.addContent(new Element("problem_class"));

        assertEquals("WARNING", RunInspectionsTool.extractSeverityFromElement(root));
    }

    // ── shouldFilterBySeverity ──────────────────────────────────────────────

    @ParameterizedTest(name = "severity={0} requiredRank={1} → filtered={2}")
    @CsvSource({
        "INFORMATION, 0, false",
        "ERROR, 3, false",
        "INFORMATION, 3, true",
        "WARNING, 3, false",
        "CUSTOM_UNKNOWN, 1, true",
    })
    void shouldFilterBySeverity(String severity, int requiredRank, boolean expectedFiltered) {
        Element root = new Element("problem");
        addProblemClass(root, severity);

        RunInspectionsTool.InspectionContext ctx = makeContext(requiredRank);
        assertEquals(expectedFiltered, RunInspectionsTool.shouldFilterBySeverity(root, ctx));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static void addProblemClass(Element root, String severity) {
        Element problemClass = new Element("problem_class");
        problemClass.setAttribute("severity", severity);
        root.addContent(problemClass);
    }

    private static RunInspectionsTool.InspectionContext makeContext(int requiredRank) {
        Map<String, Integer> severityRank = new HashMap<>();
        severityRank.put("ERROR", 4);
        severityRank.put("WARNING", 3);
        severityRank.put("WEAK_WARNING", 2);
        severityRank.put("LIKE_UNUSED_SYMBOL", 2);
        severityRank.put("INFORMATION", 1);
        severityRank.put("INFO", 1);
        severityRank.put("TEXT_ATTRIBUTES", 0);
        severityRank.put("GENERIC_SERVER_ERROR_OR_WARNING", 3);
        return new RunInspectionsTool.InspectionContext(
            "/project", new HashSet<>(), severityRank, requiredRank);
    }
}
