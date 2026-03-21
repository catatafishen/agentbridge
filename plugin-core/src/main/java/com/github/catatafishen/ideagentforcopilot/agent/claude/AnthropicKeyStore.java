package com.github.catatafishen.ideagentforcopilot.agent.claude;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Secure storage for Anthropic API keys, backed by IntelliJ's {@link PasswordSafe}.
 *
 * <p>Keys are stored per agent profile ID so different profiles can use different API keys.
 * The credential subsystem (OS keychain, KeePass, or master-password vault) is chosen
 * automatically by IntelliJ based on the platform.</p>
 */
public final class AnthropicKeyStore {

    private static final String SERVICE_NAME = "ide-agent-for-copilot.anthropic";

    private AnthropicKeyStore() {
    }

    /**
     * Retrieve the stored Anthropic API key for the given profile.
     *
     * @param profileId the agent profile ID (e.g. "claude-code")
     * @return the API key, or {@code null} if not set
     */
    @Nullable
    public static String getApiKey(@NotNull String profileId) {
        CredentialAttributes attrs = buildAttributes(profileId);
        Credentials credentials = PasswordSafe.getInstance().get(attrs);
        if (credentials == null) return null;
        String password = credentials.getPasswordAsString();
        if (password == null || password.isEmpty()) return null;
        return password;
    }

    /**
     * Store an Anthropic API key for the given profile.
     *
     * @param profileId the agent profile ID
     * @param apiKey    the API key to store, or {@code null} / empty to clear it
     */
    public static void setApiKey(@NotNull String profileId, @Nullable String apiKey) {
        CredentialAttributes attrs = buildAttributes(profileId);
        if (apiKey == null || apiKey.isEmpty()) {
            PasswordSafe.getInstance().set(attrs, null);
        } else {
            PasswordSafe.getInstance().set(attrs, new Credentials(profileId, apiKey));
        }
    }

    /**
     * Returns {@code true} if an API key is stored for the given profile.
     */
    public static boolean hasApiKey(@NotNull String profileId) {
        return getApiKey(profileId) != null;
    }

    private static CredentialAttributes buildAttributes(@NotNull String profileId) {
        return new CredentialAttributes(
            CredentialAttributesKt.generateServiceName(SERVICE_NAME, profileId)
        );
    }
}
