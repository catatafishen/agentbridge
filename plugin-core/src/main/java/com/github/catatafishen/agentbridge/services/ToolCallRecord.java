package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Unified record representing a single tool call, aggregating data from both channels
 * (ACP agent protocol and MCP tool execution) as it arrives in any order.
 *
 * <p>This is a mutable data class — fields are set progressively as ACP and MCP
 * events arrive. Thread safety is managed by {@link ToolCallTracker}'s synchronization.
 */
public final class ToolCallRecord {

    /**
     * How this tool call is routed in the UI.
     */
    public enum RoutingType {
        /**
         * Normal tool call — renders as a chip in the main message.
         */
        REGULAR,
        /**
         * Sub-agent launch (Task tool) — renders as a sub-agent entry.
         */
        SUB_AGENT,
        /**
         * Tool call made internally by a running sub-agent.
         */
        SUB_AGENT_INTERNAL,
        /**
         * Copilot's task_complete built-in — summary rendered as text, not a chip.
         */
        TASK_COMPLETE
    }

    /**
     * Lifecycle state of the tool call.
     */
    public enum State {
        /**
         * ACP reported but MCP hasn't started yet.
         */
        PENDING,
        /**
         * MCP is executing.
         */
        RUNNING,
        /**
         * Both ACP and MCP confirm successful completion.
         */
        COMPLETED,
        /**
         * Either ACP or MCP reported failure.
         */
        FAILED,
        /**
         * MCP-only call that was never correlated with ACP.
         */
        EXTERNAL
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    private final String recordId;
    private @Nullable String argsHash;

    // ── ACP-side (set when ACP reports) ──────────────────────────────────────

    private @Nullable String acpClientId;
    private @Nullable String acpTitle;
    private @Nullable JsonObject acpArgs;
    private @NotNull RoutingType routingType = RoutingType.REGULAR;
    private int acpSequence;

    // ── MCP-side (set when MCP executes) ─────────────────────────────────────

    private @Nullable String mcpToolName;
    private @Nullable JsonObject mcpArgs;
    private @Nullable String mcpResult;
    private boolean mcpSuccess;

    // ── Shared metadata ──────────────────────────────────────────────────────

    private @Nullable String kind;
    private @NotNull State state = State.PENDING;

    // ── Display ──────────────────────────────────────────────────────────────

    /**
     * The display name shown in the UI chip. Updated when MCP provides a more accurate name.
     */
    private @Nullable String displayName;

    // ── Timing ───────────────────────────────────────────────────────────────

    private long createdAt;
    private long mcpStartedAt;
    private long mcpCompletedAt;

    // ── Data throughput ──────────────────────────────────────────────────────

    private int resultBytes;

    // ── Sub-agent specific ───────────────────────────────────────────────────

    private @Nullable String subAgentType;
    private @Nullable String subAgentDescription;
    private @Nullable String subAgentPrompt;

    // ── Constructor ──────────────────────────────────────────────────────────

    ToolCallRecord(@NotNull String recordId, @Nullable String argsHash) {
        this.recordId = recordId;
        this.argsHash = argsHash;
        this.createdAt = System.currentTimeMillis();
    }

    // ── ACP field setters (called by ToolCallTracker) ────────────────────────

    void setAcpFields(
        @NotNull String acpClientId,
        @NotNull String acpTitle,
        @Nullable JsonObject acpArgs,
        @NotNull RoutingType routingType,
        int acpSequence
    ) {
        this.acpClientId = acpClientId;
        this.acpTitle = acpTitle;
        this.acpArgs = acpArgs;
        this.routingType = routingType;
        this.acpSequence = acpSequence;
        if (this.displayName == null) {
            this.displayName = acpTitle;
        }
    }

    // ── MCP field setters (called by ToolCallTracker) ────────────────────────

    void setMcpFields(
        @NotNull String toolName,
        @NotNull JsonObject args,
        @Nullable String kind,
        long startTime
    ) {
        this.mcpToolName = toolName;
        this.mcpArgs = args;
        if (kind != null) this.kind = kind;
        this.mcpStartedAt = startTime;
        this.state = State.RUNNING;
        // MCP name is authoritative — override ACP display name
        this.displayName = toolName;
    }

    void setMcpResult(@NotNull String result, boolean success) {
        this.mcpResult = result;
        this.mcpSuccess = success;
        this.mcpCompletedAt = System.currentTimeMillis();
        this.resultBytes = result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /**
     * Updates the args hash when arguments arrive late (e.g. ACP sends tool_call with no args,
     * then tool_call_update with actual args). Called by {@link ToolCallTracker#acpProvideArgs}.
     */
    void updateArgsHash(@Nullable String newHash) {
        this.argsHash = newHash;
    }

    /**
     * Updates ACP arguments without resetting other ACP fields.
     * Used when args arrive late via tool_call_update.
     */
    void setAcpArgs(@Nullable JsonObject args) {
        this.acpArgs = args;
    }

    // ── State ────────────────────────────────────────────────────────────────

    void setState(@NotNull State state) {
        this.state = state;
    }

    // ── Metadata setters ─────────────────────────────────────────────────────

    public void setKind(@NotNull String kind) {
        this.kind = kind;
    }

    public void setDisplayName(@NotNull String displayName) {
        this.displayName = displayName;
    }

    public void setSubAgentInfo(@Nullable String type, @Nullable String description, @Nullable String prompt) {
        this.subAgentType = type;
        this.subAgentDescription = description;
        this.subAgentPrompt = prompt;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public @NotNull String getRecordId() {
        return recordId;
    }

    public @Nullable String getArgsHash() {
        return argsHash;
    }

    // ACP-side
    public @Nullable String getAcpClientId() {
        return acpClientId;
    }

    public @Nullable String getAcpTitle() {
        return acpTitle;
    }

    public @Nullable JsonObject getAcpArgs() {
        return acpArgs;
    }

    public @NotNull RoutingType getRoutingType() {
        return routingType;
    }

    public int getAcpSequence() {
        return acpSequence;
    }

    // MCP-side
    public @Nullable String getMcpToolName() {
        return mcpToolName;
    }

    public @Nullable JsonObject getMcpArgs() {
        return mcpArgs;
    }

    public @Nullable String getMcpResult() {
        return mcpResult;
    }

    public boolean isMcpSuccess() {
        return mcpSuccess;
    }

    // Shared
    public @Nullable String getKind() {
        return kind;
    }

    public @NotNull State getState() {
        return state;
    }

    public @Nullable String getDisplayName() {
        return displayName;
    }

    // Timing
    public long getCreatedAt() {
        return createdAt;
    }

    public long getMcpStartedAt() {
        return mcpStartedAt;
    }

    public long getMcpCompletedAt() {
        return mcpCompletedAt;
    }

    /**
     * MCP execution duration in milliseconds, or 0 if not yet completed.
     */
    public long getMcpDurationMs() {
        if (mcpStartedAt == 0 || mcpCompletedAt == 0) return 0;
        return mcpCompletedAt - mcpStartedAt;
    }

    // Data throughput
    public int getResultBytes() {
        return resultBytes;
    }

    // Sub-agent
    public @Nullable String getSubAgentType() {
        return subAgentType;
    }

    public @Nullable String getSubAgentDescription() {
        return subAgentDescription;
    }

    public @Nullable String getSubAgentPrompt() {
        return subAgentPrompt;
    }

    // Derived
    public boolean isCorrelated() {
        return acpClientId != null && mcpToolName != null;
    }

    public boolean isAcpOnly() {
        return acpClientId != null && mcpToolName == null;
    }

    public boolean isMcpOnly() {
        return acpClientId == null && mcpToolName != null;
    }

    /**
     * The best available tool name — MCP is authoritative, falls back to ACP title.
     */
    public @NotNull String getEffectiveToolName() {
        if (mcpToolName != null) return mcpToolName;
        if (acpTitle != null) return acpTitle;
        return "unknown";
    }

    @Override
    public String toString() {
        return "ToolCallRecord{" + recordId +
            ", acp=" + acpClientId +
            ", mcp=" + mcpToolName +
            ", state=" + state +
            ", routing=" + routingType +
            '}';
    }
}
