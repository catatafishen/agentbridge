package com.github.catatafishen.agentbridge.memory.store;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Immutable query descriptor for searching the memory store.
 * Combines semantic (vector) search with optional metadata filters.
 *
 * <p>Usage:
 * <pre>
 *   MemoryQuery q = MemoryQuery.semantic("database migration strategy")
 *       .wing("my-project")
 *       .room("architecture")
 *       .limit(10)
 *       .build();
 * </pre>
 */
public record MemoryQuery(
    @Nullable String queryText,
    float @Nullable [] queryEmbedding,
    @Nullable String wing,
    @Nullable String room,
    @Nullable String memoryType,
    @Nullable String agent,
    int limit
) {

    public static final int DEFAULT_LIMIT = 10;

    public static Builder semantic(@NotNull String text) {
        return new Builder().queryText(text);
    }

    public static Builder withEmbedding(float @NotNull [] embedding) {
        return new Builder().queryEmbedding(embedding);
    }

    public static Builder filter() {
        return new Builder();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MemoryQuery other)) {
            return false;
        }
        return limit == other.limit
            && java.util.Objects.equals(queryText, other.queryText)
            && Arrays.equals(queryEmbedding, other.queryEmbedding)
            && java.util.Objects.equals(wing, other.wing)
            && java.util.Objects.equals(room, other.room)
            && java.util.Objects.equals(memoryType, other.memoryType)
            && java.util.Objects.equals(agent, other.agent);
    }

    @Override
    public int hashCode() {
        int result = java.util.Objects.hash(queryText, wing, room, memoryType, agent, limit);
        result = 31 * result + Arrays.hashCode(queryEmbedding);
        return result;
    }

    @Override
    public @NotNull String toString() {
        return "MemoryQuery["
            + "queryText=" + queryText
            + ", queryEmbedding=" + Arrays.toString(queryEmbedding)
            + ", wing=" + wing
            + ", room=" + room
            + ", memoryType=" + memoryType
            + ", agent=" + agent
            + ", limit=" + limit
            + ']';
    }

    public static final class Builder {
        private String queryText;
        private float[] queryEmbedding;
        private String wing;
        private String room;
        private String memoryType;
        private String agent;
        private int limit = DEFAULT_LIMIT;

        public Builder queryText(@Nullable String queryText) {
            this.queryText = queryText;
            return this;
        }

        public Builder queryEmbedding(float @Nullable [] queryEmbedding) {
            this.queryEmbedding = queryEmbedding;
            return this;
        }

        public Builder wing(@Nullable String wing) {
            this.wing = wing;
            return this;
        }

        public Builder room(@Nullable String room) {
            this.room = room;
            return this;
        }

        public Builder memoryType(@Nullable String memoryType) {
            this.memoryType = memoryType;
            return this;
        }

        public Builder agent(@Nullable String agent) {
            this.agent = agent;
            return this;
        }

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public MemoryQuery build() {
            return new MemoryQuery(queryText, queryEmbedding, wing, room, memoryType, agent, limit);
        }
    }
}
