package com.github.catatafishen.agentbridge.ui;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for {@link AuthCommandBuilder} — a Kotlin {@code object}
 * providing pure parsing and command-building logic for Copilot auth flows.
 */
class AuthCommandBuilderTest {

    // ─── isAuthenticationError ────────────────────────────────────────────

    @Test
    void isAuthenticationError_containsAuth_returnsTrue() {
        assertTrue(AuthCommandBuilder.INSTANCE.isAuthenticationError("Authentication failed"));
    }

    @Test
    void isAuthenticationError_containsAuthLowercase_returnsTrue() {
        assertTrue(AuthCommandBuilder.INSTANCE.isAuthenticationError("auth error occurred"));
    }

    @Test
    void isAuthenticationError_containsAuthMixedCase_returnsTrue() {
        assertTrue(AuthCommandBuilder.INSTANCE.isAuthenticationError("AUTH TOKEN EXPIRED"));
    }

    @Test
    void isAuthenticationError_containsCopilotCli_returnsTrue() {
        assertTrue(AuthCommandBuilder.INSTANCE.isAuthenticationError("copilot CLI error"));
    }

    @Test
    void isAuthenticationError_containsCopilotCliMixedCase_returnsTrue() {
        assertTrue(AuthCommandBuilder.INSTANCE.isAuthenticationError("Copilot CLI is not configured"));
    }

    @Test
    void isAuthenticationError_containsAuthenticated_returnsTrue() {
        assertTrue(AuthCommandBuilder.INSTANCE.isAuthenticationError("Not authenticated"));
    }

    @Test
    void isAuthenticationError_containsReAuthenticated_returnsTrue() {
        assertTrue(AuthCommandBuilder.INSTANCE.isAuthenticationError("re-authenticated"));
    }

    @Test
    void isAuthenticationError_networkTimeout_returnsFalse() {
        assertFalse(AuthCommandBuilder.INSTANCE.isAuthenticationError("network timeout"));
    }

    @Test
    void isAuthenticationError_emptyString_returnsFalse() {
        assertFalse(AuthCommandBuilder.INSTANCE.isAuthenticationError(""));
    }

    @Test
    void isAuthenticationError_unrelatedMessage_returnsFalse() {
        assertFalse(AuthCommandBuilder.INSTANCE.isAuthenticationError("File not found"));
    }

    @Test
    void isAuthenticationError_partialMatchAuthInWord_returnsTrue() {
        // "auth" is a substring match, so "authorize" should still match
        assertTrue(AuthCommandBuilder.INSTANCE.isAuthenticationError("Please authorize your account"));
    }

    // ─── parseDeviceCode ─────────────────────────────────────────────────

    @Test
    void parseDeviceCode_lineWithCodeOnly_returnsCodeAndNullUrl() {
        AuthCommandBuilder.ParseResult result =
                AuthCommandBuilder.INSTANCE.parseDeviceCode(
                        "Enter code: ABCD-1234", null, null);
        assertEquals("ABCD-1234", result.getCode());
        assertNull(result.getUrl());
    }

    @Test
    void parseDeviceCode_lineWithUrlOnly_returnsNullCodeAndUrl() {
        AuthCommandBuilder.ParseResult result =
                AuthCommandBuilder.INSTANCE.parseDeviceCode(
                        "Open https://github.com/login/device to continue",
                        null, null);
        assertNull(result.getCode());
        assertEquals("https://github.com/login/device", result.getUrl());
    }

    @Test
    void parseDeviceCode_lineWithBothCodeAndUrl_returnsBoth() {
        AuthCommandBuilder.ParseResult result =
                AuthCommandBuilder.INSTANCE.parseDeviceCode(
                        "Use ABCD-1234 at https://github.com/login/device",
                        null, null);
        assertEquals("ABCD-1234", result.getCode());
        assertEquals("https://github.com/login/device", result.getUrl());
    }

    @Test
    void parseDeviceCode_lineWithNeither_returnsExistingValues() {
        AuthCommandBuilder.ParseResult result =
                AuthCommandBuilder.INSTANCE.parseDeviceCode(
                        "Waiting for authorization...",
                        "PREV-CODE", "https://example.com/device");
        assertEquals("PREV-CODE", result.getCode());
        assertEquals("https://example.com/device", result.getUrl());
    }

    @Test
    void parseDeviceCode_lineWithNeither_existingBothNull_returnsBothNull() {
        AuthCommandBuilder.ParseResult result =
                AuthCommandBuilder.INSTANCE.parseDeviceCode(
                        "Some random output", null, null);
        assertNull(result.getCode());
        assertNull(result.getUrl());
    }

    @Test
    void parseDeviceCode_accumulationAcrossLines_codeFirst() {
        // First line has code only
        AuthCommandBuilder.ParseResult first =
                AuthCommandBuilder.INSTANCE.parseDeviceCode(
                        "Your code is AB12-CD34", null, null);
        assertEquals("AB12-CD34", first.getCode());
        assertNull(first.getUrl());

        // Second line has URL only — accumulate with existing code
        AuthCommandBuilder.ParseResult second =
                AuthCommandBuilder.INSTANCE.parseDeviceCode(
                        "Visit https://github.com/login/device",
                        first.getCode(), first.getUrl());
        assertEquals("AB12-CD34", second.getCode());
        assertEquals("https://github.com/login/device", second.getUrl());
    }

    @Test
    void parseDeviceCode_accumulationAcrossLines_urlFirst() {
        // First line has URL only
        AuthCommandBuilder.ParseResult first =
                AuthCommandBuilder.INSTANCE.parseDeviceCode(
                        "Go to https://github.com/login/device", null, null);
        assertNull(first.getCode());
        assertEquals("https://github.com/login/device", first.getUrl());

        // Second line has code only — accumulate with existing URL
        AuthCommandBuilder.ParseResult second =
                AuthCommandBuilder.INSTANCE.parseDeviceCode(
                        "Enter code WXYZ-9876",
                        first.getCode(), first.getUrl());
        assertEquals("WXYZ-9876", second.getCode());
        assertEquals("https://github.com/login/device", second.getUrl());
    }

    @Test
    void parseDeviceCode_codePatternEightCharGroups() {
        // 8-character groups are accepted by the regex [A-Z0-9]{4,8}
        AuthCommandBuilder.ParseResult result =
                AuthCommandBuilder.INSTANCE.parseDeviceCode(
                        "Code: ABCD1234-EFGH5678", null, null);
        assertEquals("ABCD1234-EFGH5678", result.getCode());
    }

    @Test
    void parseDeviceCode_codePatternFourCharGroups() {
        // Minimum 4-character groups
        AuthCommandBuilder.ParseResult result =
                AuthCommandBuilder.INSTANCE.parseDeviceCode(
                        "Code: ABCD-EF12", null, null);
        assertEquals("ABCD-EF12", result.getCode());
    }

    @Test
    void parseDeviceCode_newCodeOverridesExisting() {
        AuthCommandBuilder.ParseResult result =
                AuthCommandBuilder.INSTANCE.parseDeviceCode(
                        "New code: ZZZZ-9999", "AAAA-1111", null);
        assertEquals("ZZZZ-9999", result.getCode());
    }

    @Test
    void parseDeviceCode_newUrlOverridesExisting() {
        AuthCommandBuilder.ParseResult result =
                AuthCommandBuilder.INSTANCE.parseDeviceCode(
                        "Open https://github.com/login/device/new",
                        null, "https://old.example.com/device");
        assertEquals("https://github.com/login/device/new", result.getUrl());
    }

    @Test
    void parseDeviceCode_urlWithHttpScheme() {
        AuthCommandBuilder.ParseResult result =
                AuthCommandBuilder.INSTANCE.parseDeviceCode(
                        "Go to http://localhost/device", null, null);
        assertEquals("http://localhost/device", result.getUrl());
    }

    @Test
    void parseDeviceCode_urlWithSubpath() {
        AuthCommandBuilder.ParseResult result =
                AuthCommandBuilder.INSTANCE.parseDeviceCode(
                        "Visit https://github.com/login/device/callback",
                        null, null);
        assertEquals("https://github.com/login/device/callback", result.getUrl());
    }

    @Test
    void parseDeviceCode_emptyLine_returnsExistingValues() {
        AuthCommandBuilder.ParseResult result =
                AuthCommandBuilder.INSTANCE.parseDeviceCode(
                        "", "CODE-HERE", "https://url.com/device");
        assertEquals("CODE-HERE", result.getCode());
        assertEquals("https://url.com/device", result.getUrl());
    }

    // ─── ParseResult data class ──────────────────────────────────────────

    @Test
    void parseResult_getters() {
        AuthCommandBuilder.ParseResult pr =
                new AuthCommandBuilder.ParseResult("CODE-1234", "https://url.com/device");
        assertEquals("CODE-1234", pr.getCode());
        assertEquals("https://url.com/device", pr.getUrl());
    }

    @Test
    void parseResult_bothNull() {
        AuthCommandBuilder.ParseResult pr =
                new AuthCommandBuilder.ParseResult(null, null);
        assertNull(pr.getCode());
        assertNull(pr.getUrl());
    }

    @Test
    void parseResult_equality() {
        AuthCommandBuilder.ParseResult a =
                new AuthCommandBuilder.ParseResult("A", "B");
        AuthCommandBuilder.ParseResult b =
                new AuthCommandBuilder.ParseResult("A", "B");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void parseResult_inequality() {
        AuthCommandBuilder.ParseResult a =
                new AuthCommandBuilder.ParseResult("A", "B");
        AuthCommandBuilder.ParseResult b =
                new AuthCommandBuilder.ParseResult("X", "Y");
        assertNotEquals(a, b);
    }

    @Test
    void parseResult_toString_containsValues() {
        AuthCommandBuilder.ParseResult pr =
                new AuthCommandBuilder.ParseResult("CODE", "URL");
        String str = pr.toString();
        assertTrue(str.contains("CODE"));
        assertTrue(str.contains("URL"));
    }

    // ─── buildCommandWithEnvironment ─────────────────────────────────────

    @Test
    void buildCommandWithEnvironment_emptyMap_returnsCommandUnchanged() {
        String result = AuthCommandBuilder.INSTANCE.buildCommandWithEnvironment(
                "copilot auth", Collections.emptyMap());
        assertEquals("copilot auth", result);
    }

    @Test
    void buildCommandWithEnvironment_singleEnvVar_unixFormat() {
        // This test assumes a Unix-like OS (Linux/macOS)
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            return; // skip on Windows
        }

        Map<String, String> env = Collections.singletonMap("TOKEN", "abc123");
        String result = AuthCommandBuilder.INSTANCE.buildCommandWithEnvironment(
                "copilot auth", env);
        assertEquals("export TOKEN='abc123'; copilot auth", result);
    }

    @Test
    void buildCommandWithEnvironment_multipleEnvVars_unixFormat() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            return; // skip on Windows
        }

        // Use LinkedHashMap to guarantee insertion order
        Map<String, String> env = new LinkedHashMap<>();
        env.put("KEY1", "val1");
        env.put("KEY2", "val2");

        String result = AuthCommandBuilder.INSTANCE.buildCommandWithEnvironment(
                "my-command", env);
        assertEquals("export KEY1='val1'; export KEY2='val2'; my-command", result);
    }

    @Test
    void buildCommandWithEnvironment_preservesCommandExactly() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            return; // skip on Windows
        }

        String command = "npx @anthropic/copilot auth --flag=value";
        Map<String, String> env = Collections.singletonMap("PATH", "/usr/bin");
        String result = AuthCommandBuilder.INSTANCE.buildCommandWithEnvironment(command, env);
        assertTrue(result.endsWith("; " + command));
    }

    @Test
    void buildCommandWithEnvironment_envVarWithSpecialChars_unixFormat() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            return; // skip on Windows
        }

        Map<String, String> env = Collections.singletonMap("MY_VAR", "hello world");
        String result = AuthCommandBuilder.INSTANCE.buildCommandWithEnvironment("cmd", env);
        assertEquals("export MY_VAR='hello world'; cmd", result);
    }
}
