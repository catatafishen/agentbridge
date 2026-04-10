package com.github.catatafishen.agentbridge.memory.layers;

import org.jetbrains.annotations.NotNull;

/**
 * A single layer in the 4-layer memory stack.
 *
 * <p>Layers are ordered by cost/depth:
 * <ul>
 *   <li><b>L0 — Identity</b>: static identity facts from identity.txt</li>
 *   <li><b>L1 — Essential Story</b>: top-N recent drawers for the wing</li>
 *   <li><b>L2 — On-Demand</b>: filtered by room/topic</li>
 *   <li><b>L3 — Deep Search</b>: full semantic KNN search</li>
 * </ul>
 *
 * <p><b>Attribution:</b> 4-layer memory stack adapted from
 * <a href="https://github.com/milla-jovovich/mempalace">MemPalace</a>'s
 * layers.py (MIT License).
 */
public interface MemoryStack {

    /**
     * Layer identifier (e.g., "L0-identity", "L1-essential").
     */
    @NotNull String layerId();

    /**
     * Human-readable layer name.
     */
    @NotNull String displayName();

    /**
     * Render this layer's content as a text block for injection into agent context.
     *
     * @param wing  the palace wing (project scope)
     * @param query optional query hint for on-demand/deep layers (may be null for L0/L1)
     * @return rendered text, or empty string if the layer has no content
     */
    @NotNull String render(@NotNull String wing, @org.jetbrains.annotations.Nullable String query);
}
