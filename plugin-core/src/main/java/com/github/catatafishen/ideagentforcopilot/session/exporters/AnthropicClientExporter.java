package com.github.catatafishen.ideagentforcopilot.session.exporters;

import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.ArrayList;
import java.util.List;

public final class AnthropicClientExporter {

    private static final Logger LOG = Logger.getInstance(AnthropicClientExporter.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int DEFAULT_MAX_TOKEN_ESTIMATE = 20_000;

    private static final String TYPE_TEXT = "text";
    private static final String TYPE_TOOL_USE = "tool_use";
    private static final String TYPE_TOOL_RESULT = "tool_result";
    private static final String TYPE_TOOL_INVOCATION = "tool-invocation";
    private static final String FIELD_TOOL_INVOCATION = "toolInvocation";
    private static final String STATE_RESULT = "result";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private AnthropicClientExporter() {
    }

    public static void exportToFile(
        @NotNull List<SessionMessage> messages,
        @NotNull Path targetPath) throws IOException {
        exportToFile(messages, targetPath, DEFAULT_MAX_TOKEN_ESTIMATE);
    }

    public static void exportToFile(
        @NotNull List<SessionMessage> messages,
        @NotNull Path targetPath,
        int maxTokenEstimate) throws IOException {

        List<SessionMessage> budgeted = applyTokenBudget(messages, maxTokenEstimate);
        List<AnthropicMessage> anthropicMessages = toAnthropicMessages(budgeted);
        anthropicMessages = ensureUserFirst(anthropicMessages);

        StringBuilder sb = new StringBuilder();
        for (AnthropicMessage msg : anthropicMessages) {
            sb.append(msg.toJsonLine()).append('\n');
        }

        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(targetPath, sb.toString(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    @NotNull
    static List<SessionMessage> applyTokenBudget(
        @NotNull List<SessionMessage> messages,
        int maxTokenEstimate) {

        if (messages.isEmpty()) return messages;

        int budget = maxTokenEstimate;
        boolean[] keep = new boolean[messages.size()];

        for (int i = messages.size() - 1; i >= 0; i--) {
            if (budget <= 0) break;
            keep[i] = true;
            budget -= estimateTokens(messages.get(i));
        }

        List<SessionMessage> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (keep[i]) result.add(messages.get(i));
        }
        return result;
    }

    private static int estimateTokens(@NotNull SessionMessage msg) {
        int total = 0;
        for (JsonObject part : msg.parts) {
            String type = partType(part);
            if (TYPE_TEXT.equals(type) || "reasoning".equals(type)) {
                total += partText(part).length() / 4;
            } else if (TYPE_TOOL_INVOCATION.equals(type) && part.has(FIELD_TOOL_INVOCATION)) {
                JsonObject inv = part.getAsJsonObject(FIELD_TOOL_INVOCATION);
                if (inv.has(STATE_RESULT)) {
                    total += inv.get(STATE_RESULT).getAsString().length() / 4;
                }
                if (inv.has("args")) {
                    total += inv.get("args").getAsString().length() / 4;
                }
            }
        }
        return Math.max(total, 1);
    }

    /**
     * Ensures the conversation starts with a user message, as required by the Anthropic API.
     * If the first message is an assistant message (e.g. after token budget trimming cut the
     * initial user prompt), prepends a synthetic user message with context.
     */
    @NotNull
    static List<AnthropicMessage> ensureUserFirst(@NotNull List<AnthropicMessage> messages) {
        if (messages.isEmpty()) return messages;
        if (ROLE_USER.equals(messages.getFirst().role)) return messages;

        JsonObject block = new JsonObject();
        block.addProperty("type", TYPE_TEXT);
        block.addProperty(TYPE_TEXT, "(Previous conversation context restored — earlier messages were trimmed)");

        List<AnthropicMessage> fixed = new ArrayList<>(messages.size() + 1);
        fixed.add(new AnthropicMessage(ROLE_USER, List.of(block)));
        fixed.addAll(messages);
        return fixed;
    }

    /**
     * Converts v2 {@link SessionMessage} list into Anthropic API message format.
     *
     * <p>A single v2 assistant message may contain interleaved tool invocations and text
     * from multiple sequential turns (e.g., tool A → result → text → tool B → result → text).
     * The Anthropic API requires each tool-use turn to be a separate assistant message followed
     * by a user message with tool results.  This method splits at turn boundaries: a text block
     * following tool_use blocks signals a new turn.</p>
     */
    static List<AnthropicMessage> toAnthropicMessages(@NotNull List<SessionMessage> messages) {
        List<AnthropicMessage> raw = new ArrayList<>();

        for (SessionMessage msg : messages) {
            if ("separator".equals(msg.role)) continue;

            if (ROLE_USER.equals(msg.role)) {
                convertUserMessage(msg, raw);
            } else if (ROLE_ASSISTANT.equals(msg.role)) {
                convertAssistantMessage(msg, raw);
            }
        }

        return mergeConsecutiveSameRole(raw);
    }

    private static void convertUserMessage(@NotNull SessionMessage msg, @NotNull List<AnthropicMessage> out) {
        List<JsonObject> blocks = new ArrayList<>();
        for (JsonObject part : msg.parts) {
            if (TYPE_TEXT.equals(partType(part))) {
                String text = partText(part);
                if (!text.isEmpty()) {
                    blocks.add(textBlock(text));
                }
            }
        }
        if (!blocks.isEmpty()) {
            out.add(new AnthropicMessage(ROLE_USER, blocks, msg.createdAt));
        }
    }

    /**
     * Converts a v2 assistant message into one or more Anthropic turn pairs.
     *
     * <p>Sequential tool use within a single v2 message (tool → text → tool → text) is
     * split into separate assistant/user turn pairs.  A text block following tool_use blocks
     * marks a turn boundary.</p>
     */
    private static void convertAssistantMessage(@NotNull SessionMessage msg, @NotNull List<AnthropicMessage> out) {
        List<JsonObject> assistantBlocks = new ArrayList<>();
        List<JsonObject> toolResults = new ArrayList<>();
        boolean seenToolUse = false;

        for (JsonObject part : msg.parts) {
            String type = partType(part);

            if (TYPE_TEXT.equals(type)) {
                String text = partText(part);
                if (text.isEmpty()) continue;

                if (seenToolUse) {
                    emitTurn(assistantBlocks, toolResults, msg.createdAt, out);
                    assistantBlocks = new ArrayList<>();
                    toolResults = new ArrayList<>();
                    seenToolUse = false;
                }
                assistantBlocks.add(textBlock(text));

            } else if (TYPE_TOOL_INVOCATION.equals(type) && part.has(FIELD_TOOL_INVOCATION)) {
                ToolBlocks blocks = buildToolBlocks(part.getAsJsonObject(FIELD_TOOL_INVOCATION));
                if (blocks != null) {
                    assistantBlocks.add(blocks.toolUse);
                    toolResults.add(blocks.toolResult);
                    seenToolUse = true;
                }
            }
        }

        emitTurn(assistantBlocks, toolResults, msg.createdAt, out);
    }

    private static void emitTurn(
        @NotNull List<JsonObject> assistantBlocks,
        @NotNull List<JsonObject> toolResults,
        long createdAt,
        @NotNull List<AnthropicMessage> out) {

        if (assistantBlocks.isEmpty()) return;

        out.add(new AnthropicMessage(ROLE_ASSISTANT, assistantBlocks, createdAt));
        if (!toolResults.isEmpty()) {
            out.add(new AnthropicMessage(ROLE_USER, toolResults, createdAt));
        }
    }

    /**
     * Builds a tool_use + tool_result block pair from a tool invocation.
     *
     * @return the paired blocks, or {@code null} if the invocation is not in "result" state
     */
    @Nullable
    private static ToolBlocks buildToolBlocks(@NotNull JsonObject inv) {
        String state = inv.has("state") ? inv.get("state").getAsString() : "call";
        if (!STATE_RESULT.equals(state)) return null;

        String toolCallId = inv.has("toolCallId") ? inv.get("toolCallId").getAsString() : "";
        String toolName = inv.has("toolName") ? inv.get("toolName").getAsString() : "unknown";
        String argsStr = inv.has("args") ? inv.get("args").getAsString() : "{}";
        String resultStr = inv.has(STATE_RESULT) ? inv.get(STATE_RESULT).getAsString() : "";

        JsonObject inputObj;
        try {
            inputObj = JsonParser.parseString(argsStr).getAsJsonObject();
        } catch (Exception e) {
            LOG.warn("Could not parse tool args as JSON object, wrapping as string: " + argsStr);
            inputObj = new JsonObject();
            inputObj.addProperty("_raw", argsStr);
        }

        JsonObject toolUseBlock = new JsonObject();
        toolUseBlock.addProperty("type", TYPE_TOOL_USE);
        toolUseBlock.addProperty("id", toolCallId);
        toolUseBlock.addProperty("name", toolName);
        toolUseBlock.add("input", inputObj);

        JsonObject toolResultBlock = new JsonObject();
        toolResultBlock.addProperty("type", TYPE_TOOL_RESULT);
        toolResultBlock.addProperty("tool_use_id", toolCallId);
        toolResultBlock.addProperty("content", resultStr);

        return new ToolBlocks(toolUseBlock, toolResultBlock);
    }

    // ------------------------------------------------------------------
    // Part accessors
    // ------------------------------------------------------------------

    @NotNull
    private static String partType(@NotNull JsonObject part) {
        return part.has("type") ? part.get("type").getAsString() : "";
    }

    @NotNull
    private static String partText(@NotNull JsonObject part) {
        return part.has(TYPE_TEXT) ? part.get(TYPE_TEXT).getAsString() : "";
    }

    @NotNull
    private static JsonObject textBlock(@NotNull String text) {
        JsonObject block = new JsonObject();
        block.addProperty("type", TYPE_TEXT);
        block.addProperty(TYPE_TEXT, text);
        return block;
    }

    // ------------------------------------------------------------------
    // Merge & helpers
    // ------------------------------------------------------------------

    @NotNull
    private static List<AnthropicMessage> mergeConsecutiveSameRole(@NotNull List<AnthropicMessage> messages) {
        if (messages.size() <= 1) return messages;

        List<AnthropicMessage> merged = new ArrayList<>();
        for (AnthropicMessage msg : messages) {
            if (!merged.isEmpty() && merged.getLast().role.equals(msg.role)) {
                AnthropicMessage prev = merged.removeLast();
                List<JsonObject> combinedBlocks = new ArrayList<>(prev.contentBlocks);
                combinedBlocks.addAll(msg.contentBlocks);
                merged.add(new AnthropicMessage(prev.role, combinedBlocks, prev.createdAt));
            } else {
                merged.add(msg);
            }
        }
        return merged;
    }

    // ------------------------------------------------------------------
    // Inner types
    // ------------------------------------------------------------------

    private record ToolBlocks(@NotNull JsonObject toolUse, @NotNull JsonObject toolResult) {
    }

    static final class AnthropicMessage {
        final String role;
        final List<JsonObject> contentBlocks;
        /**
         * Epoch millis when the original SessionMessage was created (0 if unknown).
         */
        final long createdAt;

        AnthropicMessage(@NotNull String role, @NotNull List<JsonObject> contentBlocks, long createdAt) {
            this.role = role;
            this.contentBlocks = List.copyOf(contentBlocks);
            this.createdAt = createdAt;
        }

        AnthropicMessage(@NotNull String role, @NotNull List<JsonObject> contentBlocks) {
            this(role, contentBlocks, 0);
        }

        @NotNull
        String toJsonLine() {
            JsonObject obj = new JsonObject();
            obj.addProperty("role", role);
            var contentArray = new com.google.gson.JsonArray();
            contentBlocks.forEach(contentArray::add);
            obj.add("content", contentArray);
            return GSON.toJson(obj);
        }
    }
}
