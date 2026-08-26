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
    void buildCommand_v2_returnsCorrectArgOrder() {
        List<String> cmd = KiroClient.buildCommandStatic("v2");
        assertEquals(List.of("kiro-cli", "acp", "--agent", "intellij-task", "--trust-all-tools"), cmd);
    }

    @Test
    void buildCommand_v2_agentAfterAcp() {
        List<String> cmd = KiroClient.buildCommandStatic("v2");
        int acpIdx = cmd.indexOf("acp");
        int agentIdx = cmd.indexOf("--agent");
        assertTrue(agentIdx > acpIdx, "--agent must come after acp subcommand");
    }

    @Test
    void buildCommand_v3_usesAgentEngine() {
        List<String> cmd = KiroClient.buildCommandStatic("v3");
        assertEquals(List.of("kiro-cli", "acp", "--agent-engine", "v3"), cmd);
        assertFalse(cmd.contains("--agent"), "--agent must not be present in v3 command");
        assertFalse(cmd.contains("--trust-all-tools"),
            "--trust-all-tools not supported in v3; trust is set via autopilot:true in session/new");
    }

    @Test
    void buildCommand_defaultFallback_isV2() {
        // The deprecated no-arg overload must still return the v2 command.
        @SuppressWarnings("deprecation")
        List<String> cmd = KiroClient.buildCommandStatic();
        assertEquals(KiroClient.buildCommandStatic("v2"), cmd);
    }

    // ── supportsHttpMcp (version gating) ────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "2.14.1, true",
        "2.14.2, true",
        "2.15.0, true",
        "3.0.0, true",
        "2.14.0, false",
        "2.13.9, false",
        "2.10.0, false",
        "1.99.99, false",
        "2.14, false",
    })
    void supportsHttpMcp_versionThreshold(String version, boolean expected) {
        assertEquals(expected, KiroClient.supportsHttpMcp(version));
    }

    @Test
    void supportsHttpMcp_nullIsFalse() {
        assertFalse(KiroClient.supportsHttpMcp(null));
    }

    @Test
    void supportsHttpMcp_blankIsFalse() {
        assertFalse(KiroClient.supportsHttpMcp("   "));
    }

    @Test
    void supportsHttpMcp_garbageIsFalse() {
        assertFalse(KiroClient.supportsHttpMcp("not-a-version"));
    }

    @Test
    void supportsHttpMcp_toleratesSuffix() {
        assertTrue(KiroClient.supportsHttpMcp("2.14.1-beta"));
        assertTrue(KiroClient.supportsHttpMcp("2.15.0+build.7"));
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

    // ── readKiroProfileArn ──────────────────────────────────────────────

    @Test
    void readKiroProfileArn_extractsArnFromStateTable() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(sqlite3Available(),
            "sqlite3 CLI not available on this machine");
        java.nio.file.Path db = java.nio.file.Files.createTempFile("kiro-test", ".sqlite3");
        try {
            runSqlite(db,
                "CREATE TABLE state (key TEXT PRIMARY KEY, value BLOB);",
                "INSERT INTO state(key,value) VALUES('api.codewhisperer.profile'," +
                    "'{\"arn\":\"arn:aws:codewhisperer:us-east-1:123456789012:profile/ABCDEFGH\"," +
                    "\"profile_name\":\"my-profile\"}');");
            assertEquals("arn:aws:codewhisperer:us-east-1:123456789012:profile/ABCDEFGH",
                KiroClient.readKiroProfileArn(db));
        } finally {
            java.nio.file.Files.deleteIfExists(db);
        }
    }

    // ── isTokenFresh ────────────────────────────────────────────────────

    @Test
    void isTokenFresh_trueWhenWellBeyondBuffer() {
        java.time.Instant now = java.time.Instant.parse("2026-08-26T06:00:00Z");
        // Expires 1 hour out, buffer 200s → plenty of life left.
        assertTrue(KiroClient.isTokenFresh("2026-08-26T07:00:00Z", now, 200_000L));
    }

    @Test
    void isTokenFresh_falseWhenAlreadyExpired() {
        java.time.Instant now = java.time.Instant.parse("2026-08-26T06:25:51Z");
        // Mirrors the observed failure: token expired ~2.5 min before the callback fired.
        assertFalse(KiroClient.isTokenFresh("2026-08-26T06:23:23.858398Z", now, 200_000L));
    }

    @Test
    void isTokenFresh_falseWhenInsideRefreshBuffer() {
        java.time.Instant now = java.time.Instant.parse("2026-08-26T06:00:00Z");
        // Expires in 150s, which is inside the 200s buffer → must refresh.
        assertFalse(KiroClient.isTokenFresh("2026-08-26T06:02:30Z", now, 200_000L));
    }

    @Test
    void isTokenFresh_trueJustOutsideBuffer() {
        java.time.Instant now = java.time.Instant.parse("2026-08-26T06:00:00Z");
        // Expires in 201s, just past the 200s buffer → still fresh.
        assertTrue(KiroClient.isTokenFresh("2026-08-26T06:03:21Z", now, 200_000L));
    }

    @Test
    void isTokenFresh_falseForNullBlankOrUnparseable() {
        java.time.Instant now = java.time.Instant.parse("2026-08-26T06:00:00Z");
        assertFalse(KiroClient.isTokenFresh(null, now, 200_000L));
        assertFalse(KiroClient.isTokenFresh("", now, 200_000L));
        assertFalse(KiroClient.isTokenFresh("   ", now, 200_000L));
        assertFalse(KiroClient.isTokenFresh("not-a-timestamp", now, 200_000L));
    }

    @Test
    void readKiroProfileArn_returnsNullWhenProfileRowAbsent() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(sqlite3Available(),
            "sqlite3 CLI not available on this machine");
        java.nio.file.Path db = java.nio.file.Files.createTempFile("kiro-test", ".sqlite3");
        try {
            runSqlite(db, "CREATE TABLE state (key TEXT PRIMARY KEY, value BLOB);");
            assertNull(KiroClient.readKiroProfileArn(db));
        } finally {
            java.nio.file.Files.deleteIfExists(db);
        }
    }

    private static boolean sqlite3Available() {
        try {
            Process p = new ProcessBuilder("sqlite3", "-version").redirectErrorStream(true).start();
            return p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void runSqlite(java.nio.file.Path db, String... statements) throws Exception {
        for (String sql : statements) {
            Process p = new ProcessBuilder("sqlite3", db.toString(), sql)
                .redirectErrorStream(true).start();
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    // ── Reflection helpers ──────────────────────────────────────────────

    private static boolean invokeIsPanicLine(String line) throws Exception {
        Method m = KiroClient.class.getDeclaredMethod("isPanicLine", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, line);
    }

    // ── resolveShellType ────────────────────────────────────────────────

    @Test
    void resolveShellType_returnsBasenameOfShellEnvVar() {
        // SHELL is likely set in the test environment; just verify it returns something non-blank
        String result = KiroClient.resolveShellType();
        assertNotNull(result);
        assertFalse(result.isBlank());
        // Should not contain path separators
        assertFalse(result.contains("/"), "Expected basename only, got: " + result);
    }

    // ── resolveKiroDbPath ───────────────────────────────────────────────

    @Test
    void resolveKiroDbPath_returnsNonNullPath() {
        java.nio.file.Path path = KiroClient.resolveKiroDbPath();
        assertNotNull(path);
        assertTrue(path.toString().contains("kiro-cli"), "Path should contain kiro-cli, got: " + path);
        assertTrue(path.toString().endsWith("data.sqlite3"), "Path should end with data.sqlite3, got: " + path);
    }

    @Test
    void resolveKiroDbPath_containsUserHome() {
        java.nio.file.Path path = KiroClient.resolveKiroDbPath();
        String userHome = System.getProperty("user.home");
        assertTrue(path.toString().startsWith(userHome) ||
            path.toString().contains("kiro-cli"),
            "Expected path under user home or APPDATA/XDG, got: " + path);
    }

    // ── convertThinkingToThought ────────────────────────────────────────

    @Test
    void convertThinkingToThought_convertsChunkWithThinkingBlock() {
        var thinking = new com.github.catatafishen.agentbridge.model.ContentBlock.Thinking("some thought");
        var update = new com.github.catatafishen.agentbridge.model.SessionUpdate.AgentMessageChunk(
            List.of(thinking));
        var result = KiroClient.convertThinkingToThought(update);
        assertInstanceOf(com.github.catatafishen.agentbridge.model.SessionUpdate.AgentThoughtChunk.class,
            result, "Expected AgentThoughtChunk, got: " + result.getClass().getSimpleName());
    }

    @Test
    void convertThinkingToThought_passesThruTextChunkUnchanged() {
        var text = new com.github.catatafishen.agentbridge.model.ContentBlock.Text("hello");
        var update = new com.github.catatafishen.agentbridge.model.SessionUpdate.AgentMessageChunk(
            List.of(text));
        var result = KiroClient.convertThinkingToThought(update);
        assertSame(update, result, "Non-thinking chunk should pass through unchanged");
    }

    @Test
    void convertThinkingToThought_nonMessageChunkPassesThru() {
        var update = new com.github.catatafishen.agentbridge.model.SessionUpdate.SessionInfoChanged("title");
        var result = KiroClient.convertThinkingToThought(update);
        assertSame(update, result);
    }

    // ── extractPurposeFromArgs ──────────────────────────────────────────

    @Test
    void extractPurposeFromArgs_extractsPurposeFromJson() {
        String args = "{\"path\":\"src/Foo.java\",\"__tool_use_purpose\":\"Read the file\",\"start_line\":1}";
        assertEquals("Read the file", KiroClient.extractPurposeFromArgs(args));
    }

    @Test
    void extractPurposeFromArgs_returnsNullWhenKeyAbsent() {
        String args = "{\"path\":\"src/Foo.java\"}";
        assertNull(KiroClient.extractPurposeFromArgs(args));
    }

    @Test
    void extractPurposeFromArgs_returnsNullForNull() {
        assertNull(KiroClient.extractPurposeFromArgs(null));
    }

    @Test
    void extractPurposeFromArgs_returnsNullForEmptyString() {
        assertNull(KiroClient.extractPurposeFromArgs(""));
    }

    @Test
    void extractPurposeFromArgs_handlesKeyAtEnd() {
        String args = "{\"a\":1,\"__tool_use_purpose\":\"last\"}";
        assertEquals("last", KiroClient.extractPurposeFromArgs(args));
    }

    @Test
    void extractPurposeFromArgs_handlesKeyAtStart() {
        String args = "{\"__tool_use_purpose\":\"first\",\"a\":1}";
        assertEquals("first", KiroClient.extractPurposeFromArgs(args));
    }
}
