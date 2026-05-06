package com.github.catatafishen.agentbridge.custommcp.oauth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpOAuthTokensTest {

    @Test
    void isExpired_zeroExpiry_returnsFalse() {
        // expiresAtEpochMs == 0 means unknown expiry — never considered expired.
        McpOAuthTokens tokens = new McpOAuthTokens("at", null, 0);
        assertFalse(tokens.isExpired());
    }

    @Test
    void isExpired_negativeExpiry_returnsFalse() {
        // Negative value treated same as zero (unknown expiry).
        McpOAuthTokens tokens = new McpOAuthTokens("at", null, -1);
        assertFalse(tokens.isExpired());
    }

    @Test
    void isExpired_farFuture_returnsFalse() {
        long future = System.currentTimeMillis() + 3_600_000; // 1 hour from now
        McpOAuthTokens tokens = new McpOAuthTokens("at", null, future);
        assertFalse(tokens.isExpired());
    }

    @Test
    void isExpired_farPast_returnsTrue() {
        long past = System.currentTimeMillis() - 3_600_000; // 1 hour ago
        McpOAuthTokens tokens = new McpOAuthTokens("at", null, past);
        assertTrue(tokens.isExpired());
    }

    @Test
    void isExpired_withinGracePeriod_returnsTrue() {
        // Token expires in 15 seconds → within 30s grace → considered expired.
        long nearFuture = System.currentTimeMillis() + 15_000;
        McpOAuthTokens tokens = new McpOAuthTokens("at", null, nearFuture);
        assertTrue(tokens.isExpired());
    }

    @Test
    void isExpired_outsideGracePeriod_returnsFalse() {
        // Token expires in 31 seconds → outside 30s grace → still valid.
        long justOutside = System.currentTimeMillis() + 31_000;
        McpOAuthTokens tokens = new McpOAuthTokens("at", null, justOutside);
        assertFalse(tokens.isExpired());
    }

    @Test
    void isExpired_atGraceBoundary_returnsTrue() {
        // Token expires in exactly 30 seconds → at grace boundary → expired.
        long atBoundary = System.currentTimeMillis() + 30_000;
        McpOAuthTokens tokens = new McpOAuthTokens("at", null, atBoundary);
        assertTrue(tokens.isExpired());
    }

    @Test
    void isExpired_withRefreshToken_stillChecksAccessTokenExpiry() {
        // Having a refresh token should not affect isExpired() logic.
        long past = System.currentTimeMillis() - 60_000;
        McpOAuthTokens tokens = new McpOAuthTokens("at", "rt", past);
        assertTrue(tokens.isExpired());

        long future = System.currentTimeMillis() + 60_000;
        tokens = new McpOAuthTokens("at", "rt", future);
        assertFalse(tokens.isExpired());
    }

    @Test
    void constructor_allFieldsPersisted() {
        long expiry = System.currentTimeMillis() + 3600_000;
        McpOAuthTokens tokens = new McpOAuthTokens("access", "refresh", expiry);
        assertTrue("access".equals(tokens.accessToken()));
        assertTrue("refresh".equals(tokens.refreshToken()));
        assertTrue(expiry == tokens.expiresAtEpochMs());
    }

    @Test
    void constructor_nullRefreshToken_persisted() {
        McpOAuthTokens tokens = new McpOAuthTokens("access", null, 12345);
        assertTrue("access".equals(tokens.accessToken()));
        assertTrue(tokens.refreshToken() == null);
        assertTrue(12345 == tokens.expiresAtEpochMs());
    }
}
