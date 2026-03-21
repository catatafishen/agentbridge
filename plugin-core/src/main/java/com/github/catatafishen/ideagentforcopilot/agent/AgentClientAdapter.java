package com.github.catatafishen.ideagentforcopilot.agent;

import com.github.catatafishen.ideagentforcopilot.acp.model.ContentBlock;
import com.github.catatafishen.ideagentforcopilot.acp.model.PromptRequest;
import com.github.catatafishen.ideagentforcopilot.acp.model.PromptResponse;
import com.github.catatafishen.ideagentforcopilot.bridge.AcpException;
import com.github.catatafishen.ideagentforcopilot.bridge.AgentClient;
import com.github.catatafishen.ideagentforcopilot.bridge.ResourceReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Adapts the new {@link AgentConnector} interface to the legacy {@link AgentClient}
 * interface used by the existing UI (ChatToolWindowContent, PromptOrchestrator).
 *
 * <p>Handles type conversion between {@code acp.model} types and {@code bridge} types.</p>
 */
public class AgentClientAdapter implements AgentClient {

    private final AgentConnector connector;

    public AgentClientAdapter(AgentConnector connector) {
        this.connector = Objects.requireNonNull(connector);
    }

    @Override
    public void start() throws AcpException {
        try {
            connector.start();
        } catch (AgentStartException e) {
            throw new AcpException(e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public String createSession(@Nullable String cwd) throws AcpException {
        try {
            return connector.createSession(cwd);
        } catch (AgentSessionException e) {
            throw new AcpException(e.getMessage(), e);
        }
    }

    @Override
    public void setModel(@NotNull String sessionId, @NotNull String modelId) {
        connector.setModel(sessionId, modelId);
    }

    @NotNull
    @Override
    public String sendPrompt(@NotNull String sessionId,
                             @NotNull String prompt,
                             @Nullable String model,
                             @Nullable List<ResourceReference> references,
                             @Nullable Consumer<String> onChunk,
                             @Nullable Consumer<com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate> onUpdate,
                             @Nullable Runnable onRequest) throws AcpException {
        try {
            List<ContentBlock> contentBlocks = buildContentBlocks(prompt, references);
            PromptRequest request = new PromptRequest(sessionId, contentBlocks, model, connector.getEffectiveModeSlug());

            Consumer<com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate> bridgeConsumer =
                newUpdate -> dispatchUpdate(newUpdate, onChunk, onUpdate);

            if (onRequest != null) {
                onRequest.run();
            }

            PromptResponse response = connector.sendPrompt(request, bridgeConsumer);
            deliverTurnUsage(response, onUpdate);
            return response.stopReason() != null ? response.stopReason() : "end_turn";
        } catch (AgentPromptException e) {
            throw new AcpException(e.getMessage(), e);
        }
    }

    @NotNull
    @Override
    public List<com.github.catatafishen.ideagentforcopilot.acp.model.Model> listModels() {
        return connector.getAvailableModels();
    }

    @Override
    public void cancelSession(@NotNull String sessionId) {
        connector.cancelSession(sessionId);
    }

    @Override
    public boolean isHealthy() {
        return connector.isConnected();
    }

    @Override
    public boolean requiresResourceContentDuplication() {
        if (connector instanceof com.github.catatafishen.ideagentforcopilot.acp.client.AcpClient acpClient) {
            return acpClient.requiresInlineReferences();
        }
        return false;
    }

    @Override
    public boolean supportsMultiplier() {
        return connector.modelDisplayMode() == AgentConnector.ModelDisplayMode.MULTIPLIER;
    }

    @NotNull
    @Override
    public String getModelMultiplier(@NotNull String modelId) {
        for (com.github.catatafishen.ideagentforcopilot.acp.model.Model m : connector.getAvailableModels()) {
            if (modelId.equals(m.id())) {
                String multiplier = connector.getModelMultiplier(m);
                return multiplier != null ? multiplier : "1x";
            }
        }
        return "1x";
    }

    @NotNull
    @Override
    public List<com.github.catatafishen.ideagentforcopilot.bridge.SessionOption> listSessionOptions() {
        List<com.github.catatafishen.ideagentforcopilot.bridge.SessionOption> options = new ArrayList<>();

        // Agent dropdown is plugin-specific (not in ACP configOptions)
        List<AgentConnector.AgentMode> agents = connector.getAvailableAgents();
        if (!agents.isEmpty()) {
            List<String> values = new ArrayList<>();
            values.add("");
            Map<String, String> labels = new LinkedHashMap<>();
            for (AgentConnector.AgentMode agent : agents) {
                values.add(agent.slug());
                labels.put(agent.slug(), agent.name());
            }
            options.add(new com.github.catatafishen.ideagentforcopilot.bridge.SessionOption("agent", "Agent", values, labels));
        }

        // All other dropdowns come from ACP configOptions reported at session start
        for (AgentConnector.AgentConfigOption opt : connector.getAvailableConfigOptions()) {
            List<String> values = new ArrayList<>();
            values.add(""); // empty = keep current
            Map<String, String> labels = new LinkedHashMap<>();
            for (AgentConnector.AgentConfigOptionValue v : opt.values()) {
                values.add(v.id());
                labels.put(v.id(), v.label());
            }
            options.add(new com.github.catatafishen.ideagentforcopilot.bridge.SessionOption(opt.id(), opt.label(), values, labels));
        }

        // Fallback: if no configOptions but modes are present, add a Mode dropdown
        if (connector.getAvailableConfigOptions().isEmpty()) {
            List<AgentConnector.AgentMode> modes = connector.getAvailableModes();
            if (!modes.isEmpty()) {
                List<String> values = new ArrayList<>();
                values.add("");
                Map<String, String> labels = new LinkedHashMap<>();
                for (AgentConnector.AgentMode mode : modes) {
                    values.add(mode.slug());
                    labels.put(mode.slug(), mode.name());
                }
                options.add(new com.github.catatafishen.ideagentforcopilot.bridge.SessionOption("mode", "Mode", values, labels));
            }
        }

        return options;
    }

    @Override
    public void setSessionOption(@NotNull String sessionId, @NotNull String key, @NotNull String value) {
        if ("agent".equals(key)) {
            connector.setCurrentAgentSlug(value.isEmpty() ? null : value);
        } else if ("mode".equals(key)) {
            // Update both: prompt-level modeSlug and the ACP configOption (if supported)
            connector.setCurrentModeSlug(value.isEmpty() ? null : value);
            if (!value.isEmpty()) {
                connector.setConfigOption(sessionId, key, value);
            }
        } else {
            if (!value.isEmpty()) {
                connector.setConfigOption(sessionId, key, value);
            }
        }
    }

    @Override
    public void close() {
        connector.stop();
    }

    // ── Type conversion helpers ─────────────────────

    private List<ContentBlock> buildContentBlocks(String prompt,
                                                  @Nullable List<ResourceReference> references) {
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(new ContentBlock.Text(prompt));

        if (references != null) {
            for (ResourceReference ref : references) {
                blocks.add(new ContentBlock.Resource(
                    new ContentBlock.ResourceLink(ref.uri(), null, ref.mimeType(), ref.text(), null)
                ));
            }
        }
        return blocks;
    }

    private void dispatchUpdate(com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate newUpdate,
                                @Nullable Consumer<String> onChunk,
                                @Nullable Consumer<com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate> onUpdate) {
        if (newUpdate instanceof com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate.AgentMessageChunk(
            var content
        )) {
            dispatchTextChunk(content, onChunk);
            return;
        }

        com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate oldUpdate = convertUpdate(newUpdate);
        if (oldUpdate != null && onUpdate != null) {
            onUpdate.accept(oldUpdate);
        }
    }

    private static void dispatchTextChunk(@Nullable List<ContentBlock> content,
                                          @Nullable Consumer<String> onChunk) {
        if (onChunk == null || content == null) return;
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.Text(String text)) {
                onChunk.accept(text);
            }
        }
    }

    @Nullable
    private static com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate convertUpdate(
        com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate update) {
        return switch (update) {
            case com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate.ToolCall tc -> convertToolCall(tc);
            case com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate.ToolCallUpdate tcu ->
                convertToolCallUpdate(tcu);
            case com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate.AgentThoughtChunk(var content) ->
                convertThought(content);
            case com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate.TurnUsage(
                int input, int output, double cost
            ) -> new com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate.TurnUsage(input, output, cost);
            case com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate.Banner(
                String message, String level, String clearOn
            ) -> convertBanner(message, level, clearOn);
            case com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate.Plan(var entries) ->
                convertPlan(entries);
            default -> null;
        };
    }

    private static com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate.ToolCall convertToolCall(
        com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate.ToolCall tc) {
        List<String> filePaths = List.of();
        if (tc.locations() != null) {
            filePaths = tc.locations().stream()
                .map(com.github.catatafishen.ideagentforcopilot.acp.model.Location::uri)
                .filter(Objects::nonNull)
                .toList();
        }

        com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate.ToolKind kind =
            tc.kind() != null
                ? com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate.ToolKind.fromString(tc.kind().name().toLowerCase())
                : com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate.ToolKind.OTHER;

        return new com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate.ToolCall(
            tc.toolCallId(), tc.title(), kind, tc.arguments(),
            filePaths, null, null, null
        );
    }

    private static com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate.ToolCallUpdate convertToolCallUpdate(
        com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate.ToolCallUpdate tcu) {
        String result = extractResultText(tcu.content());

        com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate.ToolCallStatus status =
            com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate.ToolCallStatus.fromString(
                tcu.status().name());

        return new com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate.ToolCallUpdate(
            tcu.toolCallId(), status, result, tcu.error(), null
        );
    }

    @Nullable
    private static String extractResultText(
        @Nullable List<com.github.catatafishen.ideagentforcopilot.acp.model.ToolCallContent> contentList) {
        if (contentList == null || contentList.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        for (var item : contentList) {
            if (item instanceof com.github.catatafishen.ideagentforcopilot.acp.model.ToolCallContent.Content(
                var blocks
            )) {
                for (ContentBlock block : blocks) {
                    if (block instanceof ContentBlock.Text(String text)) {
                        sb.append(text);
                    }
                }
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate.AgentThought convertThought(
        @Nullable List<ContentBlock> content) {
        StringBuilder text = new StringBuilder();
        if (content != null) {
            for (ContentBlock block : content) {
                if (block instanceof ContentBlock.Text(String t)) {
                    text.append(t);
                }
            }
        }
        return new com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate.AgentThought(text.toString());
    }

    private static com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate.Banner convertBanner(
        String message, String level, String clearOn) {
        return new com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate.Banner(
            message,
            com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate.BannerLevel.fromString(level),
            com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate.ClearOn.fromString(clearOn)
        );
    }

    private static com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate.Plan convertPlan(
        @Nullable List<com.github.catatafishen.ideagentforcopilot.acp.model.PlanEntry> entries) {
        var protoPlan = new com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate.Protocol.Plan();
        if (entries != null) {
            protoPlan.entries = entries.stream().map(e -> {
                var pe = new com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate.Protocol.Plan.PlanEntry();
                pe.content = e.content();
                pe.status = e.status();
                return pe;
            }).toList();
        }
        return new com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate.Plan(protoPlan);
    }

    private static void deliverTurnUsage(PromptResponse response,
                                         @Nullable Consumer<com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate> onUpdate) {
        if (onUpdate == null || response.usage() == null) return;
        PromptResponse.TurnUsage usage = response.usage();
        int input = usage.inputTokens() != null ? usage.inputTokens().intValue() : 0;
        int output = usage.outputTokens() != null ? usage.outputTokens().intValue() : 0;
        double cost = usage.costUsd() != null ? usage.costUsd() : 0.0;
        onUpdate.accept(new com.github.catatafishen.ideagentforcopilot.bridge.SessionUpdate.TurnUsage(
            input, output, cost
        ));
    }
}
