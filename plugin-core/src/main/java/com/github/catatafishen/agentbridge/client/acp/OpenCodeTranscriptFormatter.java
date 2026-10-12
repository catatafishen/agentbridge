package com.github.catatafishen.agentbridge.client.acp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

/**
 * Converts the JSON returned by OpenCode's {@code GET /session/:id/message} into Markdown.
 * <p>
 * The response is an array of {@code {info: {role}, parts: [...]}} objects where each part is a
 * {@code text}, {@code reasoning} or {@code tool} part. Unknown part types are skipped.
 */
public final class OpenCodeTranscriptFormatter {

    static final int MAX_TOOL_FIELD_CHARS = 600;

    private OpenCodeTranscriptFormatter() {
    }

    /**
     * @throws IllegalArgumentException if [json] is not an array of messages
     */
    public static @NotNull String toMarkdown(@NotNull String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) {
            throw new IllegalArgumentException("Expected a JSON array of session messages");
        }
        StringBuilder out = new StringBuilder();
        for (JsonElement element : root.getAsJsonArray()) {
            if (element.isJsonObject()) {
                appendMessage(out, element.getAsJsonObject());
            }
        }
        return out.toString().trim();
    }

    private static void appendMessage(StringBuilder out, JsonObject message) {
        out.append("### ").append(capitalize(role(message))).append("\n\n");
        JsonArray parts = message.has("parts") && message.get("parts").isJsonArray()
            ? message.getAsJsonArray("parts") : new JsonArray();
        for (JsonElement part : parts) {
            if (part.isJsonObject()) {
                appendPart(out, part.getAsJsonObject());
            }
        }
    }

    private static String role(JsonObject message) {
        JsonObject info = message.has("info") && message.get("info").isJsonObject()
            ? message.getAsJsonObject("info") : null;
        String role = info != null ? string(info, "role") : null;
        return role != null ? role : "unknown";
    }

    private static void appendPart(StringBuilder out, JsonObject part) {
        String type = string(part, "type");
        if ("text".equals(type)) {
            String text = string(part, "text");
            if (text != null && !text.isBlank()) {
                out.append(text.trim()).append("\n\n");
            }
        } else if ("reasoning".equals(type)) {
            String text = string(part, "text");
            if (text != null && !text.isBlank()) {
                out.append("> *Reasoning:* ").append(text.trim().replace("\n", "\n> ")).append("\n\n");
            }
        } else if ("tool".equals(type)) {
            appendToolPart(out, part);
        }
    }

    private static void appendToolPart(StringBuilder out, JsonObject part) {
        String tool = string(part, "tool");
        JsonObject state = part.has("state") && part.get("state").isJsonObject()
            ? part.getAsJsonObject("state") : new JsonObject();
        String status = string(state, "status");
        out.append("**Tool:** `").append(tool != null ? tool : "unknown").append('`');
        if (status != null) {
            out.append(" (").append(status).append(')');
        }
        out.append("\n\n");
        appendField(out, "Input", state.get("input"));
        appendField(out, "Output", state.get("output"));
    }

    private static void appendField(StringBuilder out, String label, JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return;
        }
        String text = value.isJsonPrimitive() ? value.getAsString() : value.toString();
        if (text.isBlank()) {
            return;
        }
        out.append(label).append(":\n\n```\n").append(truncate(text.trim())).append("\n```\n\n");
    }

    static String truncate(String text) {
        if (text.length() <= MAX_TOOL_FIELD_CHARS) {
            return text;
        }
        return text.substring(0, MAX_TOOL_FIELD_CHARS) + "\n… (" + (text.length() - MAX_TOOL_FIELD_CHARS) + " more characters)";
    }

    private static String string(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
