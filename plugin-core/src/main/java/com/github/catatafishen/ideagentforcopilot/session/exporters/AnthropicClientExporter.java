package com.github.catatafishen.ideagentforcopilot.session.exporters;

import com.github.catatafishen.ideagentforcopilot.ui.EntryData;
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
import java.util.regex.Pattern;

public final class AnthropicClientExporter {

    private static final Logger LOG = Logger.getInstance(AnthropicClientExporter.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final String TYPE_TEXT = "text";
    private static final String TYPE_TOOL_USE = "tool_use";
    private static final String TYPE_TOOL_RESULT = "tool_result";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private static final int MAX_TOOL_NAME_LENGTH = 200;
    private static final Pattern INVALID_TOOL_NAME_CHARS = Pattern.compile("[^a-zA-Z0-9_-]");
    private static final Pattern CONSECUTIVE_UNDERSCORES = Pattern.compile("_{3,}");

    /**
     * Sanitizes a tool name for the Anthropic API, which requires tool_use names to match
     * {@code [a-zA-Z0-9_-]+} and be at most 200 characters.
     *
     * <p>Our session data stores human-readable titles for tool calls (e.g., "git add src/Foo.java",
     * "Viewing .../ChatConsolePanel.kt") which can exceed the API limit. This method replaces
     * invalid characters, collapses runs of 3+ underscores (preserving the {@code __} MCP
     * separator), and truncates to fit.</p>
     */
    public static String sanitizeToolName(@NotNull String rawName) {
        if (rawName.isEmpty()) return "unknown_tool";
        String sanitized = INVALID_TOOL_NAME_CHARS.matcher(rawName).replaceAll("_");
        sanitized = CONSECUTIVE_UNDERSCORES.matcher(sanitized).replaceAll("__");
        if (sanitized.startsWith("_")) sanitized = sanitized.substring(1);
        if (sanitized.endsWith("_")) sanitized = sanitized.substring(0, sanitized.length() - 1);
        if (sanitized.length() > MAX_TOOL_NAME_LENGTH) sanitized = sanitized.substring(0, MAX_TOOL_NAME_LENGTH);
        return sanitized.isEmpty() ? "unknown_tool" : sanitized;
    }

    private AnthropicClientExporter() {
    }

    public static void exportToFile(
        @NotNull List<EntryData> entries,
        @NotNull Path targetPath) throws IOException {

        List<AnthropicMessage> anthropicMessages = toAnthropicMessages(entries);

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

    /**
     * Converts a flat list of {@link EntryData} entries into Anthropic API message format.
     *
     * <p>The Anthropic API requires strict user/assistant alternation with tool_use blocks
     * in assistant messages and tool_result blocks in the following user message.  When a
     * text entry follows tool calls, it signals a turn boundary: the current assistant
     * blocks are flushed as a turn pair before the new text is started.</p>
     */
    static List<AnthropicMessage> toAnthropicMessages(@NotNull List<EntryData> entries) {
        List<AnthropicMessage> raw = new ArrayList<>();
        List<JsonObject> assistantBlocks = new ArrayList<>();
        List<JsonObject> toolResults = new ArrayList<>();
        boolean seenToolUse = false;
        long currentTimestamp = 0;
        String currentModel = "";

        for (EntryData entry : entries) {
            if (entry instanceof EntryData.Prompt prompt) {
                // Flush pending assistant blocks
                if (!assistantBlocks.isEmpty()) {
                    emitTurn(assistantBlocks, toolResults, currentTimestamp, currentModel, raw);
                    assistantBlocks = new ArrayList<>();
                    toolResults = new ArrayList<>();
                    seenToolUse = false;
                    currentTimestamp = 0;
                    currentModel = "";
                }
                // Add user message
                String text = prompt.getText();
                if (!text.isEmpty()) {
                    raw.add(new AnthropicMessage(ROLE_USER, List.of(textBlock(text)),
                        parseTimestamp(prompt.getTimestamp()), ""));
                }

            } else if (entry instanceof EntryData.Text text) {
                String content = text.getRaw().toString();
                if (content.isEmpty()) continue;

                if (currentTimestamp == 0) {
                    currentTimestamp = parseTimestamp(text.getTimestamp());
                }
                String entryModel = text.getModel();
                if (entryModel != null && !entryModel.isEmpty()) {
                    currentModel = entryModel;
                }

                if (seenToolUse) {
                    // Text after tool use = turn boundary
                    emitTurn(assistantBlocks, toolResults, currentTimestamp, currentModel, raw);
                    assistantBlocks = new ArrayList<>();
                    toolResults = new ArrayList<>();
                    seenToolUse = false;
                    currentTimestamp = parseTimestamp(text.getTimestamp());
                    currentModel = entryModel != null && !entryModel.isEmpty() ? entryModel : "";
                }
                assistantBlocks.add(textBlock(content));

            } else if (entry instanceof EntryData.ToolCall toolCall) {
                if (currentTimestamp == 0) {
                    currentTimestamp = parseTimestamp(toolCall.getTimestamp());
                }
                String entryModel = toolCall.getModel();
                if (entryModel != null && !entryModel.isEmpty()) {
                    currentModel = entryModel;
                }

                String toolCallId = UUID.randomUUID().toString();
                String toolName = sanitizeToolName(toolCall.getTitle());
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

            } else if (entry instanceof EntryData.Thinking thinking) {
                String entryModel = thinking.getModel();
                if (entryModel != null && !entryModel.isEmpty()) {
                    currentModel = entryModel;
                }
            } else if (entry instanceof EntryData.SubAgent subAgent) {
                String entryModel = subAgent.getModel();
                if (entryModel != null && !entryModel.isEmpty()) {
                    currentModel = entryModel;
                }
            }
            // Skip Status, TurnStats, ContextFiles, SessionSeparator
        }

        // Flush remaining
        if (!assistantBlocks.isEmpty()) {
            emitTurn(assistantBlocks, toolResults, currentTimestamp, currentModel, raw);
        }

        return mergeConsecutiveSameRole(raw);
    }

    private static void emitTurn(
        @NotNull List<JsonObject> assistantBlocks,
        @NotNull List<JsonObject> toolResults,
        long createdAt,
        @NotNull String model,
        @NotNull List<AnthropicMessage> out) {

        if (assistantBlocks.isEmpty()) return;

        out.add(new AnthropicMessage(ROLE_ASSISTANT, assistantBlocks, createdAt, model));
        if (!toolResults.isEmpty()) {
            out.add(new AnthropicMessage(ROLE_USER, toolResults, createdAt, ""));
        }
    }

    private static long parseTimestamp(@NotNull String isoTimestamp) {
        if (isoTimestamp.isEmpty()) return 0;
        try {
            return Instant.parse(isoTimestamp).toEpochMilli();
        } catch (java.time.format.DateTimeParseException e) {
            return 0;
        }
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
                merged.add(new AnthropicMessage(prev.role, combinedBlocks, prev.createdAt, prev.model));
            } else {
                merged.add(msg);
            }
        }
        return merged;
    }

    // ------------------------------------------------------------------
    // Inner types
    // ------------------------------------------------------------------

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
