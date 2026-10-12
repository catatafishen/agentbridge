package com.github.catatafishen.agentbridge.client.acp;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Address of the HTTP server that one {@code opencode acp} process starts next to its ACP transport.
 * <p>
 * OpenCode only forwards events for ACP-registered sessions, so a subagent's child session is
 * invisible over ACP. The same process also serves the regular OpenCode HTTP API, which exposes
 * every session; knowing its port lets the client read a subagent transcript from there.
 * <p>
 * Every {@link OpenCodeClient} owns its own instance: each project runs its own OpenCode process on
 * its own port, so the address must not be shared. The client chooses the port via {@code --port},
 * {@linkplain #publish publishes} it when the agent is launched and {@linkplain #clear clears} it
 * when the agent stops.
 */
public final class OpenCodeServerEndpoint {

    private static final String PORT_FLAG = "--port";
    private volatile int port = -1;

    /**
     * Returns the value of an explicit {@code --port N} / {@code --port=N} in [args], or
     * {@code null} when the flag is absent. An unparseable value is returned as {@code 0}
     * (the flag is present, but its port is unknown).
     */
    static @Nullable Integer portFromArgs(@NotNull List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            String value = null;
            if (arg.equals(PORT_FLAG)) {
                value = i + 1 < args.size() ? args.get(i + 1) : "";
            } else if (arg.startsWith(PORT_FLAG + "=")) {
                value = arg.substring(PORT_FLAG.length() + 1);
            }
            if (value != null) {
                try {
                    return Integer.parseInt(value.trim());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return null;
    }

    /**
     * Records the port of the OpenCode server just launched; {@code <= 0} means unknown.
     */
    void publish(int serverPort) {
        port = serverPort;
    }

    /**
     * Forgets the port; called when the OpenCode process it belonged to has stopped.
     */
    void clear() {
        port = -1;
    }

    /**
     * Base URL of this client's running OpenCode HTTP server, or {@code null} if its port is unknown
     * (not launched yet, stopped, or launched with an unparseable {@code --port}).
     */
    @Nullable String baseUrl() {
        int p = port;
        return p > 0 ? "http://127.0.0.1:" + p : null;
    }
}
