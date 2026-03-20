package com.github.catatafishen.ideagentforcopilot.acp.model;

import org.jetbrains.annotations.Nullable;

/**
 * Client → Agent: protocol handshake.
 * <p>
 * {@code protocolVersion} is sent as an integer (e.g. {@code 1}), matching what Copilot CLI
 * and other ACP agents expect. Using a string causes a -32603 Internal Error from Copilot.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/initialization">ACP Initialization</a>
 */
public record InitializeRequest(
        int protocolVersion,
        ClientInfo clientInfo,
        @Nullable ClientCapabilities clientCapabilities
) {

    public record ClientInfo(String name, String version) {}

    public record ClientCapabilities(
            @Nullable FsCapabilities fs,
            @Nullable Boolean terminal
    ) {
        /** Advertise read/write file and terminal capabilities. */
        public static ClientCapabilities standard() {
            return new ClientCapabilities(
                    new FsCapabilities(true, true),
                    true
            );
        }

        /** Empty capabilities — use when the agent rejects unknown capability fields. */
        public static ClientCapabilities empty() {
            return new ClientCapabilities(null, null);
        }
    }

    public record FsCapabilities(
            @Nullable Boolean readTextFile,
            @Nullable Boolean writeTextFile
    ) {}
}
