package com.github.catatafishen.agentbridge.client.acp;

import com.github.catatafishen.agentbridge.model.SessionUpdate;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Reads OpenCode's {@code opencode/session/child_update} notifications, and tracks which sub-agents (child
 * sessions) are still working.
 * <p>
 * OpenCode 2.0 and later send them once the client advertises {@code _meta["opencode/child-session-updates"]},
 * instead of mixing a sub-agent's updates into the main session's stream. They keep coming after the turn has
 * ended, while a background sub-agent works. Params:
 * <pre>
 * {rootSessionId, childSessionId, parentSessionId, depth, title?,
 *  type: "status", status: "created" | "running" | "completed" | "failed" | "interrupted", error?: {type, message}}
 * {rootSessionId, childSessionId, parentSessionId, depth, title?, type: "update", update: &lt;session update&gt;}
 * </pre>
 * Thread-safe: notifications arrive on the ACP reader thread, the post-turn window checks from a scheduler thread.
 */
final class OpenCodeChildSessions {

    static final String CAPABILITY = "opencode/child-session-updates";
    static final String METHOD = "opencode/session/child_update";

    private static final Logger LOG = Logger.getInstance(OpenCodeChildSessions.class);

    private final Set<String> working = ConcurrentHashMap.newKeySet();

    /**
     * Reads one notification and records whether its sub-agent is working.
     *
     * @param params        the notification's params
     * @param rootSessionId the main session the client is in; a notification for another one is dropped
     * @param parseUpdate   parses a sub-agent's own update the standard way
     * @return the update to deliver, or null when the notification carries none for this session
     */
    @Nullable SessionUpdate accept(@Nullable JsonObject params, @Nullable String rootSessionId,
                                   @NotNull Function<JsonObject, SessionUpdate> parseUpdate) {
        if (params == null) return null;
        String childId = string(params, "childSessionId");
        if (childId == null || childId.isBlank()) {
            LOG.warn(METHOD + " without a childSessionId: " + params);
            return null;
        }
        String root = string(params, "rootSessionId");
        if (rootSessionId == null || !rootSessionId.equals(root)) {
            LOG.debug(METHOD + " for session " + root + ", not the current " + rootSessionId);
            return null;
        }
        String title = string(params, "title");
        if (title != null && title.isBlank()) title = null;
        String type = string(params, "type");
        if ("status".equals(type)) return status(params, childId, title);
        if ("update".equals(type)) {
            JsonElement update = params.get("update");
            if (update == null || !update.isJsonObject()) return null;
            SessionUpdate inner = parseUpdate.apply(update.getAsJsonObject());
            return inner == null ? null : new SessionUpdate.ChildSessionUpdate(childId, title, inner);
        }
        LOG.warn(METHOD + " of unknown type " + type);
        return null;
    }

    private @Nullable SessionUpdate status(@NotNull JsonObject params, @NotNull String childId, @Nullable String title) {
        String status = string(params, "status");
        SessionUpdate.ChildSessionState state;
        try {
            state = SessionUpdate.ChildSessionState.valueOf(String.valueOf(status).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOG.warn(METHOD + " with unknown status " + status);
            return null;
        }
        if (state.isFinished()) {
            working.remove(childId);
        } else {
            working.add(childId);
        }
        return new SessionUpdate.ChildSessionStatus(childId, title, state, errorMessage(params));
    }

    /** Whether a sub-agent of the current session has started and not yet finished. */
    boolean anyWorking() {
        return !working.isEmpty();
    }

    /** Forgets every sub-agent, when the client leaves the session they belong to. */
    void clear() {
        working.clear();
    }

    private static @Nullable String errorMessage(@NotNull JsonObject params) {
        JsonElement error = params.get("error");
        if (error == null || !error.isJsonObject()) return null;
        String message = string(error.getAsJsonObject(), "message");
        return message == null || message.isBlank() ? null : message;
    }

    private static @Nullable String string(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? el.getAsString() : null;
    }
}
