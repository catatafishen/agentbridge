package com.github.catatafishen.agentbridge.psi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GrepCommandSafety}, the pure parser behind the {@code run_command}
 * grep restriction.
 *
 * <p>Regression coverage for issue #961: piped {@code grep} was rejected even though it never
 * touches the filesystem, and a {@code grep} whose file operand lived in {@code /tmp} was
 * rejected because the operand scan ran past the pipe into the rest of the pipeline and
 * collected {@code |}, {@code grep} and {@code tail} as if they were paths.
 */
class GrepCommandSafetyTest {

    @Nested
    @DisplayName("pipe filters")
    class PipeFilters {

        @Test
        @DisplayName("grep filtering piped stdout is a pipe filter")
        void pipedGrepIsPipeFilter() {
            List<GrepCommandSafety.GrepInvocation> found =
                GrepCommandSafety.analyze("gh pr checks 913 | grep -Ei \"build|analyze\"");

            assertEquals(1, found.size());
            assertTrue(found.getFirst().isPipeFilter());
        }

        @Test
        @DisplayName("a quoted pattern containing a pipe character is not a separator")
        void quotedPipeInPatternIsNotASeparator() {
            List<GrepCommandSafety.GrepInvocation> found =
                GrepCommandSafety.analyze("gh pr checks 913 | grep -Ei \"build|analyze\"");

            assertEquals(List.of(), found.getFirst().paths());
        }

        @Test
        @DisplayName("stderr redirection before the pipe does not hide the pipe")
        void stderrRedirectBeforePipeStillPipes() {
            List<GrepCommandSafety.GrepInvocation> found =
                GrepCommandSafety.analyze("gh pr checks 913 2>&1 | grep -i build");

            assertTrue(found.getFirst().isPipeFilter());
        }

        @Test
        @DisplayName("every grep in a multi-stage pipeline is reported")
        void multiStagePipelineReportsEveryGrep() {
            List<GrepCommandSafety.GrepInvocation> found =
                GrepCommandSafety.analyze("grep -i error /tmp/ci.log | grep -v McpHttpServer | tail -40");

            assertEquals(2, found.size());
            assertEquals(List.of("/tmp/ci.log"), found.getFirst().paths(),
                "operand scan must stop at the pipe instead of swallowing the rest of the pipeline");
            assertTrue(found.get(1).isPipeFilter());
        }

        @ParameterizedTest
        @DisplayName("only | and |& feed stdin — && , || and ; merely sequence commands")
        @ValueSource(strings = {"ls && grep pattern", "ls || grep pattern", "ls ; grep pattern"})
        void sequencingOperatorsDoNotPipe(String command) {
            assertFalse(GrepCommandSafety.analyze(command).getFirst().isPipeFilter());
        }

        @Test
        @DisplayName("|& feeds stdin")
        void pipeWithStderrFeedsStdin() {
            assertTrue(GrepCommandSafety.analyze("ls |& grep pattern").getFirst().isPipeFilter());
        }

        @Test
        @DisplayName("redirections after grep are not path operands")
        void redirectionsAreNotPaths() {
            assertEquals(List.of("/tmp/ci.log"),
                GrepCommandSafety.analyze("grep TODO /tmp/ci.log > out.txt").getFirst().paths(),
                "the redirection target is written, not searched");
            assertEquals(List.of("/tmp/ci.log"),
                GrepCommandSafety.analyze("grep TODO /tmp/ci.log 2> /dev/null").getFirst().paths(),
                "a detached redirection target must be consumed with its operator");
        }

        @Test
        @DisplayName("a leading grep with no operand is not a pipe filter")
        void leadingGrepWithoutOperandIsNotPipeFilter() {
            // Nothing is piped in: rg walks the working directory and grep waits on the terminal.
            assertFalse(GrepCommandSafety.analyze("rg pattern").getFirst().isPipeFilter());
            assertFalse(GrepCommandSafety.analyze("grep pattern").getFirst().isPipeFilter());
        }
    }

    /**
     * Shells do not require whitespace around {@code |}, {@code &&} and friends. Splitting on
     * whitespace alone left {@code cmd|grep foo} as the single token {@code cmd|grep}, which no
     * longer looked like a grep at all — so the pipe-filter exemption never applied and the
     * command was rejected.
     */
    @Nested
    @DisplayName("unspaced separators")
    class UnspacedSeparators {

        @Test
        @DisplayName("an unspaced pipe still makes grep a pipe filter")
        void unspacedPipeIsPipeFilter() {
            List<GrepCommandSafety.GrepInvocation> found =
                GrepCommandSafety.analyze("gh pr checks 963|grep -i build");

            assertEquals(1, found.size());
            assertTrue(found.getFirst().isPipeFilter());
        }

        @Test
        @DisplayName("an unspaced |& still makes grep a pipe filter")
        void unspacedPipeWithStderrIsPipeFilter() {
            assertTrue(GrepCommandSafety.analyze("ls|&grep pattern").getFirst().isPipeFilter());
        }

        @ParameterizedTest
        @DisplayName("unspaced sequencing operators are found but do not feed stdin")
        @ValueSource(strings = {"ls&&grep pattern", "ls||grep pattern", "ls;grep pattern"})
        void unspacedSequencingOperatorsDoNotPipe(String command) {
            List<GrepCommandSafety.GrepInvocation> found = GrepCommandSafety.analyze(command);

            assertEquals(1, found.size(), "grep must still be found: " + command);
            assertFalse(found.getFirst().isPipeFilter());
        }

        @Test
        @DisplayName("|| is read as one separator, not as two pipes")
        void doublePipeIsNotTwoPipes() {
            assertFalse(GrepCommandSafety.analyze("ls||grep pattern").getFirst().isPipeFilter());
        }

        @Test
        @DisplayName("operand scan stops at an unspaced pipe")
        void operandScanStopsAtUnspacedPipe() {
            List<GrepCommandSafety.GrepInvocation> found =
                GrepCommandSafety.analyze("grep -i error /tmp/ci.log|grep -v McpHttpServer");

            assertEquals(2, found.size());
            assertEquals(List.of("/tmp/ci.log"), found.getFirst().paths());
            assertTrue(found.get(1).isPipeFilter());
        }

        @Test
        @DisplayName("a pipe inside quotes is still part of the pattern")
        void quotedPipeIsNotASeparator() {
            List<GrepCommandSafety.GrepInvocation> found =
                GrepCommandSafety.analyze("grep -Ei \"build|analyze\" /tmp/ci.log");

            assertEquals(1, found.size(), "the quoted pipe must not split the command");
            assertEquals(List.of("/tmp/ci.log"), found.getFirst().paths());
        }

        @Test
        @DisplayName("a lone & is left attached so redirections survive")
        void loneAmpersandDoesNotSplitRedirections() {
            // 2>&1 must stay one token; splitting on the bare & would leave a stray "1" operand.
            assertEquals(List.of("/tmp/ci.log"),
                GrepCommandSafety.analyze("grep -i error /tmp/ci.log 2>&1").getFirst().paths());
            assertTrue(GrepCommandSafety.analyze("gh run view 1 2>&1|grep -i error")
                .getFirst().isPipeFilter());
        }
    }

    @Nested
    @DisplayName("path operands")
    class PathOperands {

        @Test
        @DisplayName("a single file operand is collected")
        void singleFileOperand() {
            assertEquals(List.of("file.log"),
                GrepCommandSafety.analyze("grep ERROR file.log").getFirst().paths());
        }

        @Test
        @DisplayName("multiple file operands are collected")
        void multipleFileOperands() {
            assertEquals(List.of("a.log", "b.log"),
                GrepCommandSafety.analyze("grep TODO a.log b.log").getFirst().paths());
        }

        @Test
        @DisplayName("single-argument flags are not mistaken for operands")
        void singleArgumentFlagsIgnored() {
            assertEquals(List.of("notes.md"),
                GrepCommandSafety.analyze("grep -i -n PATTERN notes.md").getFirst().paths());
        }

        @Test
        @DisplayName("two-argument flags consume their argument")
        void twoArgumentFlagsConsumeTheirArgument() {
            assertEquals(List.of("file.txt"),
                GrepCommandSafety.analyze("grep -A 3 -B 2 -e foo file.txt").getFirst().paths());
        }

        @Test
        @DisplayName("a glob operand yields null — the shell expands it before grep runs")
        void globOperandYieldsNull() {
            assertNull(GrepCommandSafety.analyze("grep PATTERN *.java").getFirst().paths());
        }

        @Test
        @DisplayName("rg is recognised the same as grep")
        void ripgrepIsRecognised() {
            assertEquals(List.of("logs/build.log"),
                GrepCommandSafety.analyze("rg --no-heading TODO logs/build.log").getFirst().paths());
        }

        @Test
        @DisplayName("a grep behind sudo is still found")
        void sudoPrefixedGrepIsFound() {
            assertEquals(List.of("file.log"),
                GrepCommandSafety.analyze("sudo grep PATTERN file.log").getFirst().paths());
        }

        @Test
        @DisplayName("a grep behind env is still found")
        void envPrefixedGrepIsFound() {
            assertEquals(List.of("file.log"),
                GrepCommandSafety.analyze("env grep PATTERN file.log").getFirst().paths());
        }
    }

    @Nested
    @DisplayName("wrapper commands that consume stdin as arguments")
    class StdinConsumingWrappers {

        @Test
        @DisplayName("grep behind xargs is not recognised as a grep invocation at all")
        void xargsGrepIsNotAnInvocation() {
            // xargs turns the piped-in lines into extra arguments appended after "PATTERN", so
            // this is not the harmless "no operand at all" shape isPipeFilter() exists for — it
            // opens whatever paths xargs supplies. Since the real operands cannot be determined
            // from the command text, the segment must not be reported as a grep invocation.
            assertEquals(List.of(),
                GrepCommandSafety.analyze("find . -name '*.log' | xargs grep PATTERN"));
        }

        @Test
        @DisplayName("grep behind xargs with -I is not recognised as a grep invocation")
        void xargsWithReplacementGrepIsNotAnInvocation() {
            assertEquals(List.of(),
                GrepCommandSafety.analyze("cat filelist.txt | xargs -I{} grep PATTERN {}"));
        }
    }

    @Test
    @DisplayName("a command without grep yields no invocations")
    void commandWithoutGrepYieldsNothing() {
        assertEquals(List.of(), GrepCommandSafety.analyze("cat file.txt"));
    }

    @Test
    @DisplayName("an empty command yields no invocations")
    void emptyCommandYieldsNothing() {
        assertEquals(List.of(), GrepCommandSafety.analyze(""));
    }
}
