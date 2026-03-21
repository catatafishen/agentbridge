package com.github.catatafishen.ideagentforcopilot.agent.claude;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the Claude CLI credential file ({@code ~/.claude/.credentials.json}) written by
 * {@code claude auth login} to determine whether the user is logged in.
 *
 * <p>The file contains an {@code oauthAccount} section and a {@code claudeAiOauth} section
 * with {@code accessToken} and {@code expiresAt} (Unix ms). The access token authenticates
 * the {@code claude} subprocess — it is NOT sent to any external API by this plugin.</p>
 */
public final class ClaudeCliCredentials {

    private static final Logger LOG = Logger.getInstance(ClaudeCliCredentials.class);
    private static final Path CREDENTIALS_PATH =
        Path.of(System.getProperty("user.home"), ".claude", ".credentials.json");

    private final boolean loggedIn;
    @Nullable
    private final String displayName;

    private ClaudeCliCredentials(boolean loggedIn, @Nullable String displayName) {
        this.loggedIn = loggedIn;
        this.displayName = displayName;
    }

    /**
     * Reads credentials from disk and returns a snapshot.
     * Never throws — returns a "not logged in" instance on any error.
     */
    @NotNull
    public static ClaudeCliCredentials read() {
        try {
            if (!Files.exists(CREDENTIALS_PATH)) {
                return new ClaudeCliCredentials(false, null);
            }
            String content = Files.readString(CREDENTIALS_PATH);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            if (!root.has("claudeAiOauth")) {
                return new ClaudeCliCredentials(false, null);
            }
            JsonObject oauth = root.getAsJsonObject("claudeAiOauth");
            String token = oauth.has("accessToken") ? oauth.get("accessToken").getAsString() : null;
            if (token == null || token.isEmpty()) {
                return new ClaudeCliCredentials(false, null);
            }

            long expiresAt = oauth.has("expiresAt") ? oauth.get("expiresAt").getAsLong() : 0L;
            boolean expired = expiresAt > 0 && System.currentTimeMillis() > expiresAt;
            if (expired) {
                LOG.info("Claude CLI credentials are expired (expiresAt=" + expiresAt + ")");
                return new ClaudeCliCredentials(false, null);
            }

            String name = null;
            if (root.has("oauthAccount")) {
                JsonObject account = root.getAsJsonObject("oauthAccount");
                if (account.has("displayName")) {
                    name = account.get("displayName").getAsString();
                } else if (account.has("emailAddress")) {
                    name = account.get("emailAddress").getAsString();
                }
            }
            return new ClaudeCliCredentials(true, name);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Failed to read Claude CLI credentials: " + e.getMessage());
            return new ClaudeCliCredentials(false, null);
        }
    }

    /** Returns true if the user is logged in with a non-expired token. */
    public boolean isLoggedIn() {
        return loggedIn;
    }

    /**
     * Display name or email of the logged-in account, or null if not available.
     */
    @Nullable
    public String getDisplayName() {
        return displayName;
    }
}
