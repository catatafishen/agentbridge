package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.client.nested.NestedSessionPolicy;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every ACP agent reaches the in-IDE MCP server over a URL, and a nested session's agent
 * must reach it through the endpoint that carries the session's key. Otherwise, the server cannot tell its tool
 * calls from the chat's, and the nested session would not be held to the same rules whatever agent runs it.
 * The stdio proxy command is covered by {@code McpStdioLaunchTest}.
 */
class NestedSessionEndpointWiringTest {

    private static final int PORT = 8642;
    private static final String KEY = NestedSessionPolicy.newSessionKey();
    private static final String NESTED_PATH = NestedSessionPolicy.endpointPath(KEY);

    private static String urlOf(JsonObject params) {
        return params.getAsJsonArray("mcpServers").get(0).getAsJsonObject().get("url").getAsString();
    }

    @Test
    void hermesGivesItsNestedSessionTheNestedEndpoint() {
        JsonObject params = new JsonObject();

        HermesClient.addMcpServerConfig(PORT, NESTED_PATH, params);

        assertEquals("http://127.0.0.1:" + PORT + NESTED_PATH, urlOf(params));
    }

    @Test
    void vibeGivesItsNestedSessionTheNestedEndpoint() {
        JsonObject params = new JsonObject();

        VibeClient.addMcpServerConfig(PORT, NESTED_PATH, params);

        assertEquals("http://127.0.0.1:" + PORT + NESTED_PATH, urlOf(params));
    }

    @Test
    void openCodeGivesItsNestedSessionTheNestedEndpointForOldVersions() {
        JsonObject params = new JsonObject();

        OpenCodeClient.applySessionMcpServers("1.19.0", PORT, NESTED_PATH, params);

        assertEquals("http://127.0.0.1:" + PORT + NESTED_PATH, urlOf(params));
    }

    @Test
    void openCodeDeclaresTheNestedEndpointInTheLaunchConfigOfVersion2() {
        Map<String, String> env = OpenCodeClient.buildInitConfig("2.2.5", PORT, () -> true, NESTED_PATH);

        JsonObject server = JsonParser.parseString(env.get("OPENCODE_CONFIG_CONTENT")).getAsJsonObject()
            .getAsJsonObject("mcp").getAsJsonObject("servers").getAsJsonObject("agentbridge");
        assertEquals("http://127.0.0.1:" + PORT + NESTED_PATH, server.get("url").getAsString());
    }

    @Test
    void openCodeVersion2DoesNotRegisterTheServerAgainPerSession() {
        JsonObject params = new JsonObject();

        OpenCodeClient.applySessionMcpServers("2.2.5", PORT, NESTED_PATH, params);

        assertEquals(0, params.getAsJsonArray("mcpServers").size());
    }

    @Test
    void theChatsOwnEndpointStaysTheDefaultOneForEveryAgent() {
        JsonObject hermes = new JsonObject();
        JsonObject vibe = new JsonObject();
        JsonObject openCode = new JsonObject();

        HermesClient.addMcpServerConfig(PORT, hermes);
        VibeClient.addMcpServerConfig(PORT, vibe);
        OpenCodeClient.applySessionMcpServers("1.19.0", PORT, openCode);

        for (JsonObject params : List.of(hermes, vibe, openCode)) {
            assertEquals("http://127.0.0.1:" + PORT + "/mcp", urlOf(params));
        }
    }
}
