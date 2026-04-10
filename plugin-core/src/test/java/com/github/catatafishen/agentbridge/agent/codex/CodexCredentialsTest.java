package com.github.catatafishen.agentbridge.agent.codex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CodexCredentials}.
 */
class CodexCredentialsTest {

    @TempDir
    Path tempDir;

    private String originalUserHome;

    @BeforeEach
    void redirectUserHome() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", originalUserHome);
    }

    private void writeAuth(String json) throws IOException {
        Path codexDir = tempDir.resolve(".codex");
        Files.createDirectories(codexDir);
        Files.writeString(codexDir.resolve("auth.json"), json, StandardCharsets.UTF_8);
    }

    // ── file absent ───────────────────────────────────────────────────────────

    @Test
    void notLoggedInWhenFileDoesNotExist() {
        CodexCredentials creds = CodexCredentials.read();
        assertFalse(creds.isLoggedIn());
        assertNull(creds.getDisplayName());
    }

    // ── API key auth ──────────────────────────────────────────────────────────

    @Test
    void loggedInWithApiKey() throws IOException {
        writeAuth("{\"api_key\":\"sk-abc123\"}");

        CodexCredentials creds = CodexCredentials.read();
        assertTrue(creds.isLoggedIn());
        assertNull(creds.getDisplayName());
    }

    @Test
    void notLoggedInWithBlankApiKey() throws IOException {
        writeAuth("{\"api_key\":\"\"}");

        assertFalse(CodexCredentials.read().isLoggedIn());
    }

    @Test
    void notLoggedInWithNullApiKey() throws IOException {
        writeAuth("{\"api_key\":null}");

        assertFalse(CodexCredentials.read().isLoggedIn());
    }

    // ── nested tokens format (device auth) ────────────────────────────────────

    @Test
    void loggedInWithNestedTokensFormat() throws IOException {
        writeAuth("""
            {
              "auth_mode": "chatgpt",
              "tokens": { "access_token": "tok-device-xyz" },
              "last_refresh": "2024-01-01"
            }
            """);

        assertTrue(CodexCredentials.read().isLoggedIn());
    }

    @Test
    void notLoggedInWhenNestedTokenIsBlank() throws IOException {
        writeAuth("{\"tokens\":{\"access_token\":\"\"}}");

        assertFalse(CodexCredentials.read().isLoggedIn());
    }

    // ── flat OAuth / ChatGPT format ───────────────────────────────────────────

    @Test
    void loggedInWithFlatOAuthFormat() throws IOException {
        long futureSeconds = Instant.now().plus(365, ChronoUnit.DAYS).getEpochSecond();
        writeAuth("{\"access_token\":\"tok-oauth\",\"expires_at\":" + futureSeconds + ",\"email\":\"user@example.com\"}");

        CodexCredentials creds = CodexCredentials.read();
        assertTrue(creds.isLoggedIn());
        assertEquals("user@example.com", creds.getDisplayName());
    }

    @Test
    void notLoggedInWhenFlatOAuthExpired() throws IOException {
        long pastSeconds = Instant.now().minus(365, ChronoUnit.DAYS).getEpochSecond();
        writeAuth("{\"access_token\":\"tok-old\",\"expires_at\":" + pastSeconds + "}");

        assertFalse(CodexCredentials.read().isLoggedIn());
    }

    @Test
    void loggedInWithFlatOAuthWhenNoExpiresAt() throws IOException {
        writeAuth("{\"access_token\":\"tok-no-expiry\"}");

        // expires_at = 0 means no expiry enforced
        assertTrue(CodexCredentials.read().isLoggedIn());
    }

    @Test
    void notLoggedInWithBlankFlatToken() throws IOException {
        writeAuth("{\"access_token\":\"\"}");

        assertFalse(CodexCredentials.read().isLoggedIn());
    }

    // ── error handling ────────────────────────────────────────────────────────

    @Test
    void notLoggedInOnMalformedJson() throws IOException {
        writeAuth("this is not JSON {{{");

        CodexCredentials creds = CodexCredentials.read();
        assertFalse(creds.isLoggedIn());
    }

    @Test
    void notLoggedInOnEmptyFile() throws IOException {
        writeAuth("");

        CodexCredentials creds = CodexCredentials.read();
        assertFalse(creds.isLoggedIn());
    }
}
