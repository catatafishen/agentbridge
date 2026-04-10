package com.github.catatafishen.agentbridge.memory.mining;

import com.github.catatafishen.agentbridge.ui.EntryData;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts Q+A exchange pairs from a list of conversation entries.
 *
 * <p><b>Attribution:</b> exchange chunking logic adapted from MemPalace's
 * chunk_exchanges() in convo_miner.py (MIT License).
 *
 * <p>Pairs each user {@link EntryData.Prompt} with the following
 * {@link EntryData.Text} responses (concatenated). Tool calls and thinking
 * entries are skipped — only human-readable content is included.
 */
public final class ExchangeChunker {

    private ExchangeChunker() {
    }

    public static @NotNull List<Exchange> chunk(@NotNull List<EntryData> entries) {
        List<Exchange> exchanges = new ArrayList<>();
        String currentPrompt = null;
        String currentTimestamp = "";
        StringBuilder currentResponse = new StringBuilder();

        for (EntryData entry : entries) {
            if (entry instanceof EntryData.Prompt prompt) {
                flushExchange(currentPrompt, currentResponse, currentTimestamp, exchanges);
                currentPrompt = prompt.getText();
                currentTimestamp = prompt.getTimestamp();
                currentResponse.setLength(0);
            } else if (entry instanceof EntryData.Text text && currentPrompt != null) {
                appendResponseText(text.getRaw(), currentResponse);
            }
        }

        flushExchange(currentPrompt, currentResponse, currentTimestamp, exchanges);
        return exchanges;
    }

    private static void flushExchange(String prompt, StringBuilder response,
                                      String timestamp, List<Exchange> exchanges) {
        if (prompt != null && !response.isEmpty()) {
            exchanges.add(new Exchange(prompt, response.toString().trim(), timestamp));
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
     * A single Q+A exchange pair extracted from conversation entries.
     *
     * @param prompt    the user's prompt text
     * @param response  the concatenated assistant response text
     * @param timestamp ISO 8601 timestamp of the prompt
     */
    public record Exchange(
        @NotNull String prompt,
        @NotNull String response,
        @NotNull String timestamp
    ) {
        /**
         * Get the combined text (prompt + response) for classification and embedding.
         */
        public @NotNull String combinedText() {
            return prompt + "\n\n" + response;
        }
    }
}
