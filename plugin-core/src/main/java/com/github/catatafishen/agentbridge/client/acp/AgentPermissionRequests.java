package com.github.catatafishen.agentbridge.client.acp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure classification and presentation helpers for ACP-native permission requests —
 * the agent asking the USER to approve an action (e.g. edit-approval requests titled
 * {@code "Approve edit: <path>"}, command approvals titled {@code "<description>: <command>"}),
 * as opposed to an agent tool invocation that AgentBridge itself should gate or reprimand.
 * <p>
 * Detection uses protocol shape, never title parsing: user-approval requests carry
 * {@code toolCall.kind "edit"} plus a diff content block or {@code kind "execute"} plus a
 * {@code rawInput.command} string. A bare {@code kind} without the matching payload
 * (e.g. a native {@code apply_patch} tool call) stays on the built-in tool path. This keeps
 * the classification agent-agnostic and immune to path separators inside display titles —
 * the root cause of the silent auto-deny / auto-approve bypass this class exists for.
 * <p>
 * No UI or IntelliJ platform dependencies — unit-testable in isolation per the repo's
 * UI/logic separation rule. Extracted from {@code AcpClient} (permission-flow hotspot)
 * so the client keeps only the wiring.
 */
final class AgentPermissionRequests {

    /** De-facto title-prefix convention for agent edit-approval requests. */
    static final String EDIT_TITLE_PREFIX = "Approve edit:";

    private static final String KEY_TOOL_CALL = "toolCall";
    private static final String KEY_KIND = "kind";
    private static final String KEY_TITLE = "title";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_CONTENT_TYPE = "type";
    private static final String KEY_PATH = "path";
    private static final String KEY_OLD_TEXT = "oldText";
    private static final String KEY_NEW_TEXT = "newText";
    private static final String KEY_RAW_INPUT = "rawInput";
    private static final String KEY_RAW_INPUT_COMMAND = "command";

    private static final String VALUE_KIND_EDIT = "edit";
    private static final String VALUE_KIND_EXECUTE = "execute";
    private static final String VALUE_CONTENT_TYPE_DIFF = "diff";

    /** Max lines of unified diff shown in the approval card. */
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

    private AgentPermissionRequests() {
    }

    /**
     * The protocol kind of an agent-native permission request, or {@code null} when the
     * params are NOT an agent permission request — i.e. a normal agent tool invocation that
     * AgentBridge itself should gate or reprimand.
     * <p>
     * Wire shape verified against the ACP SDK serialization ({@code model_dump(by_alias=True)}):
     * diff content blocks are {@code {type: "diff", path, newText, oldText}} (camelCase),
     * command approvals carry {@code rawInput.command}.
     */
    static @Nullable String requestKind(@Nullable JsonObject params) {
        JsonObject toolCall = params != null && params.has(KEY_TOOL_CALL)
            && params.get(KEY_TOOL_CALL).isJsonObject()
            ? params.getAsJsonObject(KEY_TOOL_CALL) : null;
        if (toolCall == null) return null;
        String kind = getStringOrEmpty(toolCall, KEY_KIND);
        if (VALUE_KIND_EDIT.equals(kind)) {
            // Edit approvals carry a diff content block per the ACP content schema.
            return hasDiffContentBlock(toolCall) ? VALUE_KIND_EDIT : null;
        }
        if (VALUE_KIND_EXECUTE.equals(kind) && hasCommandRawInput(toolCall)) {
            return VALUE_KIND_EXECUTE;
        }
        return null;
    }

    /**
     * Builds the {@code {question, args}} JSON context the chat bubble renders (same shape
     * PsiBridgeService sends for MCP tool prompts). Edit approvals carry the target
     * {@code path} plus a bounded unified {@code diff} of the change (the raw diff payload is
     * whole-file content per the ACP schema — see {@link #bubbleDiff}); command approvals
     * carry the {@code command} text; anything unrecognized falls back to the raw title.
     */
    @NotNull
    static String bubbleContext(@NotNull JsonObject params, @NotNull String toolId) {
        JsonObject context = new JsonObject();
        JsonObject toolCall = params.has(KEY_TOOL_CALL) && params.get(KEY_TOOL_CALL).isJsonObject()
            ? params.getAsJsonObject(KEY_TOOL_CALL) : new JsonObject();
        JsonObject args = new JsonObject();
        if (toolCall.has(KEY_CONTENT) && toolCall.get(KEY_CONTENT).isJsonArray()) {
            for (JsonElement el : toolCall.getAsJsonArray(KEY_CONTENT)) {
                if (!el.isJsonObject()) continue;
                JsonObject block = el.getAsJsonObject();
                if (VALUE_CONTENT_TYPE_DIFF.equals(getStringOrEmpty(block, KEY_CONTENT_TYPE))) {
                    args.addProperty("path", getStringOrEmpty(block, KEY_PATH));
                    String oldText = getOptionalString(block, KEY_OLD_TEXT);
                    String newText = getOptionalString(block, KEY_NEW_TEXT);
                    String diff = bubbleDiff(oldText, newText);
                    int[] stats = changeStats(oldText, newText);
                    if (diff != null && stats != null) {
                        // The card header shows path + stats; only sent when the diff card
                        // actually renders (diff non-null), so the panels can skip the
                        // corresponding k/v rows unconditionally.
                        args.addProperty("diff", diff);
                        args.addProperty("diffAdded", stats[0]);
                        args.addProperty("diffRemoved", stats[1]);
                        // Full texts power the "Open in editor" diff view; marked
                        // as hidden so the panels never render them as rows.
                        if (oldText != null) {
                            args.addProperty("oldText", oldText);
                        }
                        args.addProperty("newText", newText);
                    }
                    break;
                }
            }
        }
        if (args.isEmpty() && hasCommandRawInput(toolCall)) {
            args.addProperty("command",
                toolCall.getAsJsonObject(KEY_RAW_INPUT).get(KEY_RAW_INPUT_COMMAND).getAsString());
        }
        if (args.isEmpty()) {
            args.addProperty("request", toolId);
        }
        context.addProperty("question", toolId.startsWith(EDIT_TITLE_PREFIX)
            ? "Approve this file edit?"
            : "Approve this command?");
        context.add("args", args);
        return context.toString();
    }

    /**
     * Whether the toolCall carries a diff content block on the wire.
     */
    static boolean hasDiffContentBlock(@NotNull JsonObject toolCall) {
        if (!toolCall.has(KEY_CONTENT) || !toolCall.get(KEY_CONTENT).isJsonArray()) return false;
        JsonArray content = toolCall.getAsJsonArray(KEY_CONTENT);
        for (JsonElement el : content) {
            if (el.isJsonObject()
                && VALUE_CONTENT_TYPE_DIFF.equals(getStringOrEmpty(el.getAsJsonObject(), KEY_CONTENT_TYPE))) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code [+added, -removed]} change counts between {@code oldText} and {@code newText},
     * computed from the full op list BEFORE any display truncation so the header stats always
     * reflect the true magnitude of the change. {@code null} when there is nothing to show
     * (same null conditions as {@link #bubbleDiff}).
     */
    static @Nullable int[] changeStats(@Nullable String oldText, @Nullable String newText) {
        if (newText == null) return null;
        if (oldText != null && oldText.equals(newText)) return null;
        List<String> oldLines = oldText == null ? List.of() : List.of(oldText.split("\\R", -1));
        List<String> newLines = List.of(newText.split("\\R", -1));
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
     * A bounded unified line diff between {@code oldText} and {@code newText} for the approval
     * card, or {@code null} when there is nothing to show (no new text, or texts equal).
     * ACP diff payloads carry whole-file content, so unchanged regions are collapsed to
     * {@link #DIFF_CONTEXT_LINES} context lines around each change; the result is capped at
     * {@link #MAX_DIFF_LINES} lines with a {@code … N more lines} marker so the card stays
     * reviewable for very large files (the card itself scrolls beyond that).
     * {@code oldText == null} (new file) renders as pure additions. Inputs beyond the LCS
     * guards degrade to an additions-only summary so huge files cannot stall the pooled thread.
     */
    static @Nullable String bubbleDiff(@Nullable String oldText, @Nullable String newText) {
        if (newText == null) return null;
        if (oldText != null && oldText.equals(newText)) return null;
        List<String> oldLines = oldText == null ? List.of() : List.of(oldText.split("\\R", -1));
        List<String> newLines = List.of(newText.split("\\R", -1));
        int n = oldLines.size();
        int m = newLines.size();
        List<String[]> ops = new ArrayList<>(n + m);
        if (n > MAX_LCS_LINES || m > MAX_LCS_LINES || (long) n * m > MAX_LCS_CELLS) {
            // Too large for the LCS table: summarize the new content as additions.
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
     * Renders the op list with unchanged regions collapsed to {@link #DIFF_CONTEXT_LINES}
     * context lines around changes, capped at {@link #MAX_DIFF_LINES} output lines with a
     * trailing {@code … N more lines} marker when cut.
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

    private static boolean hasCommandRawInput(@NotNull JsonObject toolCall) {
        return toolCall.has(KEY_RAW_INPUT)
            && toolCall.get(KEY_RAW_INPUT).isJsonObject()
            && toolCall.getAsJsonObject(KEY_RAW_INPUT).has(KEY_RAW_INPUT_COMMAND)
            && toolCall.getAsJsonObject(KEY_RAW_INPUT).get(KEY_RAW_INPUT_COMMAND).isJsonPrimitive();
    }

    private static String getStringOrEmpty(@NotNull JsonObject obj, @NotNull String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : "";
    }

    private static @Nullable String getOptionalString(@NotNull JsonObject obj, @NotNull String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : null;
    }
}
