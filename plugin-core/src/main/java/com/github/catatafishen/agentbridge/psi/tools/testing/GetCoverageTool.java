package com.github.catatafishen.agentbridge.psi.tools.testing;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.CoverageRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Retrieves code coverage data, optionally filtered by file or class.
 */
public final class GetCoverageTool extends TestingTool {

    public GetCoverageTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_coverage";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Coverage";
    }

    @Override
    public @NotNull String description() {
        return "Retrieve code coverage data, optionally filtered by file or class. " +
            "Requires a prior test run with coverage enabled. Returns line and branch coverage percentages.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional("file", TYPE_STRING, "Optional file or class name to filter coverage results", "")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return CoverageRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        String file = args.has("file") ? args.get("file").getAsString() : "";
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;

        for (String module : List.of("", "plugin-core", "mcp-server")) {
            Path jacocoXml = module.isEmpty()
                ? Path.of(basePath, ToolUtils.BUILD_DIR, "reports", "jacoco", "test", "jacocoTestReport.xml")
                : Path.of(basePath, module, ToolUtils.BUILD_DIR, "reports", "jacoco", "test", "jacocoTestReport.xml");
            if (Files.exists(jacocoXml)) {
                return parseJacocoXml(jacocoXml, file);
            }
        }

        try {
            Class<?> cdmClass = Class.forName("com.intellij.coverage.CoverageDataManager");
            Object manager = PlatformApiCompat.getServiceByRawClass(project, cdmClass);
            if (manager != null) {
                var getCurrentBundle = cdmClass.getMethod("getCurrentSuitesBundle");
                Object bundle = getCurrentBundle.invoke(manager);
                if (bundle != null) {
                    return "Coverage data available in IntelliJ. Use View > Tool Windows > Coverage to inspect.";
                }
            }
        } catch (Exception ignored) {
            // Coverage plugin not available or no data
        }

        return """
            No coverage data found. Run tests with coverage first:
              - IntelliJ: Right-click test → Run with Coverage
              - Gradle: Add jacoco plugin and run `gradlew jacocoTestReport`""";
    }

    @SuppressWarnings("java:S3518") // division by zero is prevented by Math.max(1, ...)
    static String parseJacocoXml(Path xmlPath, String fileFilter) {
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            //noinspection HttpUrlsUsage - XML feature URI, not an actual URL
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var doc = dbf.newDocumentBuilder().parse(xmlPath.toFile());
            var packages = doc.getElementsByTagName("package");
            List<String> lines = new ArrayList<>();
            int totalLines = 0;
            int coveredLines = 0;

            for (int i = 0; i < packages.getLength(); i++) {
                var pkg = (org.w3c.dom.Element) packages.item(i);
                var classes = pkg.getElementsByTagName("class");
                for (int j = 0; j < classes.getLength(); j++) {
                    var cls = (org.w3c.dom.Element) classes.item(j);
                    String name = cls.getAttribute("name").replace('/', '.');
                    if (!fileFilter.isEmpty() && !name.contains(fileFilter)) continue;

                    var coverage = processClassCoverage(cls);
                    if (coverage != null) {
                        totalLines += coverage.total;
                        coveredLines += coverage.covered;
                        lines.add(String.format("  %s: %.1f%% (%d/%d lines)",
                            name, coverage.percentage, coverage.covered, coverage.total));
                    }
                }
            }

            if (lines.isEmpty()) return "No line coverage data in JaCoCo report";
            double totalPct = coveredLines * 100.0 / Math.max(1, totalLines);
            return String.format("Coverage: %.1f%% overall (%d/%d lines)%n%n%s",
                totalPct, coveredLines, totalLines, String.join("\n", lines));
        } catch (Exception e) {
            return "Error parsing JaCoCo report: " + e.getMessage();
        }
    }

    @SuppressWarnings("java:S3518") // division by zero is prevented by Math.max(1, ...)
    static CoverageData processClassCoverage(org.w3c.dom.Element cls) {
        var counters = cls.getElementsByTagName("counter");
        for (int k = 0; k < counters.getLength(); k++) {
            var counter = counters.item(k);
            if ("LINE".equals(counter.getAttributes().getNamedItem("type").getNodeValue())) {
                int missed = intAttr(counter, "missed");
                int covered = intAttr(counter, "covered");
                int total = missed + covered;
                double pct = covered * 100.0 / Math.max(1, total);
                return new CoverageData(covered, total, pct);
            }
        }
        return null;
    }

    record CoverageData(int covered, int total, double percentage) {
    }
}
