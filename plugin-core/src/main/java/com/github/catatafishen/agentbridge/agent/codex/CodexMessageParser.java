package com.github.catatafishen.agentbridge.agent.codex;

import com.github.catatafishen.agentbridge.acp.model.ContentBlock;
import com.github.catatafishen.agentbridge.acp.model.Model;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure JSON-parsing and string-building utilities extracted from
 * {@link CodexAppServerClient} so they can be tested in isolation.
 */
final class CodexMessageParser {

    // ── JSON field-name constants (subset used by the extracted methods) ──────
    private static final String F_TEXT = "text";
    private static final String F_DELTA = "delta";
    private static final String F_ERROR = "error";
    private static final String F_MESSAGE = "message";
    private static final String F_COMMAND = "command";

    private CodexMessageParser() {
        // utility class
    }

    // ── Reasoning text extraction ────────────────────────────────────────────

    /**
     * Recursively extracts reasoning / thinking text from the given JSON element.
     */
    @NotNull
    static String extractReasoningText(@Nullable JsonElement el) {
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonPrimitive()) return el.getAsString();
        if (el.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement child : el.getAsJsonArray()) {
                String childText = extractReasoningText(child);
                if (!childText.isEmpty()) sb.append(childText);
            }
            return sb.toString();
        }
        if (!el.isJsonObject()) return "";

        JsonObject obj = el.getAsJsonObject();
        if (obj.has(F_TEXT) && obj.get(F_TEXT).isJsonPrimitive()) return obj.get(F_TEXT).getAsString();
        if (obj.has("thinking") && obj.get("thinking").isJsonPrimitive()) return obj.get("thinking").getAsString();
        if (obj.has("summary")) return extractReasoningText(obj.get("summary"));
        if (obj.has(F_DELTA)) return extractReasoningText(obj.get(F_DELTA));
        if (obj.has("content")) return extractReasoningText(obj.get("content"));
        return "";
    }

    // ── Turn error extraction ────────────────────────────────────────────────

    /**
     * Extracts a human-readable error message from a failed turn's error object.
     * The {@code message} field may itself be a JSON string (nested error envelope),
     * in which case we try to unwrap the inner {@code error.message}.
     */
    @NotNull
    static String extractTurnErrorMessage(@NotNull JsonObject turn) {
        if (!turn.has(F_ERROR)) return "Codex turn failed";
        JsonElement errEl = turn.get(F_ERROR);
        if (!errEl.isJsonObject()) return errEl.isJsonNull() ? "Codex turn failed" : errEl.getAsString();
        JsonObject err = errEl.getAsJsonObject();
        String raw = err.has(F_MESSAGE) ? err.get(F_MESSAGE).getAsString() : err.toString();
        // The message field is sometimes a JSON string itself; try to unwrap it
        if (raw.startsWith("{")) {
            try {
                JsonObject nested = JsonParser.parseString(raw).getAsJsonObject();
                if (nested.has(F_ERROR) && nested.getAsJsonObject(F_ERROR).has(F_MESSAGE)) {
                    return nested.getAsJsonObject(F_ERROR).get(F_MESSAGE).getAsString();
                }
            } catch (RuntimeException ignored) {
                // Fall through to returning the raw string
            }
        }
        return raw;
    }

    // ── Prompt helpers ───────────────────────────────────────────────────────

    /**
     * Extracts plain text from a list of {@link ContentBlock} instances.
     */
    @NotNull
    static String extractPromptText(@NotNull List<ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof ContentBlock.Text t) {
                sb.append(t.text());
            } else if (block instanceof ContentBlock.Resource r) {
                ContentBlock.ResourceLink rl = r.resource();
                if (rl.text() != null && !rl.text().isEmpty()) {
                    sb.append("File: ").append(rl.uri()).append("\n```\n").append(rl.text()).append("\n```\n\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * Wraps a raw prompt with system-reminder XML tags when starting a new session.
     *
     * @param prompt       the raw user prompt
     * @param isNewSession whether this is the first prompt for this session
     * @param instructions the session instructions (may be {@code null} or empty)
     */
    @NotNull
    static String buildFullPrompt(@NotNull String prompt, boolean isNewSession, @Nullable String instructions) {
        if (!isNewSession) return prompt;
        StringBuilder sb = new StringBuilder();
        if (instructions != null && !instructions.isEmpty()) {
            sb.append("<system-reminder>\n").append(instructions).append("\n</system-reminder>\n\n");
        }
        sb.append(prompt);
        return sb.toString();
    }

    // ── Native approval helpers ──────────────────────────────────────────────

    /**
     * Builds a descriptive string for a native approval dialog.
     */
    @NotNull
    static String buildNativeApprovalDescription(@NotNull String method, @NotNull JsonObject params) {
        String detail = extractNativeApprovalDetail(params);
        if (detail.isEmpty()) {
            return method;
        }
        return method + "\n" + detail;
    }

    /**
     * Extracts the most relevant detail (command, path, filePath, or reason)
     * from the JSON params of a native approval request.
     */
    @NotNull
    static String extractNativeApprovalDetail(@NotNull JsonObject params) {
        for (String key : List.of(F_COMMAND, "path", "filePath", "reason")) {
            if (params.has(key) && !params.get(key).isJsonNull()) {
                JsonElement value = params.get(key);
                if (value.isJsonPrimitive()) {
                    return value.getAsString();
                }
                return value.toString();
            }
        }
        if (params.entrySet().isEmpty()) {
            return "";
        }
        return params.toString();
    }

    // ── Model parsing ────────────────────────────────────────────────────────

    /**
     * Parses a JSON element into a {@link Model} record, or {@code null} if the
     * element is not a valid model object.
     */
    @Nullable
    static Model parseModelEntry(@NotNull JsonElement el) {
        if (!el.isJsonObject()) return null;
        JsonObject m = el.getAsJsonObject();
        String id = m.has("id") ? m.get("id").getAsString() : null;
        if (id == null || id.isEmpty()) return null;
        String name = m.has("name") ? m.get("name").getAsString() : id;
        return new Model(id, name, null, null);
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────

    /**
     * Safe integer extraction from a JSON object, returning {@code 0} if the
     * field is missing or null.
     */
    static int safeGetInt(@NotNull JsonObject obj, @NotNull String field) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) return 0;
        return obj.get(field).getAsInt();
    }

    // ── Binary candidate names ───────────────────────────────────────────────

    /**
     * Builds the list of candidate binary names to search for.
     *
     * @param primaryName    the primary binary name from the profile (may be empty)
     * @param alternateNames alternate binary names from the profile
     */
    @NotNull
    static List<String> candidateNames(@NotNull String primaryName, @NotNull List<String> alternateNames) {
        List<String> names = new ArrayList<>();
        if (!primaryName.isEmpty()) names.add(primaryName);
        names.addAll(alternateNames);
        if (!names.contains("codex")) names.add("codex");
        return names;
    }
}
