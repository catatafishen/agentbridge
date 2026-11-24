package com.github.catatafishen.agentbridge.psi.tools.terminal;

import com.github.catatafishen.agentbridge.psi.tools.McpRequestDeadline;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parses and validates the {@code wait_seconds} / {@code wait_for} arguments shared by the terminal tools.
 * Pure: it only looks at the JSON, so every rejection is unit-testable.
 */
final class TerminalWaitArgs {

    static final String WAIT_SECONDS = "wait_seconds";
    static final String WAIT_FOR = "wait_for";

    /**
     * The outcome of parsing: no wait requested, a valid wait, or an {@code Error: ...} message for the caller.
     *
     * @param seconds the wait to apply, already clamped to {@link McpRequestDeadline#MAX_TIMEOUT_SECONDS}
     * @param notice  the clamp notice to show, or null when the request was honoured as given
     */
    record Parsed(
        boolean requested,
        int seconds,
        @Nullable Pattern pattern,
        @Nullable String notice,
        @Nullable String error
    ) {
        static Parsed none() {
            return new Parsed(false, 0, null, null, null);
        }

        static Parsed error(@NotNull String message) {
            return new Parsed(false, 0, null, null, message);
        }
    }

    private TerminalWaitArgs() {
    }

    /**
     * @param supportsWaitFor whether {@code wait_for} is accepted. When false the argument is ignored, because
     *                        {@code run_in_terminal} waits on its own completion marker instead.
     */
    static @NotNull Parsed parse(@NotNull JsonObject args, boolean supportsWaitFor) {
        boolean hasWaitFor = supportsWaitFor && args.has(WAIT_FOR) && !args.get(WAIT_FOR).isJsonNull();
        if (!args.has(WAIT_SECONDS) || args.get(WAIT_SECONDS).isJsonNull()) {
            return hasWaitFor
                ? Parsed.error("Error: wait_for requires wait_seconds (the maximum time to wait).")
                : Parsed.none();
        }

        int requested;
        try {
            requested = args.get(WAIT_SECONDS).getAsInt();
        } catch (RuntimeException e) {
            return Parsed.error("Error: wait_seconds must be an integer number of seconds, got: "
                + args.get(WAIT_SECONDS));
        }
        String invalid = McpRequestDeadline.rejectNonPositive(requested);
        if (invalid != null) return Parsed.error(invalid.replace("timeout", WAIT_SECONDS));

        Pattern pattern = null;
        if (hasWaitFor) {
            String regex = args.get(WAIT_FOR).getAsString();
            if (!regex.isBlank()) {
                try {
                    pattern = Pattern.compile(regex);
                } catch (PatternSyntaxException e) {
                    return Parsed.error("Error: wait_for is not a valid regex: " + e.getDescription());
                }
            }
        }
        return new Parsed(true, McpRequestDeadline.clamp(requested), pattern,
            McpRequestDeadline.clampNotice(requested), null);
    }
}
