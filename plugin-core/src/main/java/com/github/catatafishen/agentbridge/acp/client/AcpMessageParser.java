package com.github.catatafishen.agentbridge.acp.client;

import com.github.catatafishen.agentbridge.acp.model.ContentBlock;
import com.github.catatafishen.agentbridge.acp.model.Location;
import com.github.catatafishen.agentbridge.acp.model.PlanEntry;
import com.github.catatafishen.agentbridge.acp.model.SessionUpdate;
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
            // config_option_update: sent by Copilot CLI when agent configuration changes (e.g. model,
            // tool filters). Fields are undocumented; we acknowledge and ignore for now.
            case "config_option_update" -> {
                LOG.debug(displayName.get() + ": received config_option_update (not yet handled)");
                yield null;
            }
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
        String title = getStringOrEmpty(params, "title");
        String resolvedTitle = delegate.resolveToolId(title);

        SessionUpdate.ToolKind kind = null;
        if (params.has("kind")) {
            kind = SessionUpdate.ToolKind.fromString(params.get("kind").getAsString());
        }

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

        // Sub-agent detection: check explicit agentType fields, then fall back to client-specific logic
        String agentType = delegate.extractSubAgentType(params, resolvedTitle, argumentsObj);
        String subAgentDesc = null;
        String subAgentPrompt = null;
        if (agentType != null && argumentsObj != null) {
            subAgentDesc = argumentsObj.has(KEY_DESCRIPTION) ? argumentsObj.get(KEY_DESCRIPTION).getAsString() : null;
            subAgentPrompt = argumentsObj.has("prompt") ? argumentsObj.get("prompt").getAsString() : null;
        }

        return new SessionUpdate.ToolCall(toolCallId, resolvedTitle, kind, arguments, locations, agentType, subAgentDesc, subAgentPrompt, null);
    }

    private SessionUpdate.ToolCallUpdate parseToolCallUpdate(JsonObject params) {
        String toolCallId = getStringOrEmpty(params, KEY_TOOL_CALL_ID);

        SessionUpdate.ToolCallStatus status = SessionUpdate.ToolCallStatus.COMPLETED;
        if (params.has(KEY_STATUS)) {
            status = SessionUpdate.ToolCallStatus.fromString(params.get(KEY_STATUS).getAsString());
        }

        String error = params.has("error") ? params.get("error").getAsString() : null;
        String description = params.has(KEY_DESCRIPTION) ? params.get(KEY_DESCRIPTION).getAsString() : null;
        String result = extractResultText(params);

        // Extract rawInput for tool correlation - this contains the actual tool arguments
        String arguments = null;
        if (params.has(KEY_RAW_INPUT) && params.get(KEY_RAW_INPUT).isJsonObject()) {
            arguments = params.get(KEY_RAW_INPUT).getAsJsonObject().toString();
        }

        return new SessionUpdate.ToolCallUpdate(toolCallId, status, result, error, description, false, null, arguments);
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
}
