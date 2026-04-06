package com.github.catatafishen.ideagentforcopilot.session.v2;

import com.github.catatafishen.ideagentforcopilot.ui.EntryData;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts legacy {@link SessionMessage} (v2 disk format) into {@link EntryData} (UI model).
 *
 * <p>The {@link #fromMessages} direction is the only remaining conversion, used by
 * {@code SessionStoreV2.parseJsonlAutoDetect()} when reading legacy JSONL files that
 * store {@code SessionMessage} objects.
 *
 * <p>Each content part carries its own {@code "ts"} field preserving the original per-entry
 * timestamp. On deserialization, the part-level timestamp is preferred; the message-level
 * {@link SessionMessage#createdAt} is used only as a fallback for parts written before this
 * enrichment.
 */
public final class EntryDataConverter {

    private EntryDataConverter() {
        throw new IllegalStateException("Utility class");
    }

    // ── SessionMessage → EntryData ────────────────────────────────────────────

    @NotNull
    public static List<EntryData> fromMessages(@NotNull List<SessionMessage> messages) {
        List<EntryData> result = new ArrayList<>();

        for (SessionMessage msg : messages) {
            String ts = msg.createdAt > 0
                ? java.time.Instant.ofEpochMilli(msg.createdAt).toString()
                : "";

            if (EntryDataJsonAdapter.TYPE_SEPARATOR.equals(msg.role)) {
                result.add(new EntryData.SessionSeparator(
                    ts,
                    msg.agent != null ? msg.agent : ""));
                continue;
            }

            int entriesBefore = result.size();
            boolean hasTextOrThinking = false;
            // Track file part indices consumed by collectFileParts (attached to Prompt.contextFiles)
            java.util.Set<Integer> consumedFileIndices = new java.util.HashSet<>();

            for (int idx = 0; idx < msg.parts.size(); idx++) {
                JsonObject part = msg.parts.get(idx);
                String type = part.has("type") ? part.get("type").getAsString() : "";

                switch (type) {
                    case EntryDataJsonAdapter.TYPE_TEXT -> {
                        String text = part.has("text") ? part.get("text").getAsString() : "";
                        String partTs = readTimestamp(part, ts);
                        String partEid = readEntryId(part);
                        if ("user".equals(msg.role)) {
                            List<kotlin.Triple<String, String, Integer>> ctxFiles = collectFileParts(msg.parts, idx + 1, consumedFileIndices);
                            result.add(new EntryData.Prompt(text, partTs,
                                ctxFiles.isEmpty() ? null : ctxFiles, "",
                                partEid));
                        } else {
                            result.add(new EntryData.Text(
                                new StringBuilder(text),
                                partTs,
                                msg.agent != null ? msg.agent : "",
                                msg.model != null ? msg.model : "",
                                partEid));
                            hasTextOrThinking = true;
                        }
                    }
                    case "reasoning" -> {
                        String text = part.has("text") ? part.get("text").getAsString() : "";
                        String partTs = readTimestamp(part, ts);
                        String partEid = readEntryId(part);
                        result.add(new EntryData.Thinking(
                            new StringBuilder(text),
                            partTs,
                            msg.agent != null ? msg.agent : "",
                            msg.model != null ? msg.model : "",
                            partEid));
                        hasTextOrThinking = true;
                    }
                    case "tool-invocation" -> {
                        JsonObject inv = part.has("toolInvocation") ? part.getAsJsonObject("toolInvocation") : new JsonObject();
                        String toolName = inv.has("toolName") ? inv.get("toolName").getAsString() : "";
                        String args = inv.has("args") && !inv.get("args").isJsonNull() ? inv.get("args").getAsString() : null;
                        String toolResult = inv.has("result") && !inv.get("result").isJsonNull() ? inv.get("result").getAsString() : null;
                        boolean autoDenied = inv.has("denialReason");
                        String denialReason = autoDenied ? inv.get("denialReason").getAsString() : null;
                        String kind = inv.has("kind") ? inv.get("kind").getAsString() : "other";
                        String toolStatus = inv.has("status") ? inv.get("status").getAsString() : null;
                        String toolDescription = inv.has("description") ? inv.get("description").getAsString() : null;
                        String filePath = inv.has("filePath") ? inv.get("filePath").getAsString() : null;
                        boolean mcpHandled = inv.has("mcpHandled") && inv.get("mcpHandled").getAsBoolean();
                        String partTs = readTimestamp(part, ts);
                        String partEid = readEntryId(part);
                        result.add(new EntryData.ToolCall(
                            toolName, args, kind, toolResult, toolStatus, toolDescription, filePath,
                            autoDenied, denialReason, mcpHandled,
                            partTs, msg.agent != null ? msg.agent : "",
                            msg.model != null ? msg.model : "", partEid));
                    }
                    case EntryDataJsonAdapter.TYPE_SUBAGENT -> {
                        String agentType = part.has("agentType") ? part.get("agentType").getAsString() : "general-purpose";
                        String description = part.has("description") ? part.get("description").getAsString() : "";
                        String prompt = part.has("prompt") ? part.get("prompt").getAsString() : null;
                        String subResult = part.has("result") ? part.get("result").getAsString() : null;
                        String status = part.has("status") ? part.get("status").getAsString() : "completed";
                        int colorIndex = part.has("colorIndex") ? part.get("colorIndex").getAsInt() : 0;
                        String callId = part.has("callId") ? part.get("callId").getAsString() : null;
                        boolean autoDenied = part.has("autoDenied") && part.get("autoDenied").getAsBoolean();
                        String denialReason = part.has("denialReason") ? part.get("denialReason").getAsString() : null;
                        String partTs = readTimestamp(part, ts);
                        String partEid = readEntryId(part);
                        result.add(new EntryData.SubAgent(
                            agentType, description,
                            (prompt == null || prompt.isEmpty()) ? null : prompt,
                            (subResult == null || subResult.isEmpty()) ? null : subResult,
                            (status == null || status.isEmpty()) ? "completed" : status,
                            colorIndex, callId, autoDenied, denialReason,
                            partTs, msg.agent != null ? msg.agent : "",
                            msg.model != null ? msg.model : "", partEid));
                    }
                    case EntryDataJsonAdapter.TYPE_STATUS -> {
                        String icon = part.has("icon") ? part.get("icon").getAsString() : "ℹ";
                        String message = part.has("message") ? part.get("message").getAsString() : "";
                        String partEid = readEntryId(part);
                        result.add(new EntryData.Status(icon, message, partEid));
                    }
                    case "file" -> {
                        if (consumedFileIndices.contains(idx)) break;
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
            // insert a trailing empty Text so appendAgentTurn() produces a proper message block.
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

    /**
     * Reads a per-entry timestamp from a V2 part, falling back to the message-level timestamp.
     */
    @NotNull
    private static String readTimestamp(@NotNull JsonObject part, @NotNull String messageLevelTs) {
        if (part.has("ts")) {
            String partTs = part.get("ts").getAsString();
            if (!partTs.isEmpty()) return partTs;
        }
        return messageLevelTs;
    }

    /**
     * Read entry ID from a part's "eid" field, falling back to a new UUID if absent.
     */
    static String readEntryId(JsonObject part) {
        return part.has("eid") ? part.get("eid").getAsString() : java.util.UUID.randomUUID().toString();
    }

    /**
     * Collect consecutive "file" parts starting at {@code startIdx} from a parts list,
     * returning them as context file triples (name, path, line). Skips non-file parts.
     * Records consumed indices in {@code consumed} so the caller can skip them.
     */
    static List<kotlin.Triple<String, String, Integer>> collectFileParts(
        List<JsonObject> parts, int startIdx, java.util.Set<Integer> consumed) {
        List<kotlin.Triple<String, String, Integer>> files = new ArrayList<>();
        for (int i = startIdx; i < parts.size(); i++) {
            JsonObject p = parts.get(i);
            String t = p.has("type") ? p.get("type").getAsString() : "";
            if (!"file".equals(t)) continue;
            String fn = p.has("filename") ? p.get("filename").getAsString() : "";
            String path = p.has("path") ? p.get("path").getAsString() : "";
            int line = p.has("line") ? p.get("line").getAsInt() : 0;
            files.add(new kotlin.Triple<>(fn, path, line));
            consumed.add(i);
        }
        return files;
    }
}
