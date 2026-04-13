package com.github.catatafishen.agentbridge.psi.tools.testing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the package-private static helpers in {@link GetCoverageTool}:
 * {@code parseJacocoXml}, {@code processClassCoverage}, and the {@code CoverageData} record.
 */
class GetCoverageToolStaticMethodsTest {

    // ── parseJacocoXml ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseJacocoXml")
    class ParseJacocoXml {

        @Test
        @DisplayName("simple report with one class returns overall coverage line")
        void simpleReport(@TempDir Path tmp) throws Exception {
            Path xml = tmp.resolve("jacoco.xml");
            Files.writeString(xml, """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="test">
                  <package name="com/example">
                    <class name="com/example/MyClass" sourcefilename="MyClass.java">
                      <counter type="LINE" missed="5" covered="15"/>
                    </class>
                  </package>
                </report>
                """);

            String result = GetCoverageTool.parseJacocoXml(xml, "");

            assertTrue(result.contains("Coverage: 75.0% overall (15/20 lines)"), result);
            assertTrue(result.contains("com.example.MyClass: 75.0% (15/20 lines)"), result);
        }

        @Test
        @DisplayName("filter excludes non-matching classes")
        void filterExcludesNonMatching(@TempDir Path tmp) throws Exception {
            Path xml = tmp.resolve("jacoco.xml");
            Files.writeString(xml, """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="test">
                  <package name="com/example">
                    <class name="com/example/MyClass" sourcefilename="MyClass.java">
                      <counter type="LINE" missed="5" covered="15"/>
                    </class>
                  </package>
                </report>
                """);

            String result = GetCoverageTool.parseJacocoXml(xml, "Other");

            assertEquals("No line coverage data in JaCoCo report", result);
        }

        @Test
        @DisplayName("filter includes matching class")
        void filterIncludesMatching(@TempDir Path tmp) throws Exception {
            Path xml = tmp.resolve("jacoco.xml");
            Files.writeString(xml, """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="test">
                  <package name="com/example">
                    <class name="com/example/MyClass" sourcefilename="MyClass.java">
                      <counter type="LINE" missed="5" covered="15"/>
                    </class>
                  </package>
                </report>
                """);

            String result = GetCoverageTool.parseJacocoXml(xml, "MyClass");

            assertTrue(result.contains("com.example.MyClass: 75.0% (15/20 lines)"), result);
            assertTrue(result.contains("Coverage: 75.0% overall (15/20 lines)"), result);
        }

        @Test
        @DisplayName("invalid/corrupt XML returns error message")
        void invalidXml(@TempDir Path tmp) throws Exception {
            Path xml = tmp.resolve("corrupt.xml");
            Files.writeString(xml, "<<<not valid xml>>>");

            String result = GetCoverageTool.parseJacocoXml(xml, "");

            assertTrue(result.startsWith("Error parsing JaCoCo report: "), result);
        }

        @Test
        @DisplayName("multiple packages and classes are all included")
        void multiplePackagesAndClasses(@TempDir Path tmp) throws Exception {
            Path xml = tmp.resolve("jacoco.xml");
            Files.writeString(xml, """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="test">
                  <package name="com/alpha">
                    <class name="com/alpha/Foo" sourcefilename="Foo.java">
                      <counter type="LINE" missed="10" covered="10"/>
                    </class>
                  </package>
                  <package name="com/beta">
                    <class name="com/beta/Bar" sourcefilename="Bar.java">
                      <counter type="LINE" missed="0" covered="20"/>
                    </class>
                    <class name="com/beta/Baz" sourcefilename="Baz.java">
                      <counter type="LINE" missed="5" covered="5"/>
                    </class>
                  </package>
                </report>
                """);

            String result = GetCoverageTool.parseJacocoXml(xml, "");

            // total: covered = 10+20+5 = 35, total = 20+20+10 = 50 → 70.0%
            assertTrue(result.contains("Coverage: 70.0% overall (35/50 lines)"), result);
            assertTrue(result.contains("com.alpha.Foo: 50.0% (10/20 lines)"), result);
            assertTrue(result.contains("com.beta.Bar: 100.0% (20/20 lines)"), result);
            assertTrue(result.contains("com.beta.Baz: 50.0% (5/10 lines)"), result);
        }

        @Test
        @DisplayName("XML with DOCTYPE declaration is rejected (disallow-doctype-decl)")
        void doctypeRejected(@TempDir Path tmp) throws Exception {
            Path xml = tmp.resolve("doctype.xml");
            Files.writeString(xml, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE report SYSTEM "report.dtd">
                <report name="test">
                  <package name="com/example">
                    <class name="com/example/MyClass" sourcefilename="MyClass.java">
                      <counter type="LINE" missed="5" covered="15"/>
                    </class>
                  </package>
                </report>
                """);

            String result = GetCoverageTool.parseJacocoXml(xml, "");

            assertTrue(result.startsWith("Error parsing JaCoCo report: "), result);
        }
    }

    // ── processClassCoverage ─────────────────────────────────────────────────

    @Nested
    @DisplayName("processClassCoverage")
    class ProcessClassCoverage {

        private Document newDocument() throws Exception {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        }

        @Test
        @DisplayName("LINE counter is extracted into CoverageData")
        void lineCounterExtracted() throws Exception {
            Document doc = newDocument();
            Element cls = doc.createElement("class");
            cls.setAttribute("name", "com/example/MyClass");
            Element counter = doc.createElement("counter");
            counter.setAttribute("type", "LINE");
            counter.setAttribute("missed", "3");
            counter.setAttribute("covered", "7");
            cls.appendChild(counter);

            GetCoverageTool.CoverageData data = GetCoverageTool.processClassCoverage(cls);

            assertNotNull(data);
            assertEquals(7, data.covered());
            assertEquals(10, data.total());
            assertEquals(70.0, data.percentage(), 0.01);
        }

        @Test
        @DisplayName("no LINE counter returns null")
        void noLineCounter() throws Exception {
            Document doc = newDocument();
            Element cls = doc.createElement("class");
            cls.setAttribute("name", "com/example/MyClass");
            Element counter = doc.createElement("counter");
            counter.setAttribute("type", "BRANCH");
            counter.setAttribute("missed", "3");
            counter.setAttribute("covered", "7");
            cls.appendChild(counter);

            GetCoverageTool.CoverageData data = GetCoverageTool.processClassCoverage(cls);

            assertNull(data);
        }

        @Test
        @DisplayName("missed=0 covered=0 yields CoverageData(0, 0, 0.0)")
        void zeroCoverage() throws Exception {
            Document doc = newDocument();
            Element cls = doc.createElement("class");
            cls.setAttribute("name", "com/example/Empty");
            Element counter = doc.createElement("counter");
            counter.setAttribute("type", "LINE");
            counter.setAttribute("missed", "0");
            counter.setAttribute("covered", "0");
            cls.appendChild(counter);

            GetCoverageTool.CoverageData data = GetCoverageTool.processClassCoverage(cls);

            assertNotNull(data);
            assertEquals(0, data.covered());
            assertEquals(0, data.total());
            assertEquals(0.0, data.percentage(), 0.01);
        }
    }

    // ── CoverageData record ──────────────────────────────────────────────────

    @Nested
    @DisplayName("CoverageData")
    class CoverageDataTest {

        @Test
        @DisplayName("record getters return correct values")
        void recordGetters() {
            var data = new GetCoverageTool.CoverageData(15, 20, 75.0);

            assertEquals(15, data.covered());
            assertEquals(20, data.total());
            assertEquals(75.0, data.percentage(), 0.001);
        }

        @Test
        @DisplayName("equals and hashCode for identical records")
        void equalsAndHashCode() {
            var a = new GetCoverageTool.CoverageData(15, 20, 75.0);
            var b = new GetCoverageTool.CoverageData(15, 20, 75.0);

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("not equal for different values")
        void notEqual() {
            var a = new GetCoverageTool.CoverageData(15, 20, 75.0);
            var c = new GetCoverageTool.CoverageData(10, 20, 50.0);

            assertNotEquals(a, c);
        }
    }
}
