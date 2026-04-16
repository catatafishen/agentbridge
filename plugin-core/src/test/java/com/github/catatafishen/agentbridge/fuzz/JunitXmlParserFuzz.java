package com.github.catatafishen.agentbridge.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.github.catatafishen.agentbridge.psi.tools.testing.JunitXmlParser;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Jazzer fuzz target for {@link JunitXmlParser}.
 *
 * <p>Parses JUnit XML test reports. The parser uses {@code DocumentBuilderFactory}
 * with XXE protection, but could still be tested for billion laughs variants, huge
 * attribute values, and malformed XML that causes uncaught NumberFormatException
 * in {@code intAttr()} / {@code doubleAttr()}.
 *
 * <p>To run: {@code java -jar jazzer.jar --cp=<test-classpath>
 * --target_class=com.github.catatafishen.agentbridge.fuzz.JunitXmlParserFuzz}
 */
public class JunitXmlParserFuzz {

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        byte[] xmlBytes = data.consumeRemainingAsBytes();
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("fuzz-junit-", ".xml");
            Files.write(tempFile, xmlBytes);
            JunitXmlParser.parseTestSuiteXml(tempFile);
        } catch (Exception ignored) {
            // XML parse errors, IO errors, and format errors are expected
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                    // cleanup best-effort
                }
            }
        }
    }
}
