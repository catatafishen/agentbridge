package com.github.catatafishen.agentbridge.client.acp;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.function.UnaryOperator;

/**
 * Reads session data from the OpenCode HTTP server started by {@code opencode acp}
 * (see {@link OpenCodeServerEndpoint}). Blocking; call from a background thread.
 */
public final class OpenCodeSessionHttpClient {

    private static final String PASSWORD_ENV = "OPENCODE_SERVER_PASSWORD";
    private static final String USERNAME_ENV = "OPENCODE_SERVER_USERNAME";
    private static final String DEFAULT_USERNAME = "opencode";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    private OpenCodeSessionHttpClient() {
    }

    /**
     * Fetches and parses the messages of [sessionId] from the server at [baseUrl].
     *
     * @param environment looks up an environment variable the way the OpenCode process saw it at launch,
     *                    so the credentials match the ones protecting the server; returns {@code null}
     *                    for an unset variable
     * @throws IOException if the server is unreachable or answers with an error
     */
    public static @NotNull OpenCodeTranscript fetchTranscript(
        @NotNull String baseUrl,
        @NotNull String sessionId,
        @NotNull UnaryOperator<String> environment
    ) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create(baseUrl + "/session/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/message"))
            .timeout(TIMEOUT)
            .GET();
        String authorization = authorizationHeader(environment);
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("OpenCode server returned HTTP " + response.statusCode()
                + " for session " + sessionId);
        }
        try {
            return OpenCodeTranscript.parse(response.body());
        } catch (RuntimeException e) {
            throw new IOException("Unexpected response from OpenCode server: " + e.getMessage(), e);
        }
    }

    /**
     * The HTTP Basic header for {@code OPENCODE_SERVER_PASSWORD} (user {@code OPENCODE_SERVER_USERNAME},
     * default {@code opencode}), or {@code null} when no password protects the server.
     */
    static @Nullable String authorizationHeader(@NotNull UnaryOperator<String> environment) {
        String password = environment.apply(PASSWORD_ENV);
        if (password == null || password.isEmpty()) {
            return null;
        }
        String username = environment.apply(USERNAME_ENV);
        String credentials = (username != null && !username.isEmpty() ? username : DEFAULT_USERNAME) + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
