package com.github.catatafishen.agentbridge.memory.mining;

import com.github.catatafishen.agentbridge.ui.EntryData;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts Q+A exchange pairs from a list of conversation entries,
 * with traceability metadata (prompt entryId, git commit hashes).
 *
 * <p><b>Attribution:</b> exchange chunking logic adapted from MemPalace's
 * chunk_exchanges() in convo_miner.py (MIT License).
 *
 * <p>Pairs each user {@link EntryData.Prompt} with the following
 * {@link EntryData.Text} responses (concatenated). Tool calls and thinking
 * entries are skipped from response text, but git commit hashes are extracted
 * from tool call results for traceability.
 */
public final class ExchangeChunker {

    /**
     * Matches git commit output: {@code [branch abc1234] message}.
     * Captures the short SHA (7–40 hex chars) in group 1.
     */
    private static final Pattern GIT_COMMIT_SHA = Pattern.compile(
        "\\[\\S+\\s+([0-9a-f]{7,40})]");

    private ExchangeChunker() {
    }

    public static @NotNull List<Exchange> chunk(@NotNull List<EntryData> entries) {
        List<Exchange> exchanges = new ArrayList<>();
        String currentPrompt = null;
        String currentTimestamp = "";
        String currentPromptEntryId = "";
        StringBuilder currentResponse = new StringBuilder();
        List<String> currentCommits = new ArrayList<>();

        for (EntryData entry : entries) {
            if (entry instanceof EntryData.Prompt prompt) {
                flushExchange(currentPrompt, currentResponse, currentTimestamp,
                    currentPromptEntryId, currentCommits, exchanges);
                currentPrompt = prompt.getText();
                currentTimestamp = prompt.getTimestamp();
                currentPromptEntryId = prompt.getEntryId();
                currentResponse.setLength(0);
                currentCommits = new ArrayList<>();
            } else if (entry instanceof EntryData.Text text && currentPrompt != null) {
                appendResponseText(text.getRaw(), currentResponse);
            } else if (entry instanceof EntryData.ToolCall tc && currentPrompt != null) {
                extractCommitHashes(tc, currentCommits);
                appendToolResultEvidence(tc, currentResponse);
            }
        }

        flushExchange(currentPrompt, currentResponse, currentTimestamp,
            currentPromptEntryId, currentCommits, exchanges);
        return exchanges;
    }

    private static void flushExchange(String prompt, StringBuilder response,
                                      String timestamp, String promptEntryId,
                                      List<String> commitHashes,
                                      List<Exchange> exchanges) {
        if (prompt != null && !response.isEmpty()) {
            exchanges.add(new Exchange(prompt, response.toString().trim(), timestamp,
                promptEntryId, List.copyOf(commitHashes)));
        }
    }

    private static void appendResponseText(String raw, StringBuilder response) {
        if (!raw.isBlank()) {
            if (!response.isEmpty()) {
                response.append('\n');
            }
            response.append(raw);
        }
    }

    /**
     * Extract git commit SHAs from tool call results matching the
     * {@code [branch sha] message} pattern.
     */
    static void extractCommitHashes(EntryData.ToolCall tc, List<String> out) {
        String result = tc.getResult();
        if (result == null || result.isEmpty()) return;

        Matcher matcher = GIT_COMMIT_SHA.matcher(result);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
    }

    /**
     * Append evidence-relevant fragments from a tool call result.
     * Includes the file path reference (if available) and the tool name context,
     * but NOT the full result text (which can be very large for file reads/diffs).
     * The {@link com.github.catatafishen.agentbridge.memory.validation.EvidenceExtractor}
     * will pick up file paths and FQN patterns from these fragments.
     */
    static void appendToolResultEvidence(EntryData.ToolCall tc, StringBuilder response) {
        String filePath = tc.getFilePath();
        if (filePath != null && !filePath.isEmpty()) {
            appendResponseText("[tool:" + tc.getTitle() + " file:" + filePath + "]", response);
        }

        String result = tc.getResult();
        if (result == null || result.isEmpty()) return;

        // For search/list tools, the result contains file paths worth capturing.
        // Limit to first 500 chars to avoid bloating the combined text.
        String toolName = tc.getTitle();
        if (isEvidenceRichTool(toolName)) {
            String fragment = result.length() > 500 ? result.substring(0, 500) : result;
            appendResponseText("[" + toolName + " result: " + fragment + "]", response);
        }
    }

    private static boolean isEvidenceRichTool(String toolName) {
        return toolName.contains("search") || toolName.contains("find")
            || toolName.contains("list") || toolName.contains("grep")
            || toolName.contains("glob") || toolName.contains("outline");
    }

    /**
     * A single Q+A exchange pair extracted from conversation entries.
     *
     * @param prompt        the user's prompt text
     * @param response      the concatenated assistant response text
     * @param timestamp     ISO 8601 timestamp of the prompt
     * @param promptEntryId the unique entryId of the originating Prompt entry
     * @param commitHashes  git commit SHAs extracted from tool calls in this exchange
     */
    public record Exchange(
        @NotNull String prompt,
        @NotNull String response,
        @NotNull String timestamp,
        @NotNull String promptEntryId,
        @NotNull List<String> commitHashes
    ) {
        /**
         * Get the combined text (prompt + response) for classification and embedding.
         */
        public @NotNull String combinedText() {
            return prompt + "\n\n" + response;
        }
    }
}
