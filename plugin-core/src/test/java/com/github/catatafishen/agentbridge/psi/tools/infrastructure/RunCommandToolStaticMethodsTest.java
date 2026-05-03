package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the private static/pure methods in {@link RunCommandTool} using reflection.
 *
 * <ul>
 *   <li>{@code truncateForTitle(String)} — title truncation logic</li>
 *   <li>{@code formatExecuteOutput(ProcessResult, JsonObject, int, int, int)} — output formatting</li>
 * </ul>
 */
class RunCommandToolStaticMethodsTest {

    private static final RunCommandTool TOOL = new RunCommandTool(null);

    // --- Reflection handles, set up once ---------------------------------------------------
    private static Method truncateForTitle;
    private static Method formatExecuteOutput;
    private static Constructor<?> processResultCtor;

    @BeforeAll
    static void setUpReflection() throws Exception {
        truncateForTitle = RunCommandTool.class.getDeclaredMethod("truncateForTitle", String.class);
        truncateForTitle.setAccessible(true);

        // ProcessResult is a protected record inside Tool
        Class<?> processResultClass = Class.forName(
            "com.github.catatafishen.agentbridge.psi.tools.Tool$ProcessResult");
        processResultCtor = processResultClass.getDeclaredConstructor(int.class, String.class, boolean.class);
        processResultCtor.setAccessible(true);

        formatExecuteOutput = RunCommandTool.class.getDeclaredMethod(
            "formatExecuteOutput", processResultClass, JsonObject.class,
            int.class, int.class, int.class);
        formatExecuteOutput.setAccessible(true);
    }

    // --- Reflection helpers ---------------------------------------------------------------

    private static String invokeTruncateForTitle(String command) throws Exception {
        return (String) truncateForTitle.invoke(null, command);
    }

    private static Object processResult(int exitCode, String output, boolean timedOut) throws Exception {
        return processResultCtor.newInstance(exitCode, output, timedOut);
    }

    private static String invokeFormatOutput(Object result, JsonObject args,
                                             int maxChars, int offset, int timeoutSec) throws Exception {
        return (String) formatExecuteOutput.invoke(TOOL, result, args, maxChars, offset, timeoutSec);
    }

    // --- JSON builder helpers -------------------------------------------------------------

    private static JsonObject argsWithoutOffset() {
        JsonObject args = new JsonObject();
        args.addProperty("command", "echo hello");
        return args;
    }

    private static JsonObject argsWithOffset(int offset) {
        JsonObject args = new JsonObject();
        args.addProperty("command", "echo hello");
        args.addProperty("offset", offset);
        return args;
    }

    // ======================================================================================
    //  truncateForTitle
    // ======================================================================================
    @Nested
    @DisplayName("truncateForTitle")
    class TruncateForTitle {

        @Test
        @DisplayName("short command (< 40 chars) is returned unchanged")
        void shortCommand() throws Exception {
            assertEquals("ls -la", invokeTruncateForTitle("ls -la"));
        }

        @Test
        @DisplayName("command exactly 40 chars is returned unchanged")
        void exactlyAtLimit() throws Exception {
            String exactly40 = "a".repeat(40);
            assertEquals(exactly40, invokeTruncateForTitle(exactly40));
        }

        @Test
        @DisplayName("command of 41 chars is truncated to 37 chars + '...'")
        void oneOverLimit() throws Exception {
            String input = "a".repeat(41);
            String result = invokeTruncateForTitle(input);
            assertEquals("a".repeat(37) + "...", result);
            assertEquals(40, result.length());
        }

        @Test
        @DisplayName("very long command is truncated to 40 chars total")
        void veryLongCommand() throws Exception {
            String input = "gradle clean build --info --stacktrace --scan --warning-mode=all";
            assertTrue(input.length() > 40);
            String result = invokeTruncateForTitle(input);
            assertEquals(40, result.length());
            assertTrue(result.endsWith("..."));
            assertEquals(input.substring(0, 37) + "...", result);
        }

        @Test
        @DisplayName("empty string is returned as-is")
        void emptyString() throws Exception {
            assertEquals("", invokeTruncateForTitle(""));
        }

        @Test
        @DisplayName("single character is returned unchanged")
        void singleChar() throws Exception {
            assertEquals("x", invokeTruncateForTitle("x"));
        }

        @ParameterizedTest
        @DisplayName("boundary: 39/40/41 chars")
        @CsvSource({
            "39, false",
            "40, false",
            "41, true",
        })
        void boundaryLengths(int length, boolean shouldTruncate) throws Exception {
            String input = "x".repeat(length);
            String result = invokeTruncateForTitle(input);
            if (shouldTruncate) {
                assertEquals(40, result.length());
                assertTrue(result.endsWith("..."));
            } else {
                assertEquals(input, result);
            }
        }

        @Test
        @DisplayName("command with newline — full string (including newline) is considered")
        void commandWithNewline() throws Exception {
            // 10 + 1 newline + 10 = 21 chars, under limit
            String input = "echo hello\necho world";
            assertEquals(input, invokeTruncateForTitle(input));
        }

        @Test
        @DisplayName("long command with newline is truncated")
        void longCommandWithNewline() throws Exception {
            String input = "a".repeat(30) + "\n" + "b".repeat(30); // 61 chars
            String result = invokeTruncateForTitle(input);
            assertEquals(40, result.length());
            assertTrue(result.endsWith("..."));
        }
    }

    // ======================================================================================
    //  formatExecuteOutput
    // ======================================================================================
    @Nested
    @DisplayName("formatExecuteOutput")
    class FormatExecuteOutput {

        // ------ Success cases ------

        @Test
        @DisplayName("successful command (exit 0) with short output")
        void successShortOutput() throws Exception {
            Object result = processResult(0, "hello world", false);
            String output = invokeFormatOutput(result, argsWithoutOffset(), 8000, 0, 60);

            assertTrue(output.startsWith("Command succeeded"));
            assertTrue(output.contains("hello world"));
            assertFalse(output.contains("failed"));
            assertFalse(output.contains("timed out"));
        }

        @Test
        @DisplayName("successful command with empty output")
        void successEmptyOutput() throws Exception {
            Object result = processResult(0, "", false);
            String output = invokeFormatOutput(result, argsWithoutOffset(), 8000, 0, 60);

            assertTrue(output.startsWith("Command succeeded"));
        }

        @Test
        @DisplayName("successful command with output exceeding maxChars shows truncation hint")
        void successTruncated() throws Exception {
            String longOutput = "abcdefghij".repeat(20); // 200 chars
            Object result = processResult(0, longOutput, false);
            String output = invokeFormatOutput(result, argsWithoutOffset(), 50, 0, 60);

            assertTrue(output.contains("Command succeeded"));
            assertTrue(output.contains("truncated"));
            assertTrue(output.contains("Use offset="));
        }

        // ------ Failure cases ------

        @Test
        @DisplayName("failed command (exit 1) with short output shows exit code")
        void failedShortOutput() throws Exception {
            Object result = processResult(1, "error: file not found", false);
            String output = invokeFormatOutput(result, argsWithoutOffset(), 8000, 0, 60);

            assertTrue(output.startsWith("Command failed (exit code 1)"));
            assertTrue(output.contains("error: file not found"));
            assertFalse(output.contains("showing last"));
        }

        @Test
        @DisplayName("failed command with non-standard exit code 127")
        void failedExitCode127() throws Exception {
            Object result = processResult(127, "command not found", false);
            String output = invokeFormatOutput(result, argsWithoutOffset(), 8000, 0, 60);

            assertTrue(output.contains("exit code 127"));
        }

        @Test
        @DisplayName("failed command auto-tails when output exceeds maxChars and no offset arg")
        void failedAutoTail() throws Exception {
            String longOutput = "x".repeat(200);
            Object result = processResult(1, longOutput, false);
            String output = invokeFormatOutput(result, argsWithoutOffset(), 50, 0, 60);

            assertTrue(output.contains("Command failed (exit code 1)"));
            assertTrue(output.contains("showing last 50 chars"));
            assertTrue(output.contains("use offset=0 for beginning"));
        }

        @Test
        @DisplayName("failed command with explicit offset=0 in args does NOT auto-tail")
        void failedExplicitOffsetZeroNoAutoTail() throws Exception {
            String longOutput = "x".repeat(200);
            Object result = processResult(1, longOutput, false);
            String output = invokeFormatOutput(result, argsWithOffset(0), 50, 0, 60);

            assertTrue(output.contains("Command failed (exit code 1)"));
            // No auto-tail header because args has "offset" key
            assertFalse(output.contains("showing last"));
            // But the output is still truncated by ToolUtils.truncateOutput
            assertTrue(output.contains("truncated"));
        }

        @Test
        @DisplayName("failed command with small output does NOT trigger auto-tail")
        void failedSmallOutputNoAutoTail() throws Exception {
            Object result = processResult(1, "small error", false);
            String output = invokeFormatOutput(result, argsWithoutOffset(), 8000, 0, 60);

            assertTrue(output.contains("Command failed (exit code 1)"));
            assertFalse(output.contains("showing last"));
            assertTrue(output.contains("small error"));
        }

        // ------ Timeout cases ------

        @Test
        @DisplayName("timed-out command includes timeout message with correct seconds")
        void timedOut() throws Exception {
            Object result = processResult(-1, "partial output...", true);
            String output = invokeFormatOutput(result, argsWithoutOffset(), 8000, 0, 30);

            assertTrue(output.startsWith("Command timed out after 30 seconds."));
            assertTrue(output.contains("partial output..."));
            assertFalse(output.contains("Command succeeded"));
            assertFalse(output.contains("Command failed"));
        }

        @Test
        @DisplayName("timeout message uses the provided timeoutSec value")
        void timeoutUsesProvidedValue() throws Exception {
            Object result = processResult(-1, "data", true);
            String output = invokeFormatOutput(result, argsWithoutOffset(), 8000, 0, 120);

            assertTrue(output.contains("120 seconds"));
        }

        @Test
        @DisplayName("timed-out command with empty output")
        void timedOutEmptyOutput() throws Exception {
            Object result = processResult(-1, "", true);
            String output = invokeFormatOutput(result, argsWithoutOffset(), 8000, 0, 60);

            assertTrue(output.startsWith("Command timed out after 60 seconds."));
        }

        // ------ Pagination/offset cases ------

        @Test
        @DisplayName("success with non-zero offset shows correct page range")
        void successWithOffset() throws Exception {
            String longOutput = "a".repeat(100);
            Object result = processResult(0, longOutput, false);
            String output = invokeFormatOutput(result, argsWithOffset(30), 50, 30, 60);

            assertTrue(output.contains("Command succeeded"));
            // ToolUtils.truncateOutput adds pagination info when there's more to show
            assertTrue(output.contains("showing chars"));
        }

        @Test
        @DisplayName("success with offset beyond output length")
        void successWithOffsetBeyondEnd() throws Exception {
            Object result = processResult(0, "short", false);
            String output = invokeFormatOutput(result, argsWithOffset(100), 50, 100, 60);

            assertTrue(output.contains("Command succeeded"));
            // ToolUtils.truncateOutput handles offset > length
            assertTrue(output.contains("offset beyond end"));
        }
    }
}
