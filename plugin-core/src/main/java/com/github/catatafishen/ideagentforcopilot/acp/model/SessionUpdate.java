package com.github.catatafishen.ideagentforcopilot.acp.model;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Real-time session updates streamed from agent to client.
 * Unified type used by both ACP protocol clients and Claude clients.
 */
public sealed interface SessionUpdate
    permits SessionUpdate.AgentMessageChunk,
    SessionUpdate.AgentThoughtChunk,
    SessionUpdate.ToolCall,
    SessionUpdate.ToolCallUpdate,
    SessionUpdate.TurnUsage,
    SessionUpdate.Banner,
    SessionUpdate.Plan,
    SessionUpdate.AvailableCommandsChanged,
    SessionUpdate.AvailableModesChanged {

    // ── Enums ────────────────────────────────────────────────────────────────

    enum ToolKind {
        @SerializedName("read") READ("read"),
        @SerializedName("edit") EDIT("edit"),
        @SerializedName("delete") DELETE("delete"),
        @SerializedName("move") MOVE("move"),
        @SerializedName("search") SEARCH("search"),
        @SerializedName("execute") EXECUTE("execute"),
        @SerializedName("think") THINK("think"),
        @SerializedName("fetch") FETCH("fetch"),
        @SerializedName("switch_mode") SWITCH_MODE("switch_mode"),
        @SerializedName("other") OTHER("other");

        private final String value;

        ToolKind(String value) { this.value = value; }

        public String value() { return value; }

        public static ToolKind fromString(@Nullable String s) {
            if (s == null) return OTHER;
            for (ToolKind k : values()) {
                if (k.value.equalsIgnoreCase(s) || k.name().equalsIgnoreCase(s)) return k;
            }
            return OTHER;
        }

        public static ToolKind fromCategory(@Nullable Object category) {
            if (category == null) return OTHER;
            return switch (category.toString()) {
                case "SEARCH" -> SEARCH;
                case "FILE", "EDITOR", "REFACTOR" -> EDIT;
                case "BUILD", "RUN", "TERMINAL", "SHELL", "GIT" -> EXECUTE;
                case "CODE_QUALITY", "TESTING", "IDE", "PROJECT", "INFRASTRUCTURE" -> READ;
                default -> OTHER;
            };
        }
    }

    enum ToolCallStatus {
        @SerializedName("completed") COMPLETED("completed"),
        @SerializedName("failed") FAILED("failed"),
        @SerializedName("pending") PENDING("in_progress"),
        @SerializedName("in_progress") IN_PROGRESS("in_progress");

        private final String value;

        ToolCallStatus(String value) { this.value = value; }

        public String value() { return value; }

        public static ToolCallStatus fromString(@Nullable String s) {
            if (s == null) return FAILED;
            for (ToolCallStatus st : values()) {
                if (st.value.equalsIgnoreCase(s) || st.name().equalsIgnoreCase(s)) return st;
            }
            if ("success".equalsIgnoreCase(s) || "succeeded".equalsIgnoreCase(s)) return COMPLETED;
            if ("in-progress".equalsIgnoreCase(s) || "running".equalsIgnoreCase(s)) return IN_PROGRESS;
            return FAILED;
        }
    }

    enum BannerLevel {
        WARNING("warning"),
        ERROR("error");

        private final String value;

        BannerLevel(String value) { this.value = value; }

        public String value() { return value; }

        public static BannerLevel fromString(@Nullable String s) {
            if (s == null) return WARNING;
            for (BannerLevel l : values()) {
                if (l.value.equalsIgnoreCase(s)) return l;
            }
            return WARNING;
        }
    }

    enum ClearOn {
        NEXT_SUCCESS("next_success"),
        MANUAL("manual");

        private final String value;

        ClearOn(String value) { this.value = value; }

        public String value() { return value; }

        public static ClearOn fromString(@Nullable String s) {
            if (s == null) return MANUAL;
            for (ClearOn c : values()) {
                if (c.value.equalsIgnoreCase(s)) return c;
            }
            return MANUAL;
        }
    }

    // ── Event records ────────────────────────────────────────────────────────

    /** Streamed text chunk from the agent's response. */
    record AgentMessageChunk(List<ContentBlock> content) implements SessionUpdate {
        /** Extracts all text content as a plain string. */
        public String text() {
            StringBuilder sb = new StringBuilder();
            for (ContentBlock block : content) {
                if (block instanceof ContentBlock.Text t) sb.append(t.text());
            }
            return sb.toString();
        }
    }

    /** Agent's internal reasoning (thinking/chain-of-thought). */
    record AgentThoughtChunk(List<ContentBlock> content) implements SessionUpdate {
        /** Extracts all text content as a plain string. */
        public String text() {
            StringBuilder sb = new StringBuilder();
            for (ContentBlock block : content) {
                if (block instanceof ContentBlock.Text t) sb.append(t.text());
            }
            return sb.toString();
        }
    }

    /**
     * A new tool call initiated by the agent.
     *
     * @param toolCallId          unique ID correlating with {@link ToolCallUpdate}
     * @param title               normalised tool name (MCP prefix stripped)
     * @param kind                functional category for UI chip styling
     * @param arguments           serialised JSON string of the tool arguments, or null if empty
     * @param locations           file locations extracted from the tool event
     * @param agentType           non-null when this is a Task/sub-agent tool call
     * @param subAgentDescription short description of the sub-agent task, or null
     * @param subAgentPrompt      the prompt sent to the sub-agent, or null
     */
    record ToolCall(
            @NotNull String toolCallId,
            @NotNull String title,
            @Nullable ToolKind kind,
            @Nullable String arguments,
            @Nullable List<Location> locations,
            @Nullable String agentType,
            @Nullable String subAgentDescription,
            @Nullable String subAgentPrompt
    ) implements SessionUpdate {

        /** Returns true when this is a sub-agent (Task tool) call. */
        public boolean isSubAgent() { return agentType != null; }

        /** Returns file paths from locations for follow-agent file navigation. */
        public List<String> filePaths() {
            if (locations == null || locations.isEmpty()) return List.of();
            return locations.stream()
                .map(Location::uri)
                .filter(p -> p != null && !p.isEmpty())
                .toList();
        }
    }

    /**
     * Status update for an in-progress tool call.
     *
     * @param toolCallId  ID matching the originating {@link ToolCall}
     * @param status      terminal outcome
     * @param result      result text for a completed call (may be null)
     * @param error       error message for a failed call (may be null)
     * @param description optional natural language explanation of the result (may be null)
     */
    record ToolCallUpdate(
            @NotNull String toolCallId,
            @NotNull ToolCallStatus status,
            @Nullable String result,
            @Nullable String error,
            @Nullable String description
    ) implements SessionUpdate {}

    /** Agent's execution plan with task entries. */
    record Plan(List<PlanEntry> entries) implements SessionUpdate {}

    /** Available commands have changed. */
    record AvailableCommandsChanged(
            List<NewSessionResponse.AvailableCommand> commands
    ) implements SessionUpdate {}

    /** Available modes have changed. */
    record AvailableModesChanged(
            List<NewSessionResponse.AvailableMode> modes,
            @Nullable String activeSlug
    ) implements SessionUpdate {}

    /** Turn-level token and cost statistics. */
    record TurnUsage(
            int inputTokens,
            int outputTokens,
            double costUsd
    ) implements SessionUpdate {}

    /** Agent-initiated banner notification. */
    record Banner(
            @NotNull String message,
            @NotNull BannerLevel level,
            @NotNull ClearOn clearOn
    ) implements SessionUpdate {}
}
