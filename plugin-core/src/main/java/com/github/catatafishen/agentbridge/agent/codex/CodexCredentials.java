package com.github.catatafishen.agentbridge.agent.codex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the Codex CLI credential file ({@code ~/.codex/auth.json}) written by
 * {@code codex login} to determine whether the user is authenticated.
 *
 * <p>The file may contain either an {@code api_key} field (API key auth) or an
 * {@code access_token} + {@code expires_at} (OAuth/ChatGPT auth). The credentials
 * are used only to check login state — they are NOT sent anywhere by this plugin;
 * the {@code codex} subprocess handles all actual API calls.</p>
 */
public final class CodexCredentials {

    private static final Logger LOG = Logger.getInstance(CodexCredentials.class);

    /**
     * Default location: {@code $CODEX_HOME/auth.json}, falling back to {@code ~/.codex/auth.json}.
     */
    private static Path resolveAuthPath() {
        String codexHome = System.getenv("CODEX_HOME");
        if (codexHome != null && !codexHome.isBlank()) {
            return Path.of(codexHome, "auth.json");
        }
        return Path.of(System.getProperty("user.home"), ".codex", "auth.json");
    }

    private final boolean loggedIn;
    @Nullable
    private final String displayName;

    private CodexCredentials(boolean loggedIn, @Nullable String displayName) {
        this.loggedIn = loggedIn;
        this.displayName = displayName;
    }

    /**
     * Reads credentials from disk and returns a snapshot.
     * Never throws — returns a "not logged in" instance on any error.
     */
    @NotNull
    public static CodexCredentials read() {
        Path path = resolveAuthPath();
        try {
            if (!Files.exists(path)) {
                return new CodexCredentials(false, null);
            }
            String content = Files.readString(path);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();

            // API key auth: presence of api_key is sufficient
            if (root.has("api_key") && !root.get("api_key").isJsonNull()) {
                String key = root.get("api_key").getAsString();
                if (!key.isBlank()) {
                    return new CodexCredentials(true, null);
                }
            }

            // Nested tokens format written by `codex login --device-auth`:
            // { "auth_mode": "chatgpt", "tokens": { "access_token": "...", ... }, "last_refresh": "..." }
            if (root.has("tokens") && root.get("tokens").isJsonObject()) {
                JsonObject tokens = root.getAsJsonObject("tokens");
                if (tokens.has("access_token") && !tokens.get("access_token").isJsonNull()) {
                    String token = tokens.get("access_token").getAsString();
                    if (!token.isBlank()) {
                        return new CodexCredentials(true, null);
                    }
                }
            }

            // Flat OAuth / ChatGPT auth: access_token + optional expires_at (older format)
            if (root.has("access_token") && !root.get("access_token").isJsonNull()) {
                String token = root.get("access_token").getAsString();
                if (token.isBlank()) {
                    return new CodexCredentials(false, null);
                }
                // expires_at is Unix seconds (unlike Claude CLI which uses ms)
                long expiresAt = root.has("expires_at") ? root.get("expires_at").getAsLong() : 0L;
                boolean expired = expiresAt > 0 && System.currentTimeMillis() / 1000 > expiresAt;
                if (expired) {
                    LOG.info("Codex credentials are expired (expires_at=" + expiresAt + ")");
                    return new CodexCredentials(false, null);
                }
                String name = root.has("email") ? root.get("email").getAsString() : null;
                return new CodexCredentials(true, name);
            }

            return new CodexCredentials(false, null);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Failed to read Codex credentials from " + path + ": " + e.getMessage());
            return new CodexCredentials(false, null);
        }
    }

    /**
     * Returns true if the user is logged in with a non-expired credential.
     */
    public boolean isLoggedIn() {
        return loggedIn;
    }

    /**
     * Email or display name of the logged-in account, or null if not available.
     */
    @Nullable
    public String getDisplayName() {
        return displayName;
    }
}
