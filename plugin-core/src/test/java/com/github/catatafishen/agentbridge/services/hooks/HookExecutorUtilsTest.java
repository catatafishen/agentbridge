package com.github.catatafishen.agentbridge.services.hooks;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class HookExecutorUtilsTest {

    @Nested
    class IsShellInterpreter {
        @ParameterizedTest
        @ValueSource(strings = {"sh", "bash", "dash"})
        void recognizesShellByBasename(String shell) {
            assertTrue(HookExecutor.isShellInterpreter(shell));
        }

        @ParameterizedTest
        @ValueSource(strings = {"/usr/bin/sh", "/usr/bin/bash", "/bin/dash"})
        void recognizesShellByFullPath(String path) {
            assertTrue(HookExecutor.isShellInterpreter(path));
        }

        @ParameterizedTest
        @ValueSource(strings = {"zsh", "ksh", "fish", "python3", "node"})
        void rejectsNonShellInterpreters(String interpreter) {
            assertFalse(HookExecutor.isShellInterpreter(interpreter));
        }

        @Test
        void rejectsFullPathToNonShell() {
            assertFalse(HookExecutor.isShellInterpreter("/usr/bin/python3"));
        }
    }

    @Nested
    class StripTrailingLineBreaks {
        @Test
        void stripsTrailingNewlines() {
            assertEquals("hello", HookExecutor.stripTrailingLineBreaks("hello\n\n"));
        }

        @Test
        void stripsTrailingCarriageReturns() {
            assertEquals("hello", HookExecutor.stripTrailingLineBreaks("hello\r\n\r\n"));
        }

        @Test
        void preservesInternalNewlines() {
            assertEquals("hello\nworld", HookExecutor.stripTrailingLineBreaks("hello\nworld\n"));
        }

        @Test
        void returnsEmptyForAllNewlines() {
            assertEquals("", HookExecutor.stripTrailingLineBreaks("\n\n\n"));
        }

        @Test
        void returnsUnchangedWithoutTrailingBreaks() {
            assertEquals("no trailing", HookExecutor.stripTrailingLineBreaks("no trailing"));
        }

        @Test
        void handlesEmptyString() {
            assertEquals("", HookExecutor.stripTrailingLineBreaks(""));
        }
    }

    @Nested
    class Truncate {
        @Test
        void returnsShortTextUnchanged() {
            String text = "short text";
            assertEquals(text, HookExecutor.truncate(text));
        }

        @Test
        void truncatesAtMaxLength() {
            String text = "x".repeat(16_001);
            String result = HookExecutor.truncate(text);
            assertEquals(16_000 + 3, result.length()); // 16000 chars + "..."
            assertTrue(result.endsWith("..."));
        }

        @Test
        void doesNotTruncateExactlyAtLimit() {
            String text = "x".repeat(16_000);
            assertEquals(text, HookExecutor.truncate(text));
        }
    }

    @Nested
    class FormatOutput {
        @Test
        void returnsEmptyForBlankOutput() {
            assertEquals("", HookExecutor.formatOutput("", ""));
        }

        @Test
        void returnsEmptyForWhitespaceOnly() {
            assertEquals("", HookExecutor.formatOutput("  ", "  "));
        }

        @Test
        void prefixesWithColonSpace() {
            assertEquals(": hello", HookExecutor.formatOutput("hello", ""));
        }

        @Test
        void combinesStdoutAndStderr() {
            String result = HookExecutor.formatOutput("out", "err");
            assertEquals(": outerr", result);
        }

        @Test
        void truncatesLongOutput() {
            String longOutput = "x".repeat(16_001);
            String result = HookExecutor.formatOutput(longOutput, "");
            assertTrue(result.startsWith(": "));
            assertTrue(result.endsWith("..."));
        }
    }
}
