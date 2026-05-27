package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.acp.protocol.NewSessionResponse;
import com.github.catatafishen.agentbridge.model.ContentBlock;
import com.github.catatafishen.agentbridge.model.Location;
import com.github.catatafishen.agentbridge.model.PlanEntry;
import com.github.catatafishen.agentbridge.model.SessionUpdate;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Parses raw ACP {@code session/update} JSON payloads into typed {@link SessionUpdate} objects.
 * <p>
 * Parsing is separated here to keep {@link AcpClient} focused on protocol lifecycle.
 * Agent-specific behaviour (tool-id resolution, argument extraction, sub-agent detection)
 * is injected via {@link Delegate} — typically implemented by the {@link AcpClient} subclass.
 */
class AcpMessageParser {

    private static final Logger LOG = Logger.getInstance(AcpMessageParser.class);

    private static final String KEY_SESSION_UPDATE = "sessionUpdate";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_STATUS = "status";
    private static final String KEY_RESULT = "result";
    private static final String KEY_TOOL_CALL_ID = "toolCallId";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_RAW_INPUT = "rawInput";
    private static final String KEY_THINKING = "thinking";
    private static final String KEY_TITLE = "title";
    private static final String KEY_CONFIG_OPTIONS = "configOptions";
    private static final String KEY_AVAILABLE_COMMANDS = "availableCommands";
    private static final String KEY_COMMANDS = "commands";
    private static final String KEY_INPUT = "input";
    private static final String KEY_ERROR = "error";
    private static final String KEY_IS_ERROR = "isError";

    /**
     * Callbacks into the owning client for the three points where agent-specific logic is needed.
     * Implemented by {@link AcpClient} and overridden by its concrete subclasses.
     */
    interface Delegate {
        /**
         * Map a raw protocol tool title to a display/resolved ID. Default: identity.
         */
        String resolveToolId(String protocolTitle);

        /**
         * Extract the {@code arguments} object from a {@code tool_call} params.
         * The standard ACP field is {@code arguments}; override for agent-specific field names.
         */
        @Nullable JsonObject parseToolCallArguments(@NotNull JsonObject params);

        /**
         * Resolve a canonical tool name from the raw protocol title and kind.
         * For MCP tools, returns the stripped MCP name (e.g., "read_file").
         * For known built-in tools, returns the tool name (e.g., "bash").
         * For unknown native tools, returns the kind (e.g., "read", "execute").
         */
        @Nullable String resolveAcpName(@NotNull String rawTitle, @Nullable String kind);

        /**
         * Detect sub-agent invocations and return the agent type, or {@code null} if this is not
         * a sub-agent call.
         */
        @Nullable String extractSubAgentType(@NotNull JsonObject params, @NotNull String resolvedTitle,
                                             @Nullable JsonObject argumentsObj);
    }

    private final Delegate delegate;
    private final Supplier<String> displayName;

    AcpMessageParser(Delegate delegate, Supplier<String> displayName) {
        this.delegate = delegate;
        this.displayName = displayName;
    }

    /**
     * Parse a normalised {@code session/update} params object into a typed {@link SessionUpdate}.
     * The caller is responsible for normalising the envelope first (see
     * {@link AcpClient#normalizeSessionUpdateParams}).
     */
    @Nullable SessionUpdate parse(JsonObject params) {
        String type = params.has(KEY_SESSION_UPDATE)
            ? params.get(KEY_SESSION_UPDATE).getAsString() : null;
        if (type == null) {
            LOG.warn(displayName.get() + ": session/update has no '" + KEY_SESSION_UPDATE + "' field after normalization");
            return null;
        }

        return switch (type) {
            case "agent_message_chunk" -> parseMessageChunk(params);
            case "agent_thought_chunk" -> parseThoughtChunk(params);
            case "user_message_chunk" -> parseUserMessageChunk(params);
            case "tool_call" -> parseToolCall(params);
            case "tool_call_update" -> parseToolCallUpdate(params);
            case "plan" -> parsePlan(params);
            case "turn_usage" -> parseTurnUsage(params);
            case "banner" -> parseBanner(params);
            // usage_update: proposed in ACP RFD (rfds/session-usage.md), not yet in the official schema.
            // Currently used by: OpenCode (fields: used, size, cost.amount, cost.currency).
            // 'used' is cumulative context tokens (not per-turn delta), so the toolbar value grows each turn.
            case "usage_update" -> parseUsageUpdate(params);
            // config_option_update: sent by ACP agents (e.g. Copilot CLI) when available config
            // options change — e.g. when switching to a model with a different set of
            // reasoning effort levels. The notification contains either a "configOptions" array
            // (full replacement) or a single option object (Copilot's wire format).
            case "config_option_update" -> parseConfigOptionUpdate(params);
            // session_info_update: sent by Copilot CLI when the agent has auto-generated or
            // updated the session title.
            case "session_info_update" -> parseSessionInfoUpdate(params);
            // current_mode_update: sent by Copilot CLI when the active mode changes mid-session
            // (e.g. plan → code). Treated as an AvailableModesChanged with null modes list
            // (modes themselves haven't changed, only the active selection).
            case "current_mode_update" -> parseCurrentModeUpdate(params);
            // available_commands_update: sent by ACP agents when slash commands change.
            // Contains an "availableCommands" array of {name, description, input?} objects.
            case "available_commands_update" -> parseAvailableCommandsUpdate(params);
            default -> {
                LOG.warn(displayName.get() + ": unknown session update type: '" + type + "'");
                yield null;
            }
        };
    }

    private SessionUpdate.AgentMessageChunk parseMessageChunk(JsonObject params) {
        return new SessionUpdate.AgentMessageChunk(parseContentBlocks(params));
    }

    private SessionUpdate.AgentThoughtChunk parseThoughtChunk(JsonObject params) {
        return new SessionUpdate.AgentThoughtChunk(parseContentBlocks(params));
    }

    private SessionUpdate.UserMessageChunk parseUserMessageChunk(JsonObject params) {
        return new SessionUpdate.UserMessageChunk(parseContentBlocks(params));
    }

    private SessionUpdate.ToolCall parseToolCall(JsonObject params) {
        String toolCallId = getStringOrEmpty(params, KEY_TOOL_CALL_ID);
        String title = getStringOrEmpty(params, KEY_TITLE);
        String resolvedTitle = delegate.resolveToolId(title);

        SessionUpdate.ToolKind kind = null;
        if (params.has("kind")) {
            kind = SessionUpdate.ToolKind.fromString(params.get("kind").getAsString());
        }

        String kindValue = kind != null ? kind.value() : null;
        String acpName = delegate.resolveAcpName(title, kindValue);

        JsonObject argumentsObj = delegate.parseToolCallArguments(params);
        String arguments = argumentsObj != null ? argumentsObj.toString() : null;

        List<Location> locations = null;
        if (params.has("locations")) {
            locations = new ArrayList<>();
            for (JsonElement locEl : params.getAsJsonArray("locations")) {
                JsonObject locObj = locEl.getAsJsonObject();
                String uri = getStringOrEmpty(locObj, "uri");
                if (uri.isEmpty()) uri = getStringOrEmpty(locObj, "path");
                locations.add(new Location(uri, null));
            }
        }

        // Sub-agent detection uses the ORIGINAL title (e.g. "Intellij-Explore") so that
        // client-specific name normalization in resolveToolId does not interfere.
        String agentType = delegate.extractSubAgentType(params, title, argumentsObj);
        String subAgentDesc = null;
        String subAgentPrompt = null;
        if (agentType != null && argumentsObj != null) {
            subAgentDesc = argumentsObj.has(KEY_DESCRIPTION) ? argumentsObj.get(KEY_DESCRIPTION).getAsString() : null;
            subAgentPrompt = argumentsObj.has("prompt") ? argumentsObj.get("prompt").getAsString() : null;
        }

        return new SessionUpdate.ToolCall(toolCallId, resolvedTitle, acpName, kind, arguments, locations, agentType, subAgentDesc, subAgentPrompt, null);
    }

    private SessionUpdate.ToolCallUpdate parseToolCallUpdate(JsonObject params) {
        String toolCallId = getStringOrEmpty(params, KEY_TOOL_CALL_ID);

        SessionUpdate.ToolCallStatus status = SessionUpdate.ToolCallStatus.COMPLETED;
        if (params.has(KEY_STATUS)) {
            status = SessionUpdate.ToolCallStatus.fromString(params.get(KEY_STATUS).getAsString());
        }

        SessionUpdate.ToolKind kind = null;
        if (params.has("kind")) {
            kind = SessionUpdate.ToolKind.fromString(params.get("kind").getAsString());
        }

        String error = params.has(KEY_ERROR) && params.get(KEY_ERROR).isJsonPrimitive()
            ? params.get(KEY_ERROR).getAsString() : null;
        if (error != null && error.isEmpty()) {
            error = null;
        }
        String description = params.has(KEY_DESCRIPTION) ? params.get(KEY_DESCRIPTION).getAsString() : null;
        String result = extractResultText(params);

        // Extract rawInput for tool correlation - this contains the actual tool arguments
        String arguments = null;
        if (params.has(KEY_RAW_INPUT) && params.get(KEY_RAW_INPUT).isJsonObject()) {
            arguments = params.get(KEY_RAW_INPUT).getAsJsonObject().toString();
        }

        // Some agents (notably Copilot CLI's built-in execute/bash) always report
        // status="completed" because the *tool call* completed even when the
        // underlying command failed. Promote to FAILED when the agent provides an
        // explicit failure signal so the UI chip reflects reality.
        // Signals (in priority order):
        //   1. top-level `error` string is non-empty
        //   2. top-level `isError: true` boolean (MCP-style tool result flag)
        //   3. any content block carries `isError: true`
        if (status == SessionUpdate.ToolCallStatus.COMPLETED
            && (error != null
            || (params.has(KEY_IS_ERROR) && params.get(KEY_IS_ERROR).isJsonPrimitive()
            && params.get(KEY_IS_ERROR).getAsBoolean())
            || hasErrorFlaggedContent(params))) {
            status = SessionUpdate.ToolCallStatus.FAILED;
        }

        return new SessionUpdate.ToolCallUpdate(toolCallId, status, result, error, description, false, null, arguments, kind);
    }

    private static boolean hasErrorFlaggedContent(JsonObject params) {
        if (!params.has(KEY_CONTENT) || !params.get(KEY_CONTENT).isJsonArray()) {
            return false;
        }
        for (JsonElement el : params.getAsJsonArray(KEY_CONTENT)) {
            if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has(KEY_IS_ERROR) && obj.get(KEY_IS_ERROR).isJsonPrimitive()
                    && obj.get(KEY_IS_ERROR).getAsBoolean()) {
                    return true;
                }
            }
        }
        return false;
    }

    private @Nullable String extractResultText(JsonObject params) {
        if (params.has(KEY_RESULT)) {
            return params.get(KEY_RESULT).isJsonPrimitive()
                ? params.get(KEY_RESULT).getAsString()
                : params.get(KEY_RESULT).toString();
        }
        if (params.has(KEY_CONTENT)) {
            List<ContentBlock> blocks = parseContentBlocks(params);
            StringBuilder sb = new StringBuilder();
            for (ContentBlock block : blocks) {
                if (block instanceof ContentBlock.Text(String text)) sb.append(text);
            }
            return sb.isEmpty() ? null : sb.toString();
        }
        return null;
    }

    private SessionUpdate.Plan parsePlan(JsonObject params) {
        List<PlanEntry> entries = new ArrayList<>();
        if (params.has("entries")) {
            for (JsonElement entryEl : params.getAsJsonArray("entries")) {
                JsonObject entryObj = entryEl.getAsJsonObject();
                String content = getStringOrEmpty(entryObj, KEY_CONTENT);
                String status = entryObj.has(KEY_STATUS) ? entryObj.get(KEY_STATUS).getAsString() : null;
                String priority = entryObj.has("priority") ? entryObj.get("priority").getAsString() : null;
                entries.add(new PlanEntry(content, status, priority));
            }
        }
        return new SessionUpdate.Plan(entries);
    }

    private SessionUpdate.TurnUsage parseTurnUsage(JsonObject params) {
        int inputTokens = params.has("inputTokens") ? params.get("inputTokens").getAsInt() : 0;
        int outputTokens = params.has("outputTokens") ? params.get("outputTokens").getAsInt() : 0;
        double costUsd = params.has("costUsd") ? params.get("costUsd").getAsDouble() : 0.0;
        return new SessionUpdate.TurnUsage(inputTokens, outputTokens, costUsd);
    }

    private SessionUpdate.Banner parseBanner(JsonObject params) {
        String message = getStringOrEmpty(params, "message");
        String levelStr = params.has("level") ? params.get("level").getAsString() : "warning";
        String clearOnStr = params.has("clearOn") ? params.get("clearOn").getAsString() : null;
        return new SessionUpdate.Banner(
            message,
            SessionUpdate.BannerLevel.fromString(levelStr),
            SessionUpdate.ClearOn.fromString(clearOnStr)
        );
    }

    private SessionUpdate.SessionInfoChanged parseSessionInfoUpdate(JsonObject params) {
        String title = params.has(KEY_TITLE) && params.get(KEY_TITLE).isJsonPrimitive()
            ? params.get(KEY_TITLE).getAsString() : null;
        return new SessionUpdate.SessionInfoChanged(title);
    }

    private SessionUpdate.AvailableModesChanged parseCurrentModeUpdate(JsonObject params) {
        String modeSlug = params.has("slug") ? params.get("slug").getAsString() : null;
        // modes list is null — only the active selection has changed
        return new SessionUpdate.AvailableModesChanged(null, modeSlug);
    }

    private SessionUpdate.TurnUsage parseUsageUpdate(JsonObject params) {
        int used = params.has("used") ? params.get("used").getAsInt() : 0;
        Double cost = null;
        if (params.has("cost") && params.get("cost").isJsonObject()) {
            JsonObject costObj = params.getAsJsonObject("cost");
            JsonElement amountEl = costObj.get("amount");
            if (amountEl != null && amountEl.isJsonPrimitive()) {
                cost = amountEl.getAsDouble();
            }
        }
        return new SessionUpdate.TurnUsage(used, 0, cost);
    }

    private List<ContentBlock> parseContentBlocks(JsonObject params) {
        if (params.has(KEY_CONTENT) && params.get(KEY_CONTENT).isJsonArray()) {
            return parseContentArray(params.getAsJsonArray(KEY_CONTENT));
        }
        if (params.has(KEY_CONTENT) && params.get(KEY_CONTENT).isJsonObject()) {
            // Single content object: {"type":"text","text":"..."} — treat as one-element array
            JsonArray arr = new JsonArray();
            arr.add(params.get(KEY_CONTENT));
            return parseContentArray(arr);
        }
        if (params.has(KEY_CONTENT) && params.get(KEY_CONTENT).isJsonPrimitive()) {
            return List.of(new ContentBlock.Text(params.get(KEY_CONTENT).getAsString()));
        }
        if (params.has("text")) {
            return List.of(new ContentBlock.Text(params.get("text").getAsString()));
        }
        return List.of();
    }

    private List<ContentBlock> parseContentArray(JsonArray array) {
        List<ContentBlock> blocks = new ArrayList<>();
        for (JsonElement el : array) {
            if (el.isJsonObject()) {
                blocks.add(parseContentBlock(el.getAsJsonObject()));
            } else if (el.isJsonPrimitive()) {
                blocks.add(new ContentBlock.Text(el.getAsString()));
            }
        }
        return blocks;
    }

    @SuppressWarnings("java:S125") // Line below is a spec documentation comment, not commented-out code
    private ContentBlock parseContentBlock(JsonObject block) {
        String blockType = block.has("type") ? block.get("type").getAsString() : "text";
        if ("text".equals(blockType) && block.has("text")) {
            return new ContentBlock.Text(block.get("text").getAsString());
        } else if (KEY_THINKING.equals(blockType) && block.has(KEY_THINKING)) {
            return new ContentBlock.Thinking(block.get(KEY_THINKING).getAsString());
        } else if (KEY_CONTENT.equals(blockType) && block.has(KEY_CONTENT)) {
            // Spec: tool_call_update content items wrap blocks as {type:"content", content:{type,text}}
            JsonElement inner = block.get(KEY_CONTENT);
            if (inner.isJsonObject() && inner.getAsJsonObject().has("text")) {
                return new ContentBlock.Text(inner.getAsJsonObject().get("text").getAsString());
            }
        }
        return new ContentBlock.Text("");
    }

    static String getStringOrEmpty(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive()
            ? obj.get(key).getAsString() : "";
    }

    @Nullable
    private static String getStringOrNull(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) return null;
        return obj.get(key).getAsString();
    }

    /**
     * Parses an {@code available_commands_update} session/update notification.
     * The ACP spec defines the payload as: {@code {"availableCommands": [{name, description, input?}, ...]}}.
     * Also handles a flat "commands" array (used by some agents like Kiro).
     */
    private SessionUpdate.AvailableCommandsChanged parseAvailableCommandsUpdate(JsonObject params) {
        JsonArray arr = null;
        if (params.has(KEY_AVAILABLE_COMMANDS) && params.get(KEY_AVAILABLE_COMMANDS).isJsonArray()) {
            arr = params.getAsJsonArray(KEY_AVAILABLE_COMMANDS);
        } else if (params.has(KEY_COMMANDS) && params.get(KEY_COMMANDS).isJsonArray()) {
            arr = params.getAsJsonArray(KEY_COMMANDS);
        }
        List<NewSessionResponse.AvailableCommand> commands = parseCommandArray(arr);
        return new SessionUpdate.AvailableCommandsChanged(commands);
    }

    private List<NewSessionResponse.AvailableCommand> parseCommandArray(@Nullable JsonArray arr) {
        if (arr == null) return List.of();
        List<NewSessionResponse.AvailableCommand> commands = new ArrayList<>();
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            NewSessionResponse.AvailableCommand cmd = parseSingleCommand(e.getAsJsonObject());
            if (cmd != null) commands.add(cmd);
        }
        return commands;
    }

    @Nullable
    private NewSessionResponse.AvailableCommand parseSingleCommand(JsonObject cmd) {
        String name = getStringOrNull(cmd, "name");
        if (name == null || name.isBlank()) return null;
        String desc = getStringOrNull(cmd, KEY_DESCRIPTION);
        NewSessionResponse.AvailableCommandInput input = null;
        if (cmd.has(KEY_INPUT) && cmd.get(KEY_INPUT).isJsonObject()) {
            JsonObject inputObj = cmd.getAsJsonObject(KEY_INPUT);
            input = new NewSessionResponse.AvailableCommandInput(
                getStringOrNull(inputObj, "type"),
                getStringOrNull(inputObj, "placeholder")
            );
        }
        return new NewSessionResponse.AvailableCommand(name, desc != null ? desc : "", input);
    }

    /**
     * Parses a {@code config_option_update} session/update notification.
     * Handles two formats:
     * <ul>
     *   <li>Full replacement: {@code {"configOptions": [{id, label/name, values/options, selectedValueId/currentValue}, ...]}}
     *   <li>Single option: the notification object itself is the option descriptor
     * </ul>
     * The format is not officially documented; both shapes are handled defensively.
     */
    private SessionUpdate.ConfigOptionsChanged parseConfigOptionUpdate(JsonObject params) {
        List<NewSessionResponse.SessionConfigOption> options = new ArrayList<>();
        if (params.has(KEY_CONFIG_OPTIONS) && params.get(KEY_CONFIG_OPTIONS).isJsonArray()) {
            for (JsonElement e : params.getAsJsonArray(KEY_CONFIG_OPTIONS)) {
                if (e.isJsonObject()) {
                    options.add(parseConfigOption(e.getAsJsonObject()));
                }
            }
        } else if (params.has("id")
            && params.get("id").isJsonPrimitive()
            && !params.get("id").getAsString().isBlank()) {
            // Single-option update: the notification IS the option descriptor.
            NewSessionResponse.SessionConfigOption opt = parseConfigOption(params);
            options.add(opt);
        } else {
            LOG.warn(displayName.get() + ": config_option_update has unrecognised structure: " + params);
        }
        LOG.debug(displayName.get() + ": config_option_update — " + options.size() + " option(s)");
        return new SessionUpdate.ConfigOptionsChanged(options);
    }

    private static NewSessionResponse.SessionConfigOption parseConfigOption(JsonObject obj) {
        String id = getStringOrNull(obj, "id");
        String label = resolveLabel(obj, id);
        String selectedValueId = getStringOrNull(obj, "selectedValueId");
        if (selectedValueId == null) selectedValueId = getStringOrNull(obj, "currentValue");

        List<NewSessionResponse.SessionConfigOptionValue> values = parseConfigOptionValues(obj);
        return new NewSessionResponse.SessionConfigOption(id, label, null, values, selectedValueId);
    }

    private static String resolveLabel(JsonObject obj, String id) {
        String label = getStringOrNull(obj, "label");
        if (label == null) label = getStringOrNull(obj, "name");
        if (label == null) label = id != null ? id : "";
        return label;
    }

    private static List<NewSessionResponse.SessionConfigOptionValue> parseConfigOptionValues(JsonObject obj) {
        List<NewSessionResponse.SessionConfigOptionValue> values = new ArrayList<>();
        JsonElement valuesEl;
        if (obj.has("values")) {
            valuesEl = obj.get("values");
        } else if (obj.has("options")) {
            valuesEl = obj.get("options");
        } else {
            valuesEl = null;
        }
        if (valuesEl != null && valuesEl.isJsonArray()) {
            for (JsonElement e : valuesEl.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                NewSessionResponse.SessionConfigOptionValue parsed = parseSingleOptionValue(e.getAsJsonObject());
                if (parsed != null) values.add(parsed);
            }
        }
        return values;
    }

    @Nullable
    private static NewSessionResponse.SessionConfigOptionValue parseSingleOptionValue(JsonObject vo) {
        String valueId = getStringOrNull(vo, "id");
        if (valueId == null) valueId = getStringOrNull(vo, "value");
        if (valueId == null) return null;
        String valueLabel = getStringOrNull(vo, "label");
        if (valueLabel == null) valueLabel = getStringOrNull(vo, "name");
        if (valueLabel == null) valueLabel = valueId;
        return new NewSessionResponse.SessionConfigOptionValue(valueId, valueLabel);
    }
}
