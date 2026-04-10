package com.github.catatafishen.agentbridge.agent.claude;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClaudeCliCredentials}.
 */
class ClaudeCliCredentialsTest {

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

    private Path createCredentialsFile(String json) throws IOException {
        Path claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Path file = claudeDir.resolve(".credentials.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    // ── file absent ───────────────────────────────────────────────────────────

    @Test
    void notLoggedInWhenFileDoesNotExist() {
        ClaudeCliCredentials creds = ClaudeCliCredentials.read();
        assertFalse(creds.isLoggedIn());
        assertNull(creds.getDisplayName());
    }

    // ── valid credentials ─────────────────────────────────────────────────────

    @Test
    void loggedInWithValidAccessToken() throws IOException {
        createCredentialsFile("""
            {
              "claudeAiOauth": { "accessToken": "tok-abc123" }
            }
            """);

        ClaudeCliCredentials creds = ClaudeCliCredentials.read();
        assertTrue(creds.isLoggedIn());
        assertNull(creds.getDisplayName());
    }

    @Test
    void readsDisplayNameFromOauthAccount() throws IOException {
        createCredentialsFile("""
            {
              "oauthAccount": { "displayName": "Alice" },
              "claudeAiOauth": { "accessToken": "tok-xyz" }
            }
            """);

        ClaudeCliCredentials creds = ClaudeCliCredentials.read();
        assertTrue(creds.isLoggedIn());
        assertEquals("Alice", creds.getDisplayName());
    }

    @Test
    void fallsBackToEmailAddressWhenNoDisplayName() throws IOException {
        createCredentialsFile("""
            {
              "oauthAccount": { "emailAddress": "alice@example.com" },
              "claudeAiOauth": { "accessToken": "tok-xyz" }
            }
            """);

        ClaudeCliCredentials creds = ClaudeCliCredentials.read();
        assertTrue(creds.isLoggedIn());
        assertEquals("alice@example.com", creds.getDisplayName());
    }

    // ── invalid / incomplete credentials ─────────────────────────────────────

    @Test
    void notLoggedInWithoutClaudeAiOauth() throws IOException {
        createCredentialsFile("{\"oauthAccount\":{\"displayName\":\"Bob\"}}");

        assertFalse(ClaudeCliCredentials.read().isLoggedIn());
    }

    @Test
    void notLoggedInWithEmptyAccessToken() throws IOException {
        createCredentialsFile("{\"claudeAiOauth\":{\"accessToken\":\"\"}}");

        assertFalse(ClaudeCliCredentials.read().isLoggedIn());
    }

    @Test
    void notLoggedInWithNullAccessToken() throws IOException {
        createCredentialsFile("{\"claudeAiOauth\":{\"accessToken\":null}}");

        assertFalse(ClaudeCliCredentials.read().isLoggedIn());
    }

    @Test
    void notLoggedInOnMalformedJson() throws IOException {
        createCredentialsFile("not-valid-json{{{");

        // Must not throw; returns not-logged-in
        ClaudeCliCredentials creds = ClaudeCliCredentials.read();
        assertFalse(creds.isLoggedIn());
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Test
    void logoutReturnsFalseWhenFileAbsent() {
        assertFalse(ClaudeCliCredentials.logout());
    }

    @Test
    void logoutDeletesFileAndReturnsTrue() throws IOException {
        createCredentialsFile("{\"claudeAiOauth\":{\"accessToken\":\"tok\"}}");

        assertTrue(ClaudeCliCredentials.logout(), "logout must return true when file existed");
        assertFalse(Files.exists(ClaudeCliCredentials.credentialsPath()),
            "credentials file must be deleted after logout");
    }

    // ── path ──────────────────────────────────────────────────────────────────

    @Test
    void credentialsPathUsesCurrentUserHome() {
        Path expected = tempDir.resolve(".claude").resolve(".credentials.json");
        assertEquals(expected, ClaudeCliCredentials.credentialsPath());
    }
}
