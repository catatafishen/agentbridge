package com.github.catatafishen.ideagentforcopilot.acp.model;

import org.jetbrains.annotations.Nullable;

import java.util.List;


/**
 * Agent → Client: request permission for a tool call.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/tool-calls#requesting-permission">ACP Permissions</a>
 */
public record RequestPermissionRequest(
        ProtocolToolCall toolCall,
        List<PermissionOption> options
) {

    /**
     * The tool call requiring permission.
     */
    public record ProtocolToolCall(
            String toolCallId,
            String title,
            @Nullable SessionUpdate.ToolKind kind,
            @Nullable String arguments
    ) {}

    /**
     * A permission option the user can choose.
     */
    public record PermissionOption(
            String optionId,
            String kind,
            @Nullable String message
    ) {}
}
