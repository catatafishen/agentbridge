package com.github.catatafishen.agentbridge.client.acp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KiroClientTest {

    // ── resolveToolIdStatic ─────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "@agentbridge/read_file, read_file",
        "@agentbridge/search_text, search_text",
        "@agentbridge/git_status, git_status",
        "@agentbridge/web_fetch, web_fetch",
    })
    void resolveToolId_stripsAgentbridgePrefix(String input, String expected) {
        assertEquals(expected, KiroClient.resolveToolIdStatic(input));
    }

    @ParameterizedTest
    @CsvSource({
        "Running: @agentbridge/read_file, read_file",
        "Running: @agentbridge/git_commit, git_commit",
    })
    void resolveToolId_stripsRunningPrefix(String input, String expected) {
        assertEquals(expected, KiroClient.resolveToolIdStatic(input));
    }

    @Test
    void resolveToolId_mapsSearchingTheWeb() {
        assertEquals("web_search", KiroClient.resolveToolIdStatic("Searching the web"));
    }

    @Test
    void resolveToolId_mapsFetchingWebContent() {
        assertEquals("web_fetch", KiroClient.resolveToolIdStatic("Fetching web content"));
    }

    @Test
    void resolveToolId_unknownPassthrough() {
        assertEquals("some_unknown_tool", KiroClient.resolveToolIdStatic("some_unknown_tool"));
    }

    // ── isMcpToolTitleStatic ────────────────────────────────────────────

    @Test
    void isMcpToolTitle_runningPrefix() {
        assertTrue(KiroClient.isMcpToolTitleStatic("Running: @agentbridge/read_file"));
    }

    @Test
    void isMcpToolTitle_directPrefix() {
        assertTrue(KiroClient.isMcpToolTitleStatic("@agentbridge/git_status"));
    }

    @Test
    void isMcpToolTitle_humanReadableTitle() {
        assertFalse(KiroClient.isMcpToolTitleStatic("Searching the web"));
    }

    @Test
    void isMcpToolTitle_emptyString() {
        assertFalse(KiroClient.isMcpToolTitleStatic(""));
    }

    @Test
    void isMcpToolTitle_randomText() {
        assertFalse(KiroClient.isMcpToolTitleStatic("agentbridge_ without at sign"));
    }

    // ── buildCommandStatic ──────────────────────────────────────────────

    @Test
    void buildCommand_returnsCorrectArgOrder() {
        List<String> cmd = KiroClient.buildCommandStatic();
        assertEquals(List.of("kiro-cli", "acp", "--agent", "intellij-task", "--trust-all-tools"), cmd);
    }

    @Test
    void buildCommand_agentAfterAcp() {
        List<String> cmd = KiroClient.buildCommandStatic();
        int acpIdx = cmd.indexOf("acp");
        int agentIdx = cmd.indexOf("--agent");
        assertTrue(agentIdx > acpIdx, "--agent must come after acp subcommand");
    }

    // ── buildEnvironmentStatic ──────────────────────────────────────────

    @Test
    void buildEnvironment_includesRustBacktrace() {
        Map<String, String> env = KiroClient.buildEnvironmentStatic();
        assertEquals("1", env.get("RUST_BACKTRACE"));
    }

    @Test
    void buildEnvironment_onlyOneEntry() {
        assertEquals(1, KiroClient.buildEnvironmentStatic().size());
    }

    // ── isPanicLine (private static) ────────────────────────────────────

    @Test
    void isPanicLine_panickedAt() throws Exception {
        assertTrue(invokeIsPanicLine("thread 'main' panicked at 'index out of bounds', src/main.rs:42:5"));
    }

    @Test
    void isPanicLine_applicationPanicked() throws Exception {
        assertTrue(invokeIsPanicLine("The application panicked (crash handler installed)"));
    }

    @Test
    void isPanicLine_normalLine() throws Exception {
        assertFalse(invokeIsPanicLine("Starting Kiro server on port 3000"));
    }

    @Test
    void isPanicLine_emptyLine() throws Exception {
        assertFalse(invokeIsPanicLine(""));
    }

    @Test
    void isPanicLine_containsPanickedAtMiddle() throws Exception {
        assertTrue(invokeIsPanicLine("error: thread 'tokio-runtime' panicked at core/event.rs:128"));
    }

    // ── stripAnsi (package-private static) ────────────────────────────

    @Test
    void stripAnsi_removesColorCodes() {
        assertEquals(
            "The application panicked (crashed).",
            KiroClient.stripAnsi("\u001b[31mThe application panicked (crashed).\u001b[0m")
        );
    }

    @Test
    void stripAnsi_removesMultipleCodes() {
        assertEquals(
            "thread 'agent' panicked at src/main.rs:42",
            KiroClient.stripAnsi("\u001b[33mthread 'agent' panicked at \u001b[35msrc/main.rs\u001b[0m:\u001b[35m42\u001b[0m")
        );
    }

    @Test
    void stripAnsi_noOpForCleanString() {
        assertEquals("no ansi here", KiroClient.stripAnsi("no ansi here"));
    }

    @Test
    void stripAnsi_emptyString() {
        assertEquals("", KiroClient.stripAnsi(""));
    }

    @Test
    void stripAnsi_boldAndReset() {
        assertEquals("bold text", KiroClient.stripAnsi("\u001b[1mbold text\u001b[0m"));
    }

    // ── Reflection helpers ──────────────────────────────────────────────

    private static boolean invokeIsPanicLine(String line) throws Exception {
        Method m = KiroClient.class.getDeclaredMethod("isPanicLine", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, line);
    }
}
