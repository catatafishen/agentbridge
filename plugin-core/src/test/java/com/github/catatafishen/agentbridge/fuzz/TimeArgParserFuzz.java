package com.github.catatafishen.agentbridge.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.github.catatafishen.agentbridge.psi.TimeArgParser;

/**
 * Jazzer fuzz target for {@link TimeArgParser#parseLocalDateTime(String)}.
 *
 * <p>TimeArgParser accepts 7+ date/time formats (relative, ISO 8601, date-only, time-only).
 * Waterfall parsing with multiple catch-and-fallthrough blocks makes it vulnerable to
 * edge cases: overflow in relative amounts ({@code 999999999999h}), unexpected format
 * interactions, and log injection via the error message.
 *
 * <p>To run: {@code java -jar jazzer.jar --cp=<test-classpath>
 * --target_class=com.github.catatafishen.agentbridge.fuzz.TimeArgParserFuzz}
 */
public class TimeArgParserFuzz {

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        String value = data.consumeRemainingAsString();
        try {
            TimeArgParser.parseLocalDateTime(value);
        } catch (IllegalArgumentException ignored) {
            // Expected for unparseable input — the contract says so
        }
        // Any other exception type is a genuine bug
    }
}
