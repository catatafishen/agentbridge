package com.github.catatafishen.agentbridge.acp.client;

import com.github.catatafishen.agentbridge.agent.AbstractAgentClient;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenCodeClientBehaviorTest {

    private static final Gson GSON = new Gson();

    @TempDir
    Path tempDir;

    @Test
    void buildPermissionConfigOmitsDefaultAgentWhenNullOrBlank() {
        JsonObject nullConfig = GSON.fromJson(
            OpenCodeClient.buildPermissionConfig(null).get("OPENCODE_CONFIG_CONTENT"),
            JsonObject.class
        );
        JsonObject blankConfig = GSON.fromJson(
            OpenCodeClient.buildPermissionConfig("   ").get("OPENCODE_CONFIG_CONTENT"),
            JsonObject.class
        );

        assertFalse(nullConfig.has("default_agent"));
        assertFalse(blankConfig.has("default_agent"));
    }

    @Test
    void buildEnvironmentUsesSelectedPlanAgent() throws Exception {
        OpenCodeClient client = new OpenCodeClient(mock(Project.class));
        client.setCurrentAgentSlug("plan");

        JsonObject config = GSON.fromJson(
            invokeBuildEnvironment(client).get("OPENCODE_CONFIG_CONTENT"),
            JsonObject.class
        );

        assertEquals("plan", config.get("default_agent").getAsString());
    }

    @Test
    void buildEnvironmentFallsBackToBuildForNonPlanSelection() throws Exception {
        OpenCodeClient client = new OpenCodeClient(mock(Project.class));
        client.setCurrentAgentSlug("custom-agent");

        JsonObject config = GSON.fromJson(
            invokeBuildEnvironment(client).get("OPENCODE_CONFIG_CONTENT"),
            JsonObject.class
        );

        assertEquals("build", config.get("default_agent").getAsString());
    }

    @Test
    void getAvailableAgentsIncludesProjectAgentsWithoutShadowingBuiltIns() throws Exception {
        Project project = mock(Project.class);
        when(project.getBasePath()).thenReturn(tempDir.toString());

        Files.createDirectories(tempDir.resolve(".opencode/agent"));
        Files.createDirectories(tempDir.resolve(".opencode/agents"));
        Files.writeString(
            tempDir.resolve(".opencode/agent/shared.md"),
            "---\nname: Shared Primary\ndescription: First\n---\n",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            tempDir.resolve(".opencode/agents/shared.md"),
            "---\nname: Shared Secondary\ndescription: Second\n---\n",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            tempDir.resolve(".opencode/agents/local.md"),
            "---\nname: Local Agent\ndescription: Local description\n---\n",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            tempDir.resolve(".opencode/agents/build.md"),
            "---\nname: Shadow Build\n---\n",
            StandardCharsets.UTF_8
        );

        OpenCodeClient client = new OpenCodeClient(project);
        List<AbstractAgentClient.AgentMode> agents = client.getAvailableAgents();

        assertEquals(
            List.of("build", "plan", "general", "explore", "shared", "local"),
            agents.stream().map(AbstractAgentClient.AgentMode::slug).toList()
        );
        assertEquals("Shared Primary", agents.get(4).name());
        assertTrue(agents.stream().noneMatch(agent -> "Shadow Build".equals(agent.name())));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> invokeBuildEnvironment(OpenCodeClient client) throws Exception {
        Method method = OpenCodeClient.class.getDeclaredMethod("buildEnvironment", int.class, String.class);
        method.setAccessible(true);
        return (Map<String, String>) method.invoke(client, 8123, tempDir.toString());
    }
}
