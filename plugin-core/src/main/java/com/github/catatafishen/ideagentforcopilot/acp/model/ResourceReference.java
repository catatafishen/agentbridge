package com.github.catatafishen.ideagentforcopilot.acp.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * ACP resource reference — file or selection context sent with prompts.
 */
public record ResourceReference(@NotNull String uri, @Nullable String mimeType, @NotNull String text) {
}
