package com.github.catatafishen.ideagentforcopilot.acp.client;

import com.github.catatafishen.ideagentforcopilot.acp.model.PromptResponse;
import com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate;
import com.github.catatafishen.ideagentforcopilot.agent.junie.JunieKeyStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * JetBrains Junie ACP client.
 * <p>
 * Tool prefix: {@code Tool: agentbridge/read_file} → strip {@code Tool: agentbridge/}
 * MCP: injected via session/new mcpServers array
 * Model display: token count
 * Correlation: ToolChipRegistry handles chip correlation via args hash
 */
public final class JunieClient extends AcpClient {

    private static final Logger LOG = Logger.getInstance(JunieClient.class);

    /**
     * Set when we detect that Junie's process has a poisoned permission-response queue.
     * The process must be restarted before the next session to clear the error queue.
     */
    private volatile boolean restartBeforeNextSession = false;

    public JunieClient(Project project) {
        super(project);
    }

    @Override
    public String agentId() {
        return "junie";
    }

    @Override
    public String displayName() {
        return "Junie";
    }

    @Override
    protected List<String> buildCommand(String cwd, int mcpPort) {
        List<String> cmd = new ArrayList<>();
        cmd.add("junie");
        cmd.add("--acp=true");

        // If user has configured an auth token in plugin settings, use it instead of relying on CLI credentials
        String authToken = JunieKeyStore.getAuthToken();
        if (authToken != null && !authToken.isEmpty()) {
            cmd.add("--auth=" + authToken);
            LOG.info("Junie: using plugin-configured auth token");
        } else {
            LOG.info("Junie: using CLI credentials (no token configured in plugin settings)");
        }

        return cmd;
    }

    @Override
    protected void customizeNewSession(String cwd, int mcpPort, JsonObject params) {
        // Junie injects MCP via session/new mcpServers array using stdio (command + args)
        JsonObject server = buildMcpStdioServer("agentbridge", mcpPort);
        if (server == null) {
            throw new IllegalStateException("Cannot configure Junie MCP server — Java binary or mcp-server.jar not found");
        }
        JsonArray servers = new JsonArray();
        servers.add(server);
        params.add("mcpServers", servers);
    }

    @Override
    protected void onSessionCreated(String sessionId) {
        // Inject tool usage instructions
        sendSessionMessage(sessionId, buildInstructions());
    }

    @Override
    protected String resolveToolId(String protocolTitle) {
        return protocolTitle.replaceFirst("^Tool: agentbridge/", "");
    }

    @Override
    protected JsonObject buildPermissionOutcome(String optionId, @Nullable JsonObject chosenOption) {
        // Junie uses kotlinx.serialization with classDiscriminator = "kind" for RequestPermissionOutcome.
        // The discriminator value must match the option's "kind" field. "outcome" is per ACP spec.
        JsonObject outcome = new JsonObject();
        outcome.addProperty("outcome", "selected");
        String kind = chosenOption != null && chosenOption.has("kind")
            ? chosenOption.get("kind").getAsString() : optionId;
        outcome.addProperty("kind", kind);
        outcome.addProperty("optionId", optionId);
        return outcome;
    }

    @Override
    protected SessionUpdate processUpdate(SessionUpdate update) {
        // No extra processing needed — ToolChipRegistry handles correlation via args hash
        return update;
    }

    @Override
    public ModelDisplayMode modelDisplayMode() {
        return ModelDisplayMode.TOKEN_COUNT;
    }

    /**
     * Junie's {@code RequestPermissionOutcome} deserializer is broken: it uses kotlinx.serialization
     * polymorphic but has no registered subtypes, so ANY permission response we send causes a
     * {@code -32700} error on the subsequent {@code session/prompt} result. The streaming updates
     * already rendered the response in the UI. We recover gracefully here and schedule a process
     * restart so the poisoned error queue is cleared before the next session.
     */
    @Override
    protected @Nullable PromptResponse tryRecoverPromptException(Exception cause) {
        if (isJuniePermissionBug(cause)) {
            LOG.warn("Junie: recovering from known permission-response deserialization bug; process will restart before next session");
            restartBeforeNextSession = true;
            return new PromptResponse("end_turn", null);
        }
        return null;
    }

    /**
     * Restarts the Junie process before creating a new session if the previous session poisoned
     * the error queue via a broken permission response.
     */
    @Override
    protected void beforeCreateSession(String cwd) throws Exception {
        if (!restartBeforeNextSession) return;
        restartBeforeNextSession = false;
        LOG.info("Junie: restarting process to clear poisoned permission-response queue");
        stop();
        start();
    }

    private static boolean isJuniePermissionBug(Throwable t) {
        for (Throwable cursor = t; cursor != null; cursor = cursor.getCause()) {
            String msg = cursor.getMessage();
            if (msg != null && msg.contains("RequestPermissionOutcome")) return true;
        }
        return false;
    }

    private String buildInstructions() {
        return "You have access to IntelliJ IDE tools via the agentbridge MCP server. " +
            "Use these tools for file operations, code navigation, git, and terminal access.";
    }
}
