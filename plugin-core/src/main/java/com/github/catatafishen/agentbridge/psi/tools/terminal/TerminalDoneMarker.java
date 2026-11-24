package com.github.catatafishen.agentbridge.psi.tools.terminal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the shell-specific command suffix that prints a completion marker carrying the exit code, so that
 * {@code run_in_terminal} can wait for the command to finish instead of the caller sleeping.
 *
 * <p>The marker is {@code AB_DONE_<token>_<digits>}. The token is unique per call, so a marker printed by an
 * earlier command in the same (reused) terminal can never be mistaken for this command's completion. Every suffix
 * is written so that the command line as echoed by the terminal does <em>not</em> match {@link #regex}: the digits
 * only appear in the shell's output, never in the echoed command.
 */
final class TerminalDoneMarker {

    static final String PREFIX = "AB_DONE_";

    /**
     * Shell families that need a different way to print the previous exit code.
     */
    enum Shell {POSIX, FISH, POWERSHELL, CMD}

    private TerminalDoneMarker() {
    }

    /**
     * A fresh token, unique enough to tell this call's marker from any earlier one in the terminal buffer.
     */
    static @NotNull String newToken() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static @NotNull String marker(@NotNull String token) {
        return PREFIX + token + "_";
    }

    /**
     * Matches this call's marker as printed, with its exit code.
     */
    static @NotNull String regex(@NotNull String token) {
        return marker(token) + "\\d+";
    }

    /**
     * Extracts the exit code from the terminal text, or null when this call's marker has not been printed yet.
     */
    static @Nullable Integer exitCode(@NotNull String token, @NotNull String terminalText) {
        Matcher m = Pattern.compile(marker(token) + "(\\d+)").matcher(terminalText);
        if (!m.find()) return null;
        return parseCode(m.group(1));
    }

    private static final Pattern ANY_MARKER = Pattern.compile(PREFIX + "[0-9a-f]+_(\\d+)");

    /**
     * Extracts the exit code from text that matched a marker, whatever its token. Used by
     * {@code read_terminal_output}, which only knows the {@code wait_for} regex the caller passed and not the
     * token. Returns null when the text holds no marker.
     */
    static @Nullable Integer exitCodeOfMarker(@Nullable String matched) {
        if (matched == null) return null;
        Matcher m = ANY_MARKER.matcher(matched);
        return m.find() ? parseCode(m.group(1)) : null;
    }

    private static @Nullable Integer parseCode(@NotNull String digits) {
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The output of this call's command only, taken from the terminal buffer: what follows the echoed command
     * line, up to the printed marker (or to the end when the command has not finished). The buffer also holds
     * every earlier command and prompt of a reused terminal, which the caller does not want.
     *
     * <p>Returns null when the echoed command line cannot be found, for instance because the terminal wrapped
     * it in the middle of the token. The caller then falls back to the raw buffer rather than guessing.
     */
    static @Nullable String commandOutput(@NotNull String token, @NotNull String terminalText) {
        String prefix = marker(token);
        Matcher printed = Pattern.compile(Pattern.quote(prefix) + "\\d+").matcher(terminalText);
        int markerStart = -1;
        while (printed.find()) markerStart = printed.start();

        // The echoed command line holds the prefix but never digits after it, so it is the last prefix before
        // the printed marker; when nothing was printed yet, it is the last prefix in the buffer.
        int echo = markerStart >= 0
            ? terminalText.lastIndexOf(prefix, markerStart - 1)
            : terminalText.lastIndexOf(prefix);
        if (echo < 0) return null;

        int echoLineEnd = terminalText.indexOf('\n', echo);
        if (echoLineEnd < 0) return "";
        int from = echoLineEnd + 1;
        int to = markerStart >= from ? markerStart : terminalText.length();
        return terminalText.substring(from, to).strip();
    }

    /**
     * Classifies a shell executable path or name. Returns null when it is not recognised, so the caller can say
     * that the suffix is an assumption instead of silently guessing.
     */
    static @Nullable Shell detect(@Nullable String shellPathOrName) {
        if (shellPathOrName == null || shellPathOrName.isBlank()) return null;
        String name = shellPathOrName.strip().toLowerCase(Locale.ROOT);
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int space = name.indexOf(' ');
        if (space > 0) name = name.substring(0, space);
        if (name.endsWith(".exe")) name = name.substring(0, name.length() - 4);
        return switch (name) {
            case "bash", "zsh", "sh", "dash", "ksh", "ash", "mksh" -> Shell.POSIX;
            case "fish" -> Shell.FISH;
            case "pwsh", "powershell" -> Shell.POWERSHELL;
            case "cmd" -> Shell.CMD;
            default -> null;
        };
    }

    /**
     * The suffix to append to a command. The exit code of the command is printed after the marker; cmd.exe and
     * PowerShell report only 0 or 1.
     */
    static @NotNull String suffix(@NotNull Shell shell, @NotNull String token) {
        String marker = marker(token);
        return switch (shell) {
            case POSIX -> "; echo " + marker + "$?";
            case FISH -> "; echo " + marker + "$status";
            case POWERSHELL -> "; echo \"" + marker + "$(if ($?) { 0 } else { 1 })\"";
            // The caret escapes the digit, so the echoed command line does not contain "<marker><digit>".
            case CMD -> " & if errorlevel 1 (echo " + marker + "^1) else (echo " + marker + "^0)";
        };
    }
}
