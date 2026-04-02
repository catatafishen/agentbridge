package com.github.catatafishen.ideagentforcopilot.session.exporters;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class KiroClientExporter {

    private static final Logger LOG = Logger.getInstance(KiroClientExporter.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private static final String KIRO_FORMAT_VERSION = "v1";
    private static final String KIND_PROMPT = "Prompt";
    private static final String KIND_ASSISTANT_MESSAGE = "AssistantMessage";
    private static final String KIND_TOOL_RESULTS = "ToolResults";

    private static final String CONTENT_KIND_TEXT = "text";
    private static final String CONTENT_KIND_TOOL_USE = "toolUse";
    private static final String CONTENT_KIND_TOOL_RESULT = "toolResult";
    private static final String CONTENT_KIND_JSON = "json";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_VERSION = "version";
    private static final String KEY_TOOL_NAME = "toolName";
    private static final String KEY_TOOL_USE_ID = "toolUseId";
    private static final String KEY_MESSAGE_ID = "message_id";

    private static final String PART_TYPE_TEXT = "text";
    private static final String PART_TYPE_TOOL_INVOCATION = "tool-invocation";
    private static final String FIELD_TOOL_INVOCATION = "toolInvocation";
    private static final String STATE_RESULT = "result";

    private KiroClientExporter() {
    }

    /**
     * Returns the default Kiro CLI sessions directory ({@code ~/.kiro/sessions/cli/}).
     */
    @NotNull
    public static Path defaultSessionsDir() {
        return Path.of(System.getProperty("user.home"), ".kiro", "sessions", "cli");
    }

    /**
     * Exports v2 session messages to Kiro's native format.
     *
     * @param messages    v2 session messages to export
     * @param cwd         project working directory (may be null)
     * @param sessionsDir Kiro CLI sessions directory (e.g. {@code ~/.kiro/sessions/cli/})
     * @return the generated session ID, or {@code null} on failure
     */
    @Nullable
    public static String exportSession(
        @NotNull List<SessionMessage> messages,
        @Nullable String cwd,
        @NotNull Path sessionsDir) {

        if (messages.isEmpty()) {
            LOG.info("No messages to export to Kiro");
            return null;
        }

        try {
            Files.createDirectories(sessionsDir);

            String sessionId = UUID.randomUUID().toString();

            writeSessionJson(sessionId, cwd, sessionsDir);
            writeMessagesJsonl(messages, sessionId, sessionsDir);

            LOG.info("Exported v2 session to Kiro CLI: " + sessionId
                + " (" + messages.size() + " v2 messages)");
            return sessionId;
        } catch (IOException e) {
            LOG.warn("Failed to export session to Kiro CLI format", e);
            return null;
        }
    }

    private static void writeSessionJson(
        @NotNull String sessionId,
        @Nullable String cwd,
        @NotNull Path sessionsDir) throws IOException {

        String now = Instant.now().toString();
        String effectiveCwd = cwd != null ? cwd : "";

        JsonObject conversationMetadata = new JsonObject();
        conversationMetadata.add("user_turn_metadatas", new JsonArray());
        conversationMetadata.add("user_turn_start_request", null);
        conversationMetadata.add("last_request", null);

        JsonObject rtsModelState = new JsonObject();
        rtsModelState.addProperty("conversation_id", sessionId);
        rtsModelState.add("model_info", null);
        rtsModelState.add("context_usage_percentage", null);

        JsonArray allowedReadPaths = new JsonArray();
        if (!effectiveCwd.isEmpty()) {
            allowedReadPaths.add(effectiveCwd);
        }

        JsonObject filesystem = new JsonObject();
        filesystem.add("allowed_read_paths", allowedReadPaths);
        filesystem.add("allowed_write_paths", new JsonArray());
        filesystem.add("denied_read_paths", new JsonArray());
        filesystem.add("denied_write_paths", new JsonArray());

        JsonObject permissions = new JsonObject();
        permissions.add("filesystem", filesystem);
        permissions.add("trusted_tools", new JsonArray());
        permissions.add("denied_tools", new JsonArray());

        JsonObject sessionState = new JsonObject();
        sessionState.addProperty(KEY_VERSION, KIRO_FORMAT_VERSION);
        sessionState.add("conversation_metadata", conversationMetadata);
        sessionState.add("rts_model_state", rtsModelState);
        sessionState.add("permissions", permissions);

        JsonObject sessionJson = new JsonObject();
        sessionJson.addProperty("session_id", sessionId);
        sessionJson.addProperty("cwd", effectiveCwd);
        sessionJson.addProperty("created_at", now);
        sessionJson.addProperty("updated_at", now);
        sessionJson.add("session_state", sessionState);

        Files.writeString(
            sessionsDir.resolve(sessionId + ".json"),
            GSON.toJson(sessionJson),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void writeMessagesJsonl(
        @NotNull List<SessionMessage> messages,
        @NotNull String sessionId,
        @NotNull Path sessionsDir) throws IOException {

        List<JsonObject> kiroMessages = toKiroMessages(messages);

        StringBuilder sb = new StringBuilder();
        for (JsonObject msg : kiroMessages) {
            sb.append(GSON.toJson(msg)).append('\n');
        }

        Files.writeString(
            sessionsDir.resolve(sessionId + ".jsonl"),
            sb.toString(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    @NotNull
    static List<JsonObject> toKiroMessages(@NotNull List<SessionMessage> messages) {
        List<JsonObject> result = new ArrayList<>();

        for (SessionMessage msg : messages) {
            switch (msg.role) {
                case "separator" -> { /* skip */ }
                case "user" -> {
                    JsonObject prompt = convertUserMessage(msg);
                    if (prompt != null) result.add(prompt);
                }
                case "assistant" -> result.addAll(convertAssistantMessage(msg));
                default -> LOG.debug("Skipping unknown role: " + msg.role);
            }
        }

        // Kiro requires conversation history to begin with a Prompt (user message).
        // If the first message is not a Prompt, prepend a placeholder to avoid a panic.
        if (!result.isEmpty()
            && !KIND_PROMPT.equals(result.getFirst().get("kind").getAsString())) {
            LOG.warn("Kiro export: first message is not a Prompt ("
                + result.getFirst().get("kind").getAsString()
                + "); prepending placeholder to prevent crash");

            JsonArray content = new JsonArray();
            content.add(textContentBlock("(continued from previous session)"));
            result.addFirst(wrapMessage(KIND_PROMPT, UUID.randomUUID().toString(), content));
        }

        // Kiro rejects consecutive Prompt messages ("invalid conversation history").
        // Merge any consecutive Prompts by appending the later content into the earlier one.
        mergeConsecutivePrompts(result);

        return result;
    }

    /**
     * Removes duplicate consecutive {@code Prompt} messages in-place, keeping the last one.
     *
     * <p>Kiro rejects conversation history that contains two Prompts in a row without an
     * intervening {@code AssistantMessage}. This can happen when the user sends duplicate
     * messages or when a rate-limit/error turn is followed immediately by a retry prompt.
     * We drop the earlier duplicate and keep the later one.</p>
     */
    private static void mergeConsecutivePrompts(@NotNull List<JsonObject> messages) {
        int i = 0;
        while (i < messages.size() - 1) {
            JsonObject current = messages.get(i);
            JsonObject next = messages.get(i + 1);
            if (KIND_PROMPT.equals(current.get("kind").getAsString())
                && KIND_PROMPT.equals(next.get("kind").getAsString())) {
                LOG.warn("Kiro export: removing duplicate consecutive Prompt at index " + i);
                messages.remove(i);
                // don't advance i — the item now at i may also be followed by another Prompt
            } else {
                i++;
            }
        }
    }

    @Nullable
    private static JsonObject convertUserMessage(@NotNull SessionMessage msg) {
        JsonArray content = new JsonArray();

        for (JsonObject part : msg.parts) {
            if (PART_TYPE_TEXT.equals(partType(part))) {
                String text = partText(part);
                if (!text.isEmpty()) {
                    content.add(textContentBlock(text));
                }
            }
        }

        if (content.isEmpty()) return null;
        return wrapMessage(KIND_PROMPT, msg.id, content);
    }

    /**
     * Converts a v2 assistant message into one or more Kiro messages.
     *
     * <p>A single v2 assistant message may contain interleaved text and tool invocations.
     * In Kiro format, tool_use blocks are part of the AssistantMessage, and the corresponding
     * tool results are emitted as a separate ToolResults message.</p>
     *
     * <p>When text follows tool invocations, a new turn boundary is created
     * (the previous AssistantMessage + ToolResults are emitted, and a new
     * AssistantMessage begins).</p>
     */
    @NotNull
    private static List<JsonObject> convertAssistantMessage(@NotNull SessionMessage msg) {
        List<JsonObject> result = new ArrayList<>();

        JsonArray assistantContent = new JsonArray();
        List<ToolPair> pendingTools = new ArrayList<>();
        boolean seenToolUse = false;

        for (JsonObject part : msg.parts) {
            String type = partType(part);

            if (PART_TYPE_TEXT.equals(type)) {
                String text = partText(part);
                if (text.isEmpty()) continue;

                if (seenToolUse) {
                    emitAssistantTurn(assistantContent, pendingTools, result);
                    assistantContent = new JsonArray();
                    pendingTools = new ArrayList<>();
                    seenToolUse = false;
                }
                assistantContent.add(textContentBlock(text));

            } else if (PART_TYPE_TOOL_INVOCATION.equals(type) && part.has(FIELD_TOOL_INVOCATION)) {
                ToolPair pair = buildToolPair(part.getAsJsonObject(FIELD_TOOL_INVOCATION));
                if (pair != null) {
                    assistantContent.add(pair.toolUseBlock);
                    pendingTools.add(pair);
                    seenToolUse = true;
                }
            }
        }

        emitAssistantTurn(assistantContent, pendingTools, result);
        return result;
    }

    private static void emitAssistantTurn(
        @NotNull JsonArray assistantContent,
        @NotNull List<ToolPair> pendingTools,
        @NotNull List<JsonObject> out) {

        if (assistantContent.isEmpty()) return;

        out.add(wrapMessage(KIND_ASSISTANT_MESSAGE, UUID.randomUUID().toString(), assistantContent));

        if (!pendingTools.isEmpty()) {
            out.add(buildToolResultsMessage(pendingTools));
        }
    }

    @NotNull
    private static JsonObject buildToolResultsMessage(@NotNull List<ToolPair> tools) {
        String messageId = UUID.randomUUID().toString();

        JsonArray content = new JsonArray();
        JsonObject results = new JsonObject();

        for (ToolPair pair : tools) {
            content.add(pair.toolResultBlock);

            JsonObject mcpInfo = new JsonObject();
            mcpInfo.addProperty(KEY_TOOL_NAME, pair.toolName);
            mcpInfo.addProperty("serverName", "agentbridge");
            mcpInfo.add("params", pair.input);

            JsonObject toolKind = new JsonObject();
            toolKind.add("Mcp", mcpInfo);

            JsonObject toolMeta = new JsonObject();
            toolMeta.add("tool", toolKind);

            JsonArray items = new JsonArray();
            JsonObject jsonItem = new JsonObject();
            jsonItem.add("Json", pair.resultContent);
            items.add(jsonItem);

            JsonObject successWrapper = new JsonObject();
            successWrapper.add("items", items);

            JsonObject resultWrapper = new JsonObject();
            resultWrapper.add("Success", successWrapper);
            toolMeta.add(STATE_RESULT, resultWrapper);

            results.add(pair.toolCallId, toolMeta);
        }

        JsonObject data = new JsonObject();
        data.addProperty(KEY_MESSAGE_ID, messageId);
        data.add(KEY_CONTENT, content);
        data.add("results", results);

        JsonObject envelope = new JsonObject();
        envelope.addProperty(KEY_VERSION, KIRO_FORMAT_VERSION);
        envelope.addProperty("kind", KIND_TOOL_RESULTS);
        envelope.add("data", data);
        return envelope;
    }

    @Nullable
    private static ToolPair buildToolPair(@NotNull JsonObject inv) {
        String state = inv.has("state") ? inv.get("state").getAsString() : "call";
        if (!STATE_RESULT.equals(state)) return null;

        String toolCallId = inv.has("toolCallId") ? inv.get("toolCallId").getAsString() : UUID.randomUUID().toString();
        String toolName = AnthropicClientExporter.sanitizeToolName(
            inv.has(KEY_TOOL_NAME) ? inv.get(KEY_TOOL_NAME).getAsString() : "unknown");
        String argsStr = inv.has("args") ? inv.get("args").getAsString() : "{}";
        String resultStr = inv.has(STATE_RESULT) ? inv.get(STATE_RESULT).getAsString() : "";

        JsonObject inputObj;
        try {
            inputObj = JsonParser.parseString(argsStr).getAsJsonObject();
        } catch (Exception e) {
            inputObj = new JsonObject();
            inputObj.addProperty("_raw", argsStr);
        }

        JsonObject toolUseData = new JsonObject();
        toolUseData.addProperty(KEY_TOOL_USE_ID, toolCallId);
        toolUseData.addProperty("name", toolName);
        toolUseData.add("input", inputObj);

        JsonObject toolUseBlock = new JsonObject();
        toolUseBlock.addProperty("kind", CONTENT_KIND_TOOL_USE);
        toolUseBlock.add("data", toolUseData);

        JsonObject resultContent = parseResultContent(resultStr);

        JsonObject jsonContentBlock = new JsonObject();
        jsonContentBlock.addProperty("kind", CONTENT_KIND_JSON);
        jsonContentBlock.add("data", resultContent);

        JsonArray resultContentArray = new JsonArray();
        resultContentArray.add(jsonContentBlock);

        JsonObject toolResultData = new JsonObject();
        toolResultData.addProperty(KEY_TOOL_USE_ID, toolCallId);
        toolResultData.add(KEY_CONTENT, resultContentArray);

        JsonObject toolResultBlock = new JsonObject();
        toolResultBlock.addProperty("kind", CONTENT_KIND_TOOL_RESULT);
        toolResultBlock.add("data", toolResultData);

        return new ToolPair(toolCallId, toolName, inputObj, toolUseBlock, toolResultBlock, resultContent);
    }

    @NotNull
    private static JsonObject parseResultContent(@NotNull String resultStr) {
        try {
            return JsonParser.parseString(resultStr).getAsJsonObject();
        } catch (Exception e) {
            JsonObject textItem = new JsonObject();
            textItem.addProperty("type", "text");
            textItem.addProperty("text", resultStr);
            JsonArray contentArray = new JsonArray();
            contentArray.add(textItem);
            JsonObject wrapper = new JsonObject();
            wrapper.add(KEY_CONTENT, contentArray);
            return wrapper;
        }
    }

    @NotNull
    private static JsonObject textContentBlock(@NotNull String text) {
        JsonObject block = new JsonObject();
        block.addProperty("kind", CONTENT_KIND_TEXT);
        block.addProperty("data", text);
        return block;
    }

    @NotNull
    private static JsonObject wrapMessage(
        @NotNull String kind,
        @NotNull String messageId,
        @NotNull JsonArray content) {

        JsonObject data = new JsonObject();
        data.addProperty(KEY_MESSAGE_ID, messageId);
        data.add(KEY_CONTENT, content);

        JsonObject envelope = new JsonObject();
        envelope.addProperty(KEY_VERSION, KIRO_FORMAT_VERSION);
        envelope.addProperty("kind", kind);
        envelope.add("data", data);
        return envelope;
    }

    @NotNull
    private static String partType(@NotNull JsonObject part) {
        return part.has("type") ? part.get("type").getAsString() : "";
    }

    @NotNull
    private static String partText(@NotNull JsonObject part) {
        return part.has(PART_TYPE_TEXT) ? part.get(PART_TYPE_TEXT).getAsString() : "";
    }

    private record ToolPair(
        @NotNull String toolCallId,
        @NotNull String toolName,
        @NotNull JsonObject input,
        @NotNull JsonObject toolUseBlock,
        @NotNull JsonObject toolResultBlock,
        @NotNull JsonObject resultContent
    ) {
    }
}
