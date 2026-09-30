package com.github.catatafishen.agentbridge.psi.review;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Pure parser for ACP {@code session/request_permission} payloads that carry an edit
 * approval: a {@code toolCall} of {@code kind=edit} whose content list contains a
 * {@code diff} content block with the proposed {@code newText}/{@code oldText}.
 *
 * <p>Protocol-based, not agent-based: any ACP agent (Hermes, Claude Code, Copilot,
 * Gemini, ...) that asks permission for an edit with a diff block is detected the same
 * way. Detection never inspects the title — titles are display strings, and a path
 * separator inside one must not change the outcome.</p>
 *
 * <p>Wire key handling: the ACP SDKs serialize the diff block's fields camelCase
 * ({@code newText}/{@code oldText}) with {@code oldText} omitted for new files, but
 * agents built on Python models without alias serialization may emit snake_case
 * ({@code new_text}/{@code old_text}). Both are accepted. The block's discriminator
 * is likewise accepted as {@code type: "diff"} (per the ACP schema) or, for agents
 * that serialize content blocks by Python field name, {@code contentType} variants.
 * Requests that do not match the shape are reported as
 * {@link Result#notEditApproval()} so the caller keeps its default handling.</p>
 */
public final class EditApprovalRequestParser {

    private static final String KEY_TOOL_CALL = "toolCall";
    private static final String KEY_KIND = "kind";
    private static final String KEY_TITLE = "title";
    private static final String KEY_TOOL_CALL_ID = "toolCallId";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_TYPE = "type";
    private static final String VALUE_KIND_EDIT = "edit";
    private static final String VALUE_TYPE_DIFF = "diff";
    private static final String KEY_PATH = "path";
    private static final String KEY_NEW_TEXT_CAMEL = "newText";
    private static final String KEY_NEW_TEXT_SNAKE = "new_text";
    private static final String KEY_OLD_TEXT_CAMEL = "oldText";
    private static final String KEY_OLD_TEXT_SNAKE = "old_text";

    /** Parsed edit-approval request, or a "not an edit approval" marker. */
    public sealed interface Result {
        /**
         * The payload is not an edit approval (no toolCall, wrong kind, or no diff
         * content block). The caller must apply its default permission handling.
         */
        record NotEditApproval() implements Result {
        }

        /**
         * Singleton marker for payloads that are not edit approvals.
         */
        NotEditApproval INSTANCE = new NotEditApproval();

        /**
         * Convenience accessor for the {@link #INSTANCE} marker.
         */
        static @NotNull NotEditApproval notEditApproval() {
            return INSTANCE;
        }

        /**
         * A parsed edit approval.
         *
         * @param path      file the agent wants to modify (display string; may be a
         *                  comma-joined list for multi-file proposals)
         * @param oldText   current file content; {@code null} means new file
         * @param newText   proposed content (a raw V4A patch body for patch-mode calls)
         * @param title     protocol title of the tool call, for display
         * @param toolCallId stable id of the tool call, for logging
         */
        record EditApproval(@NotNull String path,
                            @Nullable String oldText,
                            @NotNull String newText,
                            @Nullable String title,
                            @NotNull String toolCallId) implements Result {
        }
    }

    private EditApprovalRequestParser() {
    }

    /**
     * Parses an ACP {@code session/request_permission} params object.
     */
    public static @NotNull Result parse(@Nullable JsonObject params) {
        JsonObject toolCall = params != null && params.has(KEY_TOOL_CALL)
            && params.get(KEY_TOOL_CALL).isJsonObject()
            ? params.getAsJsonObject(KEY_TOOL_CALL) : null;
        if (toolCall == null || !VALUE_KIND_EDIT.equals(stringField(toolCall, KEY_KIND))) {
            return Result.notEditApproval();
        }
        JsonArray content = toolCall.has(KEY_CONTENT) && toolCall.get(KEY_CONTENT).isJsonArray()
            ? toolCall.getAsJsonArray(KEY_CONTENT) : null;
        if (content == null) {
            return Result.NotEditApproval.INSTANCE;
        }
        for (JsonElement el : content) {
            if (!el.isJsonObject()) continue;
            JsonObject block = el.getAsJsonObject();
            if (!VALUE_TYPE_DIFF.equals(stringField(block, KEY_TYPE))) continue;
            String newText = stringField(block, KEY_NEW_TEXT_CAMEL, KEY_NEW_TEXT_SNAKE);
            if (newText == null) continue;
            String path = stringField(block, KEY_PATH);
            if (path == null) path = "";
            String oldText = stringField(block, KEY_OLD_TEXT_CAMEL, KEY_OLD_TEXT_SNAKE);
            return new Result.EditApproval(
                path, oldText, newText,
                stringField(toolCall, KEY_TITLE),
                stringField(toolCall, KEY_TOOL_CALL_ID));
        }
        return Result.NotEditApproval.INSTANCE;
    }

    private static @Nullable String stringField(@NotNull JsonObject obj, @NotNull String... keys) {
        for (String key : keys) {
            JsonElement el = obj.get(key);
            if (el != null && !el.isJsonNull() && el.isJsonPrimitive()) {
                return el.getAsString();
            }
        }
        return null;
    }
}
