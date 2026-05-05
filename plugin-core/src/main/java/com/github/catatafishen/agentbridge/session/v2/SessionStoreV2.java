package com.github.catatafishen.agentbridge.session.v2;

import com.github.catatafishen.agentbridge.session.exporters.ExportUtils;
import com.github.catatafishen.agentbridge.ui.ContextFileRef;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.github.catatafishen.agentbridge.ui.FileRef;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Legacy JSONL parsing utilities for migration and backfill purposes.
 *
 * <p>This class exists solely to support:
 * <ul>
 *   <li>{@link com.github.catatafishen.agentbridge.session.db.JsonlToSqliteMigrator} —
 *       converts JSONL session files to SQLite records</li>
 *   <li>Backfill services ({@code ToolCallStatisticsBackfill}, {@code TurnStatisticsBackfill}) —
 *       read legacy session index to enumerate sessions for statistics extraction</li>
 * </ul>
 *
 * <p>For runtime conversation persistence, use
 * {@link com.github.catatafishen.agentbridge.session.db.ConversationService} instead.
 */
public final class SessionStoreV2 {

    private static final Logger LOG = Logger.getInstance(SessionStoreV2.class);

    private static final String KEY_AGENT = "agent";
    private static final String KEY_CREATED_AT = "createdAt";
    private static final String KEY_DENIAL_REASON = "denialReason";
    private static final String KEY_MODEL = "model";
    private static final String KEY_FILENAME = "filename";
    private static final String KEY_STATUS = "status";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_RESULT = "result";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private SessionStoreV2() {
        // Utility class — not instantiable
    }

    // ── Session Record (shared with backfill services) ───────────────────────

    /**
     * Metadata record for a session from the legacy sessions-index.json.
     *
     * @param id        session UUID
     * @param agent     display name of the agent
     * @param name      human-readable session name
     * @param createdAt epoch millis when the session was created
     * @param updatedAt epoch millis when the session was last updated
     * @param turnCount number of user turns in the session
     */
    public record SessionRecord(
        @NotNull String id,
        @NotNull String agent,
        @NotNull String name,
        long createdAt,
        long updatedAt,
        int turnCount) {
    }

    // ── Legacy JSONL index reading ───────────────────────────────────────────

    @NotNull
    public static List<SessionRecord> listSessionsFromJsonlIndex(@Nullable String basePath) {
        if (basePath == null) return List.of();
        File indexFile = new File(new File(basePath, ExportUtils.LEGACY_SESSIONS_DIR), "sessions-index.json");
        if (!indexFile.isFile()) return List.of();
        try {
            String content = Files.readString(indexFile.toPath(), StandardCharsets.UTF_8);
            JsonArray arr = com.google.gson.JsonParser.parseString(content).getAsJsonArray();
            List<SessionRecord> result = new ArrayList<>();
            for (var element : arr) {
                SessionRecord parsed = parseSessionIndexEntry(element);
                if (parsed != null) result.add(parsed);
            }
            result.sort(Comparator.comparingLong(SessionRecord::updatedAt).reversed());
            return result;
        } catch (Exception e) {
            LOG.debug("Failed to read JSONL sessions index: " + e.getMessage());
            return List.of();
        }
    }

    @Nullable
    private static SessionRecord parseSessionIndexEntry(JsonElement element) {
        if (!element.isJsonObject()) return null;
        JsonObject obj = element.getAsJsonObject();
        String id = obj.has("id") ? obj.get("id").getAsString() : "";
        if (id.isEmpty()) return null;
        String agent = obj.has(KEY_AGENT) ? obj.get(KEY_AGENT).getAsString() : "";
        String name = obj.has("name") ? obj.get("name").getAsString() : "";
        long createdAt = obj.has(KEY_CREATED_AT) ? obj.get(KEY_CREATED_AT).getAsLong() : 0;
        long updatedAt = obj.has("updatedAt") ? obj.get("updatedAt").getAsLong() : 0;
        int turnCount = obj.has("turnCount") ? obj.get("turnCount").getAsInt() : 0;
        return new SessionRecord(id, agent, name, createdAt, updatedAt, turnCount);
    }

    // ── Session name truncation ──────────────────────────────────────────────

    private static final int MAX_SESSION_NAME_LENGTH = 60;

    static String truncateSessionName(@NotNull String promptText) {
        String name = promptText.replaceAll("\\s+", " ").trim();
        if (name.length() <= MAX_SESSION_NAME_LENGTH) return name;
        return name.substring(0, MAX_SESSION_NAME_LENGTH - 1) + "…";
    }

    // ── JSONL parsing (used by migrator and tests) ───────────────────────────

    @Nullable
    public static List<EntryData> parseJsonlAutoDetect(@NotNull String content) {
        List<EntryData> directEntries = new ArrayList<>();
        List<JsonObject> legacyMessages = new ArrayList<>();
        int skippedLines = 0;

        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (!parseOneJsonlLine(line, directEntries, legacyMessages)) skippedLines++;
        }

        if (skippedLines > 0) {
            int totalParsed = directEntries.size() + legacyMessages.size();
            LOG.warn("JSONL parse: loaded " + totalParsed + " entries, skipped "
                + skippedLines + " malformed lines");
        }

        if (!directEntries.isEmpty()) return directEntries;
        if (!legacyMessages.isEmpty()) return convertLegacyMessages(legacyMessages);
        return null;
    }

    static boolean parseOneJsonlLine(
        @NotNull String line,
        @NotNull List<EntryData> directEntries,
        @NotNull List<JsonObject> legacyMessages) {
        try {
            JsonObject obj = GSON.fromJson(line, JsonObject.class);
            if (EntryDataJsonAdapter.isEntryFormat(line)) {
                EntryData entry = EntryDataJsonAdapter.deserialize(obj);
                if (entry != null) directEntries.add(entry);
            } else {
                if (obj != null) legacyMessages.add(obj);
            }
            return true;
        } catch (Exception e) {
            LOG.warn("Skipping malformed JSONL line: " + line + " (" + e.getMessage() + ")");
            return false;
        }
    }

    @NotNull
    public static List<EntryData> convertLegacyMessages(@NotNull List<JsonObject> messages) {
        List<EntryData> result = new ArrayList<>();
        for (JsonObject msg : messages) {
            convertLegacyMessage(msg, result);
        }
        return result;
    }

    private record LegacyMsgHeader(
        @NotNull String role,
        @NotNull String agent,
        @NotNull String model,
        @NotNull String ts) {
    }

    private static LegacyMsgHeader parseLegacyMessageHeader(@NotNull JsonObject msg) {
        String role = msg.has("role") ? msg.get("role").getAsString() : "";
        long createdAt = msg.has(KEY_CREATED_AT) ? msg.get(KEY_CREATED_AT).getAsLong() : 0;
        String agent = msg.has(KEY_AGENT) && !msg.get(KEY_AGENT).isJsonNull()
            ? msg.get(KEY_AGENT).getAsString() : "";
        String model = msg.has(KEY_MODEL) && !msg.get(KEY_MODEL).isJsonNull()
            ? msg.get(KEY_MODEL).getAsString() : "";
        String ts = createdAt > 0 ? Instant.ofEpochMilli(createdAt).toString() : "";
        return new LegacyMsgHeader(role, agent, model, ts);
    }

    private static void convertLegacyMessage(@NotNull JsonObject msg, @NotNull List<EntryData> result) {
        LegacyMsgHeader h = parseLegacyMessageHeader(msg);
        if (EntryDataJsonAdapter.TYPE_SEPARATOR.equals(h.role())) {
            result.add(new EntryData.SessionSeparator(h.ts(), h.agent()));
            return;
        }

        JsonArray partsArray = msg.has("parts") ? msg.getAsJsonArray("parts") : new JsonArray();
        List<JsonObject> parts = new ArrayList<>();
        for (int i = 0; i < partsArray.size(); i++) {
            parts.add(partsArray.get(i).getAsJsonObject());
        }

        java.util.Set<Integer> consumedFileIndices = new java.util.HashSet<>();
        for (int idx = 0; idx < parts.size(); idx++) {
            processLegacyPart(parts.get(idx), h, parts, idx, consumedFileIndices, result);
        }
    }

    private static void processLegacyPart(
        @NotNull JsonObject part, @NotNull LegacyMsgHeader h,
        @NotNull List<JsonObject> parts, int idx,
        @NotNull java.util.Set<Integer> consumedFileIndices,
        @NotNull List<EntryData> result) {
        String type = part.has("type") ? part.get("type").getAsString() : "";
        switch (type) {
            case EntryDataJsonAdapter.TYPE_TEXT -> processTextPart(part, h, parts, idx, consumedFileIndices, result);
            case "reasoning" -> processReasoningPart(part, h, result);
            case "tool-invocation" -> processToolInvocationPart(part, h, result);
            case EntryDataJsonAdapter.TYPE_SUBAGENT -> processSubAgentPart(part, h, result);
            case EntryDataJsonAdapter.TYPE_STATUS -> processStatusPart(part, result);
            case "file" -> processFilePart(part, idx, consumedFileIndices, result);
            default -> { /* Unknown part type — skip for forward-compat */ }
        }
    }

    private static void processTextPart(
        @NotNull JsonObject part, @NotNull LegacyMsgHeader h,
        @NotNull List<JsonObject> parts, int idx,
        @NotNull java.util.Set<Integer> consumedFileIndices,
        @NotNull List<EntryData> result) {
        String text = part.has("text") ? part.get("text").getAsString() : "";
        String partTs = readLegacyTimestamp(part, h.ts());
        String partEid = readLegacyEntryId(part);
        if ("user".equals(h.role())) {
            List<ContextFileRef> ctxFiles = collectLegacyFileParts(parts, idx + 1, consumedFileIndices);
            result.add(new EntryData.Prompt(text, partTs, ctxFiles.isEmpty() ? null : ctxFiles, "", partEid));
        } else {
            result.add(new EntryData.Text(text, partTs, h.agent(), h.model(), partEid));
        }
    }

    private static void processReasoningPart(
        @NotNull JsonObject part, @NotNull LegacyMsgHeader h, @NotNull List<EntryData> result) {
        String text = part.has("text") ? part.get("text").getAsString() : "";
        String partTs = readLegacyTimestamp(part, h.ts());
        String partEid = readLegacyEntryId(part);
        result.add(new EntryData.Thinking(text, partTs, h.agent(), h.model(), partEid));
    }

    private static void processToolInvocationPart(
        @NotNull JsonObject part, @NotNull LegacyMsgHeader h, @NotNull List<EntryData> result) {
        JsonObject inv = part.has("toolInvocation") ? part.getAsJsonObject("toolInvocation") : new JsonObject();
        String toolName = inv.has("toolName") ? inv.get("toolName").getAsString() : "";
        String args = inv.has("args") && !inv.get("args").isJsonNull() ? inv.get("args").getAsString() : null;
        String toolResult = inv.has(KEY_RESULT) && !inv.get(KEY_RESULT).isJsonNull()
            ? inv.get(KEY_RESULT).getAsString() : null;
        boolean autoDenied = inv.has(KEY_DENIAL_REASON);
        String denialReason = autoDenied ? inv.get(KEY_DENIAL_REASON).getAsString() : null;
        String kind = inv.has("kind") ? inv.get("kind").getAsString() : "other";
        String toolStatus = inv.has(KEY_STATUS) ? inv.get(KEY_STATUS).getAsString() : null;
        String toolDescription = inv.has(KEY_DESCRIPTION) ? inv.get(KEY_DESCRIPTION).getAsString() : null;
        String filePath = inv.has("filePath") ? inv.get("filePath").getAsString() : null;
        String pluginTool = inv.has("pluginTool") ? inv.get("pluginTool").getAsString() : null;
        if (pluginTool == null && inv.has("mcpHandled") && inv.get("mcpHandled").getAsBoolean()) {
            pluginTool = toolName;
        }
        String partTs = readLegacyTimestamp(part, h.ts());
        String partEid = readLegacyEntryId(part);
        result.add(new EntryData.ToolCall(
            toolName, args, kind, toolResult, toolStatus, toolDescription, filePath,
            autoDenied, denialReason, pluginTool, partTs, h.agent(), h.model(), partEid));
    }

    private static void processSubAgentPart(
        @NotNull JsonObject part, @NotNull LegacyMsgHeader h, @NotNull List<EntryData> result) {
        String agentType = part.has("agentType") ? part.get("agentType").getAsString() : "general-purpose";
        String description = part.has(KEY_DESCRIPTION) ? part.get(KEY_DESCRIPTION).getAsString() : "";
        String prompt = part.has("prompt") ? part.get("prompt").getAsString() : null;
        String subResult = part.has(KEY_RESULT) ? part.get(KEY_RESULT).getAsString() : null;
        String status = part.has(KEY_STATUS) ? part.get(KEY_STATUS).getAsString() : "completed";
        int colorIndex = part.has("colorIndex") ? part.get("colorIndex").getAsInt() : 0;
        String callId = part.has("callId") ? part.get("callId").getAsString() : null;
        boolean autoDenied = part.has("autoDenied") && part.get("autoDenied").getAsBoolean();
        String denialReason = part.has(KEY_DENIAL_REASON) ? part.get(KEY_DENIAL_REASON).getAsString() : null;
        String partTs = readLegacyTimestamp(part, h.ts());
        String partEid = readLegacyEntryId(part);
        result.add(new EntryData.SubAgent(
            agentType, description,
            (prompt == null || prompt.isEmpty()) ? null : prompt,
            (subResult == null || subResult.isEmpty()) ? null : subResult,
            (status == null || status.isEmpty()) ? "completed" : status,
            colorIndex, callId, autoDenied, denialReason, partTs, h.agent(), h.model(), partEid));
    }

    private static void processStatusPart(@NotNull JsonObject part, @NotNull List<EntryData> result) {
        String icon = part.has("icon") ? part.get("icon").getAsString() : "ℹ";
        String message = part.has("message") ? part.get("message").getAsString() : "";
        String partEid = readLegacyEntryId(part);
        result.add(new EntryData.Status(icon, message, partEid));
    }

    private static void processFilePart(
        @NotNull JsonObject part, int idx,
        @NotNull java.util.Set<Integer> consumedFileIndices,
        @NotNull List<EntryData> result) {
        if (consumedFileIndices.contains(idx)) return;
        String filename = part.has(KEY_FILENAME) ? part.get(KEY_FILENAME).getAsString() : "";
        String path = part.has("path") ? part.get("path").getAsString() : "";
        result.add(new EntryData.ContextFiles(List.of(new FileRef(filename, path))));
    }

    // ── Legacy conversion helpers ────────────────────────────────────────────

    @NotNull
    public static String readLegacyTimestamp(@NotNull JsonObject part, @NotNull String messageLevelTs) {
        if (part.has("ts")) {
            String partTs = part.get("ts").getAsString();
            if (!partTs.isEmpty()) return partTs;
        }
        return messageLevelTs;
    }

    private static String readLegacyEntryId(JsonObject part) {
        return part.has("eid") ? part.get("eid").getAsString() : UUID.randomUUID().toString();
    }

    /**
     * Collect consecutive "file" parts starting at {@code startIdx} from a parts list.
     */
    public static List<ContextFileRef> collectLegacyFileParts(
        List<JsonObject> parts, int startIdx, java.util.Set<Integer> consumed) {
        List<ContextFileRef> files = new ArrayList<>();
        for (int i = startIdx; i < parts.size(); i++) {
            JsonObject p = parts.get(i);
            String t = p.has("type") ? p.get("type").getAsString() : "";
            if (!"file".equals(t)) continue;
            String fn = p.has(KEY_FILENAME) ? p.get(KEY_FILENAME).getAsString() : "";
            String path = p.has("path") ? p.get("path").getAsString() : "";
            int line = p.has("line") ? p.get("line").getAsInt() : 0;
            files.add(new ContextFileRef(fn, path, line));
            consumed.add(i);
        }
        return files;
    }
}
