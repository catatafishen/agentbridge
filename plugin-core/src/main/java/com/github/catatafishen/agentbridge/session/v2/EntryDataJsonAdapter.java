package com.github.catatafishen.agentbridge.session.v2;

import com.github.catatafishen.agentbridge.ui.ContextFileRef;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.github.catatafishen.agentbridge.ui.FileRef;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Serialization adapter that converts {@link EntryData} objects to/from JSON.
 *
 * <p>Each entry is represented as a single JSON object with a {@code "type"} discriminator
 * field. Field names match the Kotlin property names exactly.
 */
public final class EntryDataJsonAdapter {

    private static final Logger LOG = Logger.getInstance(EntryDataJsonAdapter.class);

    // Type discriminators
    public static final String TYPE_PROMPT = "prompt";
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_THINKING = "thinking";
    public static final String TYPE_TOOL = "tool";
    public static final String TYPE_SUBAGENT = "subagent";
    public static final String TYPE_CONTEXT = "context";
    public static final String TYPE_STATUS = "status";
    public static final String TYPE_SEPARATOR = "separator";
    public static final String TYPE_TURN_STATS = "turnStats";
    public static final String TYPE_NUDGE = "nudge";

    // JSON field name constants (used in both serialize and deserialize)
    private static final String KEY_TYPE = "type";
    private static final String KEY_TIMESTAMP = "timestamp";
    private static final String KEY_ENTRY_ID = "entryId";
    private static final String KEY_MODEL = "model";
    private static final String KEY_AGENT = "agent";
    private static final String KEY_TEXT = "text";
    private static final String KEY_RAW = "raw";
    private static final String KEY_NAME = "name";
    private static final String KEY_PATH = "path";
    private static final String KEY_TITLE = "title";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_RESULT = "result";
    private static final String KEY_STATUS = "status";
    private static final String KEY_AUTO_DENIED = "autoDenied";
    private static final String KEY_DENIAL_REASON = "denialReason";
    private static final String KEY_PLUGIN_TOOL = "pluginTool";
    private static final String KEY_FILES = "files";
    private static final String KEY_CONTEXT_FILES = "contextFiles";
    private static final String KEY_PROMPT = "prompt";

    private EntryDataJsonAdapter() {
        throw new IllegalStateException("Utility class");
    }

    // ── Serialize ─────────────────────────────────────────────────────────────

    /**
     * Converts one {@link EntryData} to a {@link JsonObject}.
     */
    @NotNull
    public static JsonObject serialize(@NotNull EntryData entry) {
        JsonObject json = new JsonObject();

        switch (entry) {
            case EntryData.Prompt p -> {
                json.addProperty(KEY_TYPE, TYPE_PROMPT);
                json.addProperty(KEY_TEXT, p.getText());
                addNonEmpty(json, KEY_TIMESTAMP, p.getTimestamp());
                if (p.getContextFiles() != null && !p.getContextFiles().isEmpty()) {
                    JsonArray arr = new JsonArray();
                    for (var ref : p.getContextFiles()) {
                        JsonObject obj = new JsonObject();
                        obj.addProperty(KEY_NAME, ref.getName());
                        obj.addProperty(KEY_PATH, ref.getPath());
                        if (ref.getLine() != 0) {
                            obj.addProperty("line", ref.getLine());
                        }
                        arr.add(obj);
                    }
                    json.add(KEY_CONTEXT_FILES, arr);
                }
                addNonEmpty(json, "id", p.getId());
                json.addProperty(KEY_ENTRY_ID, p.getEntryId());
            }
            case EntryData.Text t -> {
                json.addProperty(KEY_TYPE, TYPE_TEXT);
                json.addProperty(KEY_RAW, t.getRaw());
                addNonEmpty(json, KEY_TIMESTAMP, t.getTimestamp());
                addNonEmpty(json, KEY_AGENT, t.getAgent());
                addNonEmpty(json, KEY_MODEL, t.getModel());
                json.addProperty(KEY_ENTRY_ID, t.getEntryId());
            }
            case EntryData.Thinking th -> {
                json.addProperty(KEY_TYPE, TYPE_THINKING);
                json.addProperty(KEY_RAW, th.getRaw());
                addNonEmpty(json, KEY_TIMESTAMP, th.getTimestamp());
                addNonEmpty(json, KEY_AGENT, th.getAgent());
                addNonEmpty(json, KEY_MODEL, th.getModel());
                json.addProperty(KEY_ENTRY_ID, th.getEntryId());
            }
            case EntryData.ToolCall tc -> {
                json.addProperty(KEY_TYPE, TYPE_TOOL);
                json.addProperty(KEY_TITLE, tc.getTitle());
                addNonEmpty(json, "arguments", tc.getArguments());
                addNonEmpty(json, "kind", tc.getKind());
                addNonEmpty(json, KEY_RESULT, tc.getResult());
                addNonEmpty(json, KEY_STATUS, tc.getStatus());
                addNonEmpty(json, KEY_DESCRIPTION, tc.getDescription());
                addNonEmpty(json, "filePath", tc.getFilePath());
                if (tc.getAutoDenied()) {
                    json.addProperty(KEY_AUTO_DENIED, true);
                }
                addNonEmpty(json, KEY_DENIAL_REASON, tc.getDenialReason());
                String pluginTool = tc.getPluginTool();
                if (pluginTool != null) json.addProperty(KEY_PLUGIN_TOOL, pluginTool);
                addNonEmpty(json, KEY_TIMESTAMP, tc.getTimestamp());
                addNonEmpty(json, KEY_AGENT, tc.getAgent());
                addNonEmpty(json, KEY_MODEL, tc.getModel());
                json.addProperty(KEY_ENTRY_ID, tc.getEntryId());
            }
            case EntryData.SubAgent sa -> {
                json.addProperty(KEY_TYPE, TYPE_SUBAGENT);
                json.addProperty("agentType", sa.getAgentType());
                json.addProperty(KEY_DESCRIPTION, sa.getDescription());
                addNonEmpty(json, KEY_PROMPT, sa.getPrompt());
                addNonEmpty(json, KEY_RESULT, sa.getResult());
                addNonEmpty(json, KEY_STATUS, sa.getStatus());
                if (sa.getColorIndex() != 0) {
                    json.addProperty("colorIndex", sa.getColorIndex());
                }
                addNonEmpty(json, "callId", sa.getCallId());
                if (sa.getAutoDenied()) {
                    json.addProperty(KEY_AUTO_DENIED, true);
                }
                addNonEmpty(json, KEY_DENIAL_REASON, sa.getDenialReason());
                addNonEmpty(json, KEY_TIMESTAMP, sa.getTimestamp());
                addNonEmpty(json, KEY_AGENT, sa.getAgent());
                addNonEmpty(json, KEY_MODEL, sa.getModel());
                json.addProperty(KEY_ENTRY_ID, sa.getEntryId());
            }
            case EntryData.ContextFiles cf -> {
                json.addProperty(KEY_TYPE, TYPE_CONTEXT);
                if (!cf.getFiles().isEmpty()) {
                    JsonArray arr = new JsonArray();
                    for (var ref : cf.getFiles()) {
                        JsonObject obj = new JsonObject();
                        obj.addProperty(KEY_NAME, ref.getName());
                        obj.addProperty(KEY_PATH, ref.getPath());
                        arr.add(obj);
                    }
                    json.add(KEY_FILES, arr);
                }
                json.addProperty(KEY_ENTRY_ID, cf.getEntryId());
            }
            case EntryData.Status st -> {
                json.addProperty(KEY_TYPE, TYPE_STATUS);
                json.addProperty("icon", st.getIcon());
                json.addProperty("message", st.getMessage());
                json.addProperty(KEY_ENTRY_ID, st.getEntryId());
            }
            case EntryData.TurnStats ts -> {
                json.addProperty(KEY_TYPE, TYPE_TURN_STATS);
                json.addProperty("turnId", ts.getTurnId());
                addNonEmpty(json, KEY_TIMESTAMP, ts.getTimestamp());
                addIfNonZero(json, "durationMs", ts.getDurationMs());
                addIfNonZero(json, "inputTokens", ts.getInputTokens());
                addIfNonZero(json, "outputTokens", ts.getOutputTokens());
                addIfNonZero(json, "costUsd", ts.getCostUsd());
                addIfNonZero(json, "toolCallCount", ts.getToolCallCount());
                addIfNonZero(json, "linesAdded", ts.getLinesAdded());
                addIfNonZero(json, "linesRemoved", ts.getLinesRemoved());
                addNonEmpty(json, KEY_MODEL, ts.getModel());
                addNonEmpty(json, "multiplier", ts.getMultiplier());
                addIfNonZero(json, "totalDurationMs", ts.getTotalDurationMs());
                addIfNonZero(json, "totalInputTokens", ts.getTotalInputTokens());
                addIfNonZero(json, "totalOutputTokens", ts.getTotalOutputTokens());
                addIfNonZero(json, "totalCostUsd", ts.getTotalCostUsd());
                addIfNonZero(json, "totalToolCalls", ts.getTotalToolCalls());
                addIfNonZero(json, "totalLinesAdded", ts.getTotalLinesAdded());
                addIfNonZero(json, "totalLinesRemoved", ts.getTotalLinesRemoved());
                json.addProperty(KEY_ENTRY_ID, ts.getEntryId());
            }
            case EntryData.SessionSeparator sep -> {
                json.addProperty(KEY_TYPE, TYPE_SEPARATOR);
                addNonEmpty(json, KEY_TIMESTAMP, sep.getTimestamp());
                addNonEmpty(json, KEY_AGENT, sep.getAgent());
                json.addProperty(KEY_ENTRY_ID, sep.getEntryId());
            }
            case EntryData.Nudge n -> {
                json.addProperty(KEY_TYPE, TYPE_NUDGE);
                json.addProperty(KEY_TEXT, n.getText());
                json.addProperty("id", n.getId());
                if (n.getSent()) json.addProperty("sent", true);
                addNonEmpty(json, KEY_TIMESTAMP, n.getTimestamp());
                json.addProperty(KEY_ENTRY_ID, n.getEntryId());
            }
            default -> {
            }
        }

        return json;
    }

    // ── Deserialize ───────────────────────────────────────────────────────────

    /**
     * Converts one {@link JsonObject} back into an {@link EntryData}, or {@code null}
     * if the type is unknown (forward compatibility).
     */
    @Nullable
    public static EntryData deserialize(@NotNull JsonObject json) {
        String type = str(json, KEY_TYPE);
        String entryId = str(json, KEY_ENTRY_ID);
        if (entryId.isEmpty()) {
            entryId = UUID.randomUUID().toString();
        }

        return switch (type) {
            case TYPE_PROMPT -> {
                List<ContextFileRef> contextFiles = null;
                if (json.has(KEY_CONTEXT_FILES) && json.get(KEY_CONTEXT_FILES).isJsonArray()) {
                    contextFiles = new ArrayList<>();
                    for (var element : json.getAsJsonArray(KEY_CONTEXT_FILES)) {
                        JsonObject obj = element.getAsJsonObject();
                        contextFiles.add(new ContextFileRef(
                            str(obj, KEY_NAME),
                            str(obj, KEY_PATH),
                            intVal(obj, "line")));
                    }
                }
                yield new EntryData.Prompt(
                    str(json, KEY_TEXT),
                    str(json, KEY_TIMESTAMP),
                    contextFiles,
                    str(json, "id"),
                    entryId);
            }
            case TYPE_TEXT -> new EntryData.Text(
                str(json, KEY_RAW),
                str(json, KEY_TIMESTAMP),
                str(json, KEY_AGENT),
                str(json, KEY_MODEL),
                entryId);
            case TYPE_THINKING -> new EntryData.Thinking(
                str(json, KEY_RAW),
                str(json, KEY_TIMESTAMP),
                str(json, KEY_AGENT),
                str(json, KEY_MODEL),
                entryId);
            case TYPE_TOOL -> {
                String pluginTool = json.has(KEY_PLUGIN_TOOL) ? json.get(KEY_PLUGIN_TOOL).getAsString() : null;
                if (pluginTool == null && bool(json, "mcpHandled")) {
                    pluginTool = str(json, KEY_TITLE);
                }
                yield new EntryData.ToolCall(
                    str(json, KEY_TITLE),
                    strOrNull(json, "arguments"),
                    str(json, "kind"),
                    strOrNull(json, KEY_RESULT),
                    strOrNull(json, KEY_STATUS),
                    strOrNull(json, KEY_DESCRIPTION),
                    strOrNull(json, "filePath"),
                    bool(json, KEY_AUTO_DENIED),
                    strOrNull(json, KEY_DENIAL_REASON),
                    pluginTool,
                    str(json, KEY_TIMESTAMP),
                    str(json, KEY_AGENT),
                    str(json, KEY_MODEL),
                    entryId);
            }
            case TYPE_SUBAGENT -> new EntryData.SubAgent(
                str(json, "agentType"),
                str(json, KEY_DESCRIPTION),
                strOrNull(json, KEY_PROMPT),
                strOrNull(json, KEY_RESULT),
                strOrNull(json, KEY_STATUS),
                intVal(json, "colorIndex"),
                strOrNull(json, "callId"),
                bool(json, KEY_AUTO_DENIED),
                strOrNull(json, KEY_DENIAL_REASON),
                str(json, KEY_TIMESTAMP),
                str(json, KEY_AGENT),
                str(json, KEY_MODEL),
                entryId);
            case TYPE_CONTEXT -> {
                List<FileRef> files = new ArrayList<>();
                if (json.has(KEY_FILES) && json.get(KEY_FILES).isJsonArray()) {
                    for (var element : json.getAsJsonArray(KEY_FILES)) {
                        JsonObject obj = element.getAsJsonObject();
                        files.add(new FileRef(
                            str(obj, KEY_NAME),
                            str(obj, KEY_PATH)));
                    }
                }
                yield new EntryData.ContextFiles(files, entryId);
            }
            case TYPE_STATUS -> new EntryData.Status(
                str(json, "icon"),
                str(json, "message"),
                entryId);
            case TYPE_SEPARATOR -> new EntryData.SessionSeparator(
                str(json, KEY_TIMESTAMP),
                str(json, KEY_AGENT),
                entryId);
            case TYPE_TURN_STATS -> new EntryData.TurnStats(
                str(json, "turnId"),
                longVal(json, "durationMs"),
                longVal(json, "inputTokens"),
                longVal(json, "outputTokens"),
                doubleVal(json, "costUsd"),
                intVal(json, "toolCallCount"),
                intVal(json, "linesAdded"),
                intVal(json, "linesRemoved"),
                str(json, KEY_MODEL),
                str(json, "multiplier"),
                longVal(json, "totalDurationMs"),
                longVal(json, "totalInputTokens"),
                longVal(json, "totalOutputTokens"),
                doubleVal(json, "totalCostUsd"),
                intVal(json, "totalToolCalls"),
                intVal(json, "totalLinesAdded"),
                intVal(json, "totalLinesRemoved"),
                str(json, KEY_TIMESTAMP),
                entryId);
            case TYPE_NUDGE -> new EntryData.Nudge(
                str(json, KEY_TEXT),
                str(json, "id"),
                bool(json, "sent"),
                str(json, KEY_TIMESTAMP),
                entryId);
            default -> {
                LOG.debug("Skipping unknown entry type during deserialization: " + type);
                yield null;
            }
        };
    }

    // ── Format detection ──────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the JSON line uses the entry-per-line format
     * (has a {@code "type"} field) as opposed to the old legacy role-based
     * format (which uses a {@code "role"} field).
     */
    public static boolean isEntryFormat(@NotNull String line) {
        return line.contains("\"type\":");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @NotNull
    private static String str(@NotNull JsonObject o, @NotNull String key) {
        if (o.has(key) && !o.get(key).isJsonNull()) {
            return o.get(key).getAsString();
        }
        return "";
    }

    @Nullable
    private static String strOrNull(@NotNull JsonObject o, @NotNull String key) {
        if (o.has(key) && !o.get(key).isJsonNull()) {
            return o.get(key).getAsString();
        }
        return null;
    }

    private static boolean bool(@NotNull JsonObject o, @NotNull String key) {
        if (o.has(key) && !o.get(key).isJsonNull()) {
            return o.get(key).getAsBoolean();
        }
        return false;
    }

    private static int intVal(@NotNull JsonObject o, @NotNull String key) {
        if (o.has(key) && !o.get(key).isJsonNull()) {
            return o.get(key).getAsInt();
        }
        return 0;
    }

    private static long longVal(@NotNull JsonObject o, @NotNull String key) {
        return o.has(key) ? o.get(key).getAsLong() : 0;
    }

    private static double doubleVal(@NotNull JsonObject o, @NotNull String key) {
        return o.has(key) ? o.get(key).getAsDouble() : 0.0;
    }

    private static void addIfNonZero(@NotNull JsonObject json, @NotNull String key, long value) {
        if (value != 0) json.addProperty(key, value);
    }

    private static void addIfNonZero(@NotNull JsonObject json, @NotNull String key, int value) {
        if (value != 0) json.addProperty(key, value);
    }

    private static void addIfNonZero(@NotNull JsonObject json, @NotNull String key, double value) {
        if (value != 0.0) json.addProperty(key, value);
    }

    private static void addNonEmpty(@NotNull JsonObject json, @NotNull String key,
                                    @Nullable String value) {
        if (value != null && !value.isEmpty()) {
            json.addProperty(key, value);
        }
    }
}
