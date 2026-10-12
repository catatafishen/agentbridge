package com.github.catatafishen.agentbridge.client.acp;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Reads session data from the OpenCode HTTP server started by {@code opencode acp}
 * (see {@link OpenCodeServerEndpoint}). Blocking; call from a background thread.
 */
public final class OpenCodeSessionHttpClient {

    private static final String PASSWORD_ENV = "OPENCODE_SERVER_PASSWORD";
    private static final String DEFAULT_USERNAME = "opencode";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    private OpenCodeSessionHttpClient() {
    }

    /**
     * Fetches the messages of [sessionId] and formats them as Markdown.
     *
     * @throws IOException if the server address is unknown, unreachable, or answers with an error
     */
    public static @NotNull String fetchTranscriptMarkdown(@NotNull String sessionId)
        throws IOException, InterruptedException {
        String baseUrl = OpenCodeServerEndpoint.baseUrl();
        if (baseUrl == null) {
            throw new IOException("OpenCode HTTP server address is unknown "
                + "(the agent was started with an unparseable --port, or has not been started by AgentBridge)");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create(baseUrl + "/session/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/message"))
            .timeout(TIMEOUT)
            .GET();
        String password = System.getenv(PASSWORD_ENV);
        if (password != null && !password.isEmpty()) {
            String username = System.getenv("OPENCODE_SERVER_USERNAME");
            String credentials = (username != null && !username.isEmpty() ? username : DEFAULT_USERNAME) + ":" + password;
            request.header("Authorization", "Basic "
                + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("OpenCode server returned HTTP " + response.statusCode()
                + " for session " + sessionId);
        }
        try {
            return OpenCodeTranscriptFormatter.toMarkdown(response.body());
        } catch (RuntimeException e) {
            throw new IOException("Unexpected response from OpenCode server: " + e.getMessage(), e);
        }
    }
}
