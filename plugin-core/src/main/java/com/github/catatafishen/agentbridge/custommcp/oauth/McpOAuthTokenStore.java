package com.github.catatafishen.agentbridge.custommcp.oauth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;

/**
 * Persists OAuth tokens for custom MCP servers using IntelliJ's
 * {@link PasswordSafe} (system keychain on macOS/Windows, kwallet/SecretService on Linux).
 *
 * <p>One entry per normalized server origin (scheme+host+port). The stored value is a
 * JSON string {@code {"access_token":"…","refresh_token":"…","expires_at_ms":…}}.
 */
public final class McpOAuthTokenStore {

    private static final Logger LOG = Logger.getInstance(McpOAuthTokenStore.class);
    private static final String SERVICE_NAME = "AgentBridge MCP OAuth";
    private static final Gson GSON = new Gson();
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_EXPIRES_AT_MS = "expires_at_ms";

    private McpOAuthTokenStore() {
    }

    /**
     * Stores tokens for the given server URL, overwriting any previous entry.
     */
    public static void store(@NotNull String serverUrl, @NotNull McpOAuthTokens tokens) {
        CredentialAttributes attrs = attrs(serverUrl);
        JsonObject json = new JsonObject();
        json.addProperty(KEY_ACCESS_TOKEN, tokens.accessToken());
        if (tokens.refreshToken() != null) {
            json.addProperty(KEY_REFRESH_TOKEN, tokens.refreshToken());
        }
        json.addProperty(KEY_EXPIRES_AT_MS, tokens.expiresAtEpochMs());
        Credentials creds = new Credentials(normalizeUrl(serverUrl), GSON.toJson(json));
        PasswordSafe.getInstance().set(attrs, creds);
        LOG.info("Stored OAuth tokens for " + serverUrl);
    }

    /**
     * Loads stored tokens for the given server URL, or {@code null} if none are stored.
     */
    @Nullable
    public static McpOAuthTokens load(@NotNull String serverUrl) {
        CredentialAttributes attrs = attrs(serverUrl);
        Credentials creds = PasswordSafe.getInstance().get(attrs);
        if (creds == null) return null;
        String password = creds.getPasswordAsString();
        if (password == null || password.isBlank()) return null;
        try {
            JsonObject json = JsonParser.parseString(password).getAsJsonObject();
            String accessToken = json.has(KEY_ACCESS_TOKEN) ? json.get(KEY_ACCESS_TOKEN).getAsString() : null;
            if (accessToken == null || accessToken.isBlank()) return null;
            String refreshToken = json.has(KEY_REFRESH_TOKEN) ? json.get(KEY_REFRESH_TOKEN).getAsString() : null;
            long expiresAtMs = json.has(KEY_EXPIRES_AT_MS) ? json.get(KEY_EXPIRES_AT_MS).getAsLong() : 0;
            return new McpOAuthTokens(accessToken, refreshToken, expiresAtMs);
        } catch (Exception e) {
            LOG.warn("Failed to parse stored OAuth tokens for " + serverUrl + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Removes stored tokens for the given server URL.
     */
    public static void clear(@NotNull String serverUrl) {
        PasswordSafe.getInstance().set(attrs(serverUrl), null);
        LOG.info("Cleared OAuth tokens for " + serverUrl);
    }

    @NotNull
    private static CredentialAttributes attrs(@NotNull String serverUrl) {
        return new CredentialAttributes(
            CredentialAttributesKt.generateServiceName(SERVICE_NAME, normalizeUrl(serverUrl))
        );
    }

    /**
     * Normalizes a server URL to a stable key by extracting scheme+host+port only.
     * Stripping path and query allows token reuse across path-level endpoint variations
     * (e.g. the same origin serving tools at different paths).
     */
    @NotNull
    static String normalizeUrl(@NotNull String serverUrl) {
        try {
            URI uri = URI.create(serverUrl);
            int port = uri.getPort();
            String host = uri.getHost() != null ? uri.getHost().toLowerCase() : serverUrl;
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "https";
            return port > 0 ? scheme + "://" + host + ":" + port : scheme + "://" + host;
        } catch (Exception e) {
            return serverUrl.toLowerCase();
        }
    }
}
