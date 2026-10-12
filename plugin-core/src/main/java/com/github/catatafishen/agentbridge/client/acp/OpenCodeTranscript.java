package com.github.catatafishen.agentbridge.client.acp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * A subagent's conversation, parsed from the JSON returned by OpenCode's
 * {@code GET /session/:id/message}.
 * <p>
 * The response is an array of {@code {info: {role}, parts: [...]}} objects. Each part is a
 * {@code text}, {@code reasoning} or {@code tool} part; other part types (such as
 * {@code step-start}) carry no content and are skipped.
 */
public record OpenCodeTranscript(@NotNull List<Message> messages) {

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    private static final String ROLE_ASSISTANT = "assistant";
    private static final String KEY_STATE = "state";

    public enum PartType {TEXT, REASONING, TOOL}

    /**
     * One part of a message. {@code text} is set for text and reasoning parts; the remaining
     * fields are set for tool parts. {@code tool} has the {@code agentbridge_} MCP prefix removed,
     * {@code status} is one of the {@code STATUS_*} constants, {@code input} is the call's
     * arguments as JSON, and {@code output} is the tool result or error message.
     */
    public record Part(@NotNull PartType type, @Nullable String text, @Nullable String callId,
                       @Nullable String tool, @Nullable String status,
                       @Nullable String input, @Nullable String output) {

        static Part text(String text) {
            return new Part(PartType.TEXT, text, null, null, null, null, null);
        }

        static Part reasoning(String text) {
            return new Part(PartType.REASONING, text, null, null, null, null, null);
        }

        static Part tool(String callId, String tool, String status, String input, String output) {
            return new Part(PartType.TOOL, null, callId, tool, status, input, output);
        }
    }

    public record Message(@NotNull String role, @NotNull List<Part> parts) {
        public boolean isAssistant() {
            return ROLE_ASSISTANT.equals(role);
        }
    }

    /**
     * @throws IllegalArgumentException if [json] is not an array of messages
     */
    public static @NotNull OpenCodeTranscript parse(@NotNull String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) {
            throw new IllegalArgumentException("Expected a JSON array of session messages");
        }
        List<JsonObject> rawMessages = root.getAsJsonArray().asList().stream()
            .filter(JsonElement::isJsonObject)
            .map(JsonElement::getAsJsonObject)
            .toList();
        return new OpenCodeTranscript(IntStream.range(0, rawMessages.size())
            .mapToObj(i -> parseMessage(rawMessages.get(i), i))
            .toList());
    }

    private static Message parseMessage(JsonObject message, int messageIndex) {
        JsonObject info = object(message, "info");
        String role = info != null ? string(info, "role") : null;
        List<Part> parts = new ArrayList<>();
        JsonElement partsElement = message.get("parts");
        if (partsElement != null && partsElement.isJsonArray()) {
            JsonArray array = partsElement.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isJsonObject()) {
                    Part part = parsePart(array.get(i).getAsJsonObject(), messageIndex + "-" + i);
                    if (part != null) {
                        parts.add(part);
                    }
                }
            }
        }
        return new Message(role != null ? role : "unknown", parts);
    }

    private static @Nullable Part parsePart(JsonObject part, String fallbackId) {
        String type = string(part, "type");
        if ("text".equals(type) || "reasoning".equals(type)) {
            String text = string(part, "text");
            if (text == null || text.isBlank()) {
                return null;
            }
            return "text".equals(type) ? Part.text(text.trim()) : Part.reasoning(text.trim());
        }
        if ("tool".equals(type)) {
            return parseToolPart(part, fallbackId);
        }
        return null;
    }

    private static Part parseToolPart(JsonObject part, String fallbackId) {
        String id = string(part, "id");
        if (id == null) {
            id = string(part, "callID");
        }
        String tool = string(part, "tool");
        JsonObject state = object(part, KEY_STATE);
        String input = null;
        String output = null;
        String status = STATUS_RUNNING;
        if (state != null) {
            JsonElement inputElement = state.get("input");
            input = inputElement != null && !inputElement.isJsonNull() ? inputElement.toString() : null;
            status = toStatus(string(state, "status"));
            output = string(state, STATUS_FAILED.equals(status) ? "error" : "output");
        }
        return Part.tool(id != null ? id : fallbackId,
            tool != null ? OpenCodeClient.stripToolPrefix(tool) : "unknown", status, input, output);
    }

    /**
     * OpenCode reports {@code completed} / {@code error} / {@code pending} / {@code running}.
     */
    private static String toStatus(@Nullable String openCodeStatus) {
        if (STATUS_COMPLETED.equals(openCodeStatus)) {
            return STATUS_COMPLETED;
        }
        return "error".equals(openCodeStatus) ? STATUS_FAILED : STATUS_RUNNING;
    }

    private static @Nullable JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static @Nullable String string(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }
}
