package com.github.catatafishen.ideagentforcopilot.session.exporters;

import com.github.catatafishen.ideagentforcopilot.ui.EntryData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CodexClientExporter {

    private static final Logger LOG = Logger.getInstance(CodexClientExporter.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private CodexClientExporter() {
    }

    @NotNull
    public static Path defaultSessionsDir() {
        return Path.of(System.getProperty("user.home"), ".codex", "sessions");
    }

    @NotNull
    public static Path defaultDbPath() {
        return Path.of(System.getProperty("user.home"), ".codex", "codex.db");
    }

    @Nullable
    public static String exportSession(
        @NotNull List<EntryData> entries,
        @NotNull Path sessionsDir,
        @NotNull Path dbPath) {
        if (entries.isEmpty() || entries.stream().noneMatch(e -> e instanceof EntryData.Prompt)) return null;

        try {
            String threadId = UUID.randomUUID().toString();
            Path sessionDir = sessionsDir.resolve(threadId);
            Files.createDirectories(sessionDir);

            Path rolloutFile = sessionDir.resolve("rollout.jsonl");
            writeRolloutFile(entries, rolloutFile);

            long createdAt = System.currentTimeMillis() / 1000;
            for (EntryData entry : entries) {
                String ts = getEntryTimestamp(entry);
                if (ts != null && !ts.isEmpty()) {
                    try {
                        createdAt = Instant.parse(ts).toEpochMilli() / 1000;
                    } catch (Exception e) {
                        LOG.debug("Could not parse timestamp for Codex export: " + ts, e);
                    }
                    break;
                }
            }

            if (Files.exists(dbPath)) {
                insertThread(dbPath, threadId, rolloutFile.toString(), createdAt);
            }

            LOG.info("Exported v2 session to Codex: " + threadId);
            return threadId;
        } catch (IOException e) {
            LOG.warn("Failed to export v2 session to Codex", e);
            return null;
        }
    }

    static void writeRolloutFile(@NotNull List<EntryData> entries, @NotNull Path rolloutFile) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (EntryData entry : entries) {
            if (entry instanceof EntryData.Prompt prompt) {
                JsonObject item = new JsonObject();
                item.addProperty("type", "message");
                item.addProperty("role", "user");
                JsonArray content = new JsonArray();
                JsonObject inputText = new JsonObject();
                inputText.addProperty("type", "input_text");
                inputText.addProperty("text", prompt.getText());
                content.add(inputText);
                item.add("content", content);
                sb.append(GSON.toJson(item)).append('\n');
            } else if (entry instanceof EntryData.Text text) {
                String raw = text.getRaw().toString();
                if (!raw.isEmpty()) {
                    JsonObject item = new JsonObject();
                    item.addProperty("type", "message");
                    item.addProperty("role", "assistant");
                    JsonArray content = new JsonArray();
                    JsonObject outputText = new JsonObject();
                    outputText.addProperty("type", "output_text");
                    outputText.addProperty("text", raw);
                    content.add(outputText);
                    item.add("content", content);
                    sb.append(GSON.toJson(item)).append('\n');
                }
            } else if (entry instanceof EntryData.Thinking thinking) {
                String raw = thinking.getRaw().toString();
                if (!raw.isEmpty()) {
                    JsonObject item = new JsonObject();
                    item.addProperty("type", "reasoning");
                    JsonArray content = new JsonArray();
                    JsonObject reasoningText = new JsonObject();
                    reasoningText.addProperty("type", "reasoning_text");
                    reasoningText.addProperty("text", raw);
                    content.add(reasoningText);
                    item.add("content", content);
                    sb.append(GSON.toJson(item)).append('\n');
                }
            } else if (entry instanceof EntryData.ToolCall toolCall) {
                String callId = UUID.randomUUID().toString();

                JsonObject callItem = new JsonObject();
                callItem.addProperty("type", "function_call");
                callItem.addProperty("call_id", callId);
                callItem.addProperty("name", toolCall.getTitle());
                callItem.addProperty("arguments", toolCall.getArguments() != null ? toolCall.getArguments() : "{}");
                sb.append(GSON.toJson(callItem)).append('\n');

                JsonObject outputItem = new JsonObject();
                outputItem.addProperty("type", "function_call_output");
                outputItem.addProperty("call_id", callId);
                outputItem.addProperty("output", toolCall.getResult() != null ? toolCall.getResult() : "");
                sb.append(GSON.toJson(outputItem)).append('\n');
            }
            // Skip SubAgent, Status, TurnStats, ContextFiles, SessionSeparator
        }
        Files.writeString(rolloutFile, sb.toString(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Nullable
    private static String getEntryTimestamp(@NotNull EntryData entry) {
        if (entry instanceof EntryData.Prompt p) return p.getTimestamp();
        if (entry instanceof EntryData.Text t) return t.getTimestamp();
        if (entry instanceof EntryData.Thinking t) return t.getTimestamp();
        if (entry instanceof EntryData.ToolCall t) return t.getTimestamp();
        if (entry instanceof EntryData.SubAgent s) return s.getTimestamp();
        if (entry instanceof EntryData.SessionSeparator s) return s.getTimestamp();
        return null;
    }

    private static void insertThread(@NotNull Path dbPath, @NotNull String threadId,
                                     @NotNull String rolloutPath, long createdAt) {
        String url = "jdbc:sqlite:" + dbPath;
        try (Connection conn = DriverManager.getConnection(url);
             Statement pragmaStmt = conn.createStatement()) {
            pragmaStmt.execute("PRAGMA journal_mode=WAL");

            pragmaStmt.execute("""
                CREATE TABLE IF NOT EXISTS threads (
                    id TEXT PRIMARY KEY,
                    rollout_path TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    archived INTEGER DEFAULT 0,
                    memory_mode TEXT
                )""");

            long now = System.currentTimeMillis() / 1000;
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO threads (id, rollout_path, created_at, updated_at, archived) VALUES (?, ?, ?, ?, 0)")) {
                ps.setString(1, threadId);
                ps.setString(2, rolloutPath);
                ps.setLong(3, createdAt);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
            LOG.info("Inserted Codex thread record: " + threadId);
        } catch (SQLException e) {
            LOG.warn("Failed to insert Codex thread record into " + dbPath, e);
        }
    }
}
