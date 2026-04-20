package com.github.catatafishen.agentbridge.agent.claude;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Reads the Claude CLI credentials written by {@code claude auth login} to determine whether the
 * user is logged in.
 *
 * <p>Older Claude Code versions store credentials in {@code ~/.claude/.credentials.json}. Claude
 * Code 2.x on macOS stores the same JSON blob in the macOS Keychain under the service name
 * {@code "Claude Code-credentials"}. The access token authenticates the {@code claude}
 * subprocess — it is NOT sent to any external API by this plugin.</p>
 */
public final class ClaudeCliCredentials {

    private static final Logger LOG = Logger.getInstance(ClaudeCliCredentials.class);
    private static final String KEYCHAIN_SERVICE_NAME = "Claude Code-credentials";
    private static final String MACOS_SECURITY_BINARY = "/usr/bin/security";
    private static final int SECURITY_COMMAND_TIMEOUT_SECONDS = 3;

    private final boolean loggedIn;
    @Nullable
    private final String displayName;

    private ClaudeCliCredentials(boolean loggedIn, @Nullable String displayName) {
        this.loggedIn = loggedIn;
        this.displayName = displayName;
    }

    /**
     * Reads credentials from disk (or from the macOS Keychain on macOS) and returns a snapshot.
     * Never throws — returns a "not logged in" instance on any error.
     *
     * <p>Claude Code 2.x stores OAuth credentials in the macOS Keychain under the service name
     * {@code "Claude Code-credentials"} instead of the credentials file. On macOS we therefore
     * try the Keychain as a fallback when the file is absent or contains no valid token.</p>
     */
    @NotNull
    public static ClaudeCliCredentials read() {
        return read(ClaudeCliCredentials::runSecurityCommand);
    }

    @NotNull
    static ClaudeCliCredentials read(@NotNull SecurityCommandRunner securityCommandRunner) {
        // Always try the file first — covers non-macOS platforms and older Claude Code versions.
        Path path = credentialsPath();
        try {
            if (Files.exists(path)) {
                ClaudeCliCredentials fromFile = parseCredentials(Files.readString(path));
                if (fromFile.isLoggedIn()) return fromFile;
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Failed to read Claude CLI credentials file: " + e.getMessage());
        }

        // On macOS, Claude Code 2.x stores tokens in the Keychain instead of the file.
        if (isMac()) {
            return readFromMacOsKeychain(securityCommandRunner);
        }

        return new ClaudeCliCredentials(false, null);
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /**
     * Reads the credential JSON blob stored by Claude Code 2.x in the macOS Keychain
     * (service name {@code "Claude Code-credentials"}) via the {@code security} CLI.
     * Returns a "not logged in" instance if the entry is absent or unreadable.
     */
    @NotNull
    private static ClaudeCliCredentials readFromMacOsKeychain(@NotNull SecurityCommandRunner securityCommandRunner) {
        try {
            String json = securityCommandRunner.run(List.of(
                MACOS_SECURITY_BINARY, "find-generic-password", "-s", KEYCHAIN_SERVICE_NAME, "-w"));
            if (json != null && !json.isBlank()) {
                ClaudeCliCredentials creds = parseCredentials(json);
                if (creds.isLoggedIn()) return creds;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.debug("Interrupted while reading Claude credentials from macOS Keychain");
        } catch (IOException e) {
            LOG.debug("Could not read Claude credentials from macOS Keychain: " + e.getMessage());
        }
        return new ClaudeCliCredentials(false, null);
    }

    @Nullable
    private static String runSecurityCommand(@NotNull List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = pb.start();
        if (!process.waitFor(SECURITY_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return null;
        }
        if (process.exitValue() != 0) {
            return null;
        }
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    }

    /**
     * Parses the credential JSON content and returns a snapshot.
     * Extracted from {@link #read()} for testability — no filesystem dependency.
     */
    @NotNull
    static ClaudeCliCredentials parseCredentials(@NotNull String content) {
        try {
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            if (!root.has("claudeAiOauth")) {
                return new ClaudeCliCredentials(false, null);
            }
            JsonObject oauth = root.getAsJsonObject("claudeAiOauth");
            String token = oauth.has("accessToken") ? oauth.get("accessToken").getAsString() : null;
            if (token == null || token.isEmpty()) {
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
        } catch (RuntimeException e) {
            LOG.warn("Failed to parse Claude CLI credentials: " + e.getMessage());
            return new ClaudeCliCredentials(false, null);
        }
    }

    /**
     * Returns true if the user is logged in with a non-expired token.
     */
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

    /**
     * Deletes the credentials file and, on macOS, the Keychain entry used by Claude Code 2.x.
     *
     * @return true if any credential storage was deleted, false if nothing was removed
     */
    public static boolean logout() {
        return logout(ClaudeCliCredentials::runSecurityCommand);
    }

    static boolean logout(@NotNull SecurityCommandRunner securityCommandRunner) {
        boolean deletedFile = false;
        try {
            deletedFile = Files.deleteIfExists(credentialsPath());
        } catch (IOException e) {
            LOG.warn("Failed to delete Claude CLI credentials: " + e.getMessage());
        }
        return deletedFile || (isMac() && deleteMacOsKeychainCredentials(securityCommandRunner));
    }

    private static boolean deleteMacOsKeychainCredentials(@NotNull SecurityCommandRunner securityCommandRunner) {
        try {
            return securityCommandRunner.run(List.of(
                MACOS_SECURITY_BINARY, "delete-generic-password", "-s", KEYCHAIN_SERVICE_NAME)) != null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.debug("Interrupted while deleting Claude credentials from macOS Keychain");
        } catch (IOException e) {
            LOG.debug("Could not delete Claude credentials from macOS Keychain: " + e.getMessage());
        }
        return false;
    }

    static Path credentialsPath() {
        return Path.of(System.getProperty("user.home"), ".claude", ".credentials.json");
    }

    @FunctionalInterface
    interface SecurityCommandRunner {
        @Nullable String run(@NotNull List<String> command) throws IOException, InterruptedException;
    }
}
