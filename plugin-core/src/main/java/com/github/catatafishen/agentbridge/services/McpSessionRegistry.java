package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
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

    private final Map<String, Long> lastActivityNanos = new HashMap<>();
    private final LongSupplier nanoTime;

    McpSessionRegistry() {
        this(System::nanoTime);
    }

    McpSessionRegistry(@NotNull LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    synchronized @NotNull String openSession() {
        String sessionId;
        do {
            sessionId = UUID.randomUUID().toString();
        } while (lastActivityNanos.putIfAbsent(sessionId, nanoTime.getAsLong()) != null);
        return sessionId;
    }

    synchronized boolean touch(@NotNull String sessionId) {
        if (!lastActivityNanos.containsKey(sessionId)) return false;
        lastActivityNanos.put(sessionId, nanoTime.getAsLong());
        return true;
    }

    synchronized boolean closeSession(@NotNull String sessionId) {
        return lastActivityNanos.remove(sessionId) != null;
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
        return Set.copyOf(expired);
    }

    synchronized @NotNull Set<String> drainSessions() {
        Set<String> drained = Set.copyOf(lastActivityNanos.keySet());
        lastActivityNanos.clear();
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
