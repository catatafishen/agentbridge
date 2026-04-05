package com.github.catatafishen.ideagentforcopilot.session.v2;

import com.github.catatafishen.ideagentforcopilot.ui.EntryData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
            json.addProperty("type", "prompt");
            json.addProperty("text", p.getText());
            addNonEmpty(json, "timestamp", p.getTimestamp());
            if (p.getContextFiles() != null && !p.getContextFiles().isEmpty()) {
                JsonArray arr = new JsonArray();
                for (var triple : p.getContextFiles()) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("name", triple.getFirst());
                    obj.addProperty("path", triple.getSecond());
                    if (triple.getThird() != 0) {
                        obj.addProperty("line", triple.getThird());
                    }
                    arr.add(obj);
                }
                json.add("contextFiles", arr);
            }
            addNonEmpty(json, "id", p.getId());
            json.addProperty("entryId", p.getEntryId());

        } else if (entry instanceof EntryData.Text t) {
            json.addProperty("type", "text");
            json.addProperty("raw", t.getRaw().toString());
            addNonEmpty(json, "timestamp", t.getTimestamp());
            addNonEmpty(json, "agent", t.getAgent());
            addNonEmpty(json, "model", t.getModel());
            json.addProperty("entryId", t.getEntryId());

        } else if (entry instanceof EntryData.Thinking th) {
            json.addProperty("type", "thinking");
            json.addProperty("raw", th.getRaw().toString());
            addNonEmpty(json, "timestamp", th.getTimestamp());
            addNonEmpty(json, "agent", th.getAgent());
            addNonEmpty(json, "model", th.getModel());
            json.addProperty("entryId", th.getEntryId());

        } else if (entry instanceof EntryData.ToolCall tc) {
            json.addProperty("type", "tool");
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
            if (tc.getMcpHandled()) {
                json.addProperty("mcpHandled", true);
            }
            addNonEmpty(json, "timestamp", tc.getTimestamp());
            addNonEmpty(json, "agent", tc.getAgent());
            addNonEmpty(json, "model", tc.getModel());
            json.addProperty("entryId", tc.getEntryId());

        } else if (entry instanceof EntryData.SubAgent sa) {
            json.addProperty("type", "subagent");
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
            json.addProperty("type", "context");
            if (!cf.getFiles().isEmpty()) {
                JsonArray arr = new JsonArray();
                for (var pair : cf.getFiles()) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("name", pair.getFirst());
                    obj.addProperty("path", pair.getSecond());
                    arr.add(obj);
                }
                json.add("files", arr);
            }
            json.addProperty("entryId", cf.getEntryId());

        } else if (entry instanceof EntryData.Status st) {
            json.addProperty("type", "status");
            json.addProperty("icon", st.getIcon());
            json.addProperty("message", st.getMessage());
            json.addProperty("entryId", st.getEntryId());

        } else if (entry instanceof EntryData.TurnStats ts) {
            json.addProperty("type", "turnStats");
            json.addProperty("turnId", ts.getTurnId());
            if (ts.getDurationMs() != 0) {
                json.addProperty("durationMs", ts.getDurationMs());
            }
            if (ts.getInputTokens() != 0) {
                json.addProperty("inputTokens", ts.getInputTokens());
            }
            if (ts.getOutputTokens() != 0) {
                json.addProperty("outputTokens", ts.getOutputTokens());
            }
            if (ts.getCostUsd() != 0.0) {
                json.addProperty("costUsd", ts.getCostUsd());
            }
            if (ts.getToolCallCount() != 0) {
                json.addProperty("toolCallCount", ts.getToolCallCount());
            }
            if (ts.getLinesAdded() != 0) {
                json.addProperty("linesAdded", ts.getLinesAdded());
            }
            if (ts.getLinesRemoved() != 0) {
                json.addProperty("linesRemoved", ts.getLinesRemoved());
            }
            addNonEmpty(json, "model", ts.getModel());
            addNonEmpty(json, "multiplier", ts.getMultiplier());
            if (ts.getTotalDurationMs() != 0) {
                json.addProperty("totalDurationMs", ts.getTotalDurationMs());
            }
            if (ts.getTotalInputTokens() != 0) {
                json.addProperty("totalInputTokens", ts.getTotalInputTokens());
            }
            if (ts.getTotalOutputTokens() != 0) {
                json.addProperty("totalOutputTokens", ts.getTotalOutputTokens());
            }
            if (ts.getTotalCostUsd() != 0.0) {
                json.addProperty("totalCostUsd", ts.getTotalCostUsd());
            }
            if (ts.getTotalToolCalls() != 0) {
                json.addProperty("totalToolCalls", ts.getTotalToolCalls());
            }
            if (ts.getTotalLinesAdded() != 0) {
                json.addProperty("totalLinesAdded", ts.getTotalLinesAdded());
            }
            if (ts.getTotalLinesRemoved() != 0) {
                json.addProperty("totalLinesRemoved", ts.getTotalLinesRemoved());
            }
            json.addProperty("entryId", ts.getEntryId());

        } else if (entry instanceof EntryData.SessionSeparator sep) {
            json.addProperty("type", "separator");
            addNonEmpty(json, "timestamp", sep.getTimestamp());
            addNonEmpty(json, "agent", sep.getAgent());
            json.addProperty("entryId", sep.getEntryId());
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
            case "prompt" -> {
                List<kotlin.Triple<String, String, Integer>> contextFiles = null;
                if (json.has("contextFiles") && json.get("contextFiles").isJsonArray()) {
                    contextFiles = new ArrayList<>();
                    for (var element : json.getAsJsonArray("contextFiles")) {
                        JsonObject obj = element.getAsJsonObject();
                        contextFiles.add(new kotlin.Triple<>(
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
            case "text" -> new EntryData.Text(
                new StringBuilder(str(json, "raw")),
                str(json, "timestamp"),
                str(json, "agent"),
                str(json, "model"),
                entryId);
            case "thinking" -> new EntryData.Thinking(
                new StringBuilder(str(json, "raw")),
                str(json, "timestamp"),
                str(json, "agent"),
                str(json, "model"),
                entryId);
            case "tool" -> new EntryData.ToolCall(
                str(json, "title"),
                strOrNull(json, "arguments"),
                str(json, "kind"),
                strOrNull(json, "result"),
                strOrNull(json, "status"),
                strOrNull(json, "description"),
                strOrNull(json, "filePath"),
                bool(json, "autoDenied"),
                strOrNull(json, "denialReason"),
                bool(json, "mcpHandled"),
                str(json, "timestamp"),
                str(json, "agent"),
                str(json, "model"),
                entryId);
            case "subagent" -> new EntryData.SubAgent(
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
            case "context" -> {
                List<kotlin.Pair<String, String>> files = new ArrayList<>();
                if (json.has("files") && json.get("files").isJsonArray()) {
                    for (var element : json.getAsJsonArray("files")) {
                        JsonObject obj = element.getAsJsonObject();
                        files.add(new kotlin.Pair<>(
                            str(obj, "name"),
                            str(obj, "path")));
                    }
                }
                yield new EntryData.ContextFiles(files, entryId);
            }
            case "status" -> new EntryData.Status(
                str(json, "icon"),
                str(json, "message"),
                entryId);
            case "separator" -> new EntryData.SessionSeparator(
                str(json, "timestamp"),
                str(json, "agent"),
                entryId);
            case "turnStats" -> new EntryData.TurnStats(
                str(json, "turnId"),
                longVal(json, "durationMs"),
                longVal(json, "inputTokens"),
                longVal(json, "outputTokens"),
                doubleVal(json, "costUsd"),
                intVal(json, "toolCallCount"),
                intVal(json, "linesAdded"),
                intVal(json, "linesRemoved"),
                str(json, "model"),
                str(json, "multiplier"),
                longVal(json, "totalDurationMs"),
                longVal(json, "totalInputTokens"),
                longVal(json, "totalOutputTokens"),
                doubleVal(json, "totalCostUsd"),
                intVal(json, "totalToolCalls"),
                intVal(json, "totalLinesAdded"),
                intVal(json, "totalLinesRemoved"),
                entryId);
            default -> null;
        };
    }

    // ── Format detection ──────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the JSON line uses the entry-per-line format
     * (has a {@code "type"} field) as opposed to the old {@code SessionMessage}
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

    private static void addNonEmpty(@NotNull JsonObject json, @NotNull String key,
                                    @Nullable String value) {
        if (value != null && !value.isEmpty()) {
            json.addProperty(key, value);
        }
    }
}
