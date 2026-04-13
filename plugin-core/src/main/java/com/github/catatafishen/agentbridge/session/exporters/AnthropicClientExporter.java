package com.github.catatafishen.agentbridge.session.exporters;

import com.github.catatafishen.agentbridge.ui.EntryData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AnthropicClientExporter {

    private static final Logger LOG = Logger.getInstance(AnthropicClientExporter.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final String TYPE_TEXT = "text";
    private static final String TYPE_TOOL_USE = "tool_use";
    private static final String TYPE_TOOL_RESULT = "tool_result";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private AnthropicClientExporter() {
    }

    public static void exportToFile(
        @NotNull List<EntryData> entries,
        @NotNull Path targetPath) throws IOException {
        exportToFile(entries, targetPath, 0);
    }

    public static void exportToFile(
        @NotNull List<EntryData> entries,
        @NotNull Path targetPath,
        int maxTotalChars) throws IOException {

        List<AnthropicMessage> anthropicMessages = toAnthropicMessages(entries);

        trimToSizeBudget(anthropicMessages, maxTotalChars);

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

    static List<AnthropicMessage> toAnthropicMessages(@NotNull List<EntryData> entries) {
        var builder = new MessageListBuilder();
        for (EntryData entry : entries) {
            switch (entry) {
                case EntryData.Prompt prompt -> builder.handlePrompt(prompt);
                case EntryData.Text text -> builder.handleText(text);
                case EntryData.ToolCall toolCall -> builder.handleToolCall(toolCall);
                case EntryData.Thinking thinking -> builder.updateModel(thinking.getModel());
                case EntryData.SubAgent subAgent -> builder.updateModel(subAgent.getModel());
                default -> { /* Skip Status, TurnStats, ContextFiles, SessionSeparator, Nudge */ }
            }
        }
        builder.flushPending();
        return mergeConsecutiveSameRole(builder.messages);
    }

    // ------------------------------------------------------------------
    // Merge & helpers
    // ------------------------------------------------------------------

    static void trimToSizeBudget(@NotNull List<AnthropicMessage> messages, int maxTotalChars) {
        if (maxTotalChars <= 0) return;

        int total = messages.stream().mapToInt(m -> m.toJsonLine().length()).sum();
        while (total > maxTotalChars && messages.size() >= 2) {
            int exchangeEnd = findFirstExchangeEnd(messages);
            if (exchangeEnd <= 0) break;

            int charsDropped = 0;
            for (int k = 0; k < exchangeEnd; k++) {
                charsDropped += messages.get(k).toJsonLine().length();
            }
            messages.subList(0, exchangeEnd).clear();
            total -= charsDropped;
        }
    }

    /**
     * Finds the end index (exclusive) of the first user+assistant exchange.
     * Returns the index of the second user message, or the list size if there's only one exchange.
     */
    private static int findFirstExchangeEnd(@NotNull List<AnthropicMessage> messages) {
        // Skip past the first user message and its associated assistant messages
        for (int i = 1; i < messages.size(); i++) {
            if (ROLE_USER.equals(messages.get(i).role())) {
                return i;
            }
        }
        return messages.size();
    }

    @NotNull
    private static List<AnthropicMessage> mergeConsecutiveSameRole(@NotNull List<AnthropicMessage> messages) {
        if (messages.size() <= 1) return messages;

        List<AnthropicMessage> merged = new ArrayList<>();
        for (AnthropicMessage msg : messages) {
            if (!merged.isEmpty() && merged.getLast().role.equals(msg.role)) {
                AnthropicMessage prev = merged.removeLast();
                List<JsonObject> combinedBlocks = new ArrayList<>(prev.contentBlocks);
                combinedBlocks.addAll(msg.contentBlocks);
                merged.add(new AnthropicMessage(prev.role, combinedBlocks, prev.createdAt, prev.model));
            } else {
                merged.add(msg);
            }
        }
        return merged;
    }

    /**
     * Parses an ISO-8601 timestamp to epoch millis.
     * Returns {@code 0} if the string is empty or unparseable.
     */
    static long parseTimestamp(@NotNull String isoTimestamp) {
        if (isoTimestamp.isEmpty()) return 0;
        try {
            return Instant.parse(isoTimestamp).toEpochMilli();
        } catch (java.time.format.DateTimeParseException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Inner types
    // ------------------------------------------------------------------

    /**
     * Accumulates assistant content blocks and tool results, flushing them as
     * complete Anthropic messages when turn boundaries are detected.
     */
    private static class MessageListBuilder {
        final List<AnthropicMessage> messages = new ArrayList<>();
        List<JsonObject> assistantBlocks = new ArrayList<>();
        List<JsonObject> toolResults = new ArrayList<>();
        boolean seenToolUse = false;
        long currentTimestamp = 0;
        String currentModel = "";

        void handlePrompt(@NotNull EntryData.Prompt prompt) {
            flushPending();
            String text = prompt.getText();
            if (!text.isEmpty()) {
                messages.add(new AnthropicMessage(ROLE_USER, List.of(textBlock(text)),
                    parseTimestamp(prompt.getTimestamp()), ""));
            }
        }

        void handleText(@NotNull EntryData.Text text) {
            String content = text.getRaw();
            if (content.isEmpty()) return;

            initTimestampIfNeeded(text.getTimestamp());
            updateModel(text.getModel());

            if (seenToolUse) {
                flushPending();
                currentTimestamp = parseTimestamp(text.getTimestamp());
                String entryModel = text.getModel();
                currentModel = (entryModel != null && !entryModel.isEmpty()) ? entryModel : "";
            }
            assistantBlocks.add(textBlock(content));
        }

        void handleToolCall(@NotNull EntryData.ToolCall toolCall) {
            initTimestampIfNeeded(toolCall.getTimestamp());
            updateModel(toolCall.getModel());

            String toolCallId = UUID.randomUUID().toString();
            String toolName = ExportUtils.sanitizeToolName(toolCall.getTitle());
            String argsStr = toolCall.getArguments() != null ? toolCall.getArguments() : "{}";
            String resultStr = toolCall.getResult() != null ? toolCall.getResult() : "";

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

            assistantBlocks.add(toolUseBlock);
            toolResults.add(toolResultBlock);
            seenToolUse = true;
        }

        void updateModel(String model) {
            if (model != null && !model.isEmpty()) {
                currentModel = model;
            }
        }

        void flushPending() {
            if (!assistantBlocks.isEmpty()) {
                emitTurn(assistantBlocks, toolResults, currentTimestamp, currentModel, messages);
            }
            assistantBlocks = new ArrayList<>();
            toolResults = new ArrayList<>();
            seenToolUse = false;
            currentTimestamp = 0;
            currentModel = "";
        }

        private void initTimestampIfNeeded(String timestamp) {
            if (currentTimestamp == 0) {
                currentTimestamp = parseTimestamp(timestamp);
            }
        }

        private void emitTurn(
            @NotNull List<JsonObject> blocks,
            @NotNull List<JsonObject> results,
            long createdAt,
            @NotNull String model,
            @NotNull List<AnthropicMessage> out) {

            if (blocks.isEmpty()) return;

            out.add(new AnthropicMessage(ROLE_ASSISTANT, blocks, createdAt, model));
            if (!results.isEmpty()) {
                out.add(new AnthropicMessage(ROLE_USER, results, createdAt, ""));
            }
        }

        private static long parseTimestamp(@NotNull String isoTimestamp) {
            return AnthropicClientExporter.parseTimestamp(isoTimestamp);
        }

        @NotNull
        private static JsonObject textBlock(@NotNull String text) {
            JsonObject block = new JsonObject();
            block.addProperty("type", TYPE_TEXT);
            block.addProperty(TYPE_TEXT, text);
            return block;
        }
    }

    /**
     * @param createdAt Epoch millis parsed from the entry timestamp (0 if unknown).
     * @param model     The model name from the originating {@link EntryData} (empty if unknown).
     */
    record AnthropicMessage(String role, List<JsonObject> contentBlocks, long createdAt, String model) {
        AnthropicMessage(@NotNull String role, @NotNull List<JsonObject> contentBlocks, long createdAt, @NotNull String model) {
            this.role = role;
            this.contentBlocks = List.copyOf(contentBlocks);
            this.createdAt = createdAt;
            this.model = model;
        }

        @NotNull
        String toJsonLine() {
            JsonObject obj = new JsonObject();
            obj.addProperty("role", role);
            var contentArray = new JsonArray();
            contentBlocks.forEach(contentArray::add);
            obj.add("content", contentArray);
            return GSON.toJson(obj);
        }
    }
}
