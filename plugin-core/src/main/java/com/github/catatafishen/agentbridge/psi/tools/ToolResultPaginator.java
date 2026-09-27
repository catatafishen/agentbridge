package com.github.catatafishen.agentbridge.psi.tools;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

/**
 * Produces bounded character pages with continuation metadata for text-heavy tool results.
 */
public final class ToolResultPaginator {

    private ToolResultPaginator() {
    }

    public static @NotNull PageRequest parsePageRequest(@NotNull JsonObject args, int maxPageChars) {
        int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be zero or greater.");
        }

        int maxChars = args.has("max_chars") ? args.get("max_chars").getAsInt() : maxPageChars;
        if (maxChars <= 0 || maxChars > maxPageChars) {
            throw new IllegalArgumentException("max_chars must be between 1 and " + maxPageChars + ".");
        }
        return new PageRequest(offset, maxChars);
    }

    public static @NotNull String paginate(@NotNull String toolId,
                                           @NotNull String text,
                                           int offset,
                                           int maxChars) {
        if (text.isEmpty()) return text;
        int totalLength = text.length();
        if (offset >= totalLength) {
            return "No " + toolId + " output at offset " + offset
                + " (total length: " + totalLength + " characters).";
        }

        int end = (int) Math.min(totalLength, (long) offset + maxChars);
        if (offset == 0 && end == totalLength) return text;

        StringBuilder page = new StringBuilder(maxChars + 192);
        if (offset > 0) {
            page.append("[Showing characters ").append(offset).append('-').append(end)
                .append(" of ").append(totalLength).append("]\n\n");
        }
        page.append(text, offset, end);
        if (end < totalLength) {
            page.append("\n\n[Output paginated: showing characters ").append(offset).append('-').append(end)
                .append(" of ").append(totalLength).append(". Use offset=").append(end)
                .append(" to continue.]");
        }
        return page.toString();
    }

    public record PageRequest(int offset, int maxChars) {
    }
}
