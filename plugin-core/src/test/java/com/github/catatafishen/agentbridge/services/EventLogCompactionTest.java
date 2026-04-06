package com.github.catatafishen.agentbridge.services;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the event log compaction helpers in {@link ChatWebServer}.
 * These are package-private statics, tested directly to verify correctness
 * without needing a running server instance.
 */
class EventLogCompactionTest {

    private static final Gson GSON = new Gson();

    // ── buildStreamingPrefix ──────────────────────────────────────────────

    @Test
    void buildStreamingPrefix_extractsTurnAndAgent() {
        String js = "ChatController.finalizeAgentText('t0','main','base64html')";
        String result = ChatWebServer.buildStreamingPrefix(
            js,
            "ChatController.finalizeAgentText(",
            "ChatController.appendAgentText("
        );
        assertEquals("ChatController.appendAgentText('t0','main',", result);
    }

    @Test
    void buildStreamingPrefix_handlesMultiDigitTurnId() {
        String js = "ChatController.finalizeAgentText('t42','main','html')";
        String result = ChatWebServer.buildStreamingPrefix(
            js,
            "ChatController.finalizeAgentText(",
            "ChatController.appendAgentText("
        );
        assertEquals("ChatController.appendAgentText('t42','main',", result);
    }

    @Test
    void buildStreamingPrefix_thinkingCollapse() {
        String js = "ChatController.collapseThinking('t3','main','encoded')";
        String result = ChatWebServer.buildStreamingPrefix(
            js,
            "ChatController.collapseThinking(",
            "ChatController.addThinkingText("
        );
        assertEquals("ChatController.addThinkingText('t3','main',", result);
    }

    @Test
    void buildStreamingPrefix_returnsNullForMalformedInput() {
        assertNull(ChatWebServer.buildStreamingPrefix(
            "ChatController.finalizeAgentText()",
            "ChatController.finalizeAgentText(",
            "ChatController.appendAgentText("
        ));
    }

    @Test
    void buildStreamingPrefix_returnsNullForNoArgs() {
        assertNull(ChatWebServer.buildStreamingPrefix(
            "ChatController.finalizeAgentText(null)",
            "ChatController.finalizeAgentText(",
            "ChatController.appendAgentText("
        ));
    }

    // ── eventJsStartsWith ────────────────────────────────────────────────
    // GSON HTML-escapes single quotes as \u0027 in the stored JSON.
    // The prefix must be GSON-encoded before matching.

    @Test
    void eventJsStartsWith_matchesGsonEncodedPrefix() {
        String eventJson = buildEventJson(1, "ChatController.appendAgentText('t0','main','hello','12:34')");
        String prefix = gsonEncode("ChatController.appendAgentText('t0','main',");
        assertTrue(ChatWebServer.eventJsStartsWith(eventJson, prefix));
    }

    @Test
    void eventJsStartsWith_doesNotMatchDifferentTurn() {
        String eventJson = buildEventJson(2, "ChatController.appendAgentText('t1','main','hello','12:34')");
        String prefix = gsonEncode("ChatController.appendAgentText('t0','main',");
        assertFalse(ChatWebServer.eventJsStartsWith(eventJson, prefix));
    }

    @Test
    void eventJsStartsWith_doesNotMatchDifferentMethod() {
        String eventJson = buildEventJson(3, "ChatController.addToolCall('t0','main','tool1')");
        String prefix = gsonEncode("ChatController.appendAgentText('t0','main',");
        assertFalse(ChatWebServer.eventJsStartsWith(eventJson, prefix));
    }

    @Test
    void eventJsStartsWith_handlesThinkingEvents() {
        String eventJson = buildEventJson(4, "ChatController.addThinkingText('t0','main','thinking...')");
        String prefix = gsonEncode("ChatController.addThinkingText('t0','main',");
        assertTrue(ChatWebServer.eventJsStartsWith(eventJson, prefix));
    }

    @Test
    void eventJsStartsWith_returnsFalseForMissingJsField() {
        assertFalse(ChatWebServer.eventJsStartsWith(
            "{\"seq\":1}",
            gsonEncode("ChatController.appendAgentText(")
        ));
    }

    // ── Integration: compaction scenario ──────────────────────────────────

    @Test
    void compaction_preservesNonStreamingEvents() {
        String toolEvent = buildEventJson(1, "ChatController.addToolCall('t0','main','tool1','read_file')");
        String appendEvent = buildEventJson(2, "ChatController.appendAgentText('t0','main','hello','12:34')");
        String prefix = gsonEncode("ChatController.appendAgentText('t0','main',");

        assertTrue(ChatWebServer.eventJsStartsWith(appendEvent, prefix));
        assertFalse(ChatWebServer.eventJsStartsWith(toolEvent, prefix));
    }

    @Test
    void compaction_prefixIsolatesToSpecificTurn() {
        String eventT0 = buildEventJson(1, "ChatController.appendAgentText('t0','main','hello','12:34')");
        String eventT1 = buildEventJson(2, "ChatController.appendAgentText('t1','main','world','12:35')");
        String prefix = gsonEncode("ChatController.appendAgentText('t0','main',");

        assertTrue(ChatWebServer.eventJsStartsWith(eventT0, prefix));
        assertFalse(ChatWebServer.eventJsStartsWith(eventT1, prefix));
    }

    @Test
    void compaction_endToEnd_buildAndMatch() {
        // Simulate the full compaction flow: build prefix from finalize → encode → match
        String finalizeJs = "ChatController.finalizeAgentText('t5','main','base64html')";
        String rawPrefix = ChatWebServer.buildStreamingPrefix(
            finalizeJs,
            "ChatController.finalizeAgentText(",
            "ChatController.appendAgentText("
        );
        assertNotNull(rawPrefix);
        String encodedPrefix = rawPrefix.replace("'", "\\u0027");

        String streamEvent = buildEventJson(10, "ChatController.appendAgentText('t5','main','chunk','12:00')");
        String otherEvent = buildEventJson(11, "ChatController.appendAgentText('t4','main','old','12:00')");
        String toolEvent = buildEventJson(12, "ChatController.addToolCall('t5','main','tool1','bash')");

        assertTrue(ChatWebServer.eventJsStartsWith(streamEvent, encodedPrefix));
        assertFalse(ChatWebServer.eventJsStartsWith(otherEvent, encodedPrefix));
        assertFalse(ChatWebServer.eventJsStartsWith(toolEvent, encodedPrefix));
    }

    private static String buildEventJson(int seq, String js) {
        return "{\"seq\":" + seq + ",\"js\":" + GSON.toJson(js) + "}";
    }

    /**
     * Encode a JS prefix string the same way GSON does in the event log.
     * GSON HTML-escapes single quotes as {@code \u0027}.
     */
    @SuppressWarnings("UnicodeEscape") // intentional: matches GSON's HTML-safe encoding of single quotes
    private static String gsonEncode(String jsPrefix) {
        return jsPrefix.replace("'", "\\u0027");
    }
}
