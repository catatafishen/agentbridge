package com.github.catatafishen.agentbridge.acp.model;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

/**
 * An AI model available in the agent.
 *
 * @see <a href="https://agentclientprotocol.com/protocol/sessions">ACP Sessions</a>
 */
public record Model(
        String id,
        String name,
        @Nullable String description,
        @Nullable JsonObject _meta
) {}
