package com.github.catatafishen.agentbridge.psi.tools.file;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure preview builder for the Ask-permission bubble of the file-edit MCP tools
 * ({@code write_file} / {@code edit_text}): computes the whole-file before/after
 * texts the proposed edit would produce, so the chat panels can render the same
 * collapsible diff card the ACP edit approvals use (args {@code path},
 * {@code diff}, {@code diffAdded}, {@code diffRemoved}, {@code oldText},
 * {@code newText}).
 *
 * <p>Mirrors {@link WriteFileTool}'s argument precedence ({@code content} full
 * write, then {@code old_str}/{@code new_str} partial edit, then
 * {@code start_line}/{@code new_str} line-range replace) and its edit semantics
 * (newline normalization, {@code replace_all}, {@code case_sensitive}, first-match
 * index). The caller supplies the current file text (live Document when the file
 * is open, disk content otherwise) so this class stays free of IntelliJ platform
 * dependencies and unit-testable in isolation per the repo's UI/logic separation
 * rule. Preview computation never modifies anything; when the call would not
 * produce a renderable change the result is {@code null} and the bubble keeps its
 * plain parameter rows.</p>
 */
public final class EditPreviewBuilder {

    /** Max lines of unified diff shown in the approval card (parity with the ACP path). */
    private static final int MAX_DIFF_LINES = 400;
    /** Context lines kept around each changed line in the card diff. */
    private static final int DIFF_CONTEXT_LINES = 3;
    /**
     * Line-count guard for the O(n*m) LCS table; larger inputs degrade to an
     * additions-only summary so huge files cannot stall the pooled thread.
     */
    private static final int MAX_LCS_LINES = 4000;
    /** Cell-count guard for the LCS table (bounds memory, not just lines). */
    private static final long MAX_LCS_CELLS = 2_000_000L;

    /** Unified-diff op tags. */
    private static final String OP_EQUAL = " ";
    private static final String OP_REMOVED = "-";
    private static final String OP_ADDED = "+";

    private EditPreviewBuilder() {
    }

    /** Renderable preview of a proposed file edit, or {@code null} (nothing to show). */
    public record Preview(@NotNull String path,
                          @Nullable String oldText,
                          @NotNull String newText) {
    }

    /**
     * Computes the before/after texts for a {@code write_file} / {@code edit_text}
     * call. {@code currentText} is the file's current content ({@code null} when the
     * file does not exist — a new file), exactly as the tool itself would read it.
     * Returns {@code null} when the arguments do not describe a file edit, the edit
     * cannot be previewed (e.g. line range out of bounds), or the texts are equal.
     */
    public static @Nullable Preview buildPreview(@Nullable JsonObject args,
                                                 @Nullable String currentText) {
        if (args == null) return null;
        String path = stringArg(args, "path");
        if (path == null) path = stringArg(args, "file");
        if (path == null || path.isBlank()) return null;

        if (args.has("content") && args.get("content").isJsonPrimitive()) {
            String content = args.get("content").getAsString();
            if (currentText != null && currentText.equals(content)) return null;
            return new Preview(path, currentText, content);
        }

        if (args.has("old_str") && args.has("new_str")) {
            String oldStr = normalizeNewlines(stringArg(args, "old_str"));
            String newStr = normalizeNewlines(stringArg(args, "new_str"));
            if (oldStr == null || newStr == null) return null;
            boolean replaceAll = boolArg(args, "replace_all", false);
            boolean caseSensitive = boolArg(args, "case_sensitive", true);
            String text = currentText != null ? currentText : "";
            String searchable = caseSensitive ? text : text.toLowerCase();
            String target = caseSensitive ? oldStr : oldStr.toLowerCase();
            if (oldStr.isEmpty() && newStr.isEmpty()) return null;
            String after;
            if (oldStr.isEmpty()) {
                // Appending to an (existing) empty target mirrors the tool's
                // indexOf miss for an empty old_str: no previewable edit.
                return null;
            }
            int idx = searchable.indexOf(target);
            if (idx < 0) return null;
            if (!replaceAll && searchable.indexOf(target, idx + 1) >= 0) {
                // The tool itself rejects ambiguous matches before any edit; the
                // preview must not fabricate a single-match result.
                return null;
            }
            // Replacement is spliced into the ORIGINAL text (case preserved) — the
            // lowered copies are only for finding positions, exactly like the tool.
            after = replaceAll ? replaceAllOccurrences(text, oldStr, newStr, caseSensitive)
                : text.substring(0, idx) + newStr + text.substring(idx + oldStr.length());
            if (after.equals(currentText)) return null;
            return new Preview(path, currentText, after);
        }

        if (args.has("start_line") && args.has("new_str")) {
            if (currentText == null) return null;
            Integer startLine = intArg(args, "start_line");
            Integer endLine = args.has("end_line") ? intArg(args, "end_line") : startLine;
            String newStr = normalizeNewlines(stringArg(args, "new_str"));
            if (startLine == null || newStr == null) return null;
            if (endLine == null) endLine = startLine;
            String[] lines = currentText.split("\n", -1);
            // split() yields a phantom empty element for a trailing newline;
            // Document.getLineCount() does not — validate against the real count
            // so an out-of-range start_line never renders a card for an edit the
            // tool itself will reject.
            int lineCount = lines.length - (currentText.endsWith("\n") ? 1 : 0);
            if (startLine < 1 || startLine > lineCount) return null;
            if (endLine < startLine || endLine > lineCount) return null;
            if (!newStr.isEmpty() && !newStr.endsWith("\n")) newStr = newStr + "\n";
            StringBuilder after = new StringBuilder();
            for (int i = 0; i < startLine - 1; i++) after.append(lines[i]).append('\n');
            after.append(newStr);
            for (int i = endLine; i < lines.length; i++) {
                after.append(lines[i]);
                if (i < lines.length - 1) after.append('\n');
            }
            String result = after.toString();
            if (result.equals(currentText)) return null;
            return new Preview(path, currentText, result);
        }

        return null;
    }

    /**
     * {@code [+added, -removed]} change counts computed from the FULL change before
     * any display truncation, so the header stats always reflect the true magnitude.
     */
    public static int[] changeStats(@Nullable String oldText, @NotNull String newText) {
        List<String> oldLines = splitLines(oldText);
        List<String> newLines = splitLines(newText);
        int n = oldLines.size();
        int m = newLines.size();
        if (n > MAX_LCS_LINES || m > MAX_LCS_LINES || (long) n * m > MAX_LCS_CELLS) {
            // Too large for the LCS table: the whole new content counts as additions.
            return new int[]{m, n};
        }
        int[][] table = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                table[i][j] = oldLines.get(i).equals(newLines.get(j))
                    ? table[i + 1][j + 1] + 1
                    : Math.max(table[i + 1][j], table[i][j + 1]);
            }
        }
        int added = 0;
        int removed = 0;
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (oldLines.get(i).equals(newLines.get(j))) {
                i++;
                j++;
            } else if (table[i + 1][j] >= table[i][j + 1]) {
                removed++;
                i++;
            } else {
                added++;
                j++;
            }
        }
        added += m - j;
        removed += n - i;
        return new int[]{added, removed};
    }

    /**
     * Bounded unified line diff for the approval card, or {@code null} when there is
     * nothing to show. Unchanged regions collapse to {@link #DIFF_CONTEXT_LINES}
     * context lines around changes; output is capped at {@link #MAX_DIFF_LINES} lines
     * with a trailing {@code … N more lines} marker. {@code oldText == null} (new
     * file) renders as pure additions. Inputs beyond the LCS guards degrade to an
     * additions-only summary so huge files cannot stall the pooled thread.
     */
    public static @Nullable String boundedDiff(@Nullable String oldText, @NotNull String newText) {
        if (oldText != null && oldText.equals(newText)) return null;
        List<String> oldLines = splitLines(oldText);
        List<String> newLines = splitLines(newText);
        int n = oldLines.size();
        int m = newLines.size();
        if (n == 0 && m == 0) return null;
        List<String[]> ops = new ArrayList<>(n + m);
        if (n > MAX_LCS_LINES || m > MAX_LCS_LINES || (long) n * m > MAX_LCS_CELLS) {
            for (String line : newLines) {
                ops.add(new String[]{OP_ADDED, line});
            }
        } else {
            int[][] table = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--) {
                for (int j = m - 1; j >= 0; j--) {
                    table[i][j] = oldLines.get(i).equals(newLines.get(j))
                        ? table[i + 1][j + 1] + 1
                        : Math.max(table[i + 1][j], table[i][j + 1]);
                }
            }
            int i = 0;
            int j = 0;
            while (i < n && j < m) {
                if (oldLines.get(i).equals(newLines.get(j))) {
                    ops.add(new String[]{OP_EQUAL, oldLines.get(i)});
                    i++;
                    j++;
                } else if (table[i + 1][j] >= table[i][j + 1]) {
                    ops.add(new String[]{OP_REMOVED, oldLines.get(i)});
                    i++;
                } else {
                    ops.add(new String[]{OP_ADDED, newLines.get(j)});
                    j++;
                }
            }
            while (i < n) {
                ops.add(new String[]{OP_REMOVED, oldLines.get(i)});
                i++;
            }
            while (j < m) {
                ops.add(new String[]{OP_ADDED, newLines.get(j)});
                j++;
            }
        }
        return renderBoundedDiff(ops);
    }

    /**
     * Renders the op list with unchanged regions collapsed to context lines around
     * changes, capped at {@link #MAX_DIFF_LINES} output lines with a trailing
     * {@code … N more lines} marker when cut.
     */
    private static String renderBoundedDiff(List<String[]> ops) {
        int total = ops.size();
        boolean[] keep = new boolean[total];
        for (int k = 0; k < total; k++) {
            if (!OP_EQUAL.equals(ops.get(k)[0])) {
                int from = Math.max(0, k - DIFF_CONTEXT_LINES);
                int to = Math.min(total - 1, k + DIFF_CONTEXT_LINES);
                for (int c = from; c <= to; c++) {
                    keep[c] = true;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        int emitted = 0;
        boolean skipping = false;
        for (int k = 0; k < total; k++) {
            if (!keep[k]) {
                if (!skipping) {
                    sb.append('\u2026').append('\n');
                    skipping = true;
                }
                continue;
            }
            skipping = false;
            if (emitted >= MAX_DIFF_LINES) {
                int rest = 0;
                for (int r = k; r < total; r++) {
                    if (keep[r]) rest++;
                }
                if (rest > 0) {
                    sb.append('\u2026').append(' ').append(rest).append(" more lines").append('\n');
                }
                break;
            }
            String[] op = ops.get(k);
            sb.append(op[0]).append(op[1]).append('\n');
            emitted++;
        }
        return sb.toString();
    }

    /**
     * Replaces every occurrence of {@code target} in {@code text} with {@code replacement},
     * matching case-insensitively when requested but always splicing into the ORIGINAL
     * text (positions are found in a lowered copy; characters outside the matches keep
     * their case, exactly like the tool's document edit).
     */
    private static String replaceAllOccurrences(String text, String target, String replacement,
                                                boolean caseSensitive) {
        if (caseSensitive) return text.replace(target, replacement);
        StringBuilder sb = new StringBuilder(text.length());
        String lowered = text.toLowerCase();
        String loweredTarget = target.toLowerCase();
        int i = 0;
        while (i < text.length()) {
            int idx = lowered.indexOf(loweredTarget, i);
            if (idx < 0) {
                sb.append(text, i, text.length());
                break;
            }
            sb.append(text, i, idx).append(replacement);
            i = idx + target.length();
        }
        return sb.toString();
    }

    private static @NotNull List<String> splitLines(@Nullable String text) {
        if (text == null) return List.of();
        return List.of(text.split("\\R", -1));
    }

    private static @Nullable String normalizeNewlines(@Nullable String s) {
        return s == null ? null : s.replace("\r\n", "\n").replace("\r", "\n");
    }

    private static @Nullable String stringArg(@NotNull JsonObject args, @NotNull String key) {
        return args.has(key) && args.get(key).isJsonPrimitive()
            ? args.get(key).getAsString() : null;
    }

    private static boolean boolArg(@NotNull JsonObject args, @NotNull String key, boolean dflt) {
        return args.has(key) && args.get(key).isJsonPrimitive() && args.get(key).getAsBoolean()
            || (!args.has(key) && dflt);
    }

    private static @Nullable Integer intArg(@NotNull JsonObject args, @NotNull String key) {
        if (!args.has(key) || !args.get(key).isJsonPrimitive()) return null;
        try {
            return args.get(key).getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return null;
        }
    }
}
