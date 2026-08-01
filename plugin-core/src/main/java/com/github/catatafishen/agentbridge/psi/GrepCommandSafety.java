package com.github.catatafishen.agentbridge.psi;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses a shell command string into the individual {@code grep}/{@code rg} invocations it
 * contains, so callers can decide whether each one is safe to run through {@code run_command}.
 *
 * <p>{@code grep} is restricted because searching files on disk returns stale content — the live
 * text lives in IntelliJ's editor buffers, which {@code search_text}/{@code search_symbols} read.
 * That rationale only applies when grep actually reads project source files. Two shapes are
 * therefore harmless and must not be blocked:
 *
 * <ul>
 *   <li><b>Pipe filters</b> — {@code gh pr checks 913 | grep -Ei "build|analyze"} has no file
 *       operand at all; its input is the previous command's stdout, so there is no file to be
 *       stale about.</li>
 *   <li><b>Files outside the source roots</b> — log files, {@code /tmp} scratch output and
 *       downloaded CI artifacts are never mirrored in an editor buffer.</li>
 * </ul>
 *
 * <p>A leading {@code grep pattern} / {@code rg pattern} with no operand is <em>not</em> treated
 * as a pipe filter: with nothing piped into it, {@code rg} recursively searches the working
 * directory and {@code grep} waits on the terminal, so neither is a safe stdout filter.
 *
 * <p>This class is deliberately pure — no {@code Project}, VFS or PSI access — so the parsing
 * rules can be unit-tested directly. Deciding whether a given path sits inside a source root is
 * the caller's job (see {@link ToolUtils#grepTargetsOnlyOutsideSourceRoots}).
 */
public final class GrepCommandSafety {

    /**
     * Tokens that terminate one command in a pipeline or list. Only {@code |} and {@code |&}
     * connect the previous command's stdout to the next command's stdin; {@code &&}, {@code ||}
     * and {@code ;} merely sequence commands, so a grep after one of them is not a pipe filter.
     */
    private static final Set<String> COMMAND_SEPARATORS = Set.of("|", "|&", "||", "&&", ";");

    private static final Set<String> PIPE_SEPARATORS = Set.of("|", "|&");

    /**
     * Commands that may prefix {@code grep}/{@code rg} without changing what it reads —
     * {@code sudo grep pattern file} and {@code env grep pattern file} still open exactly the
     * file operands that follow. Anything else leading the segment (most importantly
     * {@code xargs}, but also {@code find -exec}, {@code parallel}, etc.) turns stdin into the
     * grep's file operands, so that segment must not be recognised as a grep invocation at all —
     * its "no explicit path operand" shape would otherwise be mistaken for a harmless pipe
     * filter even though it opens whatever paths the wrapper supplies.
     */
    private static final Set<String> SAFE_WRAPPER_COMMANDS = Set.of("sudo", "env");

    /**
     * Flags that consume the following token as their own argument, so that token must not be
     * mistaken for a pattern or a path.
     */
    private static final Set<String> TWO_ARG_FLAGS = Set.of(
        "-e", "-f", "--regexp", "--file",
        "--include", "--exclude", "--exclude-dir", "--include-dir",
        "-A", "-B", "-C", "--after-context", "--before-context", "--context",
        "-m", "--max-count", "--max-depth", "-t", "-T", "--type", "--type-not",
        "-g", "--glob", "--iglob"
    );

    /**
     * Flags that supply the search pattern, meaning the first bare token is already a path.
     */
    private static final Set<String> PATTERN_FLAGS = Set.of("-e", "-f", "--regexp", "--file");

    private GrepCommandSafety() {
    }

    /**
     * A single {@code grep}/{@code rg} invocation found in a command.
     *
     * @param readsPipedStdin whether the previous command pipes its stdout into this one
     * @param paths           explicit file/directory operands, or {@code null} when an operand
     *                        contains a glob — the shell expands those before grep runs, so the
     *                        real targets cannot be known here and the invocation must be
     *                        treated as unsafe rather than guessed at
     */
    public record GrepInvocation(boolean readsPipedStdin, @Nullable List<String> paths) {

        /**
         * A pipe filter consumes the previous command's stdout and never opens a file, so no
         * file can be stale.
         */
        public boolean isPipeFilter() {
            return readsPipedStdin && paths != null && paths.isEmpty();
        }
    }

    /**
     * Returns every {@code grep}/{@code rg} invocation in {@code command}, in order. An empty
     * list means the command does not invoke grep at all.
     */
    public static @NotNull List<GrepInvocation> analyze(@NotNull String command) {
        List<GrepInvocation> invocations = new ArrayList<>();
        for (CommandSegment segment : splitIntoSegments(tokenize(command))) {
            int grepIndex = indexOfGrep(segment.tokens());
            if (grepIndex < 0) continue;
            invocations.add(new GrepInvocation(
                segment.fedByPipe(), collectPathArgs(segment.tokens(), grepIndex + 1)));
        }
        return invocations;
    }

    /**
     * Splits a shell command into tokens, respecting single and double quotes. Unquoted
     * {@code |}, {@code |&}, {@code ||}, {@code &&} and {@code ;} are emitted as tokens of their
     * own, so {@code gh pr checks 1|grep build} splits the same way as the spaced form — shells
     * do not require whitespace around these operators, and the unspaced form is common enough
     * that missing it would keep rejecting pipe filters this class exists to allow.
     *
     * <p>A lone {@code &} is deliberately <em>not</em> an operator here: splitting on it would
     * tear redirections such as {@code 2>&1} apart, and backgrounding a command does not change
     * which files a later grep opens.
     *
     * <p>Backslash escapes inside quotes are not handled — adequate for the simple patterns and
     * paths handled here.
     */
    public static @NotNull List<String> tokenize(@NotNull String command) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        int i = 0;
        while (i < command.length()) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else cur.append(c);
                i++;
            } else if (c == '\'' || c == '"') {
                quote = c;
                i++;
            } else if (Character.isWhitespace(c)) {
                flushToken(out, cur);
                i++;
            } else {
                String operator = operatorAt(command, i);
                if (operator != null) {
                    flushToken(out, cur);
                    out.add(operator);
                    i += operator.length();
                } else {
                    cur.append(c);
                    i++;
                }
            }
        }
        flushToken(out, cur);
        return out;
    }

    private static void flushToken(@NotNull List<String> out, @NotNull StringBuilder cur) {
        if (!cur.isEmpty()) {
            out.add(cur.toString());
            cur.setLength(0);
        }
    }

    /**
     * Returns the command separator starting at {@code index}, or {@code null} when no separator
     * starts there. Longer operators are matched before their prefixes so {@code ||} is never
     * read as two pipes.
     */
    private static @Nullable String operatorAt(@NotNull String command, int index) {
        char c = command.charAt(index);
        if (c == ';') return ";";
        char next = index + 1 < command.length() ? command.charAt(index + 1) : 0;
        if (c == '|') {
            if (next == '&') return "|&";
            if (next == '|') return "||";
            return "|";
        }
        if (c == '&' && next == '&') return "&&";
        return null;
    }

    /**
     * Returns the explicit path operands of the first {@code grep}/{@code rg} invocation, or an
     * empty list when there are none, when an operand contains a glob, or when the command does
     * not invoke grep.
     */
    static @NotNull List<String> firstInvocationPaths(@NotNull String command) {
        List<GrepInvocation> invocations = analyze(command);
        if (invocations.isEmpty()) return List.of();
        List<String> paths = invocations.getFirst().paths();
        return paths != null ? paths : List.of();
    }

    private record CommandSegment(boolean fedByPipe, List<String> tokens) {
    }

    private static List<CommandSegment> splitIntoSegments(List<String> tokens) {
        List<CommandSegment> segments = new ArrayList<>();
        List<String> current = new ArrayList<>();
        boolean fedByPipe = false;
        for (String token : tokens) {
            if (COMMAND_SEPARATORS.contains(token)) {
                segments.add(new CommandSegment(fedByPipe, current));
                current = new ArrayList<>();
                fedByPipe = PIPE_SEPARATORS.contains(token);
            } else {
                current.add(token);
            }
        }
        segments.add(new CommandSegment(fedByPipe, current));
        return segments;
    }

    /**
     * Returns the index of {@code grep}/{@code rg} when it is the segment's own command —
     * optionally after leading {@link #SAFE_WRAPPER_COMMANDS} — or {@code -1} when the segment
     * does not run grep as its command. A {@code grep}/{@code rg} token found deeper in the
     * segment (e.g. as {@code xargs}'s argument in {@code xargs grep pattern}) is deliberately
     * not matched: {@code xargs} turns lines read from stdin into extra arguments appended after
     * the ones written here, so the invocation's real file operands cannot be determined from
     * the command text alone and must not be treated as empty.
     */
    private static int indexOfGrep(List<String> tokens) {
        int i = 0;
        while (i < tokens.size() && SAFE_WRAPPER_COMMANDS.contains(tokens.get(i).toLowerCase(Locale.ROOT))) {
            i++;
        }
        if (i >= tokens.size()) return -1;
        String token = tokens.get(i);
        return token.equalsIgnoreCase("grep") || token.equalsIgnoreCase("rg") ? i : -1;
    }

    /**
     * Collects the path operands that follow a grep invocation, or returns {@code null} when one
     * of them contains a glob.
     */
    private static @Nullable List<String> collectPathArgs(List<String> tokens, int from) {
        Operands operands = stripFlags(tokens, from);
        List<String> paths = new ArrayList<>();
        boolean patternConsumed = operands.patternConsumed();
        for (String operand : operands.values()) {
            if (!patternConsumed) {
                patternConsumed = true;
            } else if (containsGlob(operand)) {
                return null;
            } else {
                paths.add(operand);
            }
        }
        return paths;
    }

    /**
     * Drops option flags and the arguments they consume, leaving the positional operands.
     *
     * @return the operands, plus whether a flag such as {@code -e} already supplied the pattern
     * (in which case the first operand is a path rather than the pattern)
     */
    private static Operands stripFlags(List<String> tokens, int from) {
        List<String> operands = new ArrayList<>();
        boolean patternConsumed = false;
        boolean skipNext = false;
        for (int i = from; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (skipNext) {
                skipNext = false;
            } else if (isRedirection(token)) {
                // A redirection names a stream or an output file, never a file grep reads, so it
                // must not be counted as a path operand — `grep -i x a.log 2>&1` searches a.log
                // only. "2>" and "<" with a detached target consume the token after them.
                skipNext = token.endsWith(">") || token.endsWith("<");
            } else if (isFlag(token)) {
                skipNext = TWO_ARG_FLAGS.contains(token);
                patternConsumed |= PATTERN_FLAGS.contains(token);
            } else {
                operands.add(token);
            }
        }
        return new Operands(operands, patternConsumed);
    }

    /**
     * A lone {@code -} means stdin, so it is an operand rather than a flag.
     */
    private static boolean isFlag(String token) {
        return token.startsWith("-") && !token.equals("-");
    }

    /**
     * Recognises the redirection shapes that can follow a grep invocation: {@code >}, {@code >>},
     * {@code 2>}, {@code &>}, {@code 2>&1} and {@code <}, whether or not the target is attached.
     */
    private static boolean isRedirection(String token) {
        return token.indexOf('>') >= 0 || token.startsWith("<");
    }

    private record Operands(List<String> values, boolean patternConsumed) {
    }

    private static boolean containsGlob(String s) {
        return s.indexOf('*') >= 0 || s.indexOf('?') >= 0 || s.indexOf('[') >= 0;
    }
}
