package com.github.catatafishen.agentbridge.psi.tools.terminal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Builds the text {@code run_in_terminal} returns after waiting: why the wait ended, the exit code, and the output
 * of this command. Pure, so each branch (finished, still running, no marker, dormant terminal) is unit-testable.
 */
final class TerminalWaitResultFormatter {

    private TerminalWaitResultFormatter() {
    }

    /**
     * @param waitNote   the one-line reason the wait ended, from {@link TerminalWaitTracker#describe}
     * @param token      the completion-marker token added to the command, or null when the shell was not
     *                   recognised and no marker could be added
     * @param shell      the shell path or name, used in the message when there is no marker
     * @param terminalId the terminal to continue waiting on, used in the "not finished" hint
     * @param text       the full terminal text after the wait
     * @param maxLines   how many trailing lines of output to include; 0 or less means all
     */
    static @NotNull String format(
        @NotNull String waitNote,
        @Nullable String token,
        @Nullable String shell,
        @Nullable String terminalId,
        @NotNull String text,
        int maxLines
    ) {
        StringBuilder out = new StringBuilder(waitNote);
        if (token != null) {
            Integer exit = TerminalDoneMarker.exitCode(token, text);
            if (exit != null) {
                out.append("\nExit code: ").append(exit);
            } else {
                out.append("\nNot finished yet. Keep waiting with read_terminal_output(terminal_id=")
                    .append(terminalId).append(", wait_seconds=<max>, wait_for=\"")
                    .append(TerminalDoneMarker.regex(token)).append("\").");
            }
        } else {
            out.append("\nNo exit code: the shell '").append(shell == null ? "<unknown>" : shell)
                .append("' is not recognised, so no completion marker was added and the wait ended on idle output.");
        }
        String warning = TerminalTool.dormantTerminalWarning(text);
        if (warning != null) out.append("\n\n").append(warning);
        appendOutput(out, token, text, maxLines);
        return out.toString();
    }

    /**
     * Appends this command's own output. In a reused terminal the buffer holds every earlier command too, so with
     * a marker the output is cut to what this command printed. If that cannot be done (echoed line not found) or
     * there is no marker, the tail of the whole buffer is shown, and the heading says so.
     */
    private static void appendOutput(
        @NotNull StringBuilder out, @Nullable String token, @NotNull String text, int maxLines
    ) {
        if (text.isBlank()) return;
        String own = token == null ? null : TerminalDoneMarker.commandOutput(token, text);
        if (own != null) {
            out.append("\n\nOutput of this command:\n")
                .append(own.isEmpty() ? "(no output)" : TerminalTool.tailLines(own, maxLines));
        } else {
            out.append("\n\nTerminal buffer (last lines):\n").append(TerminalTool.tailLines(text.strip(), maxLines));
        }
    }
}
