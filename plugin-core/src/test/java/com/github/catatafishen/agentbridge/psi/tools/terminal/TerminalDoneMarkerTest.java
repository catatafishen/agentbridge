package com.github.catatafishen.agentbridge.psi.tools.terminal;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalDoneMarkerTest {

    private static final String TOKEN = "a1b2c3d4";

    @Test
    void detectsShellFamiliesFromPathsAndNames() {
        assertEquals(TerminalDoneMarker.Shell.POSIX, TerminalDoneMarker.detect("/bin/zsh"));
        assertEquals(TerminalDoneMarker.Shell.POSIX, TerminalDoneMarker.detect("bash"));
        assertEquals(TerminalDoneMarker.Shell.FISH, TerminalDoneMarker.detect("/usr/bin/fish"));
        assertEquals(TerminalDoneMarker.Shell.POWERSHELL,
            TerminalDoneMarker.detect("C:\\Program Files\\PowerShell\\7\\pwsh.exe"));
        assertEquals(TerminalDoneMarker.Shell.CMD, TerminalDoneMarker.detect("cmd.exe"));
        assertEquals(TerminalDoneMarker.Shell.POSIX, TerminalDoneMarker.detect("/bin/bash --login"));
    }

    @Test
    void unknownOrBlankShellIsNotGuessed() {
        assertNull(TerminalDoneMarker.detect("nu"));
        assertNull(TerminalDoneMarker.detect(""));
        assertNull(TerminalDoneMarker.detect(null));
    }

    @Test
    void suffixAsTypedNeverMatchesTheMarkerRegex() {
        Pattern marker = Pattern.compile(TerminalDoneMarker.regex(TOKEN));
        for (TerminalDoneMarker.Shell shell : TerminalDoneMarker.Shell.values()) {
            assertFalse(marker.matcher(TerminalDoneMarker.suffix(shell, TOKEN)).find(),
                shell + " suffix would match its own echoed command line");
        }
    }

    @Test
    void suffixesUseTheShellsOwnExitCodeVariable() {
        assertTrue(TerminalDoneMarker.suffix(TerminalDoneMarker.Shell.POSIX, TOKEN).contains("$?"));
        assertTrue(TerminalDoneMarker.suffix(TerminalDoneMarker.Shell.FISH, TOKEN).contains("$status"));
        assertTrue(TerminalDoneMarker.suffix(TerminalDoneMarker.Shell.POWERSHELL, TOKEN).contains("$?"));
        assertTrue(TerminalDoneMarker.suffix(TerminalDoneMarker.Shell.CMD, TOKEN).contains("errorlevel"));
    }

    @Test
    void exitCodeIsReadFromThisCallsMarker() {
        assertEquals(0, TerminalDoneMarker.exitCode(TOKEN, "build ok\nAB_DONE_" + TOKEN + "_0\n$ "));
        assertEquals(127, TerminalDoneMarker.exitCode(TOKEN, "AB_DONE_" + TOKEN + "_127"));
    }

    @Test
    void markerFromAnEarlierCommandIsIgnored() {
        assertNull(TerminalDoneMarker.exitCode(TOKEN, "AB_DONE_ffffffff_0\nstill running"));
        assertFalse(Pattern.compile(TerminalDoneMarker.regex(TOKEN)).matcher("AB_DONE_ffffffff_0").find());
    }

    @Test
    void exitCodeIsNullBeforeTheMarkerIsPrinted() {
        assertNull(TerminalDoneMarker.exitCode(TOKEN, "$ make; echo AB_DONE_" + TOKEN + "_$?"));
    }

    @Test
    void tokensAreUniquePerCall() {
        assertNotEquals(TerminalDoneMarker.newToken(), TerminalDoneMarker.newToken());
    }

    @Test
    void exitCodeOfMarkerReadsTheCodeFromAnyToken() {
        assertEquals(0, TerminalDoneMarker.exitCodeOfMarker("AB_DONE_a1b2c3d4_0"));
        assertEquals(127, TerminalDoneMarker.exitCodeOfMarker("AB_DONE_00ff00ff_127"));
    }

    @Test
    void exitCodeOfMarkerIsNullForOtherMatchesAndNull() {
        assertNull(TerminalDoneMarker.exitCodeOfMarker("BUILD SUCCESSFUL"));
        assertNull(TerminalDoneMarker.exitCodeOfMarker(null));
    }

    @Test
    void commandOutputIsWhatTheCommandPrintedBetweenEchoAndMarker() {
        String text = "$ echo hi; echo AB_DONE_" + TOKEN + "_$?\nhi\nthere\nAB_DONE_" + TOKEN + "_0\n$ ";

        assertEquals("hi\nthere", TerminalDoneMarker.commandOutput(TOKEN, text));
    }

    @Test
    void commandOutputIgnoresEarlierCommandsInAReusedTerminal() {
        String text = "$ echo a; echo AB_DONE_11111111_$?\na\nAB_DONE_11111111_0\n"
            + "$ echo b; echo AB_DONE_" + TOKEN + "_$?\nb\nAB_DONE_" + TOKEN + "_0\n$ ";

        assertEquals("b", TerminalDoneMarker.commandOutput(TOKEN, text));
    }

    @Test
    void commandOutputRunsToTheEndWhileTheCommandIsStillRunning() {
        String text = "$ make; echo AB_DONE_" + TOKEN + "_$?\nstep 1\nstep 2";

        assertEquals("step 1\nstep 2", TerminalDoneMarker.commandOutput(TOKEN, text));
    }

    @Test
    void commandOutputIsEmptyWhenTheCommandPrintedNothing() {
        String text = "$ true; echo AB_DONE_" + TOKEN + "_$?\nAB_DONE_" + TOKEN + "_0";

        assertEquals("", TerminalDoneMarker.commandOutput(TOKEN, text));
    }

    @Test
    void commandOutputIsNullWhenTheEchoedCommandIsMissing() {
        assertNull(TerminalDoneMarker.commandOutput(TOKEN, "something else entirely"));
    }
}
