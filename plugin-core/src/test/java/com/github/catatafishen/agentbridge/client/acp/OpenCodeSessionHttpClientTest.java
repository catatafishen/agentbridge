package com.github.catatafishen.agentbridge.client.acp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenCodeSessionHttpClientTest {

    @Test
    void noPasswordMeansNoAuthorizationHeader() {
        assertNull(OpenCodeSessionHttpClient.authorizationHeader(name -> null));
        assertNull(OpenCodeSessionHttpClient.authorizationHeader(
            name -> Map.of("OPENCODE_SERVER_PASSWORD", "").get(name)));
    }

    @Test
    void passwordAloneUsesTheDefaultUsername() {
        // "opencode:secret"
        assertEquals("Basic b3BlbmNvZGU6c2VjcmV0", OpenCodeSessionHttpClient.authorizationHeader(
            Map.of("OPENCODE_SERVER_PASSWORD", "secret")::get));
    }

    @Test
    void configuredUsernameIsUsed() {
        // "me:secret"
        assertEquals("Basic bWU6c2VjcmV0", OpenCodeSessionHttpClient.authorizationHeader(
            Map.of("OPENCODE_SERVER_PASSWORD", "secret", "OPENCODE_SERVER_USERNAME", "me")::get));
    }

    @Test
    void emptyUsernameFallsBackToTheDefault() {
        assertEquals("Basic b3BlbmNvZGU6c2VjcmV0", OpenCodeSessionHttpClient.authorizationHeader(
            Map.of("OPENCODE_SERVER_PASSWORD", "secret", "OPENCODE_SERVER_USERNAME", "")::get));
    }

    @Test
    void shellEnvironmentSuppliesTheCredentialsTheIdeProcessLacks() {
        String name = "AGENTBRIDGE_TEST_VARIABLE_THAT_IS_NOT_SET_IN_THE_IDE";

        assertEquals("from-shell", OpenCodeClient.launchEnvironmentVariable(Map.of(name, "from-shell"), name));
        assertNull(OpenCodeClient.launchEnvironmentVariable(Map.of(), name));
    }

    @Test
    void shellEnvironmentOverridesTheIdeEnvironmentLikeAtLaunch() {
        // PATH is set in any test JVM; the captured shell value must win, as it does in AcpClient.
        assertEquals("shell-path", OpenCodeClient.launchEnvironmentVariable(Map.of("PATH", "shell-path"), "PATH"));
    }
}
