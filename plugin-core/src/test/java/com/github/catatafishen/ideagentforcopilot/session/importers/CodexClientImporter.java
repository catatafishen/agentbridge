package com.github.catatafishen.ideagentforcopilot.session.importers;

import com.github.catatafishen.ideagentforcopilot.ui.EntryData;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CodexClientImporter {

    private static final Logger LOG = Logger.getInstance(CodexClientImporter.class);
    private static final Gson GSON = new Gson();

    private CodexClientImporter() {
    }

    @NotNull
    public static Path defaultDbPath() {
        return Path.of(System.getProperty("user.home"), ".codex", "codex.db");
    }

    @NotNull
    public static List<EntryData> importLatestThread(@NotNull Path dbPath) {
        if (!Files.exists(dbPath)) {
            LOG.info("Codex database not found at " + dbPath);
            return List.of();
        }

        String rolloutPath = findLatestRolloutPath(dbPath);
        if (rolloutPath == null) {
            LOG.info("No non-archived Codex threads found");
            return List.of();
        }

        Path rolloutFile = Path.of(rolloutPath);
        if (!Files.exists(rolloutFile)) {
            LOG.warn("Codex rollout file not found: " + rolloutPath);
            return List.of();
        }

        return importRolloutFile(rolloutFile);
    }

    @Nullable
    private static String findLatestRolloutPath(@NotNull Path dbPath) {
        String url = "jdbc:sqlite:" + dbPath;
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            try (ResultSet rs = stmt.executeQuery(
                "SELECT rollout_path FROM threads WHERE archived = 0 ORDER BY updated_at DESC LIMIT 1")) {
                if (rs.next()) {
                    return rs.getString("rollout_path");
                }
            }
        } catch (SQLException e) {
            LOG.warn("Failed to query Codex database: " + dbPath, e);
        }
        return null;
    }

    @NotNull
    public static List<EntryData> importRolloutFile(@NotNull Path rolloutFile) {
        List<String> lines;
        try {
            lines = Files.readAllLines(rolloutFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Failed to read Codex rollout file: " + rolloutFile, e);
            return List.of();
        }

        return convertRolloutLines(lines);
    }

    @NotNull
    static List<EntryData> convertRolloutLines(@NotNull List<String> lines) {
        List<EntryData> entries = new ArrayList<>();
        List<EntryData> pendingAssistantParts = new ArrayList<>();
        Map<String, EntryData.ToolCall> pendingCalls = new LinkedHashMap<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            JsonObject item;
            try {
                item = GSON.fromJson(trimmed, JsonObject.class);
            } catch (Exception e) {
                LOG.debug("Skipping unparseable Codex rollout line: " + trimmed);
                continue;
            }

            String type = JsonlUtil.getStr(item, "type");
            if (type == null) continue;

            switch (type) {
                case "message" -> handleMessage(item, entries, pendingAssistantParts);
                case "function_call" -> handleFunctionCall(item, pendingAssistantParts, pendingCalls);
                case "function_call_output" -> handleFunctionCallOutput(item, pendingAssistantParts, pendingCalls);
                case "reasoning" -> handleReasoning(item, pendingAssistantParts);
                default -> LOG.debug("Skipping unknown Codex rollout item type: " + type);
            }
        }

        flushAssistantParts(pendingAssistantParts, entries);
        return entries;
    }

    private static void handleMessage(
        @NotNull JsonObject item,
        @NotNull List<EntryData> entries,
        @NotNull List<EntryData> pendingAssistantParts
    ) {
        String role = JsonlUtil.getStr(item, "role");
        if ("user".equals(role)) {
            flushAssistantParts(pendingAssistantParts, entries);

            String text = extractContentText(item);
            entries.add(new EntryData.Prompt(text != null ? text : "", "", null, ""));
        } else if ("assistant".equals(role)) {
            String text = extractContentText(item);
            if (text != null && !text.isEmpty()) {
                pendingAssistantParts.add(new EntryData.Text(new StringBuilder(text), "", "", ""));
            }
        }
    }

    private static void handleFunctionCall(
        @NotNull JsonObject item,
        @NotNull List<EntryData> pendingAssistantParts,
        @NotNull Map<String, EntryData.ToolCall> pendingCalls
    ) {
        String callId = JsonlUtil.getStr(item, "call_id");
        String name = JsonlUtil.getStr(item, "name");
        String arguments = JsonlUtil.getStr(item, "arguments");

        EntryData.ToolCall toolCall = new EntryData.ToolCall(
            name != null ? name : "unknown",
            arguments);

        pendingAssistantParts.add(toolCall);

        if (callId != null) {
            pendingCalls.put(callId, toolCall);
        }
    }

    private static void handleFunctionCallOutput(
        @NotNull JsonObject item,
        @NotNull List<EntryData> pendingAssistantParts,
        @NotNull Map<String, EntryData.ToolCall> pendingCalls
    ) {
        String callId = JsonlUtil.getStr(item, "call_id");
        String output = JsonlUtil.getStr(item, "output");

        if (callId != null && pendingCalls.containsKey(callId)) {
            EntryData.ToolCall existing = pendingCalls.remove(callId);
            existing.setResult(output != null ? output : "");
            return;
        }

        EntryData.ToolCall orphan = new EntryData.ToolCall(
            "unknown",
            null);
        orphan.setResult(output != null ? output : "");
        pendingAssistantParts.add(orphan);
    }

    private static void handleReasoning(
        @NotNull JsonObject item,
        @NotNull List<EntryData> pendingAssistantParts
    ) {
        JsonArray content = item.getAsJsonArray("content");
        if (content == null) return;

        StringBuilder sb = new StringBuilder();
        for (JsonElement el : content) {
            if (!el.isJsonObject()) continue;
            JsonObject block = el.getAsJsonObject();
            if ("reasoning_text".equals(JsonlUtil.getStr(block, "type"))) {
                String text = JsonlUtil.getStr(block, "text");
                if (text != null) sb.append(text);
            }
        }

        if (!sb.isEmpty()) {
            pendingAssistantParts.add(new EntryData.Thinking(sb, "", "", ""));
        }
    }

    private static void flushAssistantParts(
        @NotNull List<EntryData> parts,
        @NotNull List<EntryData> entries
    ) {
        if (parts.isEmpty()) return;
        entries.addAll(parts);
        parts.clear();
    }

    @Nullable
    private static String extractContentText(@NotNull JsonObject message) {
        JsonArray content = message.getAsJsonArray("content");
        if (content == null) return null;

        StringBuilder sb = new StringBuilder();
        for (JsonElement el : content) {
            if (!el.isJsonObject()) continue;
            JsonObject block = el.getAsJsonObject();
            String text = JsonlUtil.getStr(block, "text");
            if (text != null) sb.append(text);
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
