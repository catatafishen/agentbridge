package com.github.catatafishen.agentbridge.memory.layers;

import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;

/**
 * L1 — Essential Story layer. Returns the top-N most recent drawers
 * for the wing, providing a "previously on…" summary.
 *
 * <p><b>Attribution:</b> essential story concept from MemPalace's layers.py (MIT License).
 */
public final class EssentialStoryLayer implements MemoryStack {

    private static final Logger LOG = Logger.getInstance(EssentialStoryLayer.class);
    private static final int DEFAULT_MAX_DRAWERS = 20;

    private final MemoryStore store;
    private final int maxDrawers;

    public EssentialStoryLayer(@NotNull MemoryStore store) {
        this(store, DEFAULT_MAX_DRAWERS);
    }

    public EssentialStoryLayer(@NotNull MemoryStore store, int maxDrawers) {
        this.store = store;
        this.maxDrawers = maxDrawers;
    }

    @Override
    public @NotNull String layerId() {
        return "L1-essential";
    }

    @Override
    public @NotNull String displayName() {
        return "Essential Story";
    }

    @Override
    public @NotNull String render(@NotNull String wing, @Nullable String query) {
        try {
            List<DrawerDocument> drawers = store.getTopDrawers(wing, maxDrawers);
            if (drawers.isEmpty()) return "";

            StringBuilder sb = new StringBuilder("## Essential Story\n\n");
            sb.append("Recent memories for **").append(wing).append("**:\n\n");
            for (DrawerDocument drawer : drawers) {
                sb.append("- [").append(drawer.memoryType()).append("] ")
                    .append(drawer.room()).append(": ")
                    .append(truncate(drawer.content(), 200))
                    .append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            LOG.warn("Failed to render essential story for wing: " + wing, e);
            return "";
        }
    }

    private static @NotNull String truncate(@NotNull String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "…";
    }
}
