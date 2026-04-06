package com.github.catatafishen.agentbridge.session.importers;

import com.github.catatafishen.agentbridge.session.exporters.OpenCodeClientExporter;
import com.github.catatafishen.agentbridge.ui.EntryData;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class OpenCodeClientImporter {

    private static final Logger LOG = Logger.getInstance(OpenCodeClientImporter.class);
    private static final Gson GSON = new Gson();
    private static final String KEY_PARTS = "parts";
    private static final String KEY_MODEL_ID = "modelID";

    private OpenCodeClientImporter() {
    }

    /**
     * Returns the default OpenCode database path.
     * Delegates to {@link com.github.catatafishen.agentbridge.session.exporters.OpenCodeClientExporter#defaultDbPath()}.
     */
    @NotNull
    public static Path defaultDbPath() {
        return com.github.catatafishen.agentbridge.session.exporters.OpenCodeClientExporter.defaultDbPath();
    }

    @NotNull
    public static List<EntryData> importLatestSession(@NotNull Path dbPath, @NotNull String projectDir) {
        if (!Files.exists(dbPath)) {
            LOG.info("OpenCode database not found at " + dbPath);
            return List.of();
        }

        String sessionId = findLatestSessionId(dbPath, projectDir);
        if (sessionId == null) {
            LOG.info("No OpenCode session found for project: " + projectDir);
            return List.of();
        }

        return importSession(dbPath, sessionId);
    }

    @Nullable
    private static String findLatestSessionId(@NotNull Path dbPath, @NotNull String projectDir) {
        try (Connection conn = OpenCodeClientExporter.openSqlite(dbPath)) {
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM session WHERE directory = ? ORDER BY time_updated DESC LIMIT 1")) {
                ps.setString(1, projectDir);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("id");
                    }
                }
            }
        } catch (SQLException e) {
            LOG.warn("Failed to query OpenCode database: " + dbPath, e);
        }
        return null;
    }

    @NotNull
    private static List<EntryData> importSession(@NotNull Path dbPath, @NotNull String sessionId) {
        List<EntryData> result = new ArrayList<>();

        try (Connection conn = OpenCodeClientExporter.openSqlite(dbPath)) {
            List<MessageRow> messageRows = loadMessages(conn, sessionId);

            for (MessageRow msgRow : messageRows) {
                List<JsonObject> parts = loadParts(conn, msgRow.id);
                if (parts.isEmpty() && msgRow.role != null) {
                    String text = extractTextFromMessageData(msgRow.data);
                    if (text != null) {
                        JsonObject textPart = new JsonObject();
                        textPart.addProperty("type", "text");
                        textPart.addProperty("text", text);
                        parts = List.of(textPart);
                    }
                }

                if (!parts.isEmpty()) {
                    String model = extractModel(msgRow.data);
                    if ("user".equals(msgRow.role)) {
                        // Concatenate all text parts into a single Prompt
                        StringBuilder sb = new StringBuilder();
                        for (JsonObject part : parts) {
                            String type = JsonlUtil.getStr(part, "type");
                            if ("text".equals(type)) {
                                String t = JsonlUtil.getStr(part, "text");
                                if (t != null) sb.append(t);
                            }
                        }
                        result.add(new EntryData.Prompt(sb.toString()));
                    } else {
                        // Assistant message: each part becomes its own EntryData
                        for (JsonObject part : parts) {
                            EntryData entry = convertPartToEntryData(part, model);
                            if (entry != null) {
                                result.add(entry);
                            }
                        }
                    }
                }
            }

            LOG.info("Imported OpenCode session " + sessionId + ": " + result.size() + " entries");
        } catch (SQLException e) {
            LOG.warn("Failed to import OpenCode session: " + sessionId, e);
        }
        return result;
    }

    @NotNull
    private static List<MessageRow> loadMessages(@NotNull Connection conn, @NotNull String sessionId) throws SQLException {
        List<MessageRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT id, data, time_created FROM message WHERE session_id = ? ORDER BY time_created ASC")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    String data = rs.getString("data");
                    long timeCreated = rs.getLong("time_created");

                    JsonObject dataObj = parseJson(data);
                    String role = dataObj != null ? JsonlUtil.getStr(dataObj, "role") : null;

                    rows.add(new MessageRow(id, role, data, dataObj, timeCreated));
                }
            }
        }
        return rows;
    }

    @NotNull
    private static List<JsonObject> loadParts(@NotNull Connection conn, @NotNull String messageId) throws SQLException {
        List<JsonObject> parts = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT data FROM part WHERE message_id = ? ORDER BY time_created ASC")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String data = rs.getString("data");
                    JsonObject partData = parseJson(data);
                    if (partData != null) {
                        JsonObject converted = convertOpenCodePartToV2(partData);
                        if (converted != null) {
                            parts.add(converted);
                        }
                    }
                }
            }
        }
        return parts;
    }

    /**
     * Converts an OpenCode native part to v2 format.
     * Skips ephemeral parts ({@code step-start}, {@code step-finish}) that have no content.
     *
     * <p>OpenCode tool calls use {@code {"type":"tool","callID":"...","tool":"...",
     * "state":{"status":"completed","input":{...},"output":"..."}}} which we convert
     * to v2 format {@code {"type":"tool-invocation","toolInvocation":{...}}}.</p>
     */
    @Nullable
    private static JsonObject convertOpenCodePartToV2(@NotNull JsonObject part) {
        String type = JsonlUtil.getStr(part, "type");
        if (type == null) return part;

        return switch (type) {
            case "text", "reasoning" -> part;
            case "step-start", "step-finish" -> null;
            case "tool" -> convertToolPartToV2(part);
            default -> part;
        };
    }

    /**
     * Converts a v2-format part JSON object directly to an {@link EntryData} instance.
     */
    @Nullable
    private static EntryData convertPartToEntryData(@NotNull JsonObject part, @Nullable String model) {
        String type = JsonlUtil.getStr(part, "type");
        if (type == null) return null;

        return switch (type) {
            case "text" -> {
                String text = JsonlUtil.getStr(part, "text");
                yield text != null ? new EntryData.Text(new StringBuilder(text), "", "", model != null ? model : "") : null;
            }
            case "reasoning" -> {
                String text = JsonlUtil.getStr(part, "text");
                yield text != null ? new EntryData.Thinking(new StringBuilder(text), "", "", model != null ? model : "") : null;
            }
            case "tool-invocation" -> {
                JsonObject inv = JsonlUtil.getObject(part, "toolInvocation");
                if (inv == null) yield null;
                String toolName = JsonlUtil.getStr(inv, "toolName");
                String args = JsonlUtil.getStr(inv, "args");
                String result = JsonlUtil.getStr(inv, "result");
                String state = JsonlUtil.getStr(inv, "state");
                EntryData.ToolCall tc = new EntryData.ToolCall(
                    toolName != null ? toolName : "unknown",
                    args,
                    "other",
                    result,
                    "result".equals(state) ? "completed" : state
                );
                yield tc;
            }
            default -> null;
        };
    }

    @NotNull
    private static JsonObject convertToolPartToV2(@NotNull JsonObject openCodePart) {
        String callId = JsonlUtil.getStr(openCodePart, "callID");
        String toolName = JsonlUtil.getStr(openCodePart, "tool");
        JsonObject state = JsonlUtil.getObject(openCodePart, "state");

        String status = state != null ? JsonlUtil.getStr(state, "status") : null;
        String output = state != null ? JsonlUtil.getStr(state, "output") : null;

        JsonObject invocation = new JsonObject();
        invocation.addProperty("toolCallId", callId != null ? callId : "");
        invocation.addProperty("toolName", toolName != null ? toolName : "unknown");
        invocation.addProperty("state", "completed".equals(status) ? "result" : "call");

        if (state != null && state.has("input")) {
            invocation.addProperty("args", GSON.toJson(state.get("input")));
        }
        if (output != null) {
            invocation.addProperty("result", output);
        }

        JsonObject v2Part = new JsonObject();
        v2Part.addProperty("type", "tool-invocation");
        v2Part.add("toolInvocation", invocation);
        return v2Part;
    }

    /**
     * Tries to extract text content from an OpenCode message's embedded parts array.
     * Used as a fallback when separate part rows are empty.
     */
    @Nullable
    private static String extractTextFromMessageData(@Nullable String data) {
        if (data == null) return null;
        JsonObject obj = parseJson(data);
        if (obj == null || !obj.has(KEY_PARTS) || !obj.get(KEY_PARTS).isJsonArray()) return null;

        StringBuilder sb = new StringBuilder();
        for (var el : obj.getAsJsonArray(KEY_PARTS)) {
            if (!el.isJsonObject()) continue;
            JsonObject part = el.getAsJsonObject();
            if ("text".equals(JsonlUtil.getStr(part, "type"))) {
                String text = JsonlUtil.getStr(part, "text");
                if (text != null) sb.append(text);
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * Extracts the model ID from an OpenCode message's data JSON.
     *
     * <p>OpenCode stores model info differently per role:</p>
     * <ul>
     *   <li>User messages: {@code model.modelID}</li>
     *   <li>Assistant messages: top-level {@code modelID}</li>
     * </ul>
     */
    @Nullable
    private static String extractModel(@Nullable String data) {
        if (data == null) return null;
        JsonObject obj = parseJson(data);
        if (obj == null) return null;

        // Assistant messages: top-level modelID
        String topLevel = JsonlUtil.getStr(obj, KEY_MODEL_ID);
        if (topLevel != null) return topLevel;

        // User messages: model.modelID
        JsonObject model = JsonlUtil.getObject(obj, "model");
        if (model != null) {
            String modelId = JsonlUtil.getStr(model, KEY_MODEL_ID);
            if (modelId != null) return modelId;
        }

        // Legacy fallback: metadata.assistant.modelID
        JsonObject metadata = JsonlUtil.getObject(obj, "metadata");
        if (metadata != null) {
            JsonObject assistant = JsonlUtil.getObject(metadata, "assistant");
            if (assistant != null) return JsonlUtil.getStr(assistant, KEY_MODEL_ID);
        }
        return null;
    }

    @Nullable
    private static JsonObject parseJson(@Nullable String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return GSON.fromJson(json, JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    private record MessageRow(
        @NotNull String id,
        @Nullable String role,
        @Nullable String data,
        @Nullable JsonObject dataObj,
        long timeCreated
    ) {
    }
}
