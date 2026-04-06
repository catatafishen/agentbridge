package com.github.catatafishen.agentbridge.acp.model;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Agent → Client: response to session creation with session ID, models, and capabilities.
 * <p>
 * Deserialization is handled by {@link NewSessionResponseDeserializer} which normalises the
 * various wire formats sent by different agents (array vs. map for models; string vs. object
 * values in modes).
 *
 * @see <a href="https://agentclientprotocol.com/protocol/sessions">ACP Sessions</a>
 */
public record NewSessionResponse(
    String sessionId,
    @Nullable String currentModelId,
    @Nullable String currentModeId,
    @Nullable List<Model> models,
    @Nullable List<AvailableMode> modes,
    @Nullable List<AvailableCommand> commands,
    @Nullable List<SessionConfigOption> configOptions
) {

    public record AvailableMode(
        String slug,
        String name,
        @Nullable String description
    ) {
    }

    public record AvailableCommand(
        String name,
        String description,
        @Nullable AvailableCommandInput input
    ) {
    }

    public record AvailableCommandInput(
        String type,
        @Nullable String placeholder
    ) {
    }

    public record SessionConfigOption(
        String id,
        String label,
        @Nullable String description,
        List<SessionConfigOptionValue> values,
        @Nullable String selectedValueId
    ) {
    }

    public record SessionConfigOptionValue(String id, String label) {
    }
}
