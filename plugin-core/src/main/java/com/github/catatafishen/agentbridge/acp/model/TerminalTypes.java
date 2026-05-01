package com.github.catatafishen.agentbridge.acp.model;

import org.jetbrains.annotations.Nullable;

/**
 * Terminal-related requests from agent to client.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/terminal">ACP Terminal</a>
 */
public final class TerminalTypes {

    private TerminalTypes() {}

    public record CreateTerminalRequest(
            @Nullable String cwd,
            @Nullable String name
    ) {}

    public record CreateTerminalResponse(String terminalId) {}

    public record TerminalOutputRequest(
            String terminalId,
            String command
    ) {}

    public record TerminalOutputResponse(
            String output,
            int exitCode
    ) {}

    public record TerminalReadRequest(String terminalId) {}

    public record TerminalReadResponse(String output) {}

    public record TerminalWriteRequest(
            String terminalId,
            String input
    ) {}

    public record TerminalWriteResponse() {
        public static final boolean SUCCESS = true;
    }
}
