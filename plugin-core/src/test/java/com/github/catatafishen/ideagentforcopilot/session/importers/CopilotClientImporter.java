package com.github.catatafishen.ideagentforcopilot.session.importers;

import com.github.catatafishen.ideagentforcopilot.ui.EntryData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CopilotClientImporter {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private CopilotClientImporter() {
    }

    @NotNull
    public static List<EntryData> importFile(@NotNull Path path) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        List<JsonObject> events = JsonlUtil.parseJsonl(content);
        return processEvents(events);
    }

    @NotNull
    private static List<EntryData> processEvents(@NotNull List<JsonObject> events) {
        List<EntryData> result = new ArrayList<>();
        AssistantBuffer assistantBuffer = null;
        @Nullable String currentModel = null;

        for (JsonObject event : events) {
            String type = JsonlUtil.getStr(event, "type");
            JsonObject data = JsonlUtil.getObject(event, "data");
            if (data == null) data = new JsonObject();
            String eventTs = extractEventTimestamp(event);

            switch (type) {
                case "session.start" -> {
                    currentModel = JsonlUtil.getStr(data, "selectedModel");
                }

                case "user.message" -> {
                    if (assistantBuffer != null) {
                        result.addAll(assistantBuffer.build());
                        assistantBuffer = null;
                    }

                    String userContent = JsonlUtil.getStr(data, "content");
                    if (userContent != null && !userContent.isEmpty()) {
                        result.add(new EntryData.Prompt(userContent, eventTs, null));
                    }
                }

                case "assistant.reasoning" -> {
                    if (assistantBuffer == null) {
                        assistantBuffer = new AssistantBuffer();
                    }
                    String reasoningContent = JsonlUtil.getStr(data, "content");
                    if (reasoningContent != null && !reasoningContent.isEmpty()) {
                        assistantBuffer.addEntry(new EntryData.Thinking(
                            new StringBuilder(reasoningContent),
                            eventTs,
                            "",
                            currentModel != null ? currentModel : ""));
                    }
                }

                case "assistant.message" -> {
                    if (assistantBuffer == null) {
                        assistantBuffer = new AssistantBuffer();
                    }
                    // Update model first so entries created below have the latest model
                    String messageModel = JsonlUtil.getStr(data, "model");
                    if (messageModel != null && !messageModel.isEmpty()) {
                        currentModel = messageModel;
                    }

                    String textContent = JsonlUtil.getStr(data, "content");
                    if (textContent != null && !textContent.isEmpty()) {
                        assistantBuffer.addEntry(new EntryData.Text(
                            new StringBuilder(textContent),
                            eventTs,
                            "",
                            currentModel != null ? currentModel : ""));
                    }
                    if (data.has("toolRequests") && data.get("toolRequests").isJsonArray()) {
                        for (JsonElement toolReqEl : data.getAsJsonArray("toolRequests")) {
                            if (!toolReqEl.isJsonObject()) continue;
                            JsonObject toolReq = toolReqEl.getAsJsonObject();

                            String toolCallId = JsonlUtil.getStr(toolReq, "toolCallId");
                            if (toolCallId == null) toolCallId = UUID.randomUUID().toString();
                            String toolName = JsonlUtil.getStr(toolReq, "name");
                            if (toolName == null) toolName = "unknown";
                            String argsJson = toolReq.has("arguments") ? GSON.toJson(toolReq.get("arguments")) : "{}";

                            EntryData.ToolCall toolCall = new EntryData.ToolCall(
                                toolName, argsJson, "other", null, null,
                                null, null, false, null, false,
                                eventTs, "",
                                currentModel != null ? currentModel : "");

                            int entryIndex = assistantBuffer.addEntry(toolCall);
                            assistantBuffer.trackToolCall(toolCallId, entryIndex);
                        }
                    }
                }

                case "tool.execution_complete" -> {
                    if (assistantBuffer == null) break;
                    String toolCallId = JsonlUtil.getStr(data, "toolCallId");
                    if (toolCallId == null || toolCallId.isEmpty()) break;

                    String resultContent = extractExecutionResult(data);
                    assistantBuffer.upgradeToolCallToResult(toolCallId, resultContent);
                }

                case "assistant.turn_end" -> {
                    if (assistantBuffer != null) {
                        result.addAll(assistantBuffer.build());
                        assistantBuffer = null;
                    }
                }

                case "subagent.started" -> {
                    if (assistantBuffer == null) {
                        assistantBuffer = new AssistantBuffer();
                    }
                    String toolCallId = JsonlUtil.getStr(data, "toolCallId");
                    String agentName = JsonlUtil.getStr(data, "agentName");
                    if (agentName == null) agentName = "general-purpose";
                    String agentDisplayName = JsonlUtil.getStr(data, "agentDisplayName");
                    if (agentDisplayName == null) agentDisplayName = agentName;

                    EntryData.SubAgent subAgent = new EntryData.SubAgent(
                        agentName, agentDisplayName, null, null, "running",
                        0, toolCallId, false, null,
                        eventTs, "",
                        currentModel != null ? currentModel : "");

                    int entryIndex = assistantBuffer.addEntry(subAgent);
                    if (toolCallId != null && !toolCallId.isEmpty()) {
                        assistantBuffer.trackSubagent(toolCallId, entryIndex);
                    }
                }

                case "subagent.completed" -> {
                    if (assistantBuffer == null) break;
                    String toolCallId = JsonlUtil.getStr(data, "toolCallId");
                    if (toolCallId != null && !toolCallId.isEmpty()) {
                        assistantBuffer.upgradeSubagentToDone(toolCallId);
                    }
                }

                case "assistant.usage" -> {
                    String model = JsonlUtil.getStr(data, "model");
                    if (model != null && !model.isEmpty()) {
                        currentModel = model;
                    }
                }

                default -> {
                }
            }
        }

        if (assistantBuffer != null) {
            result.addAll(assistantBuffer.build());
        }

        return result;
    }

    @NotNull
    private static String extractEventTimestamp(@NotNull JsonObject event) {
        String ts = JsonlUtil.getStr(event, "timestamp");
        return (ts != null && !ts.isEmpty()) ? ts : Instant.now().toString();
    }

    @NotNull
    private static String extractExecutionResult(@NotNull JsonObject data) {
        if (!data.has("result")) return "";
        JsonElement resultEl = data.get("result");
        if (!resultEl.isJsonObject()) {
            return resultEl.isJsonPrimitive() ? resultEl.getAsString() : resultEl.toString();
        }
        JsonObject resultObj = resultEl.getAsJsonObject();
        if (resultObj.has("content")) {
            JsonElement contentEl = resultObj.get("content");
            if (contentEl.isJsonPrimitive()) return contentEl.getAsString();
            if (contentEl.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement el : contentEl.getAsJsonArray()) {
                    if (el.isJsonObject() && el.getAsJsonObject().has("text")) {
                        if (!sb.isEmpty()) sb.append('\n');
                        sb.append(el.getAsJsonObject().get("text").getAsString());
                    }
                }
                return sb.toString();
            }
        }
        return GSON.toJson(resultObj);
    }

    private static final class AssistantBuffer {
        private final List<EntryData> entries = new ArrayList<>();
        private final Map<String, Integer> toolCallIndexes = new LinkedHashMap<>();
        private final Map<String, Integer> subagentIndexes = new LinkedHashMap<>();

        int addEntry(@NotNull EntryData entry) {
            int idx = entries.size();
            entries.add(entry);
            return idx;
        }

        void trackToolCall(@NotNull String toolCallId, int entryIndex) {
            toolCallIndexes.put(toolCallId, entryIndex);
        }

        void trackSubagent(@NotNull String toolCallId, int entryIndex) {
            subagentIndexes.put(toolCallId, entryIndex);
        }

        void upgradeToolCallToResult(@NotNull String toolCallId, @NotNull String resultContent) {
            Integer idx = toolCallIndexes.get(toolCallId);
            if (idx == null || idx < 0 || idx >= entries.size()) return;
            EntryData entry = entries.get(idx);
            if (entry instanceof EntryData.ToolCall tc) {
                tc.setResult(resultContent);
                tc.setStatus("done");
            }
        }

        void upgradeSubagentToDone(@NotNull String toolCallId) {
            Integer idx = subagentIndexes.get(toolCallId);
            if (idx == null || idx < 0 || idx >= entries.size()) return;
            EntryData entry = entries.get(idx);
            if (entry instanceof EntryData.SubAgent sa) {
                sa.setStatus("done");
            }
        }

        @NotNull
        List<EntryData> build() {
            if (entries.isEmpty()) return List.of();
            return new ArrayList<>(entries);
        }
    }
}
