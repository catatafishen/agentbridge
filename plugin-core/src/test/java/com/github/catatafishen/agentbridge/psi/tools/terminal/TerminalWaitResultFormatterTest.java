package com.github.catatafishen.agentbridge.psi.tools.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalWaitResultFormatterTest {

    private static final String TOKEN = "a1b2c3d4";
    private static final String NOTE = "Wait ended after 1200 ms: wait_for pattern matched.";

    @Test
    void finishedCommandReportsItsExitCodeAndOutput() {
        String text = "$ make; echo AB_DONE_" + TOKEN + "_$?\nbuilt ok\nAB_DONE_" + TOKEN + "_0\n$ ";

        String result = TerminalWaitResultFormatter.format(NOTE, TOKEN, "/bin/zsh", "t1", text, 50);

        assertTrue(result.startsWith(NOTE));
        assertTrue(result.contains("Exit code: 0"), result);
        assertTrue(result.contains("built ok"), result);
        assertFalse(result.contains("Not finished"), result);
    }

    @Test
    void nonZeroExitCodeIsReported() {
        String result = TerminalWaitResultFormatter.format(
            NOTE, TOKEN, "bash", "t1", "boom\nAB_DONE_" + TOKEN + "_2", 50);

        assertTrue(result.contains("Exit code: 2"), result);
    }

    @Test
    void unfinishedCommandSaysHowToKeepWaiting() {
        String text = "$ make; echo AB_DONE_" + TOKEN + "_$?\ncompiling...";
        String result = TerminalWaitResultFormatter.format(
            "Wait ended after 5000 ms: timeout reached, the command may still be running.",
            TOKEN, "bash", "term-7", text, 50);

        assertFalse(result.contains("Exit code"), result);
        assertTrue(result.contains("Not finished yet"), result);
        assertTrue(result.contains("terminal_id=term-7"), result);
        assertTrue(result.contains(TerminalDoneMarker.regex(TOKEN)), result);
        assertTrue(result.contains("compiling..."), result);
    }

    @Test
    void keepWaitingHintAppearsExactlyOnce() {
        String result = TerminalWaitResultFormatter.format(
            TerminalWaitTracker.describe(TerminalWaitTracker.Reason.TIMEOUT, 5000),
            TOKEN, "bash", "t1", "$ x; echo AB_DONE_" + TOKEN + "_$?\n...", 50);

        assertEquals(1, result.split("read_terminal_output", -1).length - 1, result);
    }

    @Test
    void markerFromAnEarlierCommandDoesNotCountAsFinished() {
        String result = TerminalWaitResultFormatter.format(
            NOTE, TOKEN, "bash", "t1", "AB_DONE_ffffffff_0\nstill running", 50);

        assertTrue(result.contains("Not finished yet"), result);
    }

    @Test
    void unrecognisedShellExplainsThereIsNoExitCode() {
        String result = TerminalWaitResultFormatter.format(NOTE, null, "nu", "t1", "output", 50);

        assertTrue(result.contains("No exit code"), result);
        assertTrue(result.contains("'nu'"), result);
        assertFalse(result.contains("Not finished"), result);
    }

    @Test
    void unknownShellNameIsShownAsUnknownNotNull() {
        String result = TerminalWaitResultFormatter.format(NOTE, null, null, "t1", "output", 50);

        assertTrue(result.contains("<unknown>"), result);
        assertFalse(result.contains("null"), result);
    }

    @Test
    void emptyTerminalGetsTheDormantWarningAndNoOutputSection() {
        String result = TerminalWaitResultFormatter.format(NOTE, TOKEN, "bash", "t1", "  \n ", 50);

        assertTrue(result.contains("WARNING"), result);
        assertFalse(result.contains("Output of this command"), result);
        assertFalse(result.contains("Terminal buffer"), result);
    }

    @Test
    void outputIsLimitedToTheLastLines() {
        String text = "$ cmd; echo AB_DONE_" + TOKEN + "_$?\nl1\nl2\nl3\nl4\nAB_DONE_" + TOKEN + "_0";

        String result = TerminalWaitResultFormatter.format(NOTE, TOKEN, "bash", "t1", text, 2);

        assertFalse(result.contains("l1"), result);
        assertFalse(result.contains("l2"), result);
        assertTrue(result.contains("l3"), result);
        assertTrue(result.contains("l4"), result);
    }

    @Test
    void zeroMaxLinesReturnsTheWholeOutputOfTheCommand() {
        String text = "$ cmd; echo AB_DONE_" + TOKEN + "_$?\nl1\nl2\nl3\nAB_DONE_" + TOKEN + "_0";

        String result = TerminalWaitResultFormatter.format(NOTE, TOKEN, "bash", "t1", text, 0);

        assertTrue(result.contains("l1"), result);
        assertTrue(result.contains("l3"), result);
    }

    @Test
    void reusedTerminalShowsOnlyTheOutputOfThisCommand() {
        String text = "$ echo alt; echo AB_DONE_aaaaaaaa_$?\nalt\nAB_DONE_aaaaaaaa_0\n"
            + "$ echo neu; echo AB_DONE_" + TOKEN + "_$?\nneu\nAB_DONE_" + TOKEN + "_0\n$ ";

        String result = TerminalWaitResultFormatter.format(NOTE, TOKEN, "bash", "t1", text, 50);

        assertTrue(result.contains("Output of this command:\nneu"), result);
        assertFalse(result.contains("alt"), result);
        assertFalse(result.contains("Terminal buffer"), result);
    }

    @Test
    void commandWithoutOutputSaysSo() {
        String text = "$ true; echo AB_DONE_" + TOKEN + "_$?\nAB_DONE_" + TOKEN + "_0\n$ ";

        String result = TerminalWaitResultFormatter.format(NOTE, TOKEN, "bash", "t1", text, 50);

        assertTrue(result.contains("(no output)"), result);
    }

    @Test
    void unfinishedCommandShowsTheOutputSoFar() {
        String text = "$ make; echo AB_DONE_" + TOKEN + "_$?\ncompiling a\ncompiling b";

        String result = TerminalWaitResultFormatter.format(NOTE, TOKEN, "bash", "t1", text, 50);

        assertTrue(result.contains("Output of this command:\ncompiling a\ncompiling b"), result);
        assertTrue(result.contains("Not finished yet"), result);
    }

    @Test
    void fallsBackToTheRawBufferWhenTheEchoedCommandIsNotFound() {
        String result = TerminalWaitResultFormatter.format(NOTE, TOKEN, "bash", "t1", "unrelated\ntext", 50);

        assertTrue(result.contains("Terminal buffer (last lines):\nunrelated\ntext"), result);
        assertFalse(result.contains("Output of this command"), result);
    }

    @Test
    void withoutMarkerTheRawBufferIsShown() {
        String result = TerminalWaitResultFormatter.format(NOTE, null, "nu", "t1", "some output", 50);

        assertTrue(result.contains("Terminal buffer (last lines):\nsome output"), result);
    }
}
