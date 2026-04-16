package com.github.catatafishen.agentbridge.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.github.catatafishen.agentbridge.permissions.AbuseDetector;

/**
 * Jazzer fuzz target for {@link AbuseDetector}.
 *
 * <p>The AbuseDetector uses a hand-rolled JSON field extractor ({@code extractCommand})
 * that scans for {@code "command"} by index and uses a custom {@code findClosingQuote}
 * that doesn't handle double-escaped backslashes. This is security-critical — it
 * determines whether a tool call is denied. Crafted JSON could bypass detection.
 *
 * <p>To run: {@code java -jar jazzer.jar --cp=<test-classpath>
 * --target_class=com.github.catatafishen.agentbridge.fuzz.AbuseDetectorFuzz}
 */
public class AbuseDetectorFuzz {

    private static final AbuseDetector DETECTOR = new AbuseDetector();
    private static final String[] TOOL_IDS = {"run_command", "run_in_terminal", "write_terminal_input"};

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        String toolId = data.pickValue(TOOL_IDS);
        String arguments = data.consumeRemainingAsString();

        // check() must never throw — it returns null for "no abuse detected"
        DETECTOR.check(toolId, arguments);
    }
}
