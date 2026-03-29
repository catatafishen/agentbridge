package com.github.catatafishen.ideagentforcopilot.session.exporters;

import com.github.catatafishen.ideagentforcopilot.session.importers.JsonlUtil;
import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
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
        @NotNull List<SessionMessage> messages,
        @NotNull Path targetPath) throws IOException {

        exportToFile(messages, targetPath, null, null);
    }

    /**
     * Exports v2 messages to Copilot CLI {@code events.jsonl}.
     *
     * @param sessionId if provided, used in the session.start event; otherwise a new UUID is generated
     * @param basePath  project base path for CWD in the session.start event; if {@code null}, falls
     *                  back to {@code System.getProperty("user.dir")}
     */
    public static void exportToFile(
        @NotNull List<SessionMessage> messages,
        @NotNull Path targetPath,
        @Nullable String sessionId,
        @Nullable String basePath) throws IOException {

        String sid = sessionId != null ? sessionId : UUID.randomUUID().toString();
        EventChain chain = new EventChain();
        StringBuilder sb = new StringBuilder();

        String model = findFirstModel(messages);
        Instant startTime;
        if (messages.isEmpty() || messages.getFirst().createdAt <= 0) {
            startTime = Instant.now();
        } else {
            startTime = Instant.ofEpochMilli(messages.getFirst().createdAt);
        }

        sb.append(chain.emit("session.start", sessionStartData(sid, model, startTime, basePath))).append('\n');

        if (model != null) {
            JsonObject modelData = new JsonObject();
            modelData.addProperty("newModel", model);
            sb.append(chain.emit("session.model_change", modelData)).append('\n');
        }

        for (SessionMessage msg : messages) {
            switch (msg.role) {
                case "user" -> writeUserMessage(msg, sb, chain);
                case "assistant" -> writeAssistantTurn(msg, sb, chain);
                default -> { /* separators and unknown roles are skipped */ }
            }
        }

        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(targetPath, sb.toString(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        LOG.info("Exported v2 session to Copilot events.jsonl: " + targetPath);
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

    private static void writeUserMessage(
        @NotNull SessionMessage msg,
        @NotNull StringBuilder sb,
        @NotNull EventChain chain) {

        String text = extractText(msg);
        if (text.isEmpty()) return;

        String interactionId = UUID.randomUUID().toString();
        JsonObject data = new JsonObject();
        data.addProperty(CONTENT_KEY, text);
        data.add("attachments", new JsonArray());
        data.addProperty(INTERACTION_ID_KEY, interactionId);
        sb.append(chain.emit("user.message", data)).append('\n');
    }

    private static void writeAssistantTurn(
        @NotNull SessionMessage msg,
        @NotNull StringBuilder sb,
        @NotNull EventChain chain) {

        String interactionId = UUID.randomUUID().toString();
        String turnId = UUID.randomUUID().toString();

        JsonObject turnStartData = new JsonObject();
        turnStartData.addProperty(TURN_ID_KEY, turnId);
        turnStartData.addProperty(INTERACTION_ID_KEY, interactionId);
        sb.append(chain.emit("assistant.turn_start", turnStartData)).append('\n');

        for (JsonObject part : msg.parts) {
            String type = JsonlUtil.getStr(part, "type");
            if (type == null) continue;

            switch (type) {
                case "reasoning" -> writeReasoningEvent(part, sb, chain);
                case "text" -> writeAssistantMessageEvent(part, msg, sb, chain, interactionId);
                case "tool-invocation" -> writeToolEvents(part, sb, chain, interactionId);
                case "subagent" -> writeSubagentEvents(part, sb, chain);
                default -> { /* status, file, etc. — not representable in events.jsonl */ }
            }
        }

        JsonObject turnEndData = new JsonObject();
        turnEndData.addProperty(TURN_ID_KEY, turnId);
        sb.append(chain.emit("assistant.turn_end", turnEndData)).append('\n');
    }

    private static void writeReasoningEvent(
        @NotNull JsonObject part,
        @NotNull StringBuilder sb,
        @NotNull EventChain chain) {

        String text = JsonlUtil.getStr(part, "text");
        if (text == null || text.isEmpty()) return;

        JsonObject data = new JsonObject();
        data.addProperty(CONTENT_KEY, text);
        sb.append(chain.emit("assistant.reasoning", data)).append('\n');
    }

    private static void writeAssistantMessageEvent(
        @NotNull JsonObject part,
        @NotNull SessionMessage msg,
        @NotNull StringBuilder sb,
        @NotNull EventChain chain,
        @NotNull String interactionId) {

        String text = JsonlUtil.getStr(part, "text");
        if (text == null || text.isEmpty()) return;

        JsonObject data = new JsonObject();
        data.addProperty("messageId", UUID.randomUUID().toString());
        data.addProperty(CONTENT_KEY, text);
        data.addProperty(INTERACTION_ID_KEY, interactionId);
        if (msg.model != null) {
            data.addProperty("model", msg.model);
        }
        sb.append(chain.emit("assistant.message", data)).append('\n');
    }

    private static void writeToolEvents(
        @NotNull JsonObject part,
        @NotNull StringBuilder sb,
        @NotNull EventChain chain,
        @NotNull String interactionId) {

        JsonObject invocation = part.getAsJsonObject("toolInvocation");
        if (invocation == null) return;

        String state = JsonlUtil.getStr(invocation, "state");
        String toolCallId = JsonlUtil.getStr(invocation, TOOL_CALL_ID_KEY);
        if (toolCallId == null) toolCallId = UUID.randomUUID().toString();
        String toolName = JsonlUtil.getStr(invocation, "toolName");
        if (toolName == null) toolName = "unknown";
        String argsStr = JsonlUtil.getStr(invocation, "args");

        if ("call".equals(state) || RESULT_KEY.equals(state)) {
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

            JsonObject data = new JsonObject();
            data.addProperty("messageId", UUID.randomUUID().toString());
            data.addProperty(CONTENT_KEY, "");
            data.add("toolRequests", toolRequests);
            data.addProperty(INTERACTION_ID_KEY, interactionId);
            sb.append(chain.emit("assistant.message", data)).append('\n');
        }

        if (RESULT_KEY.equals(state)) {
            String result = JsonlUtil.getStr(invocation, RESULT_KEY);
            JsonObject resultObj = new JsonObject();
            resultObj.addProperty(CONTENT_KEY, result != null ? result : "");

            JsonObject data = new JsonObject();
            data.addProperty(TOOL_CALL_ID_KEY, toolCallId);
            data.add(RESULT_KEY, resultObj);
            sb.append(chain.emit("tool.execution_complete", data)).append('\n');
        }
    }

    private static void writeSubagentEvents(
        @NotNull JsonObject part,
        @NotNull StringBuilder sb,
        @NotNull EventChain chain) {

        String agentType = JsonlUtil.getStr(part, "agentType");
        String description = JsonlUtil.getStr(part, "description");
        String status = JsonlUtil.getStr(part, "status");
        String toolCallId = UUID.randomUUID().toString();

        JsonObject startData = new JsonObject();
        startData.addProperty(TOOL_CALL_ID_KEY, toolCallId);
        startData.addProperty("agentName", agentType != null ? agentType : "general-purpose");
        startData.addProperty("agentDisplayName", description != null ? description : "");
        sb.append(chain.emit("subagent.started", startData)).append('\n');

        if ("done".equals(status)) {
            JsonObject completeData = new JsonObject();
            completeData.addProperty(TOOL_CALL_ID_KEY, toolCallId);
            sb.append(chain.emit("subagent.completed", completeData)).append('\n');
        }
    }

    @NotNull
    private static String extractText(@NotNull SessionMessage msg) {
        StringBuilder textBuilder = new StringBuilder();
        for (JsonObject part : msg.parts) {
            String type = JsonlUtil.getStr(part, "type");
            if ("text".equals(type)) {
                String text = JsonlUtil.getStr(part, "text");
                if (text != null) textBuilder.append(text);
            }
        }
        return textBuilder.toString();
    }

    @Nullable
    private static String findFirstModel(@NotNull List<SessionMessage> messages) {
        for (SessionMessage msg : messages) {
            if (msg.model != null && !msg.model.isEmpty()) return msg.model;
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
            String id = UUID.randomUUID().toString();
            Instant ts = Instant.ofEpochMilli(timestampMs);
            timestampMs += 100; // advance slightly to maintain ordering

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
