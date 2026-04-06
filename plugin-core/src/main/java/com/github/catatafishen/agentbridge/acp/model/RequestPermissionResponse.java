package com.github.catatafishen.agentbridge.acp.model;

/**
 * Client → Agent: response to a permission request.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/tool-calls#requesting-permission">ACP Permissions</a>
 */
public record RequestPermissionResponse(PermissionOutcome outcome) {

    public sealed interface PermissionOutcome {
        record Selected(String optionId) implements PermissionOutcome {}
        record Cancelled() implements PermissionOutcome {}
    }
}
