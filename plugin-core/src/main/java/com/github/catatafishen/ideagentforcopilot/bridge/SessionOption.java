package com.github.catatafishen.ideagentforcopilot.bridge;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Describes a client-specific session option that the UI should render as a dropdown.
 *
 * <p>Returned by {@link AgentClient#listSessionOptions()}. Each option has a stable
 * {@link #key} (used for storage and command-line mapping), a human-readable
 * {@link #displayName}, a fixed set of {@link #values}, and an optional {@link #labels} map
 * for overriding how individual values are displayed in the UI.</p>
 *
 * <p>If {@link #initialValue} is set, the toolbar shows that value when no user preference
 * has been stored (i.e. when the stored value is empty). This allows dynamic options like
 * "model" to display the agent's current selection rather than "Default".</p>
 *
 * <p>Examples:</p>
 * <ul>
 *   <li>Claude CLI: {@code ("effort", "Effort", ["", "low", "medium", "high", "max"])}</li>
 *   <li>Copilot modes: values are URL slugs; labels maps each slug to a short name.</li>
 * </ul>
 */
public record SessionOption(
    @NotNull String key,
    @NotNull String displayName,
    @NotNull List<String> values,
    @Nullable Map<String, String> labels,
    @Nullable String initialValue
) {
    /** Convenience constructor for options without custom labels or initial value. */
    public SessionOption(@NotNull String key, @NotNull String displayName, @NotNull List<String> values) {
        this(key, displayName, values, null, null);
    }

    /** Convenience constructor for options with custom labels but no initial value. */
    public SessionOption(@NotNull String key, @NotNull String displayName, @NotNull List<String> values,
                         @Nullable Map<String, String> labels) {
        this(key, displayName, values, labels, null);
    }

    /**
     * Returns the label shown in the dropdown for a given raw value.
     * Empty string maps to "Default"; non-empty values use the {@link #labels} map if present,
     * otherwise fall back to title-casing the value.
     */
    @NotNull
    public String labelFor(@Nullable String value) {
        if (value == null || value.isEmpty()) return "Default";
        if (labels != null) {
            String label = labels.get(value);
            if (label != null) return label;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
