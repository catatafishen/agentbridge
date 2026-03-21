package com.github.catatafishen.ideagentforcopilot.agent.claude;

import com.github.catatafishen.ideagentforcopilot.acp.model.ContentBlock;
import com.github.catatafishen.ideagentforcopilot.acp.model.Model;
import com.github.catatafishen.ideagentforcopilot.acp.model.SessionUpdate;
import com.github.catatafishen.ideagentforcopilot.agent.AbstractAgentClient;
import com.github.catatafishen.ideagentforcopilot.agent.AgentException;
import com.github.catatafishen.ideagentforcopilot.services.ToolDefinition;
import com.github.catatafishen.ideagentforcopilot.services.ToolRegistry;
import com.github.catatafishen.ideagentforcopilot.settings.ProjectFilesSettings;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Shared base for Claude-based {@link AbstractAgentClient} implementations.
 *
 * <p>Holds constants, session-lifecycle maps, and utility methods common to
 * {@link AnthropicDirectClient} (direct HTTPS) and {@link ClaudeCliClient}
 * (subprocess via {@code claude} CLI).
 */
abstract class AbstractClaudeAgentClient extends AbstractAgentClient {

    private static final Logger LOG = Logger.getInstance(AbstractClaudeAgentClient.class);

    @Nullable
    protected final ToolRegistry registry;

    protected AbstractClaudeAgentClient(@Nullable ToolRegistry registry) {
        this.registry = registry;
    }

    // ── Claude model defaults ────────────────────────────────────────────────

    protected static final String DEFAULT_MODEL = "claude-sonnet-4-6";

    // ── JSON field names ─────────────────────────────────────────────────────

    protected static final String FIELD_TYPE = "type";
    protected static final String FIELD_CONTENT = "content";
    protected static final String FIELD_INPUT = "input";

    // ── Session state ────────────────────────────────────────────────────────

    protected final Map<String, String> sessionModels = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> sessionOptions = new ConcurrentHashMap<>();
    protected final Map<String, AtomicBoolean> sessionCancelled = new ConcurrentHashMap<>();
    protected volatile boolean started = false;

    // ── AbstractAgentClient — shared concrete implementations ────────────────

    @Override
    public void setModel(@NotNull String sessionId, @NotNull String modelId) {
        sessionModels.put(sessionId, modelId);
        LOG.info("Model set to " + modelId + " for session " + sessionId);
    }

    @Override
    public void setSessionOption(@NotNull String sessionId, @NotNull String key, @NotNull String value) {
        sessionOptions.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(key, value);
        LOG.info("Session option [" + key + "] set to '" + value + "' for session " + sessionId);
    }

    @Nullable
    protected String getSessionOption(@NotNull String sessionId, @NotNull String key) {
        Map<String, String> opts = sessionOptions.get(sessionId);
        return opts != null ? opts.get(key) : null;
    }

    @Override
    public List<Model> getAvailableModels() {
        return List.of();
    }

    // ── Shared utilities ─────────────────────────────────────────────────────

    @NotNull
    protected String resolveModel(@NotNull String sessionId, @Nullable String model) {
        if (model != null && !model.isEmpty()) return model;
        String stored = sessionModels.get(sessionId);
        return (stored != null && !stored.isEmpty()) ? stored : DEFAULT_MODEL;
    }

    protected void ensureStarted() throws AgentException {
        if (!started) throw new AgentException(getClass().getSimpleName() + " not started", null, false);
    }

    // ── Tool name normalisation ──────────────────────────────────────────────

    @NotNull
    public String normalizeToolName(@NotNull String name) {
        if (name.startsWith("mcp__agentbridge__")) {
            return name.substring("mcp__agentbridge__".length());
        }
        return name;
    }

    // ── Project files configuration ───────────────────────────────────────────

    @Override
    @NotNull
    public List<ProjectFilesSettings.FileEntry> getDefaultProjectFiles() {
        List<ProjectFilesSettings.FileEntry> entries = new ArrayList<>();
        entries.add(new ProjectFilesSettings.FileEntry("CLAUDE.md", "CLAUDE.md", false, "Claude"));
        return entries;
    }

    // ── sessionUpdate emission ───────────────────────────────────────────────

    protected void emitToolCallStart(@NotNull String toolUseId, @NotNull String toolName,
                                     @NotNull JsonObject input,
                                     @Nullable Consumer<SessionUpdate> onUpdate) {
        if (onUpdate == null) return;
        String normalized = normalizeToolName(toolName);
        String args = (!input.isEmpty()) ? input.toString() : null;
        String agentType = input.has("agent_type") ? input.get("agent_type").getAsString() : null;
        String subAgentDesc = agentType != null && input.has("description") ? input.get("description").getAsString() : null;
        String subAgentPrompt = agentType != null && input.has("prompt") ? input.get("prompt").getAsString() : null;
        SessionUpdate.ToolKind kind = SessionUpdate.ToolKind.OTHER;
        if (registry != null) {
            ToolDefinition tool = registry.findById(normalized);
            if (tool != null) {
                kind = SessionUpdate.ToolKind.fromCategory(tool.category());
            }
        }
        onUpdate.accept(new SessionUpdate.ToolCall(
            toolUseId, normalized, kind, args, null,
            agentType, subAgentDesc, subAgentPrompt));
    }

    protected void emitToolCallEnd(@NotNull String toolUseId, @NotNull String result,
                                   boolean success,
                                   @Nullable Consumer<SessionUpdate> onUpdate) {
        if (onUpdate == null) return;
        if (success) {
            onUpdate.accept(new SessionUpdate.ToolCallUpdate(toolUseId, SessionUpdate.ToolCallStatus.COMPLETED, result, null, null));
        } else {
            onUpdate.accept(new SessionUpdate.ToolCallUpdate(toolUseId, SessionUpdate.ToolCallStatus.FAILED, null, result, null));
        }
    }

    private static final Pattern RATE_LIMIT_PATTERN =
        Pattern.compile("hit.*limit|rate.?limit|usage.*limit", Pattern.CASE_INSENSITIVE);

    protected static boolean isRateLimitError(@NotNull String errorText) {
        return RATE_LIMIT_PATTERN.matcher(errorText).find();
    }

    protected void emitBannerEvent(@NotNull String message, @NotNull SessionUpdate.BannerLevel level,
                                   @NotNull SessionUpdate.ClearOn clearOn,
                                   @Nullable Consumer<SessionUpdate> onUpdate) {
        if (onUpdate == null) return;
        onUpdate.accept(new SessionUpdate.Banner(message, level, clearOn));
    }

    protected void emitRateLimitBanner(@NotNull String message,
                                       @Nullable Consumer<SessionUpdate> onUpdate) {
        emitBannerEvent(message, SessionUpdate.BannerLevel.WARNING, SessionUpdate.ClearOn.NEXT_SUCCESS, onUpdate);
    }

    protected void emitThought(@NotNull String text, @Nullable Consumer<SessionUpdate> onUpdate) {
        if (onUpdate == null || text.isEmpty()) return;
        onUpdate.accept(new SessionUpdate.AgentThoughtChunk(List.of(new ContentBlock.Text(text))));
    }
}
