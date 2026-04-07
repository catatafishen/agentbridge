package com.github.catatafishen.agentbridge.session.exporters;

import com.github.catatafishen.agentbridge.ui.EntryData;
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

    private static final String STATE_RESULT = "result";

    /**
     * Maximum characters allowed per individual tool result in the exported history.
     * Large file reads / search results are truncated to this limit to prevent the
     * context window from being filled by a single historical tool call.
     */
    private static final int MAX_TOOL_RESULT_CHARS = 4_000;

    /**
     * Maximum total character budget for the exported conversation history.
     * Claude's 200K-token context window is approximately 800K chars; we reserve a
     * conservative 400K chars for history, leaving room for system prompt, the new
     * user message, and the model's response.
     * <p>
     * When the history exceeds this budget (after per-result truncation), the oldest
     * complete user-turn blocks are dropped from the front until it fits.
     */
    private static final int MAX_TOTAL_HISTORY_CHARS = 400_000;

    private KiroClientExporter() {
    }

    /**
     * Returns the default Kiro CLI sessions directory ({@code ~/.kiro/sessions/cli/}).
     */
    @NotNull
    public static Path defaultSessionsDir() {
        return Path.of(System.getProperty("user.home"), ".kiro", "sessions", "cli");
    }

    @Nullable
    public static String exportSession(
        @NotNull List<EntryData> entries,
        @Nullable String cwd,
        @NotNull Path sessionsDir) {

        if (entries.isEmpty()) {
            LOG.info("No messages to export to Kiro");
            return null;
        }

        try {
            Files.createDirectories(sessionsDir);

            String sessionId = UUID.randomUUID().toString();

            writeSessionJson(sessionId, cwd, sessionsDir);
            writeMessagesJsonl(entries, sessionId, sessionsDir);

            LOG.info("Exported v2 session to Kiro CLI: " + sessionId
                + " (" + entries.size() + " entries)");
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
        @NotNull List<EntryData> entries,
        @NotNull String sessionId,
        @NotNull Path sessionsDir) throws IOException {

        List<JsonObject> kiroMessages = toKiroMessages(entries);

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
    static List<JsonObject> toKiroMessages(@NotNull List<EntryData> entries) {
        List<JsonObject> result = new ArrayList<>();
        List<JsonObject> assistantBlocks = new ArrayList<>();
        List<ToolPair> pendingTools = new ArrayList<>();
        boolean seenToolUse = false;

        for (EntryData entry : entries) {
            if (entry instanceof EntryData.Prompt prompt) {
                // Flush pending assistant blocks
                if (!assistantBlocks.isEmpty()) {
                    emitAssistantTurn(assistantBlocks, pendingTools, result);
                    assistantBlocks = new ArrayList<>();
                    pendingTools = new ArrayList<>();
                    seenToolUse = false;
                }
                // Add user message
                String text = prompt.getText();
                if (!text.isEmpty()) {
                    JsonArray content = new JsonArray();
                    content.add(textContentBlock(text));
                    result.add(wrapMessage(KIND_PROMPT, UUID.randomUUID().toString(), content));
                }

            } else if (entry instanceof EntryData.Text text) {
                String content = text.getRaw();
                if (content.isEmpty()) continue;

                if (seenToolUse) {
                    // Text after tool use = turn boundary
                    emitAssistantTurn(assistantBlocks, pendingTools, result);
                    assistantBlocks = new ArrayList<>();
                    pendingTools = new ArrayList<>();
                    seenToolUse = false;
                }
                assistantBlocks.add(textContentBlock(content));

            } else if (entry instanceof EntryData.ToolCall toolCall) {
                String toolCallId = UUID.randomUUID().toString();
                String toolName = ExportUtils.sanitizeToolName(toolCall.getTitle());
                String argsStr = toolCall.getArguments() != null ? toolCall.getArguments() : "{}";
                String resultStr = toolCall.getResult() != null ? toolCall.getResult() : "";

                // Truncate large tool results — raw file reads and search dumps can be hundreds
                // of KB each. In exported history the agent no longer needs the raw content
                // (it already acted on it); a summary stub preserves conversation structure
                // without inflating the context window past Anthropic's limit.
                if (resultStr.length() > MAX_TOOL_RESULT_CHARS) {
                    resultStr = resultStr.substring(0, MAX_TOOL_RESULT_CHARS)
                        + "\n[... truncated for context window ...]";
                }

                JsonObject inputObj;
                try {
                    inputObj = JsonParser.parseString(argsStr).getAsJsonObject();
                } catch (Exception e) {
                    inputObj = new JsonObject();
                    inputObj.addProperty("_raw", argsStr);
                }

                // Build tool_use block
                JsonObject toolUseData = new JsonObject();
                toolUseData.addProperty(KEY_TOOL_USE_ID, toolCallId);
                toolUseData.addProperty("name", toolName);
                toolUseData.add("input", inputObj);

                JsonObject toolUseBlock = new JsonObject();
                toolUseBlock.addProperty("kind", CONTENT_KIND_TOOL_USE);
                toolUseBlock.add("data", toolUseData);

                // Build tool_result block
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

                assistantBlocks.add(toolUseBlock);
                pendingTools.add(new ToolPair(toolCallId, toolName, inputObj,
                    toolUseBlock, toolResultBlock, resultContent));
                seenToolUse = true;

            }
            // Skip Thinking, SubAgent, Status, TurnStats, ContextFiles, SessionSeparator, Nudge.
            // Note: Thinking is intentionally excluded — Anthropic rejects conversation history
            // containing thinking blocks unless extended thinking is explicitly enabled in the
            // session. Kiro does not enable extended thinking when resuming from exported history,
            // so including thinking blocks causes an immediate panic on session/prompt.
            // Nudge entries represent mid-turn user guidance injected into tool results; they are
            // not standalone user messages and must not be included in exported history.
        }

        // Flush remaining
        if (!assistantBlocks.isEmpty()) {
            emitAssistantTurn(assistantBlocks, pendingTools, result);
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
        mergeConsecutivePrompts(result);

        // Kiro may also reject consecutive AssistantMessages. Merge them by concatenating content.
        mergeConsecutiveAssistantMessages(result);

        // If history ends with ToolResults, a new session/prompt would produce consecutive user-role
        // messages (ToolResults + new Prompt), which Anthropic rejects as invalid conversation history.
        // Insert a placeholder AssistantMessage to close the tool round cleanly.
        if (!result.isEmpty()
            && KIND_TOOL_RESULTS.equals(result.getLast().get("kind").getAsString())) {
            LOG.warn("Kiro export: history ends with ToolResults; inserting placeholder AssistantMessage");
            JsonArray content = new JsonArray();
            content.add(textContentBlock("(Tool calls completed)"));
            result.add(wrapMessage(KIND_ASSISTANT_MESSAGE, UUID.randomUUID().toString(), content));
        }

        // Drop oldest user-turn blocks if the total history exceeds the context-window budget.
        // Each tool result was already truncated per-item above; this handles sessions that have
        // accumulated too many turns overall. Must run last so structural fixes are already applied.
        trimToSizeBudget(result);

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

    /**
     * Merges consecutive {@code AssistantMessage} entries in-place by appending the later
     * message's content into the earlier one.
     *
     * <p>Two consecutive AssistantMessages can occur when two v2 assistant turns appear
     * back-to-back (no user Prompt between them) and the first turn's last part was plain text
     * while the second turn opens with tool calls. In Kiro's format this is invalid —
     * AssistantMessages must alternate with Prompts (and optional ToolResults in between).</p>
     */
    private static void mergeConsecutiveAssistantMessages(@NotNull List<JsonObject> messages) {
        int i = 0;
        while (i < messages.size() - 1) {
            JsonObject current = messages.get(i);
            JsonObject next = messages.get(i + 1);
            if (KIND_ASSISTANT_MESSAGE.equals(current.get("kind").getAsString())
                && KIND_ASSISTANT_MESSAGE.equals(next.get("kind").getAsString())) {
                LOG.warn("Kiro export: merging consecutive AssistantMessages at index " + i);
                JsonArray mergedContent = current.getAsJsonObject("data").getAsJsonArray(KEY_CONTENT).deepCopy();
                next.getAsJsonObject("data").getAsJsonArray(KEY_CONTENT)
                    .forEach(mergedContent::add);
                current.getAsJsonObject("data").add(KEY_CONTENT, mergedContent);
                messages.remove(i + 1);
                // don't advance i — the merged message may still be followed by another AssistantMessage
            } else {
                i++;
            }
        }
    }

    /**
     * Drops the oldest complete user-turn blocks from the front of {@code messages}
     * until the total serialized character count is within {@link #MAX_TOTAL_HISTORY_CHARS}.
     *
     * <p>A "user-turn block" is everything from one {@code Prompt} up to (but not including)
     * the next {@code Prompt}. Dropping the block preserves the structural validity of the
     * remaining history because each surviving block still starts with a {@code Prompt} and
     * has matching {@code AssistantMessage}/{@code ToolResults} pairs.</p>
     *
     * <p>If fewer than two {@code Prompt} messages remain, no further trimming is performed.
     * This guarantees the history always starts with at least one user message.</p>
     */
    private static void trimToSizeBudget(@NotNull List<JsonObject> messages) {
        int totalChars = 0;
        for (JsonObject m : messages) {
            totalChars += GSON.toJson(m).length();
        }
        if (totalChars <= MAX_TOTAL_HISTORY_CHARS) return;

        while (totalChars > MAX_TOTAL_HISTORY_CHARS) {
            // Find the index of the second Prompt so we can drop everything before it.
            int secondPromptIdx = -1;
            int promptsSeen = 0;
            for (int i = 0; i < messages.size(); i++) {
                if (KIND_PROMPT.equals(messages.get(i).get("kind").getAsString())) {
                    promptsSeen++;
                    if (promptsSeen == 2) {
                        secondPromptIdx = i;
                        break;
                    }
                }
            }

            if (secondPromptIdx == -1) {
                LOG.warn("Kiro export: history exceeds budget (" + totalChars
                    + " chars) but only one Prompt remains — cannot trim further");
                break;
            }

            int charsDropped = 0;
            for (int i = 0; i < secondPromptIdx; i++) {
                charsDropped += GSON.toJson(messages.get(i)).length();
            }
            LOG.warn("Kiro export: trimming " + secondPromptIdx
                + " oldest messages (" + charsDropped + " chars) — history was "
                + totalChars + " chars, budget is " + MAX_TOTAL_HISTORY_CHARS);
            messages.subList(0, secondPromptIdx).clear();
            totalChars -= charsDropped;
        }
    }

    private static void emitAssistantTurn(
        @NotNull List<JsonObject> assistantBlocks,
        @NotNull List<ToolPair> pendingTools,
        @NotNull List<JsonObject> out) {

        if (assistantBlocks.isEmpty()) return;

        JsonArray content = new JsonArray();
        assistantBlocks.forEach(content::add);
        out.add(wrapMessage(KIND_ASSISTANT_MESSAGE, UUID.randomUUID().toString(), content));

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
