package com.github.catatafishen.ideagentforcopilot.session.v2;

import com.github.catatafishen.ideagentforcopilot.ui.EntryData;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Converts between {@link EntryData} (UI model) and {@link SessionMessage} (v2 disk format).
 *
 * <p>The conversion is lossy in minor details (e.g. per-entry timestamps collapse into the
 * message timestamp) but preserves all information needed to reconstruct the UI faithfully.
 */
public final class EntryDataConverter {

    private EntryDataConverter() {
        throw new IllegalStateException("Utility class");
    }

    // ── EntryData → SessionMessage ────────────────────────────────────────────

    /**
     * Converts a flat list of {@link EntryData} entries into grouped {@link SessionMessage}s.
     *
     * <p>Consecutive assistant-role entries belonging to the same agent are grouped into a
     * single message. A new message is started whenever the agent changes, a user prompt is
     * encountered, or a {@link EntryData.SessionSeparator} is encountered.
     */
    @NotNull
    public static List<SessionMessage> toMessages(@NotNull List<? extends EntryData> entries) {
        List<SessionMessage> result = new ArrayList<>();

        // The message currently being accumulated (null means nothing in progress).
        MutableMessage currentMsg = null;

        for (EntryData entry : entries) {
            if (entry instanceof EntryData.SessionSeparator sep) {
                // Flush whatever is pending
                if (currentMsg != null) {
                    result.add(currentMsg.build());
                    currentMsg = null;
                }
                // Emit separator as its own message (no parts)
                long sepTs = System.currentTimeMillis();
                if (!sep.getTimestamp().isEmpty()) {
                    try {
                        sepTs = java.time.Instant.parse(sep.getTimestamp()).toEpochMilli();
                    } catch (java.time.format.DateTimeParseException ignored) {
                        // Keep current time
                    }
                }
                result.add(new SessionMessage(
                    UUID.randomUUID().toString(),
                    "separator",
                    List.of(),
                    sepTs,
                    sep.getAgent().isEmpty() ? null : sep.getAgent(),
                    null));

            } else if (entry instanceof EntryData.Prompt prompt) {
                // Flush pending
                if (currentMsg != null) {
                    result.add(currentMsg.build());
                    currentMsg = null;
                }
                // Build a new user message
                MutableMessage userMsg = new MutableMessage("user", null);
                userMsg.setTimestampFromIso(prompt.getTimestamp());
                JsonObject part = new JsonObject();
                part.addProperty("type", "text");
                part.addProperty("text", prompt.getText());
                userMsg.parts.add(part);

                // Context files attached to this prompt
                if (prompt.getContextFiles() != null) {
                    for (var triple : prompt.getContextFiles()) {
                        JsonObject filePart = new JsonObject();
                        filePart.addProperty("type", "file");
                        filePart.addProperty("filename", triple.getFirst());
                        filePart.addProperty("path", triple.getSecond());
                        userMsg.parts.add(filePart);
                    }
                }
                result.add(userMsg.build());

            } else if (entry instanceof EntryData.ContextFiles ctx) {
                // Attach file parts to the most-recent user message (or create one)
                SessionMessage lastUser = findLastUserMessage(result);
                if (lastUser != null) {
                    // We need to append to it — rebuild it with extra parts
                    List<JsonObject> newParts = new ArrayList<>(lastUser.parts);
                    for (var pair : ctx.getFiles()) {
                        JsonObject filePart = new JsonObject();
                        filePart.addProperty("type", "file");
                        filePart.addProperty("filename", pair.getFirst());
                        filePart.addProperty("path", pair.getSecond());
                        newParts.add(filePart);
                    }
                    result.set(result.size() - 1,
                        new SessionMessage(lastUser.id, lastUser.role, newParts,
                            lastUser.createdAt, lastUser.agent, lastUser.model));
                } else {
                    // No prior user message — create a synthetic one
                    MutableMessage userMsg = new MutableMessage("user", null);
                    for (var pair : ctx.getFiles()) {
                        JsonObject filePart = new JsonObject();
                        filePart.addProperty("type", "file");
                        filePart.addProperty("filename", pair.getFirst());
                        filePart.addProperty("path", pair.getSecond());
                        userMsg.parts.add(filePart);
                    }
                    result.add(userMsg.build());
                }

            } else {
                // All remaining types belong to assistant messages
                String agent = agentOf(entry);

                boolean needsNewMsg = currentMsg == null
                    || !"assistant".equals(currentMsg.role)
                    || (agent != null && !agent.equals(currentMsg.agent));

                if (needsNewMsg) {
                    if (currentMsg != null) result.add(currentMsg.build());
                    currentMsg = new MutableMessage("assistant", agent);
                    currentMsg.setTimestampFromIso(timestampOf(entry));
                }

                if (entry instanceof EntryData.Text text) {
                    JsonObject part = new JsonObject();
                    part.addProperty("type", "text");
                    part.addProperty("text", text.getRaw().toString());
                    currentMsg.parts.add(part);

                } else if (entry instanceof EntryData.Thinking thinking) {
                    JsonObject part = new JsonObject();
                    part.addProperty("type", "reasoning");
                    part.addProperty("text", thinking.getRaw().toString());
                    currentMsg.parts.add(part);

                } else if (entry instanceof EntryData.ToolCall toolCall) {
                    JsonObject invocation = new JsonObject();
                    invocation.addProperty("state", "result");
                    invocation.addProperty("toolCallId", UUID.randomUUID().toString());
                    invocation.addProperty("toolName", toolCall.getTitle());
                    invocation.addProperty("args", toolCall.getArguments() != null ? toolCall.getArguments() : "");
                    invocation.addProperty("result", toolCall.getResult() != null ? toolCall.getResult() : "");
                    if (toolCall.getAutoDenied()) {
                        invocation.addProperty("denialReason",
                            toolCall.getDenialReason() != null ? toolCall.getDenialReason() : "");
                    }
                    String kind = toolCall.getKind();
                    if (!kind.isEmpty() && !"other".equals(kind)) {
                        invocation.addProperty("kind", kind);
                    }

                    JsonObject part = new JsonObject();
                    part.addProperty("type", "tool-invocation");
                    part.add("toolInvocation", invocation);
                    currentMsg.parts.add(part);

                } else if (entry instanceof EntryData.SubAgent subAgent) {
                    JsonObject part = new JsonObject();
                    part.addProperty("type", "subagent");
                    part.addProperty("agentType", subAgent.getAgentType());
                    part.addProperty("description", subAgent.getDescription());
                    part.addProperty("prompt", subAgent.getPrompt() != null ? subAgent.getPrompt() : "");
                    part.addProperty("result", subAgent.getResult() != null ? subAgent.getResult() : "");
                    part.addProperty("status", subAgent.getStatus() != null ? subAgent.getStatus() : "");
                    part.addProperty("colorIndex", subAgent.getColorIndex());
                    currentMsg.parts.add(part);

                } else if (entry instanceof EntryData.Status status) {
                    JsonObject part = new JsonObject();
                    part.addProperty("type", "status");
                    part.addProperty("icon", status.getIcon());
                    part.addProperty("message", status.getMessage());
                    currentMsg.parts.add(part);
                }
                // Unknown subtypes are silently ignored — forward-compat
            }
        }

        if (currentMsg != null) result.add(currentMsg.build());
        return result;
    }

    // ── SessionMessage → EntryData ────────────────────────────────────────────

    @NotNull
    public static List<EntryData> fromMessages(@NotNull List<SessionMessage> messages) {
        List<EntryData> result = new ArrayList<>();

        for (SessionMessage msg : messages) {
            String ts = msg.createdAt > 0
                ? java.time.Instant.ofEpochMilli(msg.createdAt).toString()
                : "";

            if ("separator".equals(msg.role)) {
                result.add(new EntryData.SessionSeparator(
                    ts,
                    msg.agent != null ? msg.agent : ""));
                continue;
            }

            int entriesBefore = result.size();
            boolean hasTextOrThinking = false;

            for (JsonObject part : msg.parts) {
                String type = part.has("type") ? part.get("type").getAsString() : "";

                switch (type) {
                    case "text" -> {
                        String text = part.has("text") ? part.get("text").getAsString() : "";
                        if ("user".equals(msg.role)) {
                            result.add(new EntryData.Prompt(text, ts, null));
                        } else {
                            result.add(new EntryData.Text(
                                new StringBuilder(text),
                                ts,
                                msg.agent != null ? msg.agent : ""));
                            hasTextOrThinking = true;
                        }
                    }
                    case "reasoning" -> {
                        String text = part.has("text") ? part.get("text").getAsString() : "";
                        result.add(new EntryData.Thinking(
                            new StringBuilder(text),
                            ts,
                            msg.agent != null ? msg.agent : ""));
                        hasTextOrThinking = true;
                    }
                    case "tool-invocation" -> {
                        JsonObject inv = part.has("toolInvocation") ? part.getAsJsonObject("toolInvocation") : new JsonObject();
                        String toolName = inv.has("toolName") ? inv.get("toolName").getAsString() : "";
                        String args = inv.has("args") ? inv.get("args").getAsString() : null;
                        String toolResult = inv.has("result") ? inv.get("result").getAsString() : null;
                        boolean autoDenied = inv.has("denialReason");
                        String denialReason = autoDenied ? inv.get("denialReason").getAsString() : null;
                        String kind = inv.has("kind") ? inv.get("kind").getAsString() : "other";
                        result.add(new EntryData.ToolCall(
                            toolName, args, kind, toolResult, null, null, null,
                            autoDenied, denialReason, false,
                            ts, msg.agent != null ? msg.agent : ""));
                    }
                    case "subagent" -> {
                        String agentType = part.has("agentType") ? part.get("agentType").getAsString() : "general-purpose";
                        String description = part.has("description") ? part.get("description").getAsString() : "";
                        String prompt = part.has("prompt") ? part.get("prompt").getAsString() : null;
                        String subResult = part.has("result") ? part.get("result").getAsString() : null;
                        String status = part.has("status") ? part.get("status").getAsString() : "completed";
                        int colorIndex = part.has("colorIndex") ? part.get("colorIndex").getAsInt() : 0;
                        result.add(new EntryData.SubAgent(
                            agentType, description,
                            (prompt == null || prompt.isEmpty()) ? null : prompt,
                            (subResult == null || subResult.isEmpty()) ? null : subResult,
                            (status == null || status.isEmpty()) ? "completed" : status,
                            colorIndex, null, false, null,
                            ts, msg.agent != null ? msg.agent : ""));
                    }
                    case "status" -> {
                        String icon = part.has("icon") ? part.get("icon").getAsString() : "ℹ";
                        String message = part.has("message") ? part.get("message").getAsString() : "";
                        result.add(new EntryData.Status(icon, message));
                    }
                    case "file" -> {
                        // file parts inside user message → ContextFiles
                        String filename = part.has("filename") ? part.get("filename").getAsString() : "";
                        String path = part.has("path") ? part.get("path").getAsString() : "";
                        result.add(new EntryData.ContextFiles(List.of(new kotlin.Pair<>(filename, path))));
                    }
                    default -> {
                        // Unknown part type — skip for forward-compat
                    }
                }
            }

            // When an assistant message has tool/subagent entries but no text or thinking,
            // the renderer groups all entries into a single segment with no message bubble.
            // Insert a trailing empty Text so appendAgentTurn() produces a proper message block.
            if ("assistant".equals(msg.role) && !hasTextOrThinking && result.size() > entriesBefore) {
                result.add(new EntryData.Text(
                    new StringBuilder(),
                    ts,
                    msg.agent != null ? msg.agent : ""));
            }
        }

        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @org.jetbrains.annotations.Nullable
    private static String agentOf(@NotNull EntryData entry) {
        if (entry instanceof EntryData.Text e) return e.getAgent().isEmpty() ? null : e.getAgent();
        if (entry instanceof EntryData.Thinking e) return e.getAgent().isEmpty() ? null : e.getAgent();
        if (entry instanceof EntryData.ToolCall e) return e.getAgent().isEmpty() ? null : e.getAgent();
        if (entry instanceof EntryData.SubAgent e) return e.getAgent().isEmpty() ? null : e.getAgent();
        if (entry instanceof EntryData.Status) return null;
        return null;
    }

    /**
     * Extracts the ISO timestamp string from an entry, or empty string if none.
     */
    @NotNull
    private static String timestampOf(@NotNull EntryData entry) {
        if (entry instanceof EntryData.Text e) return e.getTimestamp();
        if (entry instanceof EntryData.Thinking e) return e.getTimestamp();
        if (entry instanceof EntryData.ToolCall e) return e.getTimestamp();
        if (entry instanceof EntryData.SubAgent e) return e.getTimestamp();
        return "";
    }

    @org.jetbrains.annotations.Nullable
    private static SessionMessage findLastUserMessage(@NotNull List<SessionMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role)) return messages.get(i);
        }
        return null;
    }

    // ── Inner helper ──────────────────────────────────────────────────────────

    private static final class MutableMessage {
        final String role;
        final String agent;
        final List<JsonObject> parts = new ArrayList<>();
        long createdAt;

        MutableMessage(@NotNull String role, @org.jetbrains.annotations.Nullable String agent) {
            this.role = role;
            this.agent = agent;
            this.createdAt = System.currentTimeMillis();
        }

        /**
         * Sets createdAt from an ISO timestamp string (e.g. from EntryData.timestamp).
         */
        void setTimestampFromIso(@NotNull String isoTimestamp) {
            if (isoTimestamp.isEmpty()) return;
            try {
                this.createdAt = java.time.Instant.parse(isoTimestamp).toEpochMilli();
            } catch (java.time.format.DateTimeParseException ignored) {
                // Keep the default System.currentTimeMillis() value
            }
        }

        @NotNull
        SessionMessage build() {
            return new SessionMessage(
                UUID.randomUUID().toString(),
                role,
                new ArrayList<>(parts),
                createdAt,
                agent,
                null);
        }
    }
}
