package com.github.catatafishen.ideagentforcopilot.acp.model;

import org.jetbrains.annotations.Nullable;

import java.util.List;

public record InitializeResponse(
    @Nullable Integer protocolVersion,
    AgentInfo agentInfo,
    AgentCapabilities agentCapabilities,
    @Nullable List<AuthMethod> authMethods
) {

    public record AgentInfo(String name, @Nullable String title, String version) {
    }

    public record AgentCapabilities(
        @Nullable Boolean loadSession,
        @Nullable McpCapabilities mcpCapabilities,
        @Nullable PromptCapabilities promptCapabilities,
        @Nullable SessionCapabilities sessionCapabilities
    ) {
    }

    public record McpCapabilities(
        @Nullable Boolean http,
        @Nullable Boolean sse
    ) {
    }

    public record PromptCapabilities(
        @Nullable Boolean image,
        @Nullable Boolean audio,
        @Nullable Boolean embeddedContext
    ) {
    }

    public record SessionCapabilities() {
    }
}
