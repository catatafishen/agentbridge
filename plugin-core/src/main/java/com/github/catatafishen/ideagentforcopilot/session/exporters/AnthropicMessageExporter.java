package com.github.catatafishen.ideagentforcopilot.session.exporters;

import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Exports a list of {@link SessionMessage}s to the Anthropic API {@code messages.jsonl} format
 * used by Kiro and Claude CLI for session injection.
 *
 * <p>Output: one Anthropic API message object per line.</p>
 *
 * <p>Part → Anthropic block conversion:
 * <ul>
 *   <li>{@code {type:"text"}} in user message → {@code {"type":"text","text":"..."}}</li>
 *   <li>{@code {type:"text"}} in assistant message → {@code {"type":"text","text":"..."}}</li>
 *   <li>{@code {type:"reasoning"}} → skipped (not part of Anthropic stored format)</li>
 *   <li>{@code {type:"tool-invocation", toolInvocation:{state:"result",...}}} → emits:
 *       <ol>
 *         <li>Into current assistant content: {@code {"type":"tool_use","id":toolCallId,
 *             "name":toolName,"input":{...}}}</li>
 *         <li>After the assistant message: a user message with a {@code tool_result} block</li>
 *       </ol>
 *   </li>
 *   <li>{@code {type:"subagent"}}, {@code {type:"status"}}, {@code {type:"file"}} → skipped</li>
 *   <li>{@code role:"separator"} → skipped</li>
 * </ul>
 * </p>
 *
 * <p>A token budget ({@code maxTokenEstimate}, default 20 000) is applied: messages are
 * walked newest-first counting {@code text.length() / 4} per text block, then the kept set
 * is written oldest-to-newest.</p>
 */
public final class AnthropicMessageExporter {

    private static final Logger LOG = Logger.getInstance(AnthropicMessageExporter.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Default token budget for exported conversations. */
    private static final int DEFAULT_MAX_TOKEN_ESTIMATE = 20_000;

    private AnthropicMessageExporter() {
        throw new IllegalStateException("Utility class");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Exports the given messages to {@code targetPath} in Anthropic {@code messages.jsonl}
     * format, applying a default token budget of 20 000.
     *
     * @param messages   v2 session messages to export
     * @param targetPath path of the output file (created or overwritten)
     * @throws IOException if writing fails
     */
    public static void exportToFile(
            @NotNull List<SessionMessage> messages,
            @NotNull Path targetPath) throws IOException {
        exportToFile(messages, targetPath, DEFAULT_MAX_TOKEN_ESTIMATE);
    }

    /**
     * Exports the given messages to {@code targetPath} in Anthropic {@code messages.jsonl}
     * format, applying the specified token budget.
     *
     * <p>Token budget strategy: walk messages newest-first accumulating estimated token cost
     * ({@code text.length() / 4}); include messages until the budget is exhausted; then write
     * the kept messages oldest-to-newest so Claude CLI sees a coherent conversation.</p>
     *
     * @param messages         v2 session messages to export
     * @param targetPath       path of the output file (created or overwritten)
     * @param maxTokenEstimate maximum estimated token count to include
     * @throws IOException if writing fails
     */
    public static void exportToFile(
            @NotNull List<SessionMessage> messages,
            @NotNull Path targetPath,
            int maxTokenEstimate) throws IOException {

        List<SessionMessage> budgeted = applyTokenBudget(messages, maxTokenEstimate);
        List<AnthropicMessage> anthropicMessages = toAnthropicMessages(budgeted);

        StringBuilder sb = new StringBuilder();
        for (AnthropicMessage msg : anthropicMessages) {
            sb.append(msg.toJsonLine()).append('\n');
        }

        //noinspection ResultOfMethodCallIgnored — best-effort parent dir creation
        targetPath.getParent().toFile().mkdirs();
        Files.writeString(targetPath, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // ── Token budget ──────────────────────────────────────────────────────────

    /**
     * Returns a sub-list of messages fitting within the token budget.
     *
     * <p>Walks newest-first, accumulates token estimates, then returns the kept messages
     * in their original oldest-to-newest order.</p>
     */
    @NotNull
    private static List<SessionMessage> applyTokenBudget(
            @NotNull List<SessionMessage> messages,
            int maxTokenEstimate) {

        if (messages.isEmpty()) return messages;

        int budget = maxTokenEstimate;
        // Walk newest-first to decide which messages to keep
        List<Boolean> keep = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            keep.add(false);
        }

        for (int i = messages.size() - 1; i >= 0; i--) {
            SessionMessage msg = messages.get(i);
            int cost = estimateTokens(msg);
            if (budget <= 0) break;
            keep.set(i, true);
            budget -= cost;
        }

        List<SessionMessage> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (keep.get(i)) result.add(messages.get(i));
        }
        return result;
    }

    /**
     * Estimates the token count for a message as {@code sum(text.length() / 4)} for all
     * text-containing parts.
     */
    private static int estimateTokens(@NotNull SessionMessage msg) {
        int total = 0;
        for (JsonObject part : msg.parts) {
            String type = part.has("type") ? part.get("type").getAsString() : "";
            if ("text".equals(type) || "reasoning".equals(type)) {
                String text = part.has("text") ? part.get("text").getAsString() : "";
                total += text.length() / 4;
            } else if ("tool-invocation".equals(type) && part.has("toolInvocation")) {
                JsonObject inv = part.getAsJsonObject("toolInvocation");
                if (inv.has("result")) {
                    total += inv.get("result").getAsString().length() / 4;
                }
                if (inv.has("args")) {
                    total += inv.get("args").getAsString().length() / 4;
                }
            }
        }
        return Math.max(total, 1); // at least 1 token per message to avoid infinite loops
    }

    // ── Conversion ────────────────────────────────────────────────────────────

    /**
     * Converts a list of {@link SessionMessage}s into a flat list of {@link AnthropicMessage}s.
     *
     * <p>Tool-invocation parts with results generate both an assistant-side {@code tool_use}
     * block and a following user-side {@code tool_result} block.</p>
     */
    @NotNull
    private static List<AnthropicMessage> toAnthropicMessages(@NotNull List<SessionMessage> messages) {
        List<AnthropicMessage> result = new ArrayList<>();

        for (SessionMessage msg : messages) {
            if ("separator".equals(msg.role)) continue; // skip separators

            if ("user".equals(msg.role)) {
                List<JsonObject> blocks = new ArrayList<>();
                for (JsonObject part : msg.parts) {
                    String type = part.has("type") ? part.get("type").getAsString() : "";
                    if ("text".equals(type)) {
                        JsonObject block = new JsonObject();
                        block.addProperty("type", "text");
                        block.addProperty("text", part.has("text") ? part.get("text").getAsString() : "");
                        blocks.add(block);
                    }
                    // file, status, subagent parts → skip
                }
                if (!blocks.isEmpty()) {
                    result.add(new AnthropicMessage("user", blocks));
                }

            } else if ("assistant".equals(msg.role)) {
                List<JsonObject> assistantBlocks = new ArrayList<>();
                // tool_result messages to append after this assistant message
                List<AnthropicMessage> toolResultMessages = new ArrayList<>();

                for (JsonObject part : msg.parts) {
                    String type = part.has("type") ? part.get("type").getAsString() : "";

                    if ("text".equals(type)) {
                        JsonObject block = new JsonObject();
                        block.addProperty("type", "text");
                        block.addProperty("text", part.has("text") ? part.get("text").getAsString() : "");
                        assistantBlocks.add(block);

                    } else if ("reasoning".equals(type)) {
                        // Skip — not part of Anthropic stored format

                    } else if ("tool-invocation".equals(type) && part.has("toolInvocation")) {
                        JsonObject inv = part.getAsJsonObject("toolInvocation");
                        String state = inv.has("state") ? inv.get("state").getAsString() : "call";

                        // Only export completed tool calls (state == "result") to avoid
                        // injecting incomplete calls that would confuse Claude.
                        if (!"result".equals(state)) continue;

                        String toolCallId = inv.has("toolCallId") ? inv.get("toolCallId").getAsString() : "";
                        String toolName = inv.has("toolName") ? inv.get("toolName").getAsString() : "unknown";
                        String argsStr = inv.has("args") ? inv.get("args").getAsString() : "{}";
                        String resultStr = inv.has("result") ? inv.get("result").getAsString() : "";

                        // Parse args back to JsonObject for proper Anthropic "input" field
                        JsonObject inputObj;
                        try {
                            inputObj = JsonParser.parseString(argsStr).getAsJsonObject();
                        } catch (Exception e) {
                            LOG.warn("Could not parse tool args as JSON object, wrapping as string: " + argsStr);
                            inputObj = new JsonObject();
                            inputObj.addProperty("_raw", argsStr);
                        }

                        // Add tool_use block to assistant content
                        JsonObject toolUseBlock = new JsonObject();
                        toolUseBlock.addProperty("type", "tool_use");
                        toolUseBlock.addProperty("id", toolCallId);
                        toolUseBlock.addProperty("name", toolName);
                        toolUseBlock.add("input", inputObj);
                        assistantBlocks.add(toolUseBlock);

                        // Queue a tool_result user message to follow
                        JsonObject toolResultBlock = new JsonObject();
                        toolResultBlock.addProperty("type", "tool_result");
                        toolResultBlock.addProperty("tool_use_id", toolCallId);
                        toolResultBlock.addProperty("content", resultStr);
                        toolResultMessages.add(new AnthropicMessage("user", List.of(toolResultBlock)));

                    } else if ("subagent".equals(type) || "status".equals(type) || "file".equals(type)) {
                        // Skip — no Anthropic equivalent
                    }
                }

                if (!assistantBlocks.isEmpty()) {
                    result.add(new AnthropicMessage("assistant", assistantBlocks));
                    result.addAll(toolResultMessages);
                }
            }
        }

        return result;
    }

    // ── Inner helper ──────────────────────────────────────────────────────────

    /**
     * Represents one Anthropic API message: {@code {"role":"...","content":[...]}}.
     */
    private static final class AnthropicMessage {
        private final String role;
        private final List<JsonObject> contentBlocks;

        AnthropicMessage(@NotNull String role, @NotNull List<JsonObject> contentBlocks) {
            this.role = role;
            this.contentBlocks = List.copyOf(contentBlocks);
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
