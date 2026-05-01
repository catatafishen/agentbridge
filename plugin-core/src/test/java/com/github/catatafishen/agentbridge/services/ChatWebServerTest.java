package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.ui.MessageFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

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
    void escHtml_escapesSpecialCharacters(String input, String expected) {
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

    // ── TLS certificate generation ───────────────────────────────────────────

    @Test
    void generateCaCertificate_createsLoadableKeystore(@TempDir Path tempDir) throws Exception {
        String password = "test-password";
        Path caKeystore = tempDir.resolve("ca.p12");

        invokeGenerateCaCertificate(caKeystore, password);

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(caKeystore)) {
            keyStore.load(input, password.toCharArray());
        }
        assertTrue(keyStore.containsAlias("ca"));
        assertNotNull(keyStore.getCertificate("ca"));
    }

    // ── runProcess ───────────────────────────────────────────────────────────

    @Test
    void runProcess_reportsTimeoutAndStopsProcess() {
        IOException error = runFakeProcessExpectingIOException("sleep", 1);

        assertTrue(error.getMessage().contains("fake command timed out after 1 seconds"));
        assertTrue(error.getMessage().contains("starting sleep"));
    }

    @Test
    void runProcess_includesStdoutAndStderrWhenCommandFails() {
        IOException error = runFakeProcessExpectingIOException("fail", 10);

        assertTrue(error.getMessage().contains("fake command failed with exit code 7"));
        assertTrue(error.getMessage().contains("stdout marker"));
        assertTrue(error.getMessage().contains("stderr marker"));
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

    private static String invokeEscHtml(String s) {
        return MessageFormatter.INSTANCE.escapeHtml(s);
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

    private static void invokeGenerateCaCertificate(Path caKeystore, String password) throws Exception {
        Method m = ChatWebServer.class.getDeclaredMethod("generateCaCertificate", java.io.File.class, String.class);
        m.setAccessible(true);
        m.invoke(null, caKeystore.toFile(), password);
    }

    private static IOException runFakeProcessExpectingIOException(String mode, long timeoutSeconds) {
        try {
            ChatWebServer.runProcess("fake command", fakeProcessCommand(mode), timeoutSeconds);
        } catch (IOException e) {
            return e;
        }
        throw new AssertionError("Expected fake command to fail");
    }

    private static String[] fakeProcessCommand(String mode) {
        return new String[]{
            javaExecutable(),
            "-cp",
            System.getProperty("java.class.path"),
            FakeProcess.class.getName(),
            mode
        };
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    public static final class FakeProcess {
        public static void main(String[] args) throws Exception {
            if ("sleep".equals(args[0])) {
                System.out.println("starting sleep");
                System.out.flush();
                new java.util.concurrent.CountDownLatch(1).await();
                return;
            }
            if ("fail".equals(args[0])) {
                System.out.println("stdout marker");
                System.err.println("stderr marker");
                System.exit(7);
                return;
            }
            throw new IllegalArgumentException("Unknown fake process mode: " + args[0]);
        }
    }
}
