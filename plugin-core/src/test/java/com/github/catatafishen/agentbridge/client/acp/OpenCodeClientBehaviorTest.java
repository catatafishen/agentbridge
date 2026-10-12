package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.client.AbstractClient;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenCodeClientBehaviorTest {

    private static final Gson GSON = new Gson();

    @TempDir
    Path tempDir;

    @Test
    void buildPermissionConfigNeverIncludesDefaultAgent() {
        // default_agent is intentionally omitted because OpenCode v1.4.10+ rejects
        // subagent slugs (like "build") as the default_agent value.
        JsonObject config = GSON.fromJson(
            OpenCodeClient.buildPermissionConfig().get("OPENCODE_CONFIG_CONTENT"),
            JsonObject.class
        );

        assertFalse(config.has("default_agent"));
    }

    @Test
    void buildEnvironmentNeverIncludesDefaultAgent() throws Exception {
        OpenCodeClient client = new OpenCodeClient(mock(Project.class));
        client.setCurrentAgentSlug("plan");

        JsonObject config = GSON.fromJson(
            invokeBuildEnvironment(client).get("OPENCODE_CONFIG_CONTENT"),
            JsonObject.class
        );

        // Even with a specific agent selected, OPENCODE_CONFIG_CONTENT should not
        // include default_agent — the plugin controls agent selection via session/create.
        assertFalse(config.has("default_agent"));
    }

    @Test
    void buildEnvironmentContainsPermissionDenyEntries() throws Exception {
        OpenCodeClient client = new OpenCodeClient(mock(Project.class));

        JsonObject config = GSON.fromJson(
            invokeBuildEnvironment(client).get("OPENCODE_CONFIG_CONTENT"),
            JsonObject.class
        );

        assertTrue(config.has("permission"));
        JsonObject permission = config.getAsJsonObject("permission");
        for (String tool : OpenCodeClient.nativeToolsToDeny()) {
            assertTrue(permission.has(tool), "Missing tool: " + tool);
            assertEquals("deny", permission.get(tool).getAsString());
        }
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
        List<AbstractClient.AgentMode> agents = client.getAvailableAgents();

        assertEquals(
            List.of("build", "plan", "general", "explore", "scout", "shared", "local"),
            agents.stream().map(AbstractClient.AgentMode::slug).toList()
        );
        assertEquals("Shared Primary", agents.get(5).name());
        assertTrue(agents.stream().noneMatch(agent -> "Shadow Build".equals(agent.name())));
    }

    private static final String TASK_OUTPUT = """
        <task id="ses_child" state="completed">
        <task_result>
        done
        </task_result>
        </task>""";

    private static List<String> acpCommand(String... extraArgs) {
        List<String> cmd = new ArrayList<>(List.of("/usr/bin/opencode", "acp"));
        cmd.addAll(List.of(extraArgs));
        return cmd;
    }

    @Test
    void opencode1GetsAFreePortAppended() {
        OpenCodeClient client = new OpenCodeClient(mock(Project.class));
        List<String> cmd = acpCommand();

        client.pinServerPort(cmd, () -> "1.19.0");

        assertEquals(4, cmd.size());
        assertEquals("--port", cmd.get(2));
        assertTrue(Integer.parseInt(cmd.get(3)) > 0);
    }

    @Test
    void opencode2NeverGetsAPortFlag() {
        // OpenCode 2's `acp` command exits on unknown flags ("Unrecognized flag: --port").
        OpenCodeClient client = new OpenCodeClient(mock(Project.class));
        List<String> cmd = acpCommand();

        client.pinServerPort(cmd, () -> "2.0.27");

        assertEquals(List.of("/usr/bin/opencode", "acp"), cmd);
    }

    @Test
    void anUnknownVersionNeverGetsAPortFlag() {
        // The agent must launch even when the version cannot be determined.
        OpenCodeClient client = new OpenCodeClient(mock(Project.class));
        List<String> cmd = acpCommand();

        client.pinServerPort(cmd, () -> null);

        assertEquals(List.of("/usr/bin/opencode", "acp"), cmd);
    }

    @Test
    void anExplicitPortIsKeptAndTheVersionIsNotProbed() {
        OpenCodeClient client = new OpenCodeClient(mock(Project.class));
        List<String> cmd = acpCommand("--port", "4096");

        client.pinServerPort(cmd, () -> {
            throw new AssertionError("an explicit --port must not trigger a version probe");
        });

        assertEquals(List.of("/usr/bin/opencode", "acp", "--port", "4096"), cmd);
    }

    @Test
    void opencode2HasNoTranscriptSourceButThatIsNotAnError() throws Exception {
        OpenCodeClient client = new OpenCodeClient(mock(Project.class));
        client.pinServerPort(acpCommand(), () -> "2.0.27");

        assertNull(client.loadSubAgentTranscript("sa1", TASK_OUTPUT),
            "an IOException would put an error entry in the chat after every sub-agent");
    }

    @Test
    void aMissingServerAddressOnOpencode1IsAnError() {
        // Before launch (or after stop) there is no address although a server should exist.
        OpenCodeClient client = new OpenCodeClient(mock(Project.class));

        assertThrows(IOException.class, () -> client.loadSubAgentTranscript("sa1", TASK_OUTPUT));
    }

    @Test
    void relaunchingOnOpencode1AfterOpencode2ExpectsAServerAgain() {
        OpenCodeClient client = new OpenCodeClient(mock(Project.class));
        client.pinServerPort(acpCommand(), () -> "2.0.27");

        client.pinServerPort(acpCommand(), () -> "1.19.0");

        // The 1.x launch published a port; the request goes to it (nothing listens there, so it fails
        // as an I/O error instead of silently returning no transcript).
        assertThrows(IOException.class, () -> client.loadSubAgentTranscript("sa1", TASK_OUTPUT));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> invokeBuildEnvironment(OpenCodeClient client) throws Exception {
        Method method = OpenCodeClient.class.getDeclaredMethod("buildEnvironment", int.class, String.class);
        method.setAccessible(true);
        return (Map<String, String>) method.invoke(client, 8123, tempDir.toString());
    }
}
