package com.github.catatafishen.agentbridge.services;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Utility class for compacting the event log by removing redundant streaming events.
 *
 * <p>When a finalization event arrives (e.g. {@code finalizeAgentText}), all preceding
 * incremental streaming events for the same turn/agent become redundant. Without compaction,
 * streaming text tokens (50–200 per agent turn) dominate the event log and eventually evict
 * critical events like {@code restoreBatch} — causing the PWA to show empty messages on
 * initial page load.
 */
final class EventLogCompactor {

    private EventLogCompactor() {
    }

    /**
     * Compacts the event log by removing streaming events that are superseded by
     * the given finalization JS call.
     *
     * <p>When {@code finalizeAgentText('t0','main',...)} arrives, all preceding
     * {@code appendAgentText('t0','main',...)} events become redundant.
     * Similarly, {@code collapseThinking} supersedes {@code addThinkingText}.
     *
     * @param js       the JS call that may trigger compaction
     * @param eventLog the mutable event log to compact (caller must synchronize)
     */
    static void compactStreamingEvents(String js, List<String> eventLog) {
        String removePrefix = null;
        if (js.startsWith("ChatController.finalizeAgentText(")) {
            removePrefix = buildStreamingPrefix(js, "ChatController.finalizeAgentText(", "ChatController.appendAgentText(");
        } else if (js.startsWith("ChatController.collapseThinking(")) {
            removePrefix = buildStreamingPrefix(js, "ChatController.collapseThinking(", "ChatController.addThinkingText(");
        }
        if (removePrefix != null) {
            // GSON HTML-escapes single quotes as \u0027 — encode the prefix to match
            String encodedPrefix = removePrefix.replace("'", "\\u0027");
            eventLog.removeIf(ev -> eventJsStartsWith(ev, encodedPrefix));
        }
    }

    /**
     * Extracts the first two single-quoted arguments (turnId, agentId) from a JS call
     * and builds the corresponding streaming-event prefix.
     *
     * <p>E.g., for {@code ChatController.finalizeAgentText('t0','main','html')} with
     * {@code streamPrefix = "ChatController.appendAgentText("}, returns
     * {@code "ChatController.appendAgentText('t0','main',"}.
     */
    static @Nullable String buildStreamingPrefix(String js, String finalizePrefix, String streamPrefix) {
        int q1 = js.indexOf('\'', finalizePrefix.length());
        if (q1 < 0) return null;
        int q2 = js.indexOf('\'', q1 + 1);
        if (q2 < 0) return null;
        int q3 = js.indexOf('\'', q2 + 1);
        if (q3 < 0) return null;
        int q4 = js.indexOf('\'', q3 + 1);
        if (q4 < 0) return null;
        String turnId = js.substring(q1 + 1, q2);
        String agentId = js.substring(q3 + 1, q4);
        return streamPrefix + "'" + turnId + "','" + agentId + "',";
    }

    /**
     * Checks whether the {@code js} field of the given event JSON starts with {@code jsPrefix}.
     * Uses fast string matching to avoid full JSON parsing.
     */
    static boolean eventJsStartsWith(String eventJson, String jsPrefix) {
        int idx = eventJson.indexOf("\"js\":\"");
        if (idx < 0) return false;
        int jsStart = idx + 6;
        return eventJson.startsWith(jsPrefix, jsStart);
    }

    /**
     * Parses the {@code from=N} parameter from a URL query string.
     *
     * @param query the raw query string (may be {@code null})
     * @return the parsed integer value, or 0 if absent or unparseable
     */
    static int parseFromQuery(@Nullable String query) {
        if (query == null) return 0;
        for (String part : query.split("&")) {
            if (part.startsWith("from=")) {
                try {
                    return Integer.parseInt(part.substring(5));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    /**
     * Fast extraction of {@code "seq":N} from a JSON string (avoids full parse).
     *
     * @param json the JSON string
     * @return the sequence number, or 0 if not found
     */
    static int extractSeq(String json) {
        int idx = json.indexOf("\"seq\":");
        if (idx < 0) return 0;
        int start = idx + 6;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Extracts the first single-quoted string argument from a JS call like
     * {@code ChatController.fn('value')}.
     *
     * @param js the JS call string
     * @return the extracted value, or empty string if not found
     */
    static String extractFirstStringArg(String js) {
        int start = js.indexOf('\'');
        if (start < 0) return "";
        int end = js.indexOf('\'', start + 1);
        if (end < 0) return "";
        return js.substring(start + 1, end);
    }
}
