package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Tracks Streamable HTTP sessions minted by AgentBridge.
 *
 * <p>The current MCP transport specification allows the server to return an
 * {@code Mcp-Session-Id} during initialization. The client then echoes that ID on every
 * subsequent request. AgentBridge also uses the ID as the ownership boundary for stateful
 * resources such as integrated terminals.</p>
 */
final class McpSessionRegistry {

    enum RequestKind {
        INITIALIZE,
        ESTABLISHED,
        INVALID
    }

    private static final int MAX_RETIRED_SESSIONS = 64;

    private final Map<String, Long> lastActivityNanos = new HashMap<>();
    private final Map<String, String> clientNames = new HashMap<>();
    /**
     * Recently retired session IDs → client name; bounded, oldest evicted first.
     */
    private final Map<String, String> retired = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_RETIRED_SESSIONS;
        }
    };
    private final LongSupplier nanoTime;

    McpSessionRegistry() {
        this(System::nanoTime);
    }

    McpSessionRegistry(@NotNull LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    /**
     * Allocates a new session ID when the registry has room for it.
     *
     * @param maxOpenSessions upper bound on concurrent sessions. Values below 1 are treated as
     *                        "no cap" so that a mis-configured setting cannot make the server
     *                        refuse every {@code initialize}.
     * @return the new session ID, or {@code null} when the registry is already at {@code
     * maxOpenSessions} live entries. Callers must translate {@code null} into a well-formed
     * transport-level error (HTTP 503) so the client can retry after a session ends.
     */
    synchronized @Nullable String openSession(int maxOpenSessions) {
        if (maxOpenSessions >= 1 && lastActivityNanos.size() >= maxOpenSessions) {
            return null;
        }
        String sessionId;
        do {
            sessionId = UUID.randomUUID().toString();
        } while (lastActivityNanos.putIfAbsent(sessionId, nanoTime.getAsLong()) != null);
        return sessionId;
    }

    synchronized int size() {
        return lastActivityNanos.size();
    }

    synchronized boolean touch(@NotNull String sessionId) {
        if (!lastActivityNanos.containsKey(sessionId)) return false;
        lastActivityNanos.put(sessionId, nanoTime.getAsLong());
        return true;
    }

    /**
     * Records which MCP client ({@code clientInfo.name}) a live session belongs to.
     */
    synchronized void recordClientName(@NotNull String sessionId, @Nullable String clientName) {
        if (clientName == null || !lastActivityNanos.containsKey(sessionId)) return;
        clientNames.put(sessionId, clientName);
    }

    /**
     * Returns the client name of a session this registry issued and has since retired
     * (closed, expired or drained), or {@code null} if the ID was never issued by this
     * server instance (e.g. a stale ID from before an IDE restart, or from another client).
     * The empty string means "retired, client name unknown".
     */
    synchronized @Nullable String retiredClientName(@NotNull String sessionId) {
        return retired.get(sessionId);
    }

    private void retire(@NotNull String sessionId) {
        String name = clientNames.remove(sessionId);
        retired.put(sessionId, name == null ? "" : name);
    }

    synchronized boolean closeSession(@NotNull String sessionId) {
        boolean removed = lastActivityNanos.remove(sessionId) != null;
        if (removed) retire(sessionId);
        return removed;
    }

    synchronized @NotNull Set<String> expireIdleSessions(long maxIdleNanos) {
        if (maxIdleNanos < 0) {
            throw new IllegalArgumentException("maxIdleNanos must not be negative");
        }
        long now = nanoTime.getAsLong();
        Set<String> expired = new HashSet<>();
        lastActivityNanos.entrySet().removeIf(entry -> {
            boolean idle = now - entry.getValue() >= maxIdleNanos;
            if (idle) expired.add(entry.getKey());
            return idle;
        });
        expired.forEach(this::retire);
        return Set.copyOf(expired);
    }

    synchronized @NotNull Set<String> drainSessions() {
        Set<String> drained = Set.copyOf(lastActivityNanos.keySet());
        lastActivityNanos.clear();
        drained.forEach(this::retire);
        return drained;
    }

    static @NotNull RequestKind classifyRequest(@NotNull String body) {
        try {
            var parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) return RequestKind.INVALID;
            JsonObject request = parsed.getAsJsonObject();
            if (!request.has("method") || !request.get("method").isJsonPrimitive()) {
                return RequestKind.INVALID;
            }
            String method = request.get("method").getAsString();
            return "initialize".equals(method) ? RequestKind.INITIALIZE : RequestKind.ESTABLISHED;
        } catch (RuntimeException ignored) {
            return RequestKind.INVALID;
        }
    }

    static @NotNull String ownerKey(@NotNull String transport, @NotNull String sessionId) {
        return transport + ":" + sessionId;
    }
}
