package com.github.catatafishen.ideagentforcopilot.session.v2;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One line in a v2 JSONL session file.
 *
 * <p>Each message corresponds to one logical turn: a user prompt, an assistant reply,
 * or a session-separator marker.
 */
public final class SessionMessage {

    /** UUID identifying this message. */
    @NotNull
    public final String id;

    /** Role: {@code "user"}, {@code "assistant"}, or {@code "separator"}. */
    @NotNull
    public final String role;

    /** Ordered list of content parts (Gson JsonObjects for schema flexibility). */
    @NotNull
    public final List<JsonObject> parts;

    /** Unix epoch milliseconds when this message was created. */
    public final long createdAt;

    /** Optional display name of the agent that produced this message. */
    @Nullable
    public final String agent;

    /** Optional model identifier used to produce this message. */
    @Nullable
    public final String model;

    public SessionMessage(
            @NotNull String id,
            @NotNull String role,
            @NotNull List<JsonObject> parts,
            long createdAt,
            @Nullable String agent,
            @Nullable String model) {
        this.id = id;
        this.role = role;
        this.parts = List.copyOf(parts);
        this.createdAt = createdAt;
        this.agent = agent;
        this.model = model;
    }
}
