package com.github.catatafishen.agentbridge.custommcp.oauth;

import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates Proof Key for Code Exchange (PKCE) parameters as defined by
 * <a href="https://datatracker.ietf.org/doc/html/rfc7636">RFC 7636</a>.
 *
 * <p>Verifier length is 64 bytes of entropy, base64url-encoded to 86 characters —
 * well within the 43–128 character range required by the spec.
 * The challenge method is always {@code S256}.
 */
public final class McpOAuthPkce {

    /** Generated PKCE verifier and its derived challenge. */
    public record Params(
        @NotNull String verifier,
        @NotNull String challenge
    ) {
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private McpOAuthPkce() {
    }

    /**
     * Generates a fresh PKCE verifier/challenge pair using a {@link SecureRandom} source.
     *
     * @throws IllegalStateException if SHA-256 is not available (should never happen on JVM 21)
     */
    @NotNull
    public static Params generate() {
        byte[] bytes = new byte[64];
        SECURE_RANDOM.nextBytes(bytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String challenge = computeChallenge(verifier);
        return new Params(verifier, challenge);
    }

    /**
     * Returns a secure random state nonce for CSRF protection.
     */
    @NotNull
    public static String generateState() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @NotNull
    static String computeChallenge(@NotNull String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
