package com.github.catatafishen.ideagentforcopilot.session.exporters;

import com.github.catatafishen.ideagentforcopilot.ui.EntryData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

public final class CopilotClientExporter {

    private static final Logger LOG = Logger.getInstance(CopilotClientExporter.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final String CONTENT_KEY = "content";
    private static final String TOOL_CALL_ID_KEY = "toolCallId";
    private static final String RESULT_KEY = "result";
    private static final String INTERACTION_ID_KEY = "interactionId";
    private static final String TURN_ID_KEY = "turnId";

    private CopilotClientExporter() {
    }

    @NotNull
    public static Path defaultSessionStateDir(@NotNull String basePath) {
        return Path.of(System.getProperty("user.home"), ".copilot", "session-state");
    }

    public static void exportToFile(
        @NotNull List<EntryData> entries,
        @NotNull Path targetPath) throws IOException {

        exportToFile(entries, targetPath, null, null);
    }

    /**
     * Exports entries to Copilot CLI {@code events.jsonl}.
     *
     * @param sessionId if provided, used in the session.start event; otherwise a new UUID is generated
     * @param basePath  project base path for CWD in the session.start event; if {@code null}, falls
     *                  back to {@code System.getProperty("user.dir")}
     */
    public static void exportToFile(
        @NotNull List<EntryData> entries,
        @NotNull Path targetPath,
        @Nullable String sessionId,
        @Nullable String basePath) throws IOException {

        String sid = sessionId != null ? sessionId : UUID.randomUUID().toString();
        EventChain chain = new EventChain();
        StringBuilder sb = new StringBuilder();

        String model = findFirstModel(entries);
        Instant startTime = resolveStartTime(entries);

        sb.append(chain.emit("session.start", sessionStartData(sid, model, startTime, basePath))).append('\n');

        if (model != null) {
            JsonObject modelData = new JsonObject();
            modelData.addProperty("newModel", model);
            sb.append(chain.emit("session.model_change", modelData)).append('\n');
        }

        boolean inTurn = false;
        String turnId = null;
        String interactionId = null;

        for (EntryData entry : entries) {
            if (entry instanceof EntryData.Prompt prompt) {
                if (inTurn) {
                    sb.append(chain.emit("assistant.turn_end", turnEndData(turnId))).append('\n');
                    inTurn = false;
                }
                writePromptEntry(prompt, sb, chain);
            } else if (entry instanceof EntryData.Thinking thinking) {
                if (!inTurn) {
                    interactionId = UUID.randomUUID().toString();
                    turnId = UUID.randomUUID().toString();
                    sb.append(chain.emit("assistant.turn_start", turnStartData(turnId, interactionId))).append('\n');
                    inTurn = true;
                }
                writeThinkingEntry(thinking, sb, chain);
            } else if (entry instanceof EntryData.Text text) {
                if (!inTurn) {
                    interactionId = UUID.randomUUID().toString();
                    turnId = UUID.randomUUID().toString();
                    sb.append(chain.emit("assistant.turn_start", turnStartData(turnId, interactionId))).append('\n');
                    inTurn = true;
                }
                writeTextEntry(text, sb, chain, interactionId);
            } else if (entry instanceof EntryData.ToolCall toolCall) {
                if (!inTurn) {
                    interactionId = UUID.randomUUID().toString();
                    turnId = UUID.randomUUID().toString();
                    sb.append(chain.emit("assistant.turn_start", turnStartData(turnId, interactionId))).append('\n');
                    inTurn = true;
                }
                writeToolCallEntry(toolCall, sb, chain, interactionId);
            } else if (entry instanceof EntryData.SubAgent subAgent) {
                if (!inTurn) {
                    interactionId = UUID.randomUUID().toString();
                    turnId = UUID.randomUUID().toString();
                    sb.append(chain.emit("assistant.turn_start", turnStartData(turnId, interactionId))).append('\n');
                    inTurn = true;
                }
                writeSubAgentEntry(subAgent, sb, chain);
            }
            // Skip Status, TurnStats, ContextFiles, SessionSeparator
        }

        if (inTurn) {
            sb.append(chain.emit("assistant.turn_end", turnEndData(turnId))).append('\n');
        }

        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(targetPath, sb.toString(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        LOG.info("Exported session to Copilot events.jsonl: " + targetPath);
    }

    @NotNull
    private static JsonObject sessionStartData(
        @NotNull String sessionId,
        @Nullable String model,
        @NotNull Instant startTime,
        @Nullable String basePath) {

        JsonObject context = new JsonObject();
        String cwd = (basePath != null && !basePath.isEmpty()) ? basePath : System.getProperty("user.dir", "");
        context.addProperty("cwd", cwd);
        context.addProperty("gitRoot", cwd);

        JsonObject data = new JsonObject();
        data.addProperty("sessionId", sessionId);
        data.addProperty("version", 1);
        data.addProperty("producer", "copilot-agent");
        data.addProperty("startTime", startTime.toString());
        data.add("context", context);
        data.addProperty("alreadyInUse", false);
        if (model != null) {
            data.addProperty("selectedModel", model);
        }
        return data;
    }

    @NotNull
    private static Instant resolveStartTime(@NotNull List<EntryData> entries) {
        for (EntryData entry : entries) {
            String ts = null;
            if (entry instanceof EntryData.Prompt p) ts = p.getTimestamp();
            else if (entry instanceof EntryData.Text t) ts = t.getTimestamp();
            else if (entry instanceof EntryData.Thinking th) ts = th.getTimestamp();
            else if (entry instanceof EntryData.ToolCall tc) ts = tc.getTimestamp();
            else if (entry instanceof EntryData.SubAgent sa) ts = sa.getTimestamp();
            if (ts != null && !ts.isEmpty()) {
                try {
                    return Instant.parse(ts);
                } catch (Exception ignored) {
                }
            }
        }
        return Instant.now();
    }

    @NotNull
    private static JsonObject turnStartData(@NotNull String turnId, @NotNull String interactionId) {
        JsonObject data = new JsonObject();
        data.addProperty(TURN_ID_KEY, turnId);
        data.addProperty(INTERACTION_ID_KEY, interactionId);
        return data;
    }

    @NotNull
    private static JsonObject turnEndData(@NotNull String turnId) {
        JsonObject data = new JsonObject();
        data.addProperty(TURN_ID_KEY, turnId);
        return data;
    }

    private static void writePromptEntry(
        @NotNull EntryData.Prompt prompt,
        @NotNull StringBuilder sb,
        @NotNull EventChain chain) {

        String text = prompt.getText();
        if (text.isEmpty()) return;

        String interactionId = UUID.randomUUID().toString();
        JsonObject data = new JsonObject();
        data.addProperty(CONTENT_KEY, text);
        data.add("attachments", new JsonArray());
        data.addProperty(INTERACTION_ID_KEY, interactionId);
        sb.append(chain.emit("user.message", data, prompt.getTimestamp())).append('\n');
    }

    private static void writeThinkingEntry(
        @NotNull EntryData.Thinking thinking,
        @NotNull StringBuilder sb,
        @NotNull EventChain chain) {

        String text = thinking.getRaw().toString();
        if (text.isEmpty()) return;

        JsonObject data = new JsonObject();
        data.addProperty(CONTENT_KEY, text);
        sb.append(chain.emit("assistant.reasoning", data, thinking.getTimestamp())).append('\n');
    }

    private static void writeTextEntry(
        @NotNull EntryData.Text text,
        @NotNull StringBuilder sb,
        @NotNull EventChain chain,
        @NotNull String interactionId) {

        String content = text.getRaw().toString();
        if (content.isEmpty()) return;

        JsonObject data = new JsonObject();
        data.addProperty("messageId", UUID.randomUUID().toString());
        data.addProperty(CONTENT_KEY, content);
        data.addProperty(INTERACTION_ID_KEY, interactionId);
        String model = text.getModel();
        if (model != null && !model.isEmpty()) {
            data.addProperty("model", model);
        }
        sb.append(chain.emit("assistant.message", data, text.getTimestamp())).append('\n');
    }

    private static void writeToolCallEntry(
        @NotNull EntryData.ToolCall toolCall,
        @NotNull StringBuilder sb,
        @NotNull EventChain chain,
        @NotNull String interactionId) {

        String toolCallId = UUID.randomUUID().toString();
        String toolName = toolCall.getTitle();
        if (toolName == null || toolName.isEmpty()) toolName = "unknown";
        String argsStr = toolCall.getArguments();

        JsonObject toolReq = new JsonObject();
        toolReq.addProperty(TOOL_CALL_ID_KEY, toolCallId);
        toolReq.addProperty("name", toolName);
        if (argsStr != null) {
            try {
                toolReq.add("arguments", JsonParser.parseString(argsStr));
            } catch (Exception e) {
                toolReq.addProperty("arguments", argsStr);
            }
        }
        JsonArray toolRequests = new JsonArray();
        toolRequests.add(toolReq);

        JsonObject msgData = new JsonObject();
        msgData.addProperty("messageId", UUID.randomUUID().toString());
        msgData.addProperty(CONTENT_KEY, "");
        msgData.add("toolRequests", toolRequests);
        msgData.addProperty(INTERACTION_ID_KEY, interactionId);
        sb.append(chain.emit("assistant.message", msgData, toolCall.getTimestamp())).append('\n');

        String result = toolCall.getResult();
        JsonObject resultObj = new JsonObject();
        resultObj.addProperty(CONTENT_KEY, result != null ? result : "");

        JsonObject completeData = new JsonObject();
        completeData.addProperty(TOOL_CALL_ID_KEY, toolCallId);
        completeData.add(RESULT_KEY, resultObj);
        sb.append(chain.emit("tool.execution_complete", completeData, toolCall.getTimestamp())).append('\n');
    }

    private static void writeSubAgentEntry(
        @NotNull EntryData.SubAgent subAgent,
        @NotNull StringBuilder sb,
        @NotNull EventChain chain) {

        String toolCallId = UUID.randomUUID().toString();

        JsonObject startData = new JsonObject();
        startData.addProperty(TOOL_CALL_ID_KEY, toolCallId);
        String agentType = subAgent.getAgentType();
        startData.addProperty("agentName", agentType != null ? agentType : "general-purpose");
        String description = subAgent.getDescription();
        startData.addProperty("agentDisplayName", description != null ? description : "");
        sb.append(chain.emit("subagent.started", startData, subAgent.getTimestamp())).append('\n');

        if ("done".equals(subAgent.getStatus())) {
            JsonObject completeData = new JsonObject();
            completeData.addProperty(TOOL_CALL_ID_KEY, toolCallId);
            sb.append(chain.emit("subagent.completed", completeData, subAgent.getTimestamp())).append('\n');
        }
    }

    @Nullable
    private static String findFirstModel(@NotNull List<EntryData> entries) {
        for (EntryData entry : entries) {
            String model = null;
            if (entry instanceof EntryData.Text t) model = t.getModel();
            else if (entry instanceof EntryData.Thinking th) model = th.getModel();
            else if (entry instanceof EntryData.ToolCall tc) model = tc.getModel();
            else if (entry instanceof EntryData.SubAgent sa) model = sa.getModel();
            if (model != null && !model.isEmpty()) return model;
        }
        return null;
    }

    /**
     * Maintains a sequential event chain with IDs and timestamps.
     * Each event gets a UUID and references the previous event's ID as parentId.
     */
    private static final class EventChain {
        private String lastId = UUID.randomUUID().toString();
        private long timestampMs = System.currentTimeMillis();

        @NotNull
        String emit(@NotNull String type, @NotNull JsonObject data) {
            return emit(type, data, "");
        }

        @NotNull
        String emit(@NotNull String type, @NotNull JsonObject data, @NotNull String isoTimestamp) {
            String id = UUID.randomUUID().toString();
            Instant ts;
            if (!isoTimestamp.isEmpty()) {
                try {
                    ts = Instant.parse(isoTimestamp);
                    timestampMs = ts.toEpochMilli() + 1; // anchor for future fallback
                } catch (DateTimeParseException e) {
                    ts = Instant.ofEpochMilli(timestampMs);
                    timestampMs += 100;
                }
            } else {
                ts = Instant.ofEpochMilli(timestampMs);
                timestampMs += 100;
            }

            JsonObject event = new JsonObject();
            event.addProperty("type", type);
            event.add("data", data);
            event.addProperty("id", id);
            event.addProperty("timestamp", ts.toString());
            event.addProperty("parentId", lastId);

            lastId = id;
            return GSON.toJson(event);
        }
    }
}
