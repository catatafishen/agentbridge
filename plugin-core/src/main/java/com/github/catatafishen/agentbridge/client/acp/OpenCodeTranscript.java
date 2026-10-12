package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.bridge.EntryData;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * A subagent's conversation, parsed from the JSON returned by OpenCode's
 * {@code GET /session/:id/message}.
 * <p>
 * The response is an array of {@code {info: {role, agent, modelID, time}, parts: [...]}} objects.
 * Each part is a {@code text}, {@code reasoning} or {@code tool} part; other part types (such as
 * {@code step-start}) carry no content and are skipped. {@link #toEntries} converts the assistant
 * side into the entries AgentBridge stores and renders.
 */
public record OpenCodeTranscript(@NotNull List<Message> messages) {

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    private static final String ROLE_ASSISTANT = "assistant";
    private static final String KEY_STATE = "state";
    private static final String KEY_INFO = "info";

    public enum PartType {TEXT, REASONING, TOOL}

    /**
     * One part of a message. {@code id} is OpenCode's part id (or a positional fallback) and is
     * unique within the session. {@code text} is set for text and reasoning parts; the remaining
     * fields are set for tool parts. {@code tool} has the {@code agentbridge_} MCP prefix removed,
     * {@code status} is one of the {@code STATUS_*} constants, {@code input} is the call's
     * arguments as JSON, and {@code output} is the tool result or error message.
     */
    public record Part(@NotNull PartType type, @NotNull String id, @Nullable String text,
                       @Nullable String tool, @Nullable String status,
                       @Nullable String input, @Nullable String output) {

        static Part text(String id, String text) {
            return new Part(PartType.TEXT, id, text, null, null, null, null);
        }

        static Part reasoning(String id, String text) {
            return new Part(PartType.REASONING, id, text, null, null, null, null);
        }

        static Part tool(String id, String tool, String status, String input, String output) {
            return new Part(PartType.TOOL, id, null, tool, status, input, output);
        }
    }

    /**
     * A message with who wrote it: {@code agent} and {@code model} are empty and {@code timestamp}
     * is an ISO-8601 instant, or empty, when OpenCode did not report them.
     */
    public record Message(@NotNull String role, @NotNull String agent, @NotNull String model,
                          @NotNull String timestamp, @NotNull List<Part> parts) {
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

    /**
     * Converts the assistant's text, reasoning and tool calls, in order, into the entries stored
     * under a sub-agent. The user's prompt is skipped: it is already the sub-agent's own prompt.
     *
     * <p>Entry ids are derived from [subAgentEntryId] and the OpenCode part id, so converting the
     * same session again yields the same ids (writes are idempotent), while a second sub-agent that
     * resumes the same OpenCode session still gets rows of its own.
     */
    public @NotNull List<EntryData> toEntries(@NotNull String subAgentEntryId) {
        List<EntryData> entries = new ArrayList<>();
        for (Message message : messages) {
            if (!message.isAssistant()) {
                continue;
            }
            for (Part part : message.parts()) {
                entries.add(toEntry(subAgentEntryId, message, part));
            }
        }
        return entries;
    }

    private static EntryData toEntry(String subAgentEntryId, Message message, Part part) {
        String entryId = subAgentEntryId + ":" + part.id();
        // The parser only creates text/reasoning parts with text and tool parts with a tool name.
        return switch (part.type()) {
            case TEXT -> new EntryData.Text(
                Objects.requireNonNull(part.text()), message.timestamp(), message.agent(), message.model(), entryId);
            case REASONING -> new EntryData.Thinking(
                Objects.requireNonNull(part.text()), message.timestamp(), message.agent(), message.model(), entryId);
            case TOOL -> new EntryData.ToolCall(
                Objects.requireNonNull(part.tool()), part.input(), "other", part.output(), part.status(),
                null, null, false, null, null,
                message.timestamp(), message.agent(), message.model(), entryId);
        };
    }

    private static Message parseMessage(JsonObject message, int messageIndex) {
        JsonObject info = object(message, KEY_INFO);
        String role = info != null ? string(info, "role") : null;
        String agent = info != null ? string(info, "agent") : null;
        String model = info != null ? string(info, "modelID") : null;
        return new Message(role != null ? role : "unknown",
            agent != null ? agent : "", model != null ? model : "", createdAt(info),
            parseParts(message, messageIndex));
    }

    private static List<Part> parseParts(JsonObject message, int messageIndex) {
        JsonElement partsElement = message.get("parts");
        if (partsElement == null || !partsElement.isJsonArray()) {
            return List.of();
        }
        JsonArray array = partsElement.getAsJsonArray();
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            Part part = array.get(i).isJsonObject()
                ? parsePart(array.get(i).getAsJsonObject(), messageIndex + "-" + i) : null;
            if (part != null) {
                parts.add(part);
            }
        }
        return parts;
    }

    /** {@code info.time.created} (epoch millis) as an ISO-8601 instant; empty when absent. */
    private static String createdAt(@Nullable JsonObject info) {
        JsonObject time = info != null ? object(info, "time") : null;
        JsonElement created = time != null ? time.get("created") : null;
        if (created == null || !created.isJsonPrimitive() || !created.getAsJsonPrimitive().isNumber()) {
            return "";
        }
        return Instant.ofEpochMilli(created.getAsLong()).toString();
    }

    private static @Nullable Part parsePart(JsonObject part, String fallbackId) {
        String id = string(part, "id");
        String partId = id != null ? id : fallbackId;
        String type = string(part, "type");
        if ("text".equals(type) || "reasoning".equals(type)) {
            String text = string(part, "text");
            if (text == null || text.isBlank()) {
                return null;
            }
            return "text".equals(type) ? Part.text(partId, text.trim()) : Part.reasoning(partId, text.trim());
        }
        if ("tool".equals(type)) {
            return parseToolPart(part, partId);
        }
        return null;
    }

    private static Part parseToolPart(JsonObject part, String partId) {
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
        return Part.tool(partId, tool != null ? OpenCodeClient.stripToolPrefix(tool) : "unknown",
            status, input, output);
    }

    /** OpenCode reports {@code completed} / {@code error} / {@code pending} / {@code running}. */
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
