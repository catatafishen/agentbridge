package com.github.catatafishen.agentbridge.acp.model;

import org.jetbrains.annotations.Nullable;

/**
 * An entry in an agent's execution plan.
 */
public record PlanEntry(
        String content,
        @Nullable String status,
        @Nullable String priority
) {}
