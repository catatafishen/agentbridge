package com.github.catatafishen.agentbridge.session.exporters;

import com.github.catatafishen.agentbridge.ui.EntryData;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Utility methods for trimming {@link EntryData} lists to a character budget
 * and generating OpenCode-style IDs.
 *
 * <p>Extracted from {@link OpenCodeClientExporter} to keep the exporter focused
 * on database interaction.</p>
 */
final class EntryBudgetTrimmer {

    private EntryBudgetTrimmer() {
        // utility class
    }

    // ── Budget trimming ───────────────────────────────────────────────────────

    /**
     * Trims a conversation entry list so its total character count stays within
     * {@code maxTotalChars}.  Older turns are dropped first; the very first
     * prompt is always preserved.
     *
     * @return a (possibly shorter) copy of the list, or the original if it
     * already fits
     */
    static @NotNull List<EntryData> trimEntriesToBudget(@NotNull List<EntryData> entries, int maxTotalChars) {
        if (maxTotalChars <= 0) return entries;

        int total = countTotalChars(entries);
        if (total <= maxTotalChars) return entries;

        List<EntryData> result = new ArrayList<>(entries);
        while (total > maxTotalChars) {
            int secondPromptIdx = findSecondPromptIndex(result);
            if (secondPromptIdx != -1) {
                int charsDropped = countTotalChars(result.subList(0, secondPromptIdx));
                result.subList(0, secondPromptIdx).clear();
                total -= charsDropped;
            } else {
                int freed = dropOldestNonPrompt(result);
                if (freed < 0) break; // nothing to drop
                total -= freed;
            }
        }
        return result;
    }

    /**
     * Counts the character footprint of a single entry.
     */
    static int countEntryChars(@NotNull EntryData e) {
        return switch (e) {
            case EntryData.Prompt p -> p.getText().length();
            case EntryData.Text t -> t.getRaw().length();
            case EntryData.ToolCall tc -> {
                int n = 0;
                if (tc.getArguments() != null) n += tc.getArguments().length();
                if (tc.getResult() != null) n += tc.getResult().length();
                yield n;
            }
            default -> 0;
        };
    }

    /**
     * Sums the character count of every entry in the list.
     */
    static int countTotalChars(@NotNull List<EntryData> entries) {
        int total = 0;
        for (EntryData e : entries) total += countEntryChars(e);
        return total;
    }

    /**
     * Finds the index of the second {@link EntryData.Prompt} in the list.
     *
     * @return the index, or {@code -1} if fewer than two prompts exist
     */
    static int findSecondPromptIndex(@NotNull List<EntryData> entries) {
        int promptsSeen = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) instanceof EntryData.Prompt && ++promptsSeen == 2) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Drops the oldest non-Prompt entry after the initial Prompt.
     *
     * @return the number of characters freed, or {@code -1} if nothing could
     * be dropped
     */
    static int dropOldestNonPrompt(@NotNull List<EntryData> result) {
        for (int i = 1; i < result.size(); i++) {
            if (!(result.get(i) instanceof EntryData.Prompt)) {
                EntryData dropped = result.remove(i);
                return countEntryChars(dropped);
            }
        }
        return -1;
    }

    // ── ID / hash utilities ───────────────────────────────────────────────────

    /**
     * Generates an OpenCode-style prefixed ID (e.g. {@code ses_abc123...}).
     * Uses a UUID as the random component.
     */
    @NotNull
    static String generateId(@NotNull String prefix) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return prefix + "_" + uuid;
    }

    /**
     * Returns the SHA-1 hex digest of the given string (UTF-8).
     */
    @NotNull
    static String sha1Hex(@NotNull String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is always available in Java
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }
}
