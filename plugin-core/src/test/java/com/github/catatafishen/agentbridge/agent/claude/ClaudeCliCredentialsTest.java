package com.github.catatafishen.agentbridge.agent.claude;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ClaudeCliCredentials}.
 */
class ClaudeCliCredentialsTest {

    @TempDir
    Path tempDir;

    private String originalUserHome;
    private String originalOsName;

    @BeforeEach
    void redirectUserHome() {
        originalUserHome = System.getProperty("user.home");
        originalOsName = System.getProperty("os.name");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", originalUserHome);
        if (originalOsName == null) {
            System.clearProperty("os.name");
        } else {
            System.setProperty("os.name", originalOsName);
        }
    }

    private void createCredentialsFile(String json) throws IOException {
        Path claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Path file = claudeDir.resolve(".credentials.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    // ── file absent ───────────────────────────────────────────────────────────

    @Test
    void notLoggedInWhenFileDoesNotExist() {
        ClaudeCliCredentials creds = ClaudeCliCredentials.read();
        assertFalse(creds.isLoggedIn());
        assertNull(creds.getDisplayName());
    }

    @Test
    void fallsBackToMacOsKeychainWhenFileDoesNotExist() {
        System.setProperty("os.name", "Mac OS X");

        ClaudeCliCredentials creds = ClaudeCliCredentials.read(command -> {
            assertEquals(List.of("/usr/bin/security", "find-generic-password", "-s",
                "Claude Code-credentials", "-w"), command);
            return """
                {
                  "oauthAccount": { "displayName": "Alice" },
                  "claudeAiOauth": { "accessToken": "tok-keychain" }
                }
                """;
        });

        assertTrue(creds.isLoggedIn());
        assertEquals("Alice", creds.getDisplayName());
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

    @Test
    void prefersCredentialsFileOverMacOsKeychain() throws IOException {
        System.setProperty("os.name", "Mac OS X");
        createCredentialsFile("""
            {
              "oauthAccount": { "displayName": "File Alice" },
              "claudeAiOauth": { "accessToken": "tok-file" }
            }
            """);

        ClaudeCliCredentials creds = ClaudeCliCredentials.read(command -> {
            throw new AssertionError("Keychain must not be queried when the credentials file is valid");
        });

        assertTrue(creds.isLoggedIn());
        assertEquals("File Alice", creds.getDisplayName());
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

    @Test
    void fallsBackToMacOsKeychainWhenFileIsMalformed() throws IOException {
        System.setProperty("os.name", "Mac OS X");
        createCredentialsFile("not-valid-json{{{");

        ClaudeCliCredentials creds = ClaudeCliCredentials.read(command -> """
            {
              "oauthAccount": { "emailAddress": "alice@example.com" },
              "claudeAiOauth": { "accessToken": "tok-keychain" }
            }
            """);

        assertTrue(creds.isLoggedIn());
        assertEquals("alice@example.com", creds.getDisplayName());
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

    @Test
    void logoutDeletesMacOsKeychainCredential() {
        System.setProperty("os.name", "Mac OS X");

        assertTrue(ClaudeCliCredentials.logout(command -> {
            assertEquals(List.of("/usr/bin/security", "delete-generic-password", "-s",
                "Claude Code-credentials"), command);
            return "";
        }));
    }

    // ── parseCredentials (pure parsing, no filesystem) ─────────────────────

    @Test
    void parseCredentials_validFullCredentials() {
        String json = """
            {
                "claudeAiOauth": {"accessToken": "sk-ant-123"},
                "oauthAccount": {"displayName": "John", "emailAddress": "john@example.com"}
            }""";

        ClaudeCliCredentials creds = ClaudeCliCredentials.parseCredentials(json);
        assertTrue(creds.isLoggedIn());
        assertEquals("John", creds.getDisplayName());
    }

    @Test
    void parseCredentials_fallsBackToEmail() {
        String json = """
            {
                "claudeAiOauth": {"accessToken": "sk-ant-123"},
                "oauthAccount": {"emailAddress": "user@example.com"}
            }""";

        ClaudeCliCredentials creds = ClaudeCliCredentials.parseCredentials(json);
        assertTrue(creds.isLoggedIn());
        assertEquals("user@example.com", creds.getDisplayName());
    }

    @Test
    void parseCredentials_emptyObjectNotLoggedIn() {
        assertFalse(ClaudeCliCredentials.parseCredentials("{}").isLoggedIn());
    }

    @Test
    void parseCredentials_invalidJsonNotLoggedIn() {
        ClaudeCliCredentials creds = ClaudeCliCredentials.parseCredentials("not json");
        assertFalse(creds.isLoggedIn());
    }

    // ── path ──────────────────────────────────────────────────────────────────

    @Test
    void credentialsPathUsesCurrentUserHome() {
        Path expected = tempDir.resolve(".claude").resolve(".credentials.json");
        assertEquals(expected, ClaudeCliCredentials.credentialsPath());
    }
}
