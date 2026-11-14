package com.github.catatafishen.agentbridge.acp.protocol;

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

    public record ClientInfo(String name, String title, String version) {
    }

    public record ClientCapabilities(
        @Nullable FsCapabilities fs,
        @Nullable Boolean terminal,
        @Nullable SessionCapabilities session
    ) {
        /**
         * Advertise read/write file, terminal and session-compaction capabilities.
         */
        public static ClientCapabilities standard() {
            return new ClientCapabilities(
                new FsCapabilities(true, true),
                true,
                SessionCapabilities.withCompaction()
            );
        }

        /**
         * Empty capabilities — use when the agent rejects unknown capability fields.
         */
        public static ClientCapabilities empty() {
            return new ClientCapabilities(null, null, null);
        }
    }

    public record FsCapabilities(
        @Nullable Boolean readTextFile,
        @Nullable Boolean writeTextFile
    ) {
    }

    /**
     * {@code clientCapabilities.session}. {@code compaction: {}} declares support for the standard
     * {@code compaction_update} / {@code compaction_summary_chunk} session updates; agents must not
     * send them otherwise.
     *
     * @see <a href="https://agentclientprotocol.com/protocol/v1/prompt-turn#session-compaction">ACP Session Compaction</a>
     */
    public record SessionCapabilities(@Nullable CompactionCapabilities compaction) {
        public static SessionCapabilities withCompaction() {
            return new SessionCapabilities(new CompactionCapabilities());
        }
    }

    /**
     * Serialized as {@code {}}: advertises the complete compaction contract.
     */
    public record CompactionCapabilities() {
    }
}
