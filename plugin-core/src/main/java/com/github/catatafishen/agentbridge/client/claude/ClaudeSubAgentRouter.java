package com.github.catatafishen.agentbridge.client.claude;

import com.github.catatafishen.agentbridge.model.ContentBlock;
import com.github.catatafishen.agentbridge.model.SessionUpdate;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends the stream-json events of Claude Code's sub-agents (its Agent tool) to their sub-agent in the chat,
 * for one prompt. Events a sub-agent produces carry {@code parent_tool_use_id} and {@code agent_id};
 * {@code system} events of subtype {@code task_started} and {@code task_notification} report when it starts
 * and ends. A sub-agent's updates are wrapped in {@link SessionUpdate.ChildSessionUpdate} keyed by its agent
 * id, the id the CLI also names in the result of a background launch.
 *
 * <p>Used only by the stream reader thread.</p>
 */
final class ClaudeSubAgentRouter {

    /** The tool a sub-agent calls to deliver its report; its message is the sub-agent's answer. */
    static final String HANDBACK_TOOL = "SubagentHandback";

    private static final String ASYNC_LAUNCH_PREFIX = "Async agent launched successfully";
    private static final Pattern AGENT_ID = Pattern.compile("agentId:\\s*([\\w-]+)");
    private static final String PARAGRAPH_BREAK = "\n\n";
    /** Stream key of the main agent in {@link #lastTextMessage}; agent ids never contain a space. */
    private static final String MAIN_STREAM = "main agent";

    private static final String FIELD_TASK_ID = "task_id";
    private static final String FIELD_TOOL_USE_ID = "tool_use_id";
    private static final String FIELD_DESCRIPTION = "description";
    private static final Set<String> KNOWN_FAILURES = Set.of("failed", "error");

    /** Agent tool_use id → the id of the sub-agent it started. */
    private final Map<String, String> agentByToolUse = new HashMap<>();
    private final Map<String, String> descriptionByAgent = new HashMap<>();
    private final Set<String> handedBackAgents = new HashSet<>();
    private final Set<String> handbackToolUses = new HashSet<>();
    /** Per stream, the message id of the text shown last, or null once a tool call followed it. */
    private final Map<String, String> lastTextMessage = new HashMap<>();
    /** Sub-agents that started and have not reported an end, in start order. */
    private final Set<String> runningAgents = new LinkedHashSet<>();

    /**
     * The id of the sub-agent that produced {@code event}, or null for the main agent's own events.
     */
    @Nullable
    String childId(@NotNull JsonObject event) {
        String parentToolUse = string(event, "parent_tool_use_id");
        if (parentToolUse == null) return null;
        String agentId = string(event, "agent_id");
        String childId = agentId != null ? agentId : parentToolUse;
        agentByToolUse.putIfAbsent(parentToolUse, childId);
        String description = string(event, "task_description");
        if (description != null) descriptionByAgent.putIfAbsent(childId, description);
        return childId;
    }

    /** Where updates from {@code childId} go: straight to {@code onUpdate} for the main agent. */
    @NotNull
    Consumer<SessionUpdate> target(@Nullable String childId, @NotNull Consumer<SessionUpdate> onUpdate) {
        if (childId == null) return onUpdate;
        String title = descriptionByAgent.get(childId);
        return update -> onUpdate.accept(new SessionUpdate.ChildSessionUpdate(childId, title, update));
    }

    /**
     * {@code text} as a message chunk of the given stream. Text that starts a new message right after another
     * message's text gets a paragraph break, so the two do not run together in one bubble.
     */
    @NotNull
    SessionUpdate text(@Nullable String childId, @Nullable String messageId, @NotNull String text) {
        String stream = childId != null ? childId : MAIN_STREAM;
        String previous = lastTextMessage.get(stream);
        boolean newMessage = previous != null && messageId != null && !previous.equals(messageId);
        if (messageId != null) lastTextMessage.put(stream, messageId);
        String chunk = newMessage ? PARAGRAPH_BREAK + text : text;
        return new SessionUpdate.AgentMessageChunk(List.of(new ContentBlock.Text(chunk)));
    }

    /** A tool call of the given stream separates the text before it from the text after it. */
    void toolCalled(@Nullable String childId) {
        lastTextMessage.remove(childId != null ? childId : MAIN_STREAM);
    }

    /**
     * Records the hand-back tool call {@code toolUseId} of sub-agent {@code childId} and returns its report,
     * or null when the call carries none.
     */
    @Nullable
    String handback(@Nullable String childId, @NotNull String toolUseId, @NotNull JsonObject input) {
        handbackToolUses.add(toolUseId);
        if (childId != null) handedBackAgents.add(childId);
        String message = string(input, "message");
        return message == null || message.isBlank() ? null : message;
    }

    /** Whether {@code toolUseId} is a hand-back call, whose result only confirms the delivery. */
    boolean isHandbackResult(@NotNull String toolUseId) {
        return handbackToolUses.contains(toolUseId);
    }

    /**
     * The result to show for tool call {@code toolUseId}. When it started a sub-agent that handed its report
     * back, the CLI's result only says where the report went, and the report is already in the sub-agent's
     * section, so the result is empty.
     */
    @NotNull
    String toolResult(@NotNull String toolUseId, @NotNull String content) {
        String agentId = agentByToolUse.get(toolUseId);
        return agentId != null && handedBackAgents.contains(agentId) ? "" : content;
    }

    /**
     * The sub-agent status a {@code system} event reports, or null when it reports none: {@code task_started}
     * of a sub-agent, and {@code task_notification} of a task already known to be one.
     */
    @Nullable
    SessionUpdate.ChildSessionStatus taskStatus(@NotNull JsonObject event) {
        String subtype = string(event, "subtype");
        String taskId = string(event, FIELD_TASK_ID);
        if (subtype == null || taskId == null) return null;
        if ("task_started".equals(subtype)) {
            if (!"local_agent".equals(string(event, "task_type"))) return null;
            String toolUseId = string(event, FIELD_TOOL_USE_ID);
            if (toolUseId != null) agentByToolUse.put(toolUseId, taskId);
            String description = string(event, FIELD_DESCRIPTION);
            if (description != null) descriptionByAgent.put(taskId, description);
            runningAgents.add(taskId);
            return new SessionUpdate.ChildSessionStatus(
                taskId, description, SessionUpdate.ChildSessionState.RUNNING, null);
        }
        if ("task_notification".equals(subtype) && agentByToolUse.containsValue(taskId)) {
            runningAgents.remove(taskId);
            String status = string(event, "status");
            SessionUpdate.ChildSessionState state = finalState(status);
            String error = switch (state) {
                case COMPLETED -> null;
                case FAILED -> KNOWN_FAILURES.contains(status)
                    ? string(event, "summary")
                    : "Claude Code reported the sub-agent's status as '" + status + "'.";
                default -> string(event, "summary");
            };
            return new SessionUpdate.ChildSessionStatus(taskId, descriptionByAgent.get(taskId), state, error);
        }
        return null;
    }

    /**
     * The end of every sub-agent that started but never reported one, for when the CLI's output ends. Each is
     * reported once.
     */
    @NotNull
    List<SessionUpdate.ChildSessionStatus> unfinishedAtEndOfStream() {
        List<SessionUpdate.ChildSessionStatus> ended = new ArrayList<>();
        for (String agentId : runningAgents) {
            ended.add(new SessionUpdate.ChildSessionStatus(agentId, descriptionByAgent.get(agentId),
                SessionUpdate.ChildSessionState.FAILED, "Claude Code exited before the sub-agent reported back."));
        }
        runningAgents.clear();
        return ended;
    }

    /**
     * The id of the sub-agent that a tool result {@code result} started in the background, or null when the
     * result is not such a launch.
     */
    @Nullable
    static String backgroundAgentId(@Nullable String result) {
        if (result == null || !result.startsWith(ASYNC_LAUNCH_PREFIX)) return null;
        Matcher m = AGENT_ID.matcher(result);
        return m.find() ? m.group(1) : null;
    }

    /** How a sub-agent with the final {@code status} ended; a status this class does not know counts as failed. */
    @NotNull
    static SessionUpdate.ChildSessionState finalState(@Nullable String status) {
        if (status == null) return SessionUpdate.ChildSessionState.FAILED;
        return switch (status) {
            case "completed" -> SessionUpdate.ChildSessionState.COMPLETED;
            case "killed", "stopped", "cancelled", "canceled", "interrupted" ->
                SessionUpdate.ChildSessionState.INTERRUPTED;
            default -> SessionUpdate.ChildSessionState.FAILED;
        };
    }

    @Nullable
    private static String string(@NotNull JsonObject obj, @NotNull String field) {
        JsonElement el = obj.get(field);
        if (el == null || !el.isJsonPrimitive()) return null;
        String value = el.getAsString();
        return value.isEmpty() ? null : value;
    }
}
