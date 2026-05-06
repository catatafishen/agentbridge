package com.github.catatafishen.agentbridge.custommcp.oauth;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpOAuthFlowTest {

    // ── buildDiscoveryUrl ────────────────────────────────────────────────────

    @Test
    void buildDiscoveryUrl_withExplicitPort_appendsWellKnown() throws IOException {
        String url = McpOAuthFlow.buildDiscoveryUrl("http://example.com:8080/mcp?foo=bar");
        assertEquals("http://example.com:8080/.well-known/oauth-authorization-server", url);
    }

    @Test
    void buildDiscoveryUrl_withoutPort_omitsDefaultPort() throws IOException {
        String url = McpOAuthFlow.buildDiscoveryUrl("https://auth.example.com/v1/mcp");
        assertEquals("https://auth.example.com/.well-known/oauth-authorization-server", url);
    }

    @Test
    void buildDiscoveryUrl_malformedUrl_throwsIOException() {
        assertThrows(IOException.class, () -> McpOAuthFlow.buildDiscoveryUrl("not a url!!!"));
    }

    // ── parseMetadata ────────────────────────────────────────────────────────

    @Test
    void parseMetadata_fullJson_parsesAllFields() throws IOException {
        String json = """
            {
              "issuer": "https://auth.example.com",
              "authorization_endpoint": "https://auth.example.com/oauth/authorize",
              "token_endpoint": "https://auth.example.com/oauth/token",
              "registration_endpoint": "https://auth.example.com/oauth/register",
              "scopes_supported": ["read", "write"]
            }
            """;
        McpOAuthMetadata meta = McpOAuthFlow.parseMetadata(json);
        assertEquals("https://auth.example.com", meta.issuer());
        assertEquals("https://auth.example.com/oauth/authorize", meta.authorizationEndpoint());
        assertEquals("https://auth.example.com/oauth/token", meta.tokenEndpoint());
        assertEquals("https://auth.example.com/oauth/register", meta.registrationEndpoint());
        assertEquals("read write", meta.scope());
    }

    @Test
    void parseMetadata_minimalJson_parsesRequiredFields() throws IOException {
        String json = """
            {
              "issuer": "https://auth.example.com",
              "authorization_endpoint": "https://auth.example.com/authorize",
              "token_endpoint": "https://auth.example.com/token"
            }
            """;
        McpOAuthMetadata meta = McpOAuthFlow.parseMetadata(json);
        assertEquals("https://auth.example.com", meta.issuer());
        assertNull(meta.registrationEndpoint());
        assertNull(meta.scope());
    }

    @Test
    void parseMetadata_missingTokenEndpoint_throwsIOException() {
        String json = """
            {
              "issuer": "https://auth.example.com",
              "authorization_endpoint": "https://auth.example.com/authorize"
            }
            """;
        assertThrows(IOException.class, () -> McpOAuthFlow.parseMetadata(json));
    }

    @Test
    void parseMetadata_missingAuthorizationEndpoint_throwsIOException() {
        String json = """
            {
              "issuer": "https://auth.example.com",
              "token_endpoint": "https://auth.example.com/token"
            }
            """;
        assertThrows(IOException.class, () -> McpOAuthFlow.parseMetadata(json));
    }

    @Test
    void parseMetadata_invalidJson_throwsIOException() {
        assertThrows(IOException.class, () -> McpOAuthFlow.parseMetadata("not json"));
    }

    // ── parseTokenResponse ────────────────────────────────────────────────────

    @Test
    void parseTokenResponse_fullResponse_parsesAllFields() throws IOException {
        String json = """
            {
              "access_token": "eyJhbGciOi...",
              "refresh_token": "rt-abc123",
              "token_type": "Bearer",
              "expires_in": 3600
            }
            """;
        McpOAuthTokens tokens = McpOAuthFlow.parseTokenResponse(json);
        assertTrue("eyJhbGciOi...".equals(tokens.accessToken()));
        assertTrue("rt-abc123".equals(tokens.refreshToken()));
        // expiresAtEpochMs should be roughly now + 3600s; allow 5s clock drift.
        long expected = System.currentTimeMillis() + 3_600_000;
        assertTrue(Math.abs(tokens.expiresAtEpochMs() - expected) < 5_000,
            "expiry should be ~now + 1h");
        assertFalse(tokens.isExpired());
    }

    @Test
    void parseTokenResponse_noRefreshToken_returnsNullRefreshToken() throws IOException {
        String json = """
            {
              "access_token": "at-only",
              "token_type": "Bearer",
              "expires_in": 60
            }
            """;
        McpOAuthTokens tokens = McpOAuthFlow.parseTokenResponse(json);
        assertTrue("at-only".equals(tokens.accessToken()));
        assertTrue(tokens.refreshToken() == null);
    }

    @Test
    void parseTokenResponse_noExpiresIn_setsZeroExpiry() throws IOException {
        String json = """
            {
              "access_token": "at-no-expiry",
              "token_type": "Bearer"
            }
            """;
        McpOAuthTokens tokens = McpOAuthFlow.parseTokenResponse(json);
        assertTrue("at-no-expiry".equals(tokens.accessToken()));
        assertTrue(0 == tokens.expiresAtEpochMs());
        assertFalse(tokens.isExpired());
    }

    @Test
    void parseTokenResponse_missingAccessToken_throwsIOException() {
        String json = """
            {
              "token_type": "Bearer",
              "expires_in": 3600
            }
            """;
        assertThrows(IOException.class, () -> McpOAuthFlow.parseTokenResponse(json));
    }

    @Test
    void parseTokenResponse_nullAccessToken_throwsIOException() {
        String json = """
            {
              "access_token": null,
              "token_type": "Bearer"
            }
            """;
        assertThrows(IOException.class, () -> McpOAuthFlow.parseTokenResponse(json));
    }

    @Test
    void parseTokenResponse_invalidJson_throwsIOException() {
        assertThrows(IOException.class, () -> McpOAuthFlow.parseTokenResponse("not json"));
    }

    @Test
    void parseTokenResponse_emptyObject_throwsIOException() {
        assertThrows(IOException.class, () -> McpOAuthFlow.parseTokenResponse("{}"));
    }

    // ── buildAuthUrl ─────────────────────────────────────────────────────────

    @Test
    void buildAuthUrl_containsAllRequiredParams() throws IOException {
        String authEndpoint = "https://auth.example.com/authorize";
        String redirectUri = "http://127.0.0.1:54321/callback";
        String challenge = McpOAuthPkce.generate().challenge();
        String state = McpOAuthPkce.generateState();

        String url = McpOAuthFlow.buildAuthUrl(authEndpoint, redirectUri, challenge, state, null);

        assertTrue(url.startsWith(authEndpoint + "?"), "should start with auth endpoint");
        assertTrue(url.contains("response_type=code"), "should contain response_type=code");
        assertTrue(url.contains("client_id=agentbridge"), "should contain client_id=agentbridge");
        assertTrue(url.contains("code_challenge_method=S256"), "should contain code_challenge_method=S256");
        assertTrue(url.contains("code_challenge="), "should contain code_challenge");
        assertTrue(url.contains("redirect_uri="), "should contain redirect_uri");
        assertTrue(url.contains("state="), "should contain state");
    }

    @Test
    void buildAuthUrl_includesScopeWhenProvided() throws IOException {
        String url = McpOAuthFlow.buildAuthUrl(
            "https://auth.example.com/authorize",
            "http://127.0.0.1:54321/callback",
            McpOAuthPkce.generate().challenge(),
            McpOAuthPkce.generateState(),
            "read write"
        );
        assertTrue(url.contains("scope=read+write"), "should contain scope parameter");
    }

    @Test
    void buildAuthUrl_omitsScopeWhenNull() throws IOException {
        String url = McpOAuthFlow.buildAuthUrl(
            "https://auth.example.com/authorize",
            "http://127.0.0.1:54321/callback",
            McpOAuthPkce.generate().challenge(),
            McpOAuthPkce.generateState(),
            null
        );
        org.junit.jupiter.api.Assertions.assertFalse(url.contains("scope="), "should not contain scope param when null");
    }
}
