package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for static utility methods in {@link ChatWebServer}.
 * These methods handle SSE event parsing, query string processing,
 * and HTML/JS escaping — all critical for the web chat pipeline.
 */
class ChatWebServerTest {

    // ── buildStreamingPrefix ──────────────────────────────────────────────────

    @Test
    void buildStreamingPrefix_extractsTurnIdAndAgentId() {
        String js = "ChatController.finalizeAgentText('t0','main','<p>done</p>')";
        String result = ChatWebServer.buildStreamingPrefix(
            js,
            "ChatController.finalizeAgentText(",
            "ChatController.appendAgentText("
        );
        assertEquals("ChatController.appendAgentText('t0','main',", result);
    }

    @Test
    void buildStreamingPrefix_worksWithCollapseThinking() {
        String js = "ChatController.collapseThinking('t3','agent-2','summary')";
        String result = ChatWebServer.buildStreamingPrefix(
            js,
            "ChatController.collapseThinking(",
            "ChatController.addThinkingText("
        );
        assertEquals("ChatController.addThinkingText('t3','agent-2',", result);
    }

    @Test
    void buildStreamingPrefix_returnsNullWhenNoQuotes() {
        String result = ChatWebServer.buildStreamingPrefix(
            "ChatController.finalizeAgentText(no-quotes)",
            "ChatController.finalizeAgentText(",
            "ChatController.appendAgentText("
        );
        assertNull(result);
    }

    @Test
    void buildStreamingPrefix_returnsNullWhenOnlyOneQuotedArg() {
        String result = ChatWebServer.buildStreamingPrefix(
            "ChatController.finalizeAgentText('t0')",
            "ChatController.finalizeAgentText(",
            "ChatController.appendAgentText("
        );
        assertNull(result);
    }

    @Test
    void buildStreamingPrefix_returnsNullWhenMissingClosingQuote() {
        String result = ChatWebServer.buildStreamingPrefix(
            "ChatController.finalizeAgentText('t0','main",
            "ChatController.finalizeAgentText(",
            "ChatController.appendAgentText("
        );
        assertNull(result);
    }

    // ── eventJsStartsWith ────────────────────────────────────────────────────

    @Test
    void eventJsStartsWith_matchesWhenPrefixIsPresent() {
        String eventJson = "{\"seq\":5,\"js\":\"ChatController.appendAgentText('t0','main','hello')\"}";
        assertTrue(ChatWebServer.eventJsStartsWith(eventJson, "ChatController.appendAgentText("));
    }

    @Test
    void eventJsStartsWith_returnsFalseForDifferentPrefix() {
        String eventJson = "{\"seq\":5,\"js\":\"ChatController.appendAgentText('t0','main','hello')\"}";
        assertFalse(ChatWebServer.eventJsStartsWith(eventJson, "ChatController.collapseThinking("));
    }

    @Test
    void eventJsStartsWith_returnsFalseWhenNoJsField() {
        assertFalse(ChatWebServer.eventJsStartsWith("{\"seq\":5}", "anything"));
    }

    @Test
    void eventJsStartsWith_handlesEncodedQuotes() {
        String eventJson = "{\"js\":\"ChatController.appendAgentText(\\u0027t0\\u0027,\\u0027main\\u0027,\\u0027x\\u0027)\"}";
        assertTrue(ChatWebServer.eventJsStartsWith(eventJson, "ChatController.appendAgentText(\\u0027t0\\u0027,\\u0027main\\u0027,"));
    }

    // ── parseFromQuery ───────────────────────────────────────────────────────

    @Test
    void parseFromQuery_extractsFromParameter() throws Exception {
        assertEquals(42, invokeParseFromQuery("from=42"));
    }

    @Test
    void parseFromQuery_returnsZeroForNull() throws Exception {
        assertEquals(0, invokeParseFromQuery(null));
    }

    @Test
    void parseFromQuery_returnsZeroForMissingParam() throws Exception {
        assertEquals(0, invokeParseFromQuery("other=5"));
    }

    @Test
    void parseFromQuery_handlesMultipleParams() throws Exception {
        assertEquals(10, invokeParseFromQuery("page=2&from=10&limit=50"));
    }

    @Test
    void parseFromQuery_returnsZeroForNonNumeric() throws Exception {
        assertEquals(0, invokeParseFromQuery("from=abc"));
    }

    // ── extractSeq ───────────────────────────────────────────────────────────

    @Test
    void extractSeq_extractsSequenceNumber() throws Exception {
        assertEquals(42, invokeExtractSeq("{\"seq\":42,\"js\":\"...\"}"));
    }

    @Test
    void extractSeq_returnsZeroWhenMissing() throws Exception {
        assertEquals(0, invokeExtractSeq("{\"js\":\"...\"}"));
    }

    @Test
    void extractSeq_returnsZeroForNonNumeric() throws Exception {
        assertEquals(0, invokeExtractSeq("{\"seq\":\"abc\"}"));
    }

    @Test
    void extractSeq_handlesLargeNumbers() throws Exception {
        assertEquals(999999, invokeExtractSeq("{\"seq\":999999}"));
    }

    // ── extractFirstStringArg ────────────────────────────────────────────────

    @Test
    void extractFirstStringArg_extractsQuotedArg() throws Exception {
        assertEquals("t0", invokeExtractFirstStringArg("ChatController.fn('t0','more')"));
    }

    @Test
    void extractFirstStringArg_returnsEmptyWhenNoQuotes() throws Exception {
        assertEquals("", invokeExtractFirstStringArg("ChatController.fn(42)"));
    }

    @Test
    void extractFirstStringArg_returnsEmptyWhenMissingClosingQuote() throws Exception {
        assertEquals("", invokeExtractFirstStringArg("ChatController.fn('unclosed"));
    }

    // ── escHtml ──────────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "'hello',         'hello'",
        "'a & b',         'a &amp; b'",
        "'<script>',      '&lt;script&gt;'",
        "'\"attr\"',      '&quot;attr&quot;'",
        "'a & <b> \"c\"', 'a &amp; &lt;b&gt; &quot;c&quot;'",
    })
    void escHtml_escapesSpecialCharacters(String input, String expected) throws Exception {
        assertEquals(expected, invokeEscHtml(input));
    }

    // ── escJs ────────────────────────────────────────────────────────────────

    @Test
    void escJs_wrapsInDoubleQuotesAndEscapes() throws Exception {
        assertEquals("\"hello\"", invokeEscJs("hello"));
    }

    @Test
    void escJs_escapesBackslashesAndQuotes() throws Exception {
        assertEquals("\"a\\\\b\\\"c\"", invokeEscJs("a\\b\"c"));
    }

    @Test
    void escJs_escapesNewlines() throws Exception {
        assertEquals("\"line1\\nline2\\rline3\"", invokeEscJs("line1\nline2\rline3"));
    }

    // ── jsonString ───────────────────────────────────────────────────────────

    @Test
    void jsonString_extractsStringValue() throws Exception {
        assertEquals("hello", invokeJsonString("{\"key\":\"hello\"}", "key"));
    }

    @Test
    void jsonString_returnsNullForMissingKey() throws Exception {
        assertNull(invokeJsonString("{\"other\":\"value\"}", "key"));
    }

    @Test
    void jsonString_returnsNullForInvalidJson() throws Exception {
        assertNull(invokeJsonString("not json", "key"));
    }

    @Test
    void jsonString_convertsNumberToString() throws Exception {
        assertNotNull(invokeJsonString("{\"key\":42}", "key"));
    }

    // ── Reflection helpers ───────────────────────────────────────────────────

    private static int invokeParseFromQuery(String query) throws Exception {
        Method m = ChatWebServer.class.getDeclaredMethod("parseFromQuery", String.class);
        m.setAccessible(true);
        return (int) m.invoke(null, query);
    }

    private static int invokeExtractSeq(String json) throws Exception {
        Method m = ChatWebServer.class.getDeclaredMethod("extractSeq", String.class);
        m.setAccessible(true);
        return (int) m.invoke(null, json);
    }

    private static String invokeExtractFirstStringArg(String js) throws Exception {
        Method m = ChatWebServer.class.getDeclaredMethod("extractFirstStringArg", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, js);
    }

    private static String invokeEscHtml(String s) throws Exception {
        Method m = ChatWebServer.class.getDeclaredMethod("escHtml", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }

    private static String invokeEscJs(String s) throws Exception {
        Method m = ChatWebServer.class.getDeclaredMethod("escJs", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }

    private static String invokeJsonString(String body, String key) throws Exception {
        Method m = ChatWebServer.class.getDeclaredMethod("jsonString", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, body, key);
    }
}
