package com.github.catatafishen.agentbridge.memory.layers;

import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import com.github.catatafishen.agentbridge.memory.store.MemoryQuery;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;

/**
 * L2 — On-Demand layer. Retrieves drawers filtered by wing and an optional
 * room/topic query. Used by the {@code memory_recall} MCP tool.
 *
 * <p><b>Attribution:</b> on-demand recall concept from MemPalace's layers.py (MIT License).
 */
public final class OnDemandLayer implements MemoryStack {

    private static final Logger LOG = Logger.getInstance(OnDemandLayer.class);
    private static final int DEFAULT_LIMIT = 15;

    private final MemoryStore store;
    private final int limit;

    public OnDemandLayer(@NotNull MemoryStore store) {
        this(store, DEFAULT_LIMIT);
    }

    public OnDemandLayer(@NotNull MemoryStore store, int limit) {
        this.store = store;
        this.limit = limit;
    }

    @Override
    public @NotNull String layerId() {
        return "L2-on-demand";
    }

    @Override
    public @NotNull String displayName() {
        return "On-Demand Recall";
    }

    @Override
    public @NotNull String render(@NotNull String wing, @Nullable String query) {
        try {
            MemoryQuery mq = MemoryQuery.filter()
                .wing(wing)
                .room(query)
                .limit(limit)
                .build();

            List<DrawerDocument.SearchResult> results = store.search(mq, null);
            if (results.isEmpty()) return "";

            StringBuilder sb = new StringBuilder("## On-Demand Recall");
            if (query != null && !query.isEmpty()) {
                sb.append(" — ").append(query);
            }
            sb.append("\n\n");

            for (DrawerDocument.SearchResult result : results) {
                DrawerDocument d = result.drawer();
                sb.append("- ");
                if (DrawerDocument.STATE_VERIFIED.equals(d.verificationState())) {
                    sb.append("✓ ");
                } else if (DrawerDocument.STATE_STALE.equals(d.verificationState())) {
                    sb.append("⚠ ");
                }
                sb.append("[").append(d.memoryType()).append("] ")
                    .append(d.room()).append(": ")
                    .append(truncate(d.content(), 300))
                    .append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            LOG.warn("Failed to render on-demand layer for wing: " + wing, e);
            return "";
        }
    }

    private static @NotNull String truncate(@NotNull String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "…";
    }
}
