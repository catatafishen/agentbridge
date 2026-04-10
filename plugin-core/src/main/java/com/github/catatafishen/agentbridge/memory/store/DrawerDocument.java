package com.github.catatafishen.agentbridge.memory.store;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;

/**
 * Immutable POJO representing a single memory "drawer" stored in the Lucene index.
 * Each drawer is one semantically meaningful chunk extracted from a conversation turn.
 *
 * <p><b>Attribution:</b> drawer concept and ID generation scheme adapted from
 * <a href="https://github.com/milla-jovovich/mempalace">MemPalace</a> (MIT License).
 */
public record DrawerDocument(
    @NotNull String id,
    @NotNull String wing,
    @NotNull String room,
    @NotNull String content,
    @NotNull String memoryType,
    @NotNull String sourceSession,
    @NotNull String sourceFile,
    @NotNull String agent,
    @NotNull Instant filedAt,
    @NotNull String addedBy,
    @NotNull String sourceTurnIndex,
    @NotNull String sourceCommits
) {

    /**
     * Maximum content length allowed (from MemPalace config.py sanitize_content).
     */
    public static final int MAX_CONTENT_LENGTH = 100_000;

    /**
     * Maximum entity/name length (from MemPalace config.py sanitize_name).
     */
    public static final int MAX_NAME_LENGTH = 128;

    /**
     * Memory types — orthogonal to rooms, classifies the nature of the knowledge.
     * Adapted from MemPalace's general_extractor.py with coding-specific refinements.
     */
    public static final String TYPE_CONTEXT = "context";
    public static final String TYPE_DECISION = "decision";
    public static final String TYPE_PROBLEM = "problem";
    public static final String TYPE_SOLUTION = "solution";
    public static final String TYPE_GENERAL = "general";

    /**
     * Room constants — topic shelves for memory recall.
     * Orthogonal to types: room = "where to look", type = "what kind of knowledge".
     */
    public static final String ROOM_CODEBASE = "codebase";
    public static final String ROOM_DEBUGGING = "debugging";
    public static final String ROOM_WORKFLOW = "workflow";
    public static final String ROOM_DECISIONS = "decisions";
    public static final String ROOM_PREFERENCES = "preferences";
    public static final String ROOM_GENERAL = "general";

    public static final String ADDED_BY_MINER = "miner";
    public static final String ADDED_BY_MCP = "mcp";

    /**
     * Compact builder for programmatic construction.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id = "";
        private String wing = "";
        private String room = ROOM_GENERAL;
        private String content = "";
        private String memoryType = TYPE_GENERAL;
        private String sourceSession = "";
        private String sourceFile = "";
        private String agent = "";
        private Instant filedAt = Instant.now();
        private String addedBy = ADDED_BY_MINER;
        private String sourceTurnIndex = "";
        private String sourceCommits = "";

        public Builder id(@NotNull String id) {
            this.id = id;
            return this;
        }

        public Builder wing(@NotNull String wing) {
            this.wing = wing;
            return this;
        }

        public Builder room(@NotNull String room) {
            this.room = room;
            return this;
        }

        public Builder content(@NotNull String content) {
            this.content = content;
            return this;
        }

        public Builder memoryType(@NotNull String memoryType) {
            this.memoryType = memoryType;
            return this;
        }

        public Builder sourceSession(@NotNull String sourceSession) {
            this.sourceSession = sourceSession;
            return this;
        }

        public Builder sourceFile(@NotNull String sourceFile) {
            this.sourceFile = sourceFile;
            return this;
        }

        public Builder agent(@NotNull String agent) {
            this.agent = agent;
            return this;
        }

        public Builder filedAt(@NotNull Instant filedAt) {
            this.filedAt = filedAt;
            return this;
        }

        public Builder addedBy(@NotNull String addedBy) {
            this.addedBy = addedBy;
            return this;
        }

        public Builder sourceTurnIndex(@NotNull String sourceTurnIndex) {
            this.sourceTurnIndex = sourceTurnIndex;
            return this;
        }

        public Builder sourceCommits(@NotNull String sourceCommits) {
            this.sourceCommits = sourceCommits;
            return this;
        }

        public DrawerDocument build() {
            return new DrawerDocument(id, wing, room, content, memoryType, sourceSession,
                sourceFile, agent, filedAt, addedBy, sourceTurnIndex, sourceCommits);
        }
    }

    /**
     * Search result wrapping a drawer with its relevance score.
     */
    public record SearchResult(@NotNull DrawerDocument drawer, float score) {
    }
}
