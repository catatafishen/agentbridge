package com.github.catatafishen.agentbridge.memory.layers;

import com.github.catatafishen.agentbridge.memory.embedding.Embedder;
import com.github.catatafishen.agentbridge.memory.store.DrawerDocument;
import com.github.catatafishen.agentbridge.memory.store.MemoryQuery;
import com.github.catatafishen.agentbridge.memory.store.MemoryStore;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * L3 — Deep Search layer. Full semantic KNN search using embeddings.
 * This is the most expensive layer, requiring inference for the query.
 *
 * <p><b>Attribution:</b> deep search concept from MemPalace's layers.py (MIT License).
 */
public final class DeepSearchLayer implements MemoryStack {

    private static final Logger LOG = Logger.getInstance(DeepSearchLayer.class);
    private static final int DEFAULT_LIMIT = 10;

    private final MemoryStore store;
    private final Embedder embedder;
    private final int limit;

    public DeepSearchLayer(@NotNull MemoryStore store, @NotNull Embedder embedder) {
        this(store, embedder, DEFAULT_LIMIT);
    }

    public DeepSearchLayer(@NotNull MemoryStore store, @NotNull Embedder embedder, int limit) {
        this.store = store;
        this.embedder = embedder;
        this.limit = limit;
    }

    @Override
    public @NotNull String layerId() {
        return "L3-deep-search";
    }

    @Override
    public @NotNull String displayName() {
        return "Deep Search";
    }

    @Override
    public @NotNull String render(@NotNull String wing, @Nullable String query) {
        if (query == null || query.isEmpty()) return "";

        try {
            float[] embedding = embedder.embed(query);
            MemoryQuery mq = MemoryQuery.withEmbedding(embedding)
                .wing(wing)
                .limit(limit)
                .build();

            List<DrawerDocument.SearchResult> results = store.search(mq, embedding);
            if (results.isEmpty()) return "";

            StringBuilder sb = new StringBuilder("## Deep Search Results\n\n");
            sb.append("Semantic matches for: *").append(query).append("*\n\n");
            for (DrawerDocument.SearchResult result : results) {
                DrawerDocument d = result.drawer();
                sb.append("- [").append(String.format("%.2f", result.score())).append("] ");
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
        } catch (Exception e) {
            LOG.warn("Failed to render deep search for: " + query, e);
            return "";
        }
    }

    private static @NotNull String truncate(@NotNull String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "…";
    }
}
