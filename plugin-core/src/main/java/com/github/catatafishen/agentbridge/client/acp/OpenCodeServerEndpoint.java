package com.github.catatafishen.agentbridge.client.acp;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Address of the HTTP server that {@code opencode acp} starts next to its ACP transport.
 * <p>
 * OpenCode only forwards events for ACP-registered sessions, so a subagent's child session is
 * invisible over ACP. The same process also serves the regular OpenCode HTTP API, which exposes
 * every session; knowing its port lets the UI read a subagent transcript from there.
 * The port is chosen by {@link OpenCodeClient} via {@code --port} and published here when the
 * agent is launched.
 */
public final class OpenCodeServerEndpoint {

    private static final String PORT_FLAG = "--port";
    private static volatile int port = -1;

    private OpenCodeServerEndpoint() {
    }

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
    static void publish(int serverPort) {
        port = serverPort;
    }

    /**
     * The port of the running OpenCode HTTP server, or {@code -1} if unknown.
     */
    public static int port() {
        return port;
    }

    /**
     * Base URL of the running OpenCode HTTP server, or {@code null} if its port is unknown.
     */
    public static @Nullable String baseUrl() {
        int p = port;
        return p > 0 ? "http://127.0.0.1:" + p : null;
    }
}
