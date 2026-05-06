package com.github.catatafishen.agentbridge.custommcp.oauth;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An access token and optional refresh token obtained via OAuth PKCE flow.
 *
 * @param accessToken      bearer token for MCP server requests
 * @param refreshToken     token used to obtain a new access token without re-authorizing; may be null
 * @param expiresAtEpochMs wall-clock expiry in epoch milliseconds; 0 = unknown
 */
public record McpOAuthTokens(
    @NotNull String accessToken,
    @Nullable String refreshToken,
    long expiresAtEpochMs
) {

    /**
     * Returns {@code true} if the access token has a known expiry and it has already passed.
     * Adds a 30-second grace buffer to avoid edge-case races near the boundary.
     */
    public boolean isExpired() {
        if (expiresAtEpochMs <= 0) return false;
        long nowMs = System.currentTimeMillis();
        return nowMs >= expiresAtEpochMs - 30_000;
    }
}
