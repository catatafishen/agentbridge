package com.github.catatafishen.ideagentforcopilot.session.importers;

import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    public static List<SessionMessage> importFile(@NotNull Path path) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        List<JsonObject> events = JsonlUtil.parseJsonl(content);
        return processEvents(events);
    }

    @NotNull
    private static List<SessionMessage> processEvents(@NotNull List<JsonObject> events) {
        List<SessionMessage> result = new ArrayList<>();
        AssistantBuffer assistantBuffer = null;
        @Nullable String currentModel = null;

        for (JsonObject event : events) {
            String type = JsonlUtil.getStr(event, "type");
            JsonObject data = JsonlUtil.getObject(event, "data");
            if (data == null) data = new JsonObject();

            switch (type) {
                case "session.start" -> {
                    if (data != null) {
                        currentModel = JsonlUtil.getStr(data, "selectedModel");
                    }
                }

                case "user.message" -> {
                    if (assistantBuffer != null) {
                        SessionMessage msg = assistantBuffer.build(currentModel);
                        if (msg != null) result.add(msg);
                        assistantBuffer = null;
                    }

                    String userContent = data != null ? JsonlUtil.getStr(data, "content") : null;
                    if (userContent != null && !userContent.isEmpty()) {
                        JsonObject textPart = new JsonObject();
                        textPart.addProperty("type", "text");
                        textPart.addProperty("text", userContent);

                        result.add(new SessionMessage(
                            UUID.randomUUID().toString(),
                            "user",
                            List.of(textPart),
                            System.currentTimeMillis(),
                            null,
                            null));
                    }
                }

                case "assistant.reasoning" -> {
                    if (assistantBuffer == null) {
                        assistantBuffer = new AssistantBuffer();
                    }
                    String reasoningContent = data != null ? JsonlUtil.getStr(data, "content") : null;
                    if (reasoningContent != null && !reasoningContent.isEmpty()) {
                        JsonObject reasoningPart = new JsonObject();
                        reasoningPart.addProperty("type", "reasoning");
                        reasoningPart.addProperty("text", reasoningContent);
                        assistantBuffer.addPart(reasoningPart);
                    }
                }

                case "assistant.message" -> {
                    if (assistantBuffer == null) {
                        assistantBuffer = new AssistantBuffer();
                    }
                    String textContent = data != null ? JsonlUtil.getStr(data, "content") : null;
                    if (textContent != null && !textContent.isEmpty()) {
                        JsonObject textPart = new JsonObject();
                        textPart.addProperty("type", "text");
                        textPart.addProperty("text", textContent);
                        assistantBuffer.addPart(textPart);
                    }
                    if (data != null && data.has("toolRequests") && data.get("toolRequests").isJsonArray()) {
                        for (JsonElement toolReqEl : data.getAsJsonArray("toolRequests")) {
                            if (!toolReqEl.isJsonObject()) continue;
                            JsonObject toolReq = toolReqEl.getAsJsonObject();

                            String toolCallId = JsonlUtil.getStr(toolReq, "toolCallId");
                            if (toolCallId == null) toolCallId = UUID.randomUUID().toString();
                            String toolName = JsonlUtil.getStr(toolReq, "name");
                            if (toolName == null) toolName = "unknown";
                            String argsJson = toolReq.has("arguments") ? GSON.toJson(toolReq.get("arguments")) : "{}";

                            JsonObject invocation = new JsonObject();
                            invocation.addProperty("state", "call");
                            invocation.addProperty("toolCallId", toolCallId);
                            invocation.addProperty("toolName", toolName);
                            invocation.addProperty("args", argsJson);

                            JsonObject toolPart = new JsonObject();
                            toolPart.addProperty("type", "tool-invocation");
                            toolPart.add("toolInvocation", invocation);

                            int partIndex = assistantBuffer.addPart(toolPart);
                            assistantBuffer.trackToolCall(toolCallId, partIndex);
                        }
                    }
                    if (data != null) {
                        currentModel = JsonlUtil.getStr(data, "model");
                    }
                }

                case "tool.execution_complete" -> {
                    if (assistantBuffer == null) break;
                    String toolCallId = data != null ? JsonlUtil.getStr(data, "toolCallId") : null;
                    if (toolCallId == null || toolCallId.isEmpty()) break;

                    String resultContent = extractExecutionResult(data);
                    assistantBuffer.upgradeToolCallToResult(toolCallId, resultContent);
                }

                case "assistant.turn_end" -> {
                    if (assistantBuffer != null) {
                        SessionMessage msg = assistantBuffer.build(currentModel);
                        if (msg != null) result.add(msg);
                        assistantBuffer = null;
                    }
                }

                case "subagent.started" -> {
                    if (assistantBuffer == null) {
                        assistantBuffer = new AssistantBuffer();
                    }
                    String toolCallId = data != null ? JsonlUtil.getStr(data, "toolCallId") : null;
                    String agentName = data != null ? JsonlUtil.getStr(data, "agentName") : null;
                    if (agentName == null) agentName = "general-purpose";
                    String agentDisplayName = data != null ? JsonlUtil.getStr(data, "agentDisplayName") : null;
                    if (agentDisplayName == null) agentDisplayName = agentName;

                    JsonObject subagentPart = new JsonObject();
                    subagentPart.addProperty("type", "subagent");
                    subagentPart.addProperty("agentType", agentName);
                    subagentPart.addProperty("description", agentDisplayName);
                    subagentPart.addProperty("status", "running");

                    int partIndex = assistantBuffer.addPart(subagentPart);
                    if (toolCallId != null && !toolCallId.isEmpty()) {
                        assistantBuffer.trackSubagent(toolCallId, partIndex);
                    }
                }

                case "subagent.completed" -> {
                    if (assistantBuffer == null) break;
                    String toolCallId = data != null ? JsonlUtil.getStr(data, "toolCallId") : null;
                    if (toolCallId != null && !toolCallId.isEmpty()) {
                        assistantBuffer.upgradeSubagentToDone(toolCallId);
                    }
                }

                case "assistant.usage" -> {
                    if (data != null) {
                        String model = JsonlUtil.getStr(data, "model");
                        if (model != null && !model.isEmpty()) {
                            currentModel = model;
                        }
                    }
                }

                default -> {
                }
            }
        }

        if (assistantBuffer != null) {
            SessionMessage msg = assistantBuffer.build(currentModel);
            if (msg != null) result.add(msg);
        }

        return result;
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
        private final List<JsonObject> parts = new ArrayList<>();
        private final Map<String, Integer> toolCallIndexes = new LinkedHashMap<>();
        private final Map<String, Integer> subagentIndexes = new LinkedHashMap<>();

        int addPart(@NotNull JsonObject part) {
            int idx = parts.size();
            parts.add(part);
            return idx;
        }

        void trackToolCall(@NotNull String toolCallId, int partIndex) {
            toolCallIndexes.put(toolCallId, partIndex);
        }

        void trackSubagent(@NotNull String toolCallId, int partIndex) {
            subagentIndexes.put(toolCallId, partIndex);
        }

        void upgradeToolCallToResult(@NotNull String toolCallId, @NotNull String resultContent) {
            Integer idx = toolCallIndexes.get(toolCallId);
            if (idx == null || idx < 0 || idx >= parts.size()) return;

            JsonObject existing = parts.get(idx);
            if (!existing.has("toolInvocation")) return;

            JsonObject invocation = existing.getAsJsonObject("toolInvocation").deepCopy();
            invocation.addProperty("state", "result");
            invocation.addProperty("result", resultContent);

            JsonObject updated = new JsonObject();
            updated.addProperty("type", "tool-invocation");
            updated.add("toolInvocation", invocation);
            parts.set(idx, updated);
        }

        void upgradeSubagentToDone(@NotNull String toolCallId) {
            Integer idx = subagentIndexes.get(toolCallId);
            if (idx == null || idx < 0 || idx >= parts.size()) return;

            JsonObject existing = parts.get(idx).deepCopy();
            existing.addProperty("status", "done");
            parts.set(idx, existing);
        }

        @Nullable
        SessionMessage build(@Nullable String model) {
            if (parts.isEmpty()) return null;
            return new SessionMessage(
                UUID.randomUUID().toString(),
                "assistant",
                new ArrayList<>(parts),
                System.currentTimeMillis(),
                null,
                model);
        }
    }
}
