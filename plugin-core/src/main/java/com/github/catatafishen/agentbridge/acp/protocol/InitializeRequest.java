package com.github.catatafishen.agentbridge.acp.protocol;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

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

    /**
     * @param meta agent-specific capabilities, sent as {@code _meta}; omitted when null
     */
    public record ClientCapabilities(
        @Nullable FsCapabilities fs,
        @Nullable Boolean terminal,
        @SerializedName("_meta") @Nullable Map<String, Object> meta
    ) {
        /**
         * Advertise read/write file and terminal capabilities.
         */
        public static ClientCapabilities standard() {
            return new ClientCapabilities(
                new FsCapabilities(true, true),
                true,
                null
            );
        }

        /**
         * Empty capabilities — use when the agent rejects unknown capability fields.
         */
        public static ClientCapabilities empty() {
            return new ClientCapabilities(null, null, null);
        }

        /** These capabilities with {@code _meta} set to {@code meta}. */
        public @NotNull ClientCapabilities withMeta(@NotNull Map<String, Object> meta) {
            return new ClientCapabilities(fs, terminal, Map.copyOf(meta));
        }
    }

    public record FsCapabilities(
        @Nullable Boolean readTextFile,
        @Nullable Boolean writeTextFile
    ) {
    }
}
