package com.github.catatafishen.agentbridge.services;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EventLogCompactor} — the utility that removes redundant
 * streaming events from the in-memory event log when finalization events arrive.
 *
 * <p>All methods are package-private statics tested directly without a running server.
 */
class EventLogCompactorTest {

    private static final Gson GSON = new Gson();

    // ── compactStreamingEvents ───────────────────────────────────────────

    @Test
    void compactStreamingEvents_removesRedundantAppendEventsOnFinalize() {
        List<String> log = new ArrayList<>();
        log.add(buildEventJson(1, "ChatController.appendAgentText('t0','main','chunk1','12:00')"));
        log.add(buildEventJson(2, "ChatController.appendAgentText('t0','main','chunk2','12:01')"));
        log.add(buildEventJson(3, "ChatController.addToolCall('t0','main','tool1','bash')"));

        String finalizeJs = "ChatController.finalizeAgentText('t0','main','base64html')";
        EventLogCompactor.compactStreamingEvents(finalizeJs, log);

        // append events removed, tool event preserved
        assertEquals(1, log.size());
        assertTrue(log.get(0).contains("addToolCall"));
    }

    @Test
    void compactStreamingEvents_removesRedundantThinkingEventsOnCollapse() {
        List<String> log = new ArrayList<>();
        log.add(buildEventJson(1, "ChatController.addThinkingText('t2','main','thinking...')"));
        log.add(buildEventJson(2, "ChatController.addThinkingText('t2','main','more thinking')"));
        log.add(buildEventJson(3, "ChatController.appendAgentText('t2','main','reply','12:05')"));

        String collapseJs = "ChatController.collapseThinking('t2','main','encoded')";
        EventLogCompactor.compactStreamingEvents(collapseJs, log);

        // thinking events removed, append event preserved
        assertEquals(1, log.size());
        assertTrue(log.get(0).contains("appendAgentText"));
    }

    @Test
    void compactStreamingEvents_emptyLogRemainsEmpty() {
        List<String> log = new ArrayList<>();
        EventLogCompactor.compactStreamingEvents(
                "ChatController.finalizeAgentText('t0','main','html')", log);
        assertTrue(log.isEmpty());
    }

    @Test
    void compactStreamingEvents_noMatchingEventsLeavesLogIntact() {
        List<String> log = new ArrayList<>();
        log.add(buildEventJson(1, "ChatController.addToolCall('t0','main','tool1','read_file')"));
        log.add(buildEventJson(2, "ChatController.setTitle('Hello')"));

        String finalizeJs = "ChatController.finalizeAgentText('t0','main','html')";
        EventLogCompactor.compactStreamingEvents(finalizeJs, log);

        assertEquals(2, log.size());
    }

    @Test
    void compactStreamingEvents_onlyRemovesMatchingTurn() {
        List<String> log = new ArrayList<>();
        log.add(buildEventJson(1, "ChatController.appendAgentText('t0','main','chunk','12:00')"));
        log.add(buildEventJson(2, "ChatController.appendAgentText('t1','main','other','12:01')"));

        String finalizeJs = "ChatController.finalizeAgentText('t0','main','html')";
        EventLogCompactor.compactStreamingEvents(finalizeJs, log);

        // t0 append removed, t1 append preserved
        assertEquals(1, log.size());
        assertTrue(log.get(0).contains("'t1'") || log.get(0).contains("\\u0027t1\\u0027"));
    }

    @Test
    void compactStreamingEvents_unrelatedJsCallDoesNothing() {
        List<String> log = new ArrayList<>();
        log.add(buildEventJson(1, "ChatController.appendAgentText('t0','main','hi','12:00')"));

        EventLogCompactor.compactStreamingEvents("ChatController.setTitle('x')", log);

        assertEquals(1, log.size());
    }

    @Test
    void compactStreamingEvents_malformedFinalizeDoesNothing() {
        List<String> log = new ArrayList<>();
        log.add(buildEventJson(1, "ChatController.appendAgentText('t0','main','hi','12:00')"));

        // No single-quoted args → buildStreamingPrefix returns null → no removal
        EventLogCompactor.compactStreamingEvents("ChatController.finalizeAgentText()", log);

        assertEquals(1, log.size());
    }

    // ── buildStreamingPrefix ─────────────────────────────────────────────

    @Test
    void buildStreamingPrefix_extractsTurnAndAgent() {
        String js = "ChatController.finalizeAgentText('t0','main','base64html')";
        String result = EventLogCompactor.buildStreamingPrefix(
                js,
                "ChatController.finalizeAgentText(",
                "ChatController.appendAgentText(");
        assertEquals("ChatController.appendAgentText('t0','main',", result);
    }

    @Test
    void buildStreamingPrefix_handlesMultiDigitTurnId() {
        String js = "ChatController.finalizeAgentText('t123','agent7','html')";
        String result = EventLogCompactor.buildStreamingPrefix(
                js,
                "ChatController.finalizeAgentText(",
                "ChatController.appendAgentText(");
        assertEquals("ChatController.appendAgentText('t123','agent7',", result);
    }

    @Test
    void buildStreamingPrefix_thinkingCollapse() {
        String js = "ChatController.collapseThinking('t3','main','encoded')";
        String result = EventLogCompactor.buildStreamingPrefix(
                js,
                "ChatController.collapseThinking(",
                "ChatController.addThinkingText(");
        assertEquals("ChatController.addThinkingText('t3','main',", result);
    }

    @Test
    void buildStreamingPrefix_returnsNullWhenNoQuotes() {
        assertNull(EventLogCompactor.buildStreamingPrefix(
                "ChatController.finalizeAgentText()",
                "ChatController.finalizeAgentText(",
                "ChatController.appendAgentText("));
    }

    @Test
    void buildStreamingPrefix_returnsNullForOnlyOneQuotedArg() {
        // Only one pair of quotes → q3/q4 not found
        assertNull(EventLogCompactor.buildStreamingPrefix(
                "ChatController.finalizeAgentText('t0')",
                "ChatController.finalizeAgentText(",
                "ChatController.appendAgentText("));
    }

    @Test
    void buildStreamingPrefix_returnsNullWhenNoArgs() {
        assertNull(EventLogCompactor.buildStreamingPrefix(
                "ChatController.finalizeAgentText(null)",
                "ChatController.finalizeAgentText(",
                "ChatController.appendAgentText("));
    }

    @Test
    void buildStreamingPrefix_returnsNullForUnterminatedSecondArg() {
        // Three quotes but no fourth → q4 not found
        assertNull(EventLogCompactor.buildStreamingPrefix(
                "ChatController.finalizeAgentText('t0','main)",
                "ChatController.finalizeAgentText(",
                "ChatController.appendAgentText("));
    }

    // ── eventJsStartsWith ────────────────────────────────────────────────

    @Test
    void eventJsStartsWith_matchesGsonEncodedPrefix() {
        String eventJson = buildEventJson(1, "ChatController.appendAgentText('t0','main','hello','12:34')");
        String prefix = gsonEncode("ChatController.appendAgentText('t0','main',");
        assertTrue(EventLogCompactor.eventJsStartsWith(eventJson, prefix));
    }

    @Test
    void eventJsStartsWith_doesNotMatchDifferentTurn() {
        String eventJson = buildEventJson(2, "ChatController.appendAgentText('t1','main','hi','12:34')");
        String prefix = gsonEncode("ChatController.appendAgentText('t0','main',");
        assertFalse(EventLogCompactor.eventJsStartsWith(eventJson, prefix));
    }

    @Test
    void eventJsStartsWith_doesNotMatchDifferentMethod() {
        String eventJson = buildEventJson(3, "ChatController.addToolCall('t0','main','tool1','bash')");
        String prefix = gsonEncode("ChatController.appendAgentText('t0','main',");
        assertFalse(EventLogCompactor.eventJsStartsWith(eventJson, prefix));
    }

    @Test
    void eventJsStartsWith_returnsFalseWhenNoJsField() {
        assertFalse(EventLogCompactor.eventJsStartsWith("{\"seq\":1}", "anything"));
    }

    @Test
    void eventJsStartsWith_returnsFalseForEmptyJson() {
        assertFalse(EventLogCompactor.eventJsStartsWith("{}", "prefix"));
    }

    @Test
    void eventJsStartsWith_matchesThinkingEvents() {
        String eventJson = buildEventJson(4, "ChatController.addThinkingText('t0','main','thinking...')");
        String prefix = gsonEncode("ChatController.addThinkingText('t0','main',");
        assertTrue(EventLogCompactor.eventJsStartsWith(eventJson, prefix));
    }

    @Test
    void eventJsStartsWith_emptyPrefixAlwaysMatches() {
        String eventJson = buildEventJson(1, "anything");
        assertTrue(EventLogCompactor.eventJsStartsWith(eventJson, ""));
    }

    // ── parseFromQuery ───────────────────────────────────────────────────

    @Test
    void parseFromQuery_parsesSimpleFrom() {
        assertEquals(5, EventLogCompactor.parseFromQuery("from=5"));
    }

    @Test
    void parseFromQuery_parsesZero() {
        assertEquals(0, EventLogCompactor.parseFromQuery("from=0"));
    }

    @Test
    void parseFromQuery_parsesLargeNumber() {
        assertEquals(99999, EventLogCompactor.parseFromQuery("from=99999"));
    }

    @Test
    void parseFromQuery_returnsZeroForNullQuery() {
        assertEquals(0, EventLogCompactor.parseFromQuery(null));
    }

    @Test
    void parseFromQuery_returnsZeroWhenFromMissing() {
        assertEquals(0, EventLogCompactor.parseFromQuery("page=2&size=10"));
    }

    @Test
    void parseFromQuery_returnsZeroForNonNumericFrom() {
        assertEquals(0, EventLogCompactor.parseFromQuery("from=abc"));
    }

    @Test
    void parseFromQuery_parsesFromAmongOtherParams() {
        assertEquals(42, EventLogCompactor.parseFromQuery("page=1&from=42&size=10"));
    }

    @Test
    void parseFromQuery_returnsZeroForEmptyFromValue() {
        assertEquals(0, EventLogCompactor.parseFromQuery("from="));
    }

    @Test
    void parseFromQuery_returnsZeroForEmptyQuery() {
        assertEquals(0, EventLogCompactor.parseFromQuery(""));
    }

    @Test
    void parseFromQuery_parsesNegativeValue() {
        assertEquals(-1, EventLogCompactor.parseFromQuery("from=-1"));
    }

    // ── extractSeq ───────────────────────────────────────────────────────

    @Test
    void extractSeq_extractsSimpleSeq() {
        assertEquals(42, EventLogCompactor.extractSeq("{\"seq\":42,\"js\":\"...\"}"));
    }

    @Test
    void extractSeq_extractsZeroSeq() {
        assertEquals(0, EventLogCompactor.extractSeq("{\"seq\":0,\"js\":\"...\"}"));
    }

    @Test
    void extractSeq_extractsLargeSeq() {
        assertEquals(123456, EventLogCompactor.extractSeq("{\"seq\":123456}"));
    }

    @Test
    void extractSeq_returnsZeroWhenMissing() {
        assertEquals(0, EventLogCompactor.extractSeq("{\"js\":\"something\"}"));
    }

    @Test
    void extractSeq_returnsZeroForNonNumericValue() {
        assertEquals(0, EventLogCompactor.extractSeq("{\"seq\":abc}"));
    }

    @Test
    void extractSeq_handlesSeqAtEndOfJson() {
        assertEquals(7, EventLogCompactor.extractSeq("{\"js\":\"x\",\"seq\":7}"));
    }

    @Test
    void extractSeq_extractsFromRealEventJson() {
        String event = buildEventJson(99, "ChatController.setTitle('hi')");
        assertEquals(99, EventLogCompactor.extractSeq(event));
    }

    @Test
    void extractSeq_returnsZeroForEmptyObject() {
        assertEquals(0, EventLogCompactor.extractSeq("{}"));
    }

    // ── extractFirstStringArg ────────────────────────────────────────────

    @Test
    void extractFirstStringArg_extractsSimpleArg() {
        assertEquals("hello", EventLogCompactor.extractFirstStringArg("func('hello')"));
    }

    @Test
    void extractFirstStringArg_extractsFirstArgOnly() {
        assertEquals("t0", EventLogCompactor.extractFirstStringArg(
                "ChatController.finalizeAgentText('t0','main','html')"));
    }

    @Test
    void extractFirstStringArg_returnsEmptyForNoQuotes() {
        assertEquals("", EventLogCompactor.extractFirstStringArg("func(42)"));
    }

    @Test
    void extractFirstStringArg_returnsEmptyForSingleQuote() {
        assertEquals("", EventLogCompactor.extractFirstStringArg("func('unterminated"));
    }

    @Test
    void extractFirstStringArg_returnsEmptyForEmptyString() {
        assertEquals("", EventLogCompactor.extractFirstStringArg(""));
    }

    @Test
    void extractFirstStringArg_handlesEmptyQuotedArg() {
        assertEquals("", EventLogCompactor.extractFirstStringArg("func('')"));
    }

    @Test
    void extractFirstStringArg_handlesArgWithSpecialChars() {
        assertEquals("/path/to/file.java",
                EventLogCompactor.extractFirstStringArg("read('/path/to/file.java')"));
    }

    @Test
    void extractFirstStringArg_handlesSpacesInArg() {
        assertEquals("hello world",
                EventLogCompactor.extractFirstStringArg("func('hello world')"));
    }

    // ── End-to-end integration ───────────────────────────────────────────

    @Test
    void endToEnd_buildPrefixThenCompact() {
        // Build prefix from finalization JS
        String finalizeJs = "ChatController.finalizeAgentText('t5','main','base64html')";
        String rawPrefix = EventLogCompactor.buildStreamingPrefix(
                finalizeJs,
                "ChatController.finalizeAgentText(",
                "ChatController.appendAgentText(");
        assertNotNull(rawPrefix);

        // Simulate event log with mixed events
        List<String> log = new ArrayList<>();
        log.add(buildEventJson(1, "ChatController.appendAgentText('t5','main','chunk1','12:00')"));
        log.add(buildEventJson(2, "ChatController.appendAgentText('t5','main','chunk2','12:01')"));
        log.add(buildEventJson(3, "ChatController.appendAgentText('t4','main','old','12:00')"));
        log.add(buildEventJson(4, "ChatController.addToolCall('t5','main','tool1','bash')"));
        log.add(buildEventJson(5, "ChatController.addThinkingText('t5','main','thinking')"));

        EventLogCompactor.compactStreamingEvents(finalizeJs, log);

        // Only t5 append events removed; t4 append, tool call, and thinking preserved
        assertEquals(3, log.size());
        assertFalse(log.stream().anyMatch(e -> e.contains("\\u0027t5\\u0027") && e.contains("appendAgentText")));
        assertTrue(log.stream().anyMatch(e -> e.contains("addToolCall")));
        assertTrue(log.stream().anyMatch(e -> e.contains("addThinkingText")));
    }

    @Test
    void endToEnd_collapseThinkingThenCompact() {
        String collapseJs = "ChatController.collapseThinking('t1','main','summary')";

        List<String> log = new ArrayList<>();
        log.add(buildEventJson(1, "ChatController.addThinkingText('t1','main','thought1')"));
        log.add(buildEventJson(2, "ChatController.addThinkingText('t1','main','thought2')"));
        log.add(buildEventJson(3, "ChatController.appendAgentText('t1','main','reply','12:05')"));

        EventLogCompactor.compactStreamingEvents(collapseJs, log);

        assertEquals(1, log.size());
        assertTrue(log.get(0).contains("appendAgentText"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static String buildEventJson(int seq, String js) {
        return "{\"seq\":" + seq + ",\"js\":" + GSON.toJson(js) + "}";
    }

    /**
     * Encode a JS prefix string the same way GSON does — HTML-escapes single quotes
     * as {@code \u0027}.
     */
    @SuppressWarnings("UnicodeEscape")
    private static String gsonEncode(String jsPrefix) {
        return jsPrefix.replace("'", "\\u0027");
    }
}
