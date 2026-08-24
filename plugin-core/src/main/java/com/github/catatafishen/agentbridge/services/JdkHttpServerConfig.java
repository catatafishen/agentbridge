package com.github.catatafishen.agentbridge.services;

/**
 * Ensures the JDK's built-in {@code com.sun.net.httpserver.HttpServer} disables Nagle's
 * algorithm on the sockets it accepts.
 *
 * <p>{@code com.sun.net.httpserver.HttpServer} reads the {@code sun.net.httpserver.nodelay}
 * system property exactly once, in a static initializer of the internal
 * {@code sun.net.httpserver.ServerConfig} class, and defaults it to {@code false} (Nagle's
 * algorithm enabled) when unset. Nagle's algorithm delays sending small TCP segments in the
 * hope of coalescing them with more data, and interacts badly with the peer's delayed-ACK
 * timer: whichever side is waiting can stall for the full delayed-ACK interval before either
 * side sends anything. That interval is far longer on macOS's BSD-derived TCP stack than on
 * Linux's, which is exactly why POST request bodies to the streamable-HTTP {@code /mcp}
 * endpoint (split across more than one TCP segment) could hang on macOS while the GET-based
 * {@code /sse} endpoint — which has no request body to stall on — worked fine on every
 * platform (see issue #988).
 *
 * <p>Call {@link #ensureNoDelay()} before the <em>first</em>
 * {@code HttpServer.create(...)}/{@code HttpsServer.create(...)} call anywhere in the JVM.
 * Once {@code ServerConfig} has read the property, later changes to it have no effect.
 */
public final class JdkHttpServerConfig {
    static {
        if (System.getProperty("sun.net.httpserver.nodelay") == null) {
            System.setProperty("sun.net.httpserver.nodelay", "true");
        }
    }

    private JdkHttpServerConfig() {
    }

    /**
     * No-op beyond triggering this class's static initializer on first call — which is exactly
     * what's needed here, since the system property only needs to be set once, before any
     * {@code HttpServer} is created. See the class Javadoc for why this matters.
     */
    public static void ensureNoDelay() {
        // Intentionally empty; the effect happens in the static initializer above.
    }
}
