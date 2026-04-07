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

        if (entry instanceof EntryData.Prompt p) {
            json.addProperty("type", TYPE_PROMPT);
            json.addProperty("text", p.getText());
            addNonEmpty(json, "timestamp", p.getTimestamp());
            if (p.getContextFiles() != null && !p.getContextFiles().isEmpty()) {
                JsonArray arr = new JsonArray();
                for (var ref : p.getContextFiles()) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("name", ref.getName());
                    obj.addProperty("path", ref.getPath());
                    if (ref.getLine() != 0) {
                        obj.addProperty("line", ref.getLine());
                    }
                    arr.add(obj);
                }
                json.add("contextFiles", arr);
            }
            addNonEmpty(json, "id", p.getId());
            json.addProperty("entryId", p.getEntryId());

        } else if (entry instanceof EntryData.Text t) {
            json.addProperty("type", TYPE_TEXT);
            json.addProperty("raw", t.getRaw());
            addNonEmpty(json, "timestamp", t.getTimestamp());
            addNonEmpty(json, "agent", t.getAgent());
            addNonEmpty(json, "model", t.getModel());
            json.addProperty("entryId", t.getEntryId());

        } else if (entry instanceof EntryData.Thinking th) {
            json.addProperty("type", TYPE_THINKING);
            json.addProperty("raw", th.getRaw());
            addNonEmpty(json, "timestamp", th.getTimestamp());
            addNonEmpty(json, "agent", th.getAgent());
            addNonEmpty(json, "model", th.getModel());
            json.addProperty("entryId", th.getEntryId());

        } else if (entry instanceof EntryData.ToolCall tc) {
            json.addProperty("type", TYPE_TOOL);
            json.addProperty("title", tc.getTitle());
            addNonEmpty(json, "arguments", tc.getArguments());
            addNonEmpty(json, "kind", tc.getKind());
            addNonEmpty(json, "result", tc.getResult());
            addNonEmpty(json, "status", tc.getStatus());
            addNonEmpty(json, "description", tc.getDescription());
            addNonEmpty(json, "filePath", tc.getFilePath());
            if (tc.getAutoDenied()) {
                json.addProperty("autoDenied", true);
            }
            addNonEmpty(json, "denialReason", tc.getDenialReason());
            String pluginTool = tc.getPluginTool();
            if (pluginTool != null) json.addProperty("pluginTool", pluginTool);
            addNonEmpty(json, "timestamp", tc.getTimestamp());
            addNonEmpty(json, "agent", tc.getAgent());
            addNonEmpty(json, "model", tc.getModel());
            json.addProperty("entryId", tc.getEntryId());

        } else if (entry instanceof EntryData.SubAgent sa) {
            json.addProperty("type", TYPE_SUBAGENT);
            json.addProperty("agentType", sa.getAgentType());
            json.addProperty("description", sa.getDescription());
            addNonEmpty(json, "prompt", sa.getPrompt());
            addNonEmpty(json, "result", sa.getResult());
            addNonEmpty(json, "status", sa.getStatus());
            if (sa.getColorIndex() != 0) {
                json.addProperty("colorIndex", sa.getColorIndex());
            }
            addNonEmpty(json, "callId", sa.getCallId());
            if (sa.getAutoDenied()) {
                json.addProperty("autoDenied", true);
            }
            addNonEmpty(json, "denialReason", sa.getDenialReason());
            addNonEmpty(json, "timestamp", sa.getTimestamp());
            addNonEmpty(json, "agent", sa.getAgent());
            addNonEmpty(json, "model", sa.getModel());
            json.addProperty("entryId", sa.getEntryId());

        } else if (entry instanceof EntryData.ContextFiles cf) {
            json.addProperty("type", TYPE_CONTEXT);
            if (!cf.getFiles().isEmpty()) {
                JsonArray arr = new JsonArray();
                for (var ref : cf.getFiles()) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("name", ref.getName());
                    obj.addProperty("path", ref.getPath());
                    arr.add(obj);
                }
                json.add("files", arr);
            }
            json.addProperty("entryId", cf.getEntryId());

        } else if (entry instanceof EntryData.Status st) {
            json.addProperty("type", TYPE_STATUS);
            json.addProperty("icon", st.getIcon());
            json.addProperty("message", st.getMessage());
            json.addProperty("entryId", st.getEntryId());

        } else if (entry instanceof EntryData.TurnStats ts) {
            json.addProperty("type", TYPE_TURN_STATS);
            json.addProperty("turnId", ts.getTurnId());
            addIfNonZero(json, "durationMs", ts.getDurationMs());
            addIfNonZero(json, "inputTokens", ts.getInputTokens());
            addIfNonZero(json, "outputTokens", ts.getOutputTokens());
            addIfNonZero(json, "costUsd", ts.getCostUsd());
            addIfNonZero(json, "toolCallCount", ts.getToolCallCount());
            addNonEmpty(json, "model", ts.getModel());
            addNonEmpty(json, "multiplier", ts.getMultiplier());
            addIfNonZero(json, "totalDurationMs", ts.getTotalDurationMs());
            addIfNonZero(json, "totalInputTokens", ts.getTotalInputTokens());
            addIfNonZero(json, "totalOutputTokens", ts.getTotalOutputTokens());
            addIfNonZero(json, "totalCostUsd", ts.getTotalCostUsd());
            addIfNonZero(json, "totalToolCalls", ts.getTotalToolCalls());
            json.addProperty("entryId", ts.getEntryId());

        } else if (entry instanceof EntryData.SessionSeparator sep) {
            json.addProperty("type", TYPE_SEPARATOR);
            addNonEmpty(json, "timestamp", sep.getTimestamp());
            addNonEmpty(json, "agent", sep.getAgent());
            json.addProperty("entryId", sep.getEntryId());

        } else if (entry instanceof EntryData.Nudge n) {
            json.addProperty("type", TYPE_NUDGE);
            json.addProperty("text", n.getText());
            json.addProperty("id", n.getId());
            if (n.getSent()) json.addProperty("sent", true);
            addNonEmpty(json, "timestamp", n.getTimestamp());
            json.addProperty("entryId", n.getEntryId());
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
        String type = str(json, "type");
        String entryId = str(json, "entryId");
        if (entryId.isEmpty()) {
            entryId = UUID.randomUUID().toString();
        }

        return switch (type) {
            case TYPE_PROMPT -> {
                List<ContextFileRef> contextFiles = null;
                if (json.has("contextFiles") && json.get("contextFiles").isJsonArray()) {
                    contextFiles = new ArrayList<>();
                    for (var element : json.getAsJsonArray("contextFiles")) {
                        JsonObject obj = element.getAsJsonObject();
                        contextFiles.add(new ContextFileRef(
                            str(obj, "name"),
                            str(obj, "path"),
                            intVal(obj, "line")));
                    }
                }
                yield new EntryData.Prompt(
                    str(json, "text"),
                    str(json, "timestamp"),
                    contextFiles,
                    str(json, "id"),
                    entryId);
            }
            case TYPE_TEXT -> new EntryData.Text(
                str(json, "raw"),
                str(json, "timestamp"),
                str(json, "agent"),
                str(json, "model"),
                entryId);
            case TYPE_THINKING -> new EntryData.Thinking(
                str(json, "raw"),
                str(json, "timestamp"),
                str(json, "agent"),
                str(json, "model"),
                entryId);
            case TYPE_TOOL -> {
                // Read new format first, fall back to legacy boolean
                String pluginTool = json.has("pluginTool") ? json.get("pluginTool").getAsString() : null;
                if (pluginTool == null && bool(json, "mcpHandled")) {
                    pluginTool = str(json, "title"); // best effort: use the title as the tool name
                }
                yield new EntryData.ToolCall(
                    str(json, "title"),
                    strOrNull(json, "arguments"),
                    str(json, "kind"),
                    strOrNull(json, "result"),
                    strOrNull(json, "status"),
                    strOrNull(json, "description"),
                    strOrNull(json, "filePath"),
                    bool(json, "autoDenied"),
                    strOrNull(json, "denialReason"),
                    pluginTool,
                    str(json, "timestamp"),
                    str(json, "agent"),
                    str(json, "model"),
                    entryId);
            }
            case TYPE_SUBAGENT -> new EntryData.SubAgent(
                str(json, "agentType"),
                str(json, "description"),
                strOrNull(json, "prompt"),
                strOrNull(json, "result"),
                strOrNull(json, "status"),
                intVal(json, "colorIndex"),
                strOrNull(json, "callId"),
                bool(json, "autoDenied"),
                strOrNull(json, "denialReason"),
                str(json, "timestamp"),
                str(json, "agent"),
                str(json, "model"),
                entryId);
            case TYPE_CONTEXT -> {
                List<FileRef> files = new ArrayList<>();
                if (json.has("files") && json.get("files").isJsonArray()) {
                    for (var element : json.getAsJsonArray("files")) {
                        JsonObject obj = element.getAsJsonObject();
                        files.add(new FileRef(
                            str(obj, "name"),
                            str(obj, "path")));
                    }
                }
                yield new EntryData.ContextFiles(files, entryId);
            }
            case TYPE_STATUS -> new EntryData.Status(
                str(json, "icon"),
                str(json, "message"),
                entryId);
            case TYPE_SEPARATOR -> new EntryData.SessionSeparator(
                str(json, "timestamp"),
                str(json, "agent"),
                entryId);
            case TYPE_TURN_STATS -> new EntryData.TurnStats(
                str(json, "turnId"),
                longVal(json, "durationMs"),
                longVal(json, "inputTokens"),
                longVal(json, "outputTokens"),
                doubleVal(json, "costUsd"),
                intVal(json, "toolCallCount"),
                str(json, "model"),
                str(json, "multiplier"),
                longVal(json, "totalDurationMs"),
                longVal(json, "totalInputTokens"),
                longVal(json, "totalOutputTokens"),
                doubleVal(json, "totalCostUsd"),
                intVal(json, "totalToolCalls"),
                entryId);
            case TYPE_NUDGE -> new EntryData.Nudge(
                str(json, "text"),
                str(json, "id"),
                bool(json, "sent"),
                str(json, "timestamp"),
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
