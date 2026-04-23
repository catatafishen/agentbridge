package com.github.catatafishen.agentbridge.acp.client.intercept;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight shell-like tokenizer used to decide whether an ACP {@code terminal/create}
 * command is safe to intercept and redirect to an MCP tool.
 *
 * <p><b>Conservative by design:</b> any command that contains shell metacharacters
 * (pipes, redirects, command substitution, subshells, chaining, env-var expansion)
 * returns {@code null} from {@link #tokenize(String)} so the caller falls back to
 * the original (visible) terminal path.
 *
 * <p>Supported quoting: single quotes (literal) and double quotes (no expansion —
 * we never run an actual shell, so {@code $VAR} inside double quotes is left as the
 * literal string for the agent to pick up if needed). Backslash escapes a single
 * following character.
 */
public final class ShellCommandSplitter {

    private static final String UNSAFE_CHARS = "|<>;&`$()";

    private ShellCommandSplitter() {
    }

    /**
     * Split {@code command} into tokens, or return {@code null} when the command contains
     * shell metacharacters that make safe redirection impossible.
     *
     * @param command the raw command line (as passed to {@code terminal/create})
     * @return list of argv-style tokens, or {@code null} if the command is too complex to redirect
     */
    public static @Nullable List<String> tokenize(@NotNull String command) {
        String trimmed = command.trim();
        if (trimmed.isEmpty()) return null;

        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        int len = trimmed.length();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean hasContent = false;

        while (i < len) {
            char c = trimmed.charAt(i);

            if (!inSingle && !inDouble && UNSAFE_CHARS.indexOf(c) >= 0) {
                return null;
            }

            if (c == '\\' && !inSingle && i + 1 < len) {
                current.append(trimmed.charAt(i + 1));
                hasContent = true;
                i += 2;
                continue;
            }

            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                hasContent = true;
                i++;
                continue;
            }

            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                hasContent = true;
                i++;
                continue;
            }

            if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (hasContent) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    hasContent = false;
                }
                i++;
                continue;
            }

            current.append(c);
            hasContent = true;
            i++;
        }

        if (inSingle || inDouble) return null;
        if (hasContent) tokens.add(current.toString());

        return tokens.isEmpty() ? null : tokens;
    }
}
