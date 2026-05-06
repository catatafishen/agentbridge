package com.github.catatafishen.agentbridge.custommcp.oauth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates the OAuth 2.1 PKCE flow for an MCP server.
 *
 * <ol>
 *   <li>Discovers the authorization server via {@code /.well-known/oauth-authorization-server}</li>
 *   <li>Opens the user's browser to the authorization endpoint</li>
 *   <li>Waits for the callback on a local server and exchanges the code for tokens</li>
 *   <li>Optionally refreshes a stored access token using a refresh token</li>
 * </ol>
 *
 * <p>Callers must invoke this from a background thread — the {@link #authenticate} method
 * blocks for up to {@value #AUTH_TIMEOUT_MINUTES} minutes while the user completes sign-in.
 */
public final class McpOAuthFlow {

    private static final Logger LOG = Logger.getInstance(McpOAuthFlow.class);

    /** Maximum time to wait for the user to complete authorization in the browser. */
    static final int AUTH_TIMEOUT_MINUTES = 5;

    /** Fixed client_id sent in all OAuth requests. */
    private static final String CLIENT_ID = "agentbridge";

    private static final int HTTP_TIMEOUT_MS = 10_000;

    private McpOAuthFlow() {
    }

    /**
     * Runs the full OAuth PKCE authentication flow for the given MCP server URL.
     *
     * <ol>
     *   <li>Fetches authorization server metadata from {@code {origin}/.well-known/oauth-authorization-server}</li>
     *   <li>Starts a local callback server on {@code localhost:0}</li>
     *   <li>Opens the browser at the authorization endpoint (PKCE S256)</li>
     *   <li>Waits up to {@value #AUTH_TIMEOUT_MINUTES} minutes for the redirect</li>
     *   <li>Exchanges the authorization code for tokens</li>
     * </ol>
     *
     * @param serverUrl the MCP server URL that returned HTTP 401
     * @return the obtained tokens
     * @throws IOException          if discovery or token exchange fails
     * @throws InterruptedException if the waiting thread is interrupted
     */
    @NotNull
    public static McpOAuthTokens authenticate(@NotNull String serverUrl)
        throws IOException, InterruptedException {

        McpOAuthMetadata meta = discoverMetadata(serverUrl);
        McpOAuthPkce.Params pkce = McpOAuthPkce.generate();
        String state = McpOAuthPkce.generateState();

        try (McpOAuthCallbackServer callbackServer = new McpOAuthCallbackServer()) {
            callbackServer.start();
            String redirectUri = callbackServer.getCallbackUri();

            String authUrl = buildAuthUrl(meta.authorizationEndpoint(), redirectUri, pkce.challenge(), state);
            LOG.info("Opening browser for OAuth authorization: " + authUrl);
            BrowserUtil.browse(authUrl);

            McpOAuthCallbackServer.Result result = callbackServer.waitForCallback(AUTH_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (result == null) {
                throw new IOException("OAuth authorization timed out after " + AUTH_TIMEOUT_MINUTES + " minutes");
            }
            if (!state.equals(result.state())) {
                throw new IOException("OAuth state mismatch — possible CSRF; expected=" + state + " got=" + result.state());
            }
            return exchangeCode(meta.tokenEndpoint(), result.code(), pkce.verifier(), redirectUri);
        }
    }

    /**
     * Attempts to refresh the access token using the stored refresh token.
     *
     * @param serverUrl    the MCP server URL (used for metadata discovery)
     * @param refreshToken the stored refresh token
     * @return refreshed tokens, or {@code null} if refresh is not supported or failed
     */
    @Nullable
    public static McpOAuthTokens refreshAccessToken(@NotNull String serverUrl, @NotNull String refreshToken) {
        try {
            McpOAuthMetadata meta = discoverMetadata(serverUrl);
            return doRefresh(meta.tokenEndpoint(), refreshToken);
        } catch (Exception e) {
            LOG.info("Token refresh failed for " + serverUrl + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Fetches the OAuth authorization server metadata from the well-known discovery endpoint.
     *
     * @param serverUrl any URL on the MCP server; only the scheme+host+port are used
     * @throws IOException if the endpoint is unreachable or returns an unexpected response
     */
    @NotNull
    public static McpOAuthMetadata discoverMetadata(@NotNull String serverUrl) throws IOException {
        String discoveryUrl = buildDiscoveryUrl(serverUrl);
        LOG.info("Discovering OAuth AS metadata from " + discoveryUrl);

        HttpURLConnection conn = openGet(discoveryUrl);
        try {
            int status = conn.getResponseCode();
            if (status != 200) {
                throw new IOException(
                    "OAuth discovery returned HTTP " + status + " from " + discoveryUrl
                );
            }
            String body;
            try (InputStream is = conn.getInputStream()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            return parseMetadata(body);
        } finally {
            conn.disconnect();
        }
    }

    // ── Visible for testing ──────────────────────────────────────────────────

    @NotNull
    static String buildDiscoveryUrl(@NotNull String serverUrl) throws IOException {
        try {
            URI uri = URI.create(serverUrl);
            int port = uri.getPort();
            String host = uri.getHost();
            String scheme = uri.getScheme();
            if (host == null || scheme == null) {
                throw new IOException("Cannot parse origin from URL: " + serverUrl);
            }
            String origin = port > 0 ? scheme + "://" + host + ":" + port : scheme + "://" + host;
            return origin + "/.well-known/oauth-authorization-server";
        } catch (IllegalArgumentException e) {
            throw new IOException("Malformed MCP server URL: " + serverUrl, e);
        }
    }

    @NotNull
    static McpOAuthMetadata parseMetadata(@NotNull String json) throws IOException {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String issuer = required(obj, "issuer", json);
            String authorizationEndpoint = required(obj, "authorization_endpoint", json);
            String tokenEndpoint = required(obj, "token_endpoint", json);
            String registrationEndpoint = obj.has("registration_endpoint")
                ? obj.get("registration_endpoint").getAsString() : null;
            String scope = obj.has("scopes_supported")
                ? obj.getAsJsonArray("scopes_supported").asList().stream()
                .map(com.google.gson.JsonElement::getAsString).reduce((a, b) -> a + " " + b).orElse(null)
                : null;
            return new McpOAuthMetadata(issuer, authorizationEndpoint, tokenEndpoint, registrationEndpoint, scope);
        } catch (Exception e) {
            throw new IOException("Failed to parse OAuth AS metadata: " + e.getMessage(), e);
        }
    }

    @NotNull
    static String buildAuthUrl(
        @NotNull String authEndpoint,
        @NotNull String redirectUri,
        @NotNull String codeChallenge,
        @NotNull String state
    ) throws IOException {
        try {
            return authEndpoint
                + "?response_type=code"
                + "&client_id=" + encode(CLIENT_ID)
                + "&redirect_uri=" + encode(redirectUri)
                + "&code_challenge=" + encode(codeChallenge)
                + "&code_challenge_method=S256"
                + "&state=" + encode(state);
        } catch (Exception e) {
            throw new IOException("Failed to build authorization URL", e);
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    @NotNull
    private static McpOAuthTokens exchangeCode(
        @NotNull String tokenEndpoint,
        @NotNull String code,
        @NotNull String codeVerifier,
        @NotNull String redirectUri
    ) throws IOException {
        String body = "grant_type=authorization_code"
            + "&code=" + encode(code)
            + "&redirect_uri=" + encode(redirectUri)
            + "&client_id=" + encode(CLIENT_ID)
            + "&code_verifier=" + encode(codeVerifier);
        return doTokenRequest(tokenEndpoint, body);
    }

    @NotNull
    private static McpOAuthTokens doRefresh(@NotNull String tokenEndpoint, @NotNull String refreshToken)
        throws IOException {
        String body = "grant_type=refresh_token"
            + "&refresh_token=" + encode(refreshToken)
            + "&client_id=" + encode(CLIENT_ID);
        return doTokenRequest(tokenEndpoint, body);
    }

    @NotNull
    private static McpOAuthTokens doTokenRequest(@NotNull String tokenEndpoint, @NotNull String formBody)
        throws IOException {
        byte[] bodyBytes = formBody.getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) URI.create(tokenEndpoint).toURL().openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Accept", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int status = conn.getResponseCode();
            InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String responseBody = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            if (status != 200) {
                throw new IOException("Token endpoint returned HTTP " + status + ": " + responseBody);
            }
            return parseTokenResponse(responseBody);
        } finally {
            conn.disconnect();
        }
    }

    @NotNull
    private static McpOAuthTokens parseTokenResponse(@NotNull String json) throws IOException {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String accessToken = required(obj, "access_token", json);
            String refreshToken = obj.has("refresh_token") ? obj.get("refresh_token").getAsString() : null;
            long expiresAtMs = 0;
            if (obj.has("expires_in")) {
                long expiresInSec = obj.get("expires_in").getAsLong();
                expiresAtMs = System.currentTimeMillis() + expiresInSec * 1000;
            }
            return new McpOAuthTokens(accessToken, refreshToken, expiresAtMs);
        } catch (Exception e) {
            throw new IOException("Failed to parse token response: " + e.getMessage(), e);
        }
    }

    @NotNull
    private static HttpURLConnection openGet(@NotNull String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(HTTP_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    @NotNull
    private static String required(@NotNull JsonObject obj, @NotNull String key, @NotNull String context)
        throws IOException {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            throw new IOException("Missing required field '" + key + "' in: " + context.substring(0, Math.min(200, context.length())));
        }
        return obj.get(key).getAsString();
    }

    @NotNull
    private static String encode(@NotNull String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }
}
