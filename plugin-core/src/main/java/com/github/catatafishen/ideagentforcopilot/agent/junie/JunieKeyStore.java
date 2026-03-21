package com.github.catatafishen.ideagentforcopilot.agent.junie;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Secure storage for Junie authentication tokens, backed by IntelliJ's {@link PasswordSafe}.
 *
 * <p>Tokens are stored in the OS keychain, KeePass, or master-password vault (platform-dependent).
 * Users can generate a token at <a href="https://junie.jetbrains.com/cli">junie.jetbrains.com/cli</a>.</p>
 */
public final class JunieKeyStore {

    private static final String SERVICE_NAME = "ide-agent-for-copilot.junie";
    private static final String JUNIE_PROFILE_ID = "junie";

    private JunieKeyStore() {
    }

    /**
     * Retrieve the stored Junie authentication token.
     *
     * @return the auth token, or {@code null} if not set
     */
    @Nullable
    public static String getAuthToken() {
        CredentialAttributes attrs = buildAttributes();
        Credentials credentials = PasswordSafe.getInstance().get(attrs);
        if (credentials == null) return null;
        String password = credentials.getPasswordAsString();
        if (password == null || password.isEmpty()) return null;
        return password;
    }

    /**
     * Store a Junie authentication token.
     *
     * @param token the auth token to store, or {@code null} / empty to clear it
     */
    public static void setAuthToken(@Nullable String token) {
        CredentialAttributes attrs = buildAttributes();
        if (token == null || token.isEmpty()) {
            PasswordSafe.getInstance().set(attrs, null);
        } else {
            PasswordSafe.getInstance().set(attrs, new Credentials(JUNIE_PROFILE_ID, token));
        }
    }

    /**
     * Returns {@code true} if an auth token is stored.
     */
    public static boolean hasAuthToken() {
        return getAuthToken() != null;
    }

    private static CredentialAttributes buildAttributes() {
        return new CredentialAttributes(
            CredentialAttributesKt.generateServiceName(SERVICE_NAME, JUNIE_PROFILE_ID)
        );
    }
}
