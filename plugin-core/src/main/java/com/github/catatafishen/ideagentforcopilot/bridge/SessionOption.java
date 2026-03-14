package com.github.catatafishen.ideagentforcopilot.bridge;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Describes a client-specific session option that the UI should render as a dropdown.
 *
 * <p>Returned by {@link AgentClient#listSessionOptions()}. Each option has a stable
 * {@link #key} (used for storage and command-line mapping), a human-readable
 * {@link #displayName}, and a fixed set of {@link #values}.  The first entry in
 * {@code values} is always the empty/default choice (displayed as "Default").</p>
 *
 * <p>Examples:</p>
 * <ul>
 *   <li>Claude CLI: {@code ("effort", "Effort", ["", "low", "medium", "high", "max"])}</li>
 * </ul>
 */
public record SessionOption(
    @NotNull String key,
    @NotNull String displayName,
    @NotNull List<String> values
) {
    /**
     * Returns the label shown in the dropdown for a given raw value.
     * Empty string maps to "Default"; non-empty values are title-cased.
     */
    @NotNull
    public String labelFor(@Nullable String value) {
        if (value == null || value.isEmpty()) return "Default";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
