package com.github.catatafishen.ideagentforcopilot.session.importers;

import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Imports a Copilot CLI session from an {@code events.jsonl} file into a list of
 * {@link SessionMessage}s.
 *
 * <p>Events are walked in file (timestamp) order. Assistant parts are accumulated in a
 * buffer and emitted when {@code assistant.turn_end} is seen or a new {@code user.message}
 * arrives.</p>
 *
 * <p>Key event types handled:
 * <ul>
 *   <li>{@code user.message} — creates a user {@link SessionMessage}</li>
 *   <li>{@code assistant.reasoning} — accumulates a reasoning part</li>
 *   <li>{@code assistant.message} — accumulates text and tool-invocation parts</li>
 *   <li>{@code tool.execution_complete} — upgrades matching tool-invocation to
 *       {@code state:"result"}</li>
 *   <li>{@code assistant.turn_end} — emits the buffered assistant message</li>
 *   <li>{@code subagent.started} — accumulates a subagent part with
 *       {@code status:"running"}</li>
 *   <li>{@code subagent.completed} — upgrades matching subagent part to
 *       {@code status:"done"}</li>
 * </ul>
 * </p>
 */
public final class CopilotEventsImporter {

    private static final Logger LOG = Logger.getInstance(CopilotEventsImporter.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private CopilotEventsImporter() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Imports a Copilot {@code events.jsonl} file and returns a list of
     * {@link SessionMessage}s.
     *
     * @param path path to the {@code events.jsonl} file
     * @return list of converted session messages (never null)
     * @throws IOException if the file cannot be read
     */
    @NotNull
    public static List<SessionMessage> importFile(@NotNull Path path) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        List<JsonObject> events = parseJsonl(content);
        return processEvents(events);
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
                LOG.warn("Skipping malformed JSONL line during Copilot events import: " + line, e);
            }
        }
        return result;
    }

    // ── Event processing ──────────────────────────────────────────────────────

    @NotNull
    private static List<SessionMessage> processEvents(@NotNull List<JsonObject> events) {
        List<SessionMessage> result = new ArrayList<>();

        // Buffer for the current assistant message being assembled.
        // Key: toolCallId → index into assistantParts for tool-invocation/subagent parts.
        AssistantBuffer assistantBuffer = null;
        @Nullable String currentModel = null;

        for (JsonObject event : events) {
            String type = event.has("type") ? event.get("type").getAsString() : "";
            JsonObject data = event.has("data") ? event.getAsJsonObject("data") : new JsonObject();

            switch (type) {
                case "session.start" -> {
                    if (data.has("selectedModel")) {
                        currentModel = data.get("selectedModel").getAsString();
                    }
                    // Ignore other session.start fields (branch, cwd) for the message list
                }

                case "user.message" -> {
                    // Flush any pending assistant buffer before handling user message
                    if (assistantBuffer != null) {
                        SessionMessage msg = assistantBuffer.build(currentModel);
                        if (msg != null) result.add(msg);
                        assistantBuffer = null;
                    }

                    String userContent = data.has("content") ? data.get("content").getAsString() : "";
                    if (!userContent.isEmpty()) {
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
                    String reasoningContent = data.has("content") ? data.get("content").getAsString() : "";
                    if (!reasoningContent.isEmpty()) {
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
                    // Text content
                    String textContent = data.has("content") ? data.get("content").getAsString() : "";
                    if (!textContent.isEmpty()) {
                        JsonObject textPart = new JsonObject();
                        textPart.addProperty("type", "text");
                        textPart.addProperty("text", textContent);
                        assistantBuffer.addPart(textPart);
                    }
                    // Tool requests
                    if (data.has("toolRequests") && data.get("toolRequests").isJsonArray()) {
                        for (JsonElement toolReqEl : data.getAsJsonArray("toolRequests")) {
                            if (!toolReqEl.isJsonObject()) continue;
                            JsonObject toolReq = toolReqEl.getAsJsonObject();

                            String toolCallId = toolReq.has("toolCallId")
                                ? toolReq.get("toolCallId").getAsString()
                                : UUID.randomUUID().toString();
                            String toolName = toolReq.has("name")
                                ? toolReq.get("name").getAsString()
                                : "unknown";
                            String argsJson = toolReq.has("arguments")
                                ? GSON.toJson(toolReq.get("arguments"))
                                : "{}";

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
                    // Update model if present
                    if (data.has("model")) {
                        currentModel = data.get("model").getAsString();
                    }
                }

                case "tool.execution_complete" -> {
                    if (assistantBuffer == null) break;
                    String toolCallId = data.has("toolCallId") ? data.get("toolCallId").getAsString() : "";
                    if (toolCallId.isEmpty()) break;

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
                    String toolCallId = data.has("toolCallId") ? data.get("toolCallId").getAsString() : "";
                    String agentName = data.has("agentName") ? data.get("agentName").getAsString() : "general-purpose";
                    String agentDisplayName = data.has("agentDisplayName")
                        ? data.get("agentDisplayName").getAsString()
                        : agentName;

                    JsonObject subagentPart = new JsonObject();
                    subagentPart.addProperty("type", "subagent");
                    subagentPart.addProperty("agentType", agentName);
                    subagentPart.addProperty("description", agentDisplayName);
                    subagentPart.addProperty("status", "running");

                    int partIndex = assistantBuffer.addPart(subagentPart);
                    if (!toolCallId.isEmpty()) {
                        assistantBuffer.trackSubagent(toolCallId, partIndex);
                    }
                }

                case "subagent.completed" -> {
                    if (assistantBuffer == null) break;
                    String toolCallId = data.has("toolCallId") ? data.get("toolCallId").getAsString() : "";
                    if (!toolCallId.isEmpty()) {
                        assistantBuffer.upgradeSubagentToDone(toolCallId);
                    }
                }

                case "assistant.usage" -> {
                    // Update model from usage event
                    if (data.has("model") && !data.get("model").getAsString().isEmpty()) {
                        currentModel = data.get("model").getAsString();
                    }
                    // inputTokens, outputTokens, cost — not stored in SessionMessage currently
                }

                default -> {
                    // Unknown event types are silently ignored for forward-compat
                }
            }
        }

        // Flush any remaining buffer at end-of-file
        if (assistantBuffer != null) {
            SessionMessage msg = assistantBuffer.build(currentModel);
            if (msg != null) result.add(msg);
        }

        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
            // Array of content blocks
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

    // ── Inner helper ──────────────────────────────────────────────────────────

    /**
     * Mutable buffer for an in-progress assistant message.
     */
    private static final class AssistantBuffer {

        /**
         * Ordered list of parts being accumulated.
         */
        private final List<JsonObject> parts = new ArrayList<>();

        /**
         * Maps toolCallId → index in {@link #parts} for tool-invocation parts,
         * so results can be patched in when {@code tool.execution_complete} arrives.
         */
        private final Map<String, Integer> toolCallIndexes = new LinkedHashMap<>();

        /**
         * Maps toolCallId → index in {@link #parts} for subagent parts,
         * so they can be upgraded to {@code status:"done"} on {@code subagent.completed}.
         */
        private final Map<String, Integer> subagentIndexes = new LinkedHashMap<>();

        /**
         * Adds a part and returns its index.
         */
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

        /**
         * Upgrades a tool-invocation part from {@code state:"call"} to
         * {@code state:"result"} with the given result content.
         */
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

        /**
         * Upgrades a subagent part from {@code status:"running"} to
         * {@code status:"done"}.
         */
        void upgradeSubagentToDone(@NotNull String toolCallId) {
            Integer idx = subagentIndexes.get(toolCallId);
            if (idx == null || idx < 0 || idx >= parts.size()) return;

            JsonObject existing = parts.get(idx).deepCopy();
            existing.addProperty("status", "done");
            parts.set(idx, existing);
        }

        /**
         * Builds a {@link SessionMessage} from the buffered parts.
         * Returns {@code null} if there are no parts.
         */
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
