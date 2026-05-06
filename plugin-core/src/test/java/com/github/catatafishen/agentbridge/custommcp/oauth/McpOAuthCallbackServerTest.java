package com.github.catatafishen.agentbridge.custommcp.oauth;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpOAuthCallbackServerTest {

    @Test
    void waitForCallback_codeAndStateAreReturnedFromRedirect() throws IOException, InterruptedException {
        try (McpOAuthCallbackServer srv = new McpOAuthCallbackServer()) {
            srv.start();
            String callbackUri = srv.getCallbackUri() + "?code=auth-code-123&state=mystate";

            // Simulate browser redirect from a background thread.
            Thread browser = new Thread(() -> {
                try {
                    HttpURLConnection conn = (HttpURLConnection) URI.create(callbackUri).toURL().openConnection();
                    conn.setConnectTimeout(5_000);
                    conn.setReadTimeout(5_000);
                    // Read the body so the server can finish writing the response.
                    conn.getInputStream().readAllBytes();
                    conn.disconnect();
                } catch (IOException ignored) {
                    // Connection reset after server closes is expected.
                }
            });
            browser.setDaemon(true);
            browser.start();

            McpOAuthCallbackServer.Result result = srv.waitForCallback(10, TimeUnit.SECONDS);

            assertNotNull(result, "callback result should not be null");
            assertEquals("auth-code-123", result.code());
            assertEquals("mystate", result.state());
        }
    }

    @Test
    void waitForCallback_timeoutReturnsNull() throws IOException, InterruptedException {
        try (McpOAuthCallbackServer srv = new McpOAuthCallbackServer()) {
            srv.start();
            // Don't send a request — should time out quickly.
            McpOAuthCallbackServer.Result result = srv.waitForCallback(200, TimeUnit.MILLISECONDS);
            assertNull(result, "should return null on timeout");
        }
    }

    @Test
    void waitForCallback_errorParameter_returnsNull() throws IOException, InterruptedException {
        try (McpOAuthCallbackServer srv = new McpOAuthCallbackServer()) {
            srv.start();
            String callbackUri = srv.getCallbackUri() + "?error=access_denied";

            Thread browser = new Thread(() -> {
                try {
                    HttpURLConnection conn = (HttpURLConnection) URI.create(callbackUri).toURL().openConnection();
                    conn.setConnectTimeout(5_000);
                    conn.setReadTimeout(5_000);
                    conn.getInputStream().readAllBytes();
                    conn.disconnect();
                } catch (IOException ignored) {
                }
            });
            browser.setDaemon(true);
            browser.start();

            McpOAuthCallbackServer.Result result = srv.waitForCallback(10, TimeUnit.SECONDS);
            assertNull(result, "error redirect should return null");
        }
    }

    @Test
    void waitForCallback_errorWithDescription_returnsNull() throws IOException, InterruptedException {
        try (McpOAuthCallbackServer srv = new McpOAuthCallbackServer()) {
            srv.start();
            String callbackUri = srv.getCallbackUri() + "?error=access_denied&error_description=User+denied+the+request";

            Thread browser = new Thread(() -> {
                try {
                    HttpURLConnection conn = (HttpURLConnection) URI.create(callbackUri).toURL().openConnection();
                    conn.setConnectTimeout(5_000);
                    conn.setReadTimeout(5_000);
                    conn.getInputStream().readAllBytes();
                    conn.disconnect();
                } catch (IOException ignored) {
                }
            });
            browser.setDaemon(true);
            browser.start();

            McpOAuthCallbackServer.Result result = srv.waitForCallback(10, TimeUnit.SECONDS);
            assertNull(result, "error with description should return null");
        }
    }

    @Test
    void waitForCallback_missingCode_returnsNull() throws IOException, InterruptedException {
        try (McpOAuthCallbackServer srv = new McpOAuthCallbackServer()) {
            srv.start();
            // Redirect has state but no code parameter.
            String callbackUri = srv.getCallbackUri() + "?state=mystate";

            Thread browser = new Thread(() -> {
                try {
                    HttpURLConnection conn = (HttpURLConnection) URI.create(callbackUri).toURL().openConnection();
                    conn.setConnectTimeout(5_000);
                    conn.setReadTimeout(5_000);
                    conn.getInputStream().readAllBytes();
                    conn.disconnect();
                } catch (IOException ignored) {
                }
            });
            browser.setDaemon(true);
            browser.start();

            McpOAuthCallbackServer.Result result = srv.waitForCallback(10, TimeUnit.SECONDS);
            assertNull(result, "missing code should return null");
        }
    }

    @Test
    void getCallbackUri_usesLocalhostAndBoundPort() throws IOException {
        try (McpOAuthCallbackServer srv = new McpOAuthCallbackServer()) {
            srv.start();
            String uri = srv.getCallbackUri();
            assertNotNull(uri);
            // Must be a loopback URI — never 0.0.0.0.
            assertTrue(uri.startsWith("http://localhost:"), "callback URI should use localhost");
        }
    }
}
