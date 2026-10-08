package com.github.catatafishen.agentbridge.psi.tools.testing;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for parsing JUnit XML test reports and test target strings.
 * All methods are stateless and have no IntelliJ dependencies.
 */
public final class JunitXmlParser {

    private static final String BUILD_DIR = "build";
    private static final String ATTR_MESSAGE = "message";

    private JunitXmlParser() {
        // utility class
    }

    // ── Test target parsing ──────────────────────────────────

    /**
     * Parses a test target string like "com.example.Foo.testMethod" into [classFqn, method].
     * If no method component is detected, the second element is {@code null}.
     */
    static String[] parseTestTarget(String target) {
        String testClass = target;
        String testMethod = null;
        int lastDot = target.lastIndexOf('.');
        if (lastDot > 0) {
            String possibleMethod = target.substring(lastDot + 1);
            String possibleClass = target.substring(0, lastDot);
            if (!possibleMethod.isEmpty() && Character.isLowerCase(possibleMethod.charAt(0))) {
                testClass = possibleClass;
                testMethod = possibleMethod;
            }
        }
        return new String[]{testClass, testMethod};
    }

    // ── JUnit XML result aggregation ─────────────────────────

    /**
     * Aggregates JUnit XML results across all report directories found under {@code basePath}.
     * Returns a formatted summary string, or an empty string if no results were found.
     */
    static String parseJunitXmlResults(String basePath, String module) {
        return parseJunitXmlResults(basePath, module, Map.of());
    }

    /**
     * Like {@link #parseJunitXmlResults(String, String)}, but only reads report files that are new or changed
     * compared to {@code baseline} (see {@link #snapshotReportTimes}). Report directories of modules that were not
     * part of this run keep the files of an earlier run, which must not be presented as the outcome of this one.
     */
    static String parseJunitXmlResults(String basePath, String module, Map<Path, Long> baseline) {
        List<Path> reportDirs = findTestReportDirs(basePath, module);
        if (reportDirs.isEmpty()) return "";

        int totalTests = 0;
        int totalFailed = 0;
        int totalErrors = 0;
        int totalSkipped = 0;
        double totalTime = 0;
        List<String> failures = new ArrayList<>();

        for (Path reportDir : reportDirs) {
            for (Path xmlFile : listXmlFiles(reportDir)) {
                TestSuiteResult result = isChangedSince(xmlFile, baseline) ? parseTestSuiteXml(xmlFile) : null;
                if (result != null) {
                    totalTests += result.tests;
                    totalFailed += result.failed;
                    totalErrors += result.errors;
                    totalSkipped += result.skipped;
                    totalTime += result.time;
                    failures.addAll(result.failures);
                }
            }
        }

        if (totalTests == 0) return "";
        return formatTestResults(totalTests, totalFailed, totalErrors, totalSkipped, totalTime, failures);
    }

    // ── Filesystem helpers ───────────────────────────────────

    /**
     * Records the last-modified time of every report file under {@code basePath}. Taken before a run starts, it is
     * the baseline that lets {@link #parseJunitXmlResults(String, String, Map)} tell this run's reports from the
     * ones an earlier run left behind, without relying on clock comparisons.
     */
    static Map<Path, Long> snapshotReportTimes(String basePath) {
        Map<Path, Long> snapshot = new HashMap<>();
        for (Path reportDir : findTestReportDirs(basePath, "")) {
            for (Path xmlFile : listXmlFiles(reportDir)) {
                snapshot.put(xmlFile, lastModifiedMillis(xmlFile));
            }
        }
        return snapshot;
    }

    /**
     * A report counts as written by this run when it did not exist in the baseline or its modification time
     * differs from the baseline's. A rewrite that leaves the timestamp unchanged is treated as not written, so a
     * coarse file-system clock degrades to "no confirmed results" rather than to stale counts.
     */
    static boolean isChangedSince(Path file, Map<Path, Long> baseline) {
        long now = lastModifiedMillis(file);
        if (now < 0) return false;
        Long before = baseline.get(file);
        return before == null || before != now;
    }

    private static long lastModifiedMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    private static List<Path> listXmlFiles(Path reportDir) {
        try (var xmlFiles = Files.list(reportDir)) {
            return xmlFiles.filter(p -> p.toString().endsWith(".xml")).toList();
        } catch (IOException ignored) {
            // IO errors during directory listing are non-fatal
            return List.of();
        }
    }

    /**
     * Walks the filesystem under {@code basePath} to find directories matching
     * the conventional Gradle test-results layout.
     */
    static List<Path> findTestReportDirs(String basePath, String module) {
        List<Path> reportDirs = new ArrayList<>();
        if (module.isEmpty()) {
            try (var dirs = Files.walk(Path.of(basePath), 4)) {
                dirs.filter(p -> p.endsWith("test-results/test") && Files.isDirectory(p))
                    .forEach(reportDirs::add);
            } catch (IOException ignored) {
                // Directory walk errors are non-fatal
            }
        } else {
            Path dir = Path.of(basePath, module, BUILD_DIR, "test-results", "test");
            if (Files.isDirectory(dir)) reportDirs.add(dir);
        }
        return reportDirs;
    }

    // ── Single XML file parsing ──────────────────────────────

    public record TestSuiteResult(int tests, int failed, int errors, int skipped,
                                  double time, List<String> failures) {
    }

    /**
     * Parses a single JUnit XML test suite report file.
     * Returns {@code null} if parsing fails.
     */
    public static TestSuiteResult parseTestSuiteXml(Path xmlFile) {
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            //noinspection HttpUrlsUsage - XML feature URI, not an actual URL
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var doc = dbf.newDocumentBuilder().parse(xmlFile.toFile());
            var suites = doc.getElementsByTagName("testsuite");

            int tests = 0;
            int failed = 0;
            int errors = 0;
            int skipped = 0;
            double time = 0;
            List<String> failures = new ArrayList<>();

            for (int i = 0; i < suites.getLength(); i++) {
                var suite = suites.item(i);
                tests += intAttr(suite, "tests");
                failed += intAttr(suite, "failures");
                errors += intAttr(suite, "errors");
                skipped += intAttr(suite, "skipped");
                time += doubleAttr(suite, "time");
                collectFailureDetails((Element) suite, failures);
            }
            return new TestSuiteResult(tests, failed, errors, skipped, time, failures);
        } catch (Exception ignored) {
            // XML parsing errors are non-fatal
            return null;
        }
    }

    // ── Failure detail extraction ────────────────────────────

    /**
     * Extracts failure details from {@code <testcase>} nodes within a {@code <testsuite>} element.
     */
    static void collectFailureDetails(Element suite, List<String> failures) {
        var testcases = suite.getElementsByTagName("testcase");
        for (int j = 0; j < testcases.getLength(); j++) {
            var tc = testcases.item(j);
            var failNodes = ((Element) tc).getElementsByTagName("failure");
            if (failNodes.getLength() > 0) {
                String tcName = tc.getAttributes().getNamedItem("name").getNodeValue();
                String cls = tc.getAttributes().getNamedItem("classname").getNodeValue();
                String msg = failNodes.item(0).getAttributes().getNamedItem(ATTR_MESSAGE).getNodeValue();
                failures.add(String.format("  %s.%s: %s", cls, tcName, msg));
            }
        }
    }

    // ── Result formatting ────────────────────────────────────

    /**
     * Formats aggregated test results into a human-readable summary string.
     */
    static String formatTestResults(int totalTests, int totalFailed, int totalErrors,
                                    int totalSkipped, double totalTime, List<String> failures) {
        int passed = totalTests - totalFailed - totalErrors - totalSkipped;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Test Results: %d tests, %d passed, %d failed, %d errors, %d skipped (%.1fs)%n",
            totalTests, passed, totalFailed, totalErrors, totalSkipped, totalTime));

        if (!failures.isEmpty()) {
            sb.append("\nFailures:\n");
            failures.forEach(f -> sb.append(f).append("\n"));
        }
        return sb.toString().trim();
    }

    // ── XML attribute helpers ────────────────────────────────

    static int intAttr(Node node, String attr) {
        var item = node.getAttributes().getNamedItem(attr);
        return item != null ? Integer.parseInt(item.getNodeValue()) : 0;
    }

    static double doubleAttr(Node node, String attr) {
        var item = node.getAttributes().getNamedItem(attr);
        return item != null ? Double.parseDouble(item.getNodeValue()) : 0.0;
    }
}
