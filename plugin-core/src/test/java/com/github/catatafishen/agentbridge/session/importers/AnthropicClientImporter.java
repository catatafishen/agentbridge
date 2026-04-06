package com.github.catatafishen.agentbridge.session.importers;

import com.github.catatafishen.agentbridge.ui.EntryData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AnthropicClientImporter {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private AnthropicClientImporter() {
    }

    @NotNull
    public static List<EntryData> importFile(@NotNull Path path) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        List<JsonObject> rawMessages = JsonlUtil.parseJsonl(content);
        return convertMessages(rawMessages);
    }

    @NotNull
    private static List<EntryData> convertMessages(@NotNull List<JsonObject> rawMessages) {
        List<EntryData> result = new ArrayList<>();
        String timestamp = Instant.now().toString();

        for (int i = 0; i < rawMessages.size(); i++) {
            JsonObject raw = rawMessages.get(i);
            String role = JsonlUtil.getStr(raw, "role");
            JsonArray content = JsonlUtil.getArray(raw, "content");
            if (content == null) content = new JsonArray();

            if ("user".equals(role)) {
                if (isOnlyToolResults(content)) {
                    continue;
                }

                StringBuilder textBuilder = new StringBuilder();
                for (JsonElement block : content) {
                    if (!block.isJsonObject()) continue;
                    JsonObject b = block.getAsJsonObject();
                    String type = JsonlUtil.getStr(b, "type");
                    if ("text".equals(type)) {
                        String text = JsonlUtil.getStr(b, "text");
                        if (text != null && !text.isEmpty()) {
                            if (!textBuilder.isEmpty()) textBuilder.append('\n');
                            textBuilder.append(text);
                        }
                    }
                }

                if (!textBuilder.isEmpty()) {
                    result.add(new EntryData.Prompt(textBuilder.toString(), timestamp, null, "", ""));
                }

            } else if ("assistant".equals(role)) {
                ToolResultMap toolResults = collectToolResultsFromFollowing(rawMessages, i + 1);

                for (JsonElement block : content) {
                    if (!block.isJsonObject()) continue;
                    JsonObject b = block.getAsJsonObject();
                    String type = JsonlUtil.getStr(b, "type");

                    if ("text".equals(type)) {
                        String text = JsonlUtil.getStr(b, "text");
                        if (text != null && !text.isEmpty()) {
                            result.add(new EntryData.Text(
                                text, timestamp, "", "", ""));
                        }

                    } else if ("tool_use".equals(type)) {
                        String toolCallId = JsonlUtil.getStr(b, "id");
                        if (toolCallId == null) toolCallId = UUID.randomUUID().toString();
                        String toolName = JsonlUtil.getStr(b, "name");
                        if (toolName == null) toolName = "unknown";
                        String argsJson = b.has("input") ? GSON.toJson(b.get("input")) : "{}";

                        @Nullable String resultContent = toolResults.get(toolCallId);

                        result.add(new EntryData.ToolCall(
                            toolName, argsJson, "other", resultContent,
                            null, null, null, false, null, null,
                            timestamp, "", "", ""));

                    } else if ("thinking".equals(type) || "reasoning".equals(type)) {
                        String text = JsonlUtil.getStr(b, "text");
                        if (text != null && !text.isEmpty()) {
                            result.add(new EntryData.Thinking(
                                text, timestamp, "", "", ""));
                        }
                    }
                }
            }
        }

        return result;
    }

    private static boolean isOnlyToolResults(@NotNull JsonArray content) {
        if (content.isEmpty()) return false;
        for (JsonElement el : content) {
            if (!el.isJsonObject()) return false;
            String type = JsonlUtil.getStr(el.getAsJsonObject(), "type");
            if (!"tool_result".equals(type)) return false;
        }
        return true;
    }

    /**
     * Collects tool results from ALL consecutive user messages following the assistant
     * message at the given index. Handles both native Claude CLI format (one user message
     * per tool_result) and merged format (tool_result + text in the same user message,
     * produced by {@code mergeConsecutiveSameRole}).
     */
    @NotNull
    private static ToolResultMap collectToolResultsFromFollowing(
        @NotNull List<JsonObject> messages, int fromIndex) {
        ToolResultMap map = new ToolResultMap();
        for (int j = fromIndex; j < messages.size(); j++) {
            JsonObject m = messages.get(j);
            if (!"user".equals(JsonlUtil.getStr(m, "role"))) break;
            JsonArray content = JsonlUtil.getArray(m, "content");
            if (content == null) break;

            boolean foundToolResult = false;
            for (JsonElement el : content) {
                if (!el.isJsonObject()) continue;
                JsonObject block = el.getAsJsonObject();
                if (!"tool_result".equals(JsonlUtil.getStr(block, "type"))) continue;
                foundToolResult = true;
                String toolUseId = JsonlUtil.getStr(block, "tool_use_id");
                String resultContent = extractToolResultContent(block);
                if (toolUseId != null && !toolUseId.isEmpty()) {
                    map.put(toolUseId, resultContent);
                }
            }
            if (!foundToolResult) break;
        }
        return map;
    }

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
