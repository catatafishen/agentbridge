package com.github.catatafishen.ideagentforcopilot.session.importers;

import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Imports a Kiro or Claude CLI session (Anthropic API message format) into
 * {@link SessionMessage} list.
 *
 * <p>Input: path to a {@code messages.jsonl} file where each line is one Anthropic API
 * message object ({@code {"role":"user"|"assistant","content":[...]}}).</p>
 *
 * <p>Conversion rules:
 * <ul>
 *   <li>{@code role:user} with only {@code tool_result} blocks → skipped (results are
 *       embedded into the preceding tool-invocation parts instead)</li>
 *   <li>{@code role:user} with {@code text} block → {@code SessionMessage} with
 *       {@code role:"user"} and a {@code {type:"text", text}} part</li>
 *   <li>{@code role:assistant} {@code text} block → {@code {type:"text", text}} part</li>
 *   <li>{@code role:assistant} {@code tool_use} block → {@code {type:"tool-invocation"}}
 *       part; the immediately following user message is inspected for matching
 *       {@code tool_result} to set {@code state:"result"}</li>
 * </ul>
 * </p>
 */
public final class AnthropicMessageImporter {

    private static final Logger LOG = Logger.getInstance(AnthropicMessageImporter.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private AnthropicMessageImporter() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Imports a {@code messages.jsonl} file (Anthropic API format) and returns a list of
     * {@link SessionMessage}s.
     *
     * @param path path to the {@code messages.jsonl} file
     * @return list of converted session messages (never null)
     * @throws IOException if the file cannot be read
     */
    @NotNull
    public static List<SessionMessage> importFile(@NotNull Path path) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        List<JsonObject> rawMessages = parseJsonl(content);
        return convertMessages(rawMessages);
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    @NotNull
    private static List<JsonObject> parseJsonl(@NotNull String content) {
        List<JsonObject> result = new ArrayList<>();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                JsonElement el = JsonParser.parseString(line);
                if (el.isJsonObject()) {
                    result.add(el.getAsJsonObject());
                }
            } catch (Exception e) {
                LOG.warn("Skipping malformed JSONL line during Anthropic import: " + line, e);
            }
        }
        return result;
    }

    // ── Conversion ────────────────────────────────────────────────────────────

    @NotNull
    private static List<SessionMessage> convertMessages(@NotNull List<JsonObject> rawMessages) {
        List<SessionMessage> result = new ArrayList<>();

        for (int i = 0; i < rawMessages.size(); i++) {
            JsonObject raw = rawMessages.get(i);
            String role = raw.has("role") ? raw.get("role").getAsString() : "";
            JsonArray content = raw.has("content") ? raw.getAsJsonArray("content") : new JsonArray();

            if ("user".equals(role)) {
                // Skip messages that contain only tool_result blocks — results are embedded
                // into the preceding assistant message's tool-invocation parts.
                if (isOnlyToolResults(content)) {
                    continue;
                }

                List<JsonObject> parts = new ArrayList<>();
                for (JsonElement block : content) {
                    if (!block.isJsonObject()) continue;
                    JsonObject b = block.getAsJsonObject();
                    String type = b.has("type") ? b.get("type").getAsString() : "";
                    if ("text".equals(type)) {
                        JsonObject part = new JsonObject();
                        part.addProperty("type", "text");
                        part.addProperty("text", b.has("text") ? b.get("text").getAsString() : "");
                        parts.add(part);
                    }
                    // tool_result in a mixed user message → skip individual blocks
                }

                if (!parts.isEmpty()) {
                    result.add(new SessionMessage(
                            UUID.randomUUID().toString(),
                            "user",
                            parts,
                            System.currentTimeMillis(),
                            null,
                            null));
                }

            } else if ("assistant".equals(role)) {
                // Look-ahead: collect tool results from the immediately following user message
                @Nullable JsonObject nextUserRaw = findNextUserMessage(rawMessages, i + 1);
                ToolResultMap toolResults = collectToolResults(nextUserRaw);

                List<JsonObject> parts = new ArrayList<>();
                for (JsonElement block : content) {
                    if (!block.isJsonObject()) continue;
                    JsonObject b = block.getAsJsonObject();
                    String type = b.has("type") ? b.get("type").getAsString() : "";

                    if ("text".equals(type)) {
                        JsonObject part = new JsonObject();
                        part.addProperty("type", "text");
                        part.addProperty("text", b.has("text") ? b.get("text").getAsString() : "");
                        parts.add(part);

                    } else if ("tool_use".equals(type)) {
                        String toolCallId = b.has("id") ? b.get("id").getAsString() : UUID.randomUUID().toString();
                        String toolName = b.has("name") ? b.get("name").getAsString() : "unknown";
                        String argsJson = b.has("input") ? GSON.toJson(b.get("input")) : "{}";

                        @Nullable String resultContent = toolResults.get(toolCallId);
                        boolean hasResult = resultContent != null;

                        JsonObject invocation = new JsonObject();
                        invocation.addProperty("state", hasResult ? "result" : "call");
                        invocation.addProperty("toolCallId", toolCallId);
                        invocation.addProperty("toolName", toolName);
                        invocation.addProperty("args", argsJson);
                        if (hasResult) {
                            invocation.addProperty("result", resultContent);
                        }

                        JsonObject part = new JsonObject();
                        part.addProperty("type", "tool-invocation");
                        part.add("toolInvocation", invocation);
                        parts.add(part);
                    }
                    // Other block types are skipped
                }

                if (!parts.isEmpty()) {
                    result.add(new SessionMessage(
                            UUID.randomUUID().toString(),
                            "assistant",
                            parts,
                            System.currentTimeMillis(),
                            null,
                            null));
                }
            }
            // Unknown roles are skipped
        }

        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isOnlyToolResults(@NotNull JsonArray content) {
        if (content.isEmpty()) return false;
        for (JsonElement el : content) {
            if (!el.isJsonObject()) return false;
            String type = el.getAsJsonObject().has("type")
                    ? el.getAsJsonObject().get("type").getAsString()
                    : "";
            if (!"tool_result".equals(type)) return false;
        }
        return true;
    }

    @Nullable
    private static JsonObject findNextUserMessage(@NotNull List<JsonObject> messages, int fromIndex) {
        for (int j = fromIndex; j < messages.size(); j++) {
            JsonObject m = messages.get(j);
            if ("user".equals(m.has("role") ? m.get("role").getAsString() : "")) {
                return m;
            }
            // Stop looking if we hit another assistant message
            if ("assistant".equals(m.has("role") ? m.get("role").getAsString() : "")) {
                break;
            }
        }
        return null;
    }

    /**
     * Collects all {@code tool_result} blocks from a user message into a map of
     * {@code toolUseId → content}.
     */
    @NotNull
    private static ToolResultMap collectToolResults(@Nullable JsonObject userMessage) {
        ToolResultMap map = new ToolResultMap();
        if (userMessage == null) return map;
        JsonArray content = userMessage.has("content")
                ? userMessage.getAsJsonArray("content")
                : new JsonArray();
        for (JsonElement el : content) {
            if (!el.isJsonObject()) continue;
            JsonObject block = el.getAsJsonObject();
            String type = block.has("type") ? block.get("type").getAsString() : "";
            if ("tool_result".equals(type)) {
                String toolUseId = block.has("tool_use_id") ? block.get("tool_use_id").getAsString() : "";
                String resultContent = extractToolResultContent(block);
                if (!toolUseId.isEmpty()) {
                    map.put(toolUseId, resultContent);
                }
            }
        }
        return map;
    }

    /**
     * Extracts the content string from a {@code tool_result} block.
     * The {@code content} field may be a plain string or an array of content blocks.
     */
    @NotNull
    private static String extractToolResultContent(@NotNull JsonObject toolResultBlock) {
        if (!toolResultBlock.has("content")) return "";
        JsonElement contentEl = toolResultBlock.get("content");
        if (contentEl.isJsonPrimitive()) {
            return contentEl.getAsString();
        }
        if (contentEl.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement el : contentEl.getAsJsonArray()) {
                if (el.isJsonObject()) {
                    JsonObject b = el.getAsJsonObject();
                    if (b.has("text")) {
                        if (!sb.isEmpty()) sb.append('\n');
                        sb.append(b.get("text").getAsString());
                    }
                }
            }
            return sb.toString();
        }
        return contentEl.toString();
    }

    // ── Inner helper ──────────────────────────────────────────────────────────

    /** Simple string-to-string map to keep the tool-result lookup self-contained. */
    private static final class ToolResultMap {
        private final java.util.HashMap<String, String> map = new java.util.HashMap<>();

        void put(@NotNull String key, @NotNull String value) {
            map.put(key, value);
        }

        @Nullable
        String get(@NotNull String key) {
            return map.get(key);
        }
    }
}
