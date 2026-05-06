package com.github.catatafishen.agentbridge.custommcp.oauth;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Authorization server metadata discovered from the MCP server's
 * {@code /.well-known/oauth-authorization-server} endpoint, as defined by
 * <a href="https://datatracker.ietf.org/doc/html/rfc8414">RFC 8414</a> and the
 * <a href="https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization">
 * MCP authorization spec</a>.
 */
public record McpOAuthMetadata(
    @NotNull String issuer,
    @NotNull String authorizationEndpoint,
    @NotNull String tokenEndpoint,
    @Nullable String registrationEndpoint,
    @Nullable String scope
) {
}
