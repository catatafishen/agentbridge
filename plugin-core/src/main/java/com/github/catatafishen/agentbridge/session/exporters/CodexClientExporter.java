package com.github.catatafishen.agentbridge.session.exporters;

import com.github.catatafishen.agentbridge.ui.EntryData;
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

    private static final String F_TYPE = "type";
    private static final String F_CONTENT = "content";
    private static final String F_TIMESTAMP = "timestamp";

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
        return exportSession(entries, sessionsDir, dbPath, null);
    }

    @Nullable
    public static String exportSession(
        @NotNull List<EntryData> entries,
        @NotNull Path sessionsDir,
        @NotNull Path dbPath,
        @Nullable String cwd) {
        if (entries.isEmpty() || entries.stream().noneMatch(e -> e instanceof EntryData.Prompt)) return null;

        try {
            String threadId = UUID.randomUUID().toString();
            Path sessionDir = sessionsDir.resolve(threadId);
            Files.createDirectories(sessionDir);

            Path rolloutFile = sessionDir.resolve("rollout.jsonl");
            writeRolloutFile(entries, rolloutFile, threadId, cwd);

            long createdAt = findCreatedAt(entries);

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

    private static long findCreatedAt(@NotNull List<EntryData> entries) {
        long createdAt = System.currentTimeMillis() / 1000;
        for (EntryData entry : entries) {
            String ts = entry.getTimestamp();
            if (!ts.isEmpty()) {
                return parseCreatedAt(ts, createdAt);
            }
        }
        return createdAt;
    }

    private static long parseCreatedAt(@NotNull String timestamp, long fallback) {
        try {
            return Instant.parse(timestamp).toEpochMilli() / 1000;
        } catch (Exception e) {
            LOG.debug("Could not parse timestamp for Codex export: " + timestamp, e);
            return fallback;
        }
    }

    /**
     * Writes a rollout JSONL file for testing. A random thread ID is generated and cwd is null.
     * Use {@link #writeRolloutFile(List, Path, String, String)} when the thread ID and cwd are known.
     */
    static void writeRolloutFile(@NotNull List<EntryData> entries, @NotNull Path rolloutFile) throws IOException {
        writeRolloutFile(entries, rolloutFile, UUID.randomUUID().toString(), null);
    }

    /**
     * Writes a native Codex rollout JSONL file.
     *
     * <p>Format: a {@code session_meta} header line, followed by each content item wrapped in a
     * {@code response_item} envelope. This matches the format expected by {@code codex thread/resume}.</p>
     */
    static void writeRolloutFile(
        @NotNull List<EntryData> entries,
        @NotNull Path rolloutFile,
        @NotNull String threadId,
        @Nullable String cwd) throws IOException {
        StringBuilder sb = new StringBuilder();
        String timestamp = Instant.now().toString();

        writeSessionMeta(sb, threadId, timestamp, cwd);

        for (EntryData entry : entries) {
            writeEntryItem(entry, sb, timestamp);
        }
        Files.writeString(rolloutFile, sb.toString(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void writeEntryItem(
        @NotNull EntryData entry,
        @NotNull StringBuilder sb,
        @NotNull String timestamp) {
        switch (entry) {
            case EntryData.Prompt prompt -> {
                JsonObject item = new JsonObject();
                item.addProperty(F_TYPE, "message");
                item.addProperty("role", "user");
                JsonArray content = new JsonArray();
                JsonObject inputText = new JsonObject();
                inputText.addProperty(F_TYPE, "input_text");
                inputText.addProperty("text", prompt.getText());
                content.add(inputText);
                item.add(F_CONTENT, content);
                writeResponseItem(sb, item, timestamp);
            }
            case EntryData.Text text -> {
                String raw = text.getRaw();
                if (!raw.isEmpty()) {
                    JsonObject item = new JsonObject();
                    item.addProperty(F_TYPE, "message");
                    item.addProperty("role", "assistant");
                    JsonArray content = new JsonArray();
                    JsonObject outputText = new JsonObject();
                    outputText.addProperty(F_TYPE, "output_text");
                    outputText.addProperty("text", raw);
                    content.add(outputText);
                    item.add(F_CONTENT, content);
                    writeResponseItem(sb, item, timestamp);
                }
            }
            case EntryData.Thinking thinking -> {
                String raw = thinking.getRaw();
                if (!raw.isEmpty()) {
                    JsonObject item = new JsonObject();
                    item.addProperty(F_TYPE, "reasoning");
                    JsonArray content = new JsonArray();
                    JsonObject reasoningText = new JsonObject();
                    reasoningText.addProperty(F_TYPE, "reasoning_text");
                    reasoningText.addProperty("text", raw);
                    content.add(reasoningText);
                    item.add(F_CONTENT, content);
                    writeResponseItem(sb, item, timestamp);
                }
            }
            case EntryData.ToolCall toolCall -> {
                String callId = UUID.randomUUID().toString();
                String toolName = ExportUtils.normalizeToolNameForCodex(toolCall.getTitle());

                JsonObject callItem = new JsonObject();
                callItem.addProperty(F_TYPE, "function_call");
                callItem.addProperty("call_id", callId);
                callItem.addProperty("name", toolName);
                callItem.addProperty("arguments", toolCall.getArguments() != null ? toolCall.getArguments() : "{}");
                writeResponseItem(sb, callItem, timestamp);

                JsonObject outputItem = new JsonObject();
                outputItem.addProperty(F_TYPE, "function_call_output");
                outputItem.addProperty("call_id", callId);
                outputItem.addProperty("output", toolCall.getResult() != null ? toolCall.getResult() : "");
                writeResponseItem(sb, outputItem, timestamp);
            }
            default -> {
                // Skip SubAgent, Status, TurnStats, ContextFiles, SessionSeparator
            }
        }
    }

    private static void writeSessionMeta(
        @NotNull StringBuilder sb,
        @NotNull String threadId,
        @NotNull String timestamp,
        @Nullable String cwd) {
        JsonObject meta = new JsonObject();
        meta.addProperty(F_TYPE, "session_meta");
        meta.addProperty("id", threadId);
        meta.addProperty(F_TIMESTAMP, timestamp);
        if (cwd != null) {
            meta.addProperty("cwd", cwd);
        }
        meta.addProperty("originator", "intellij-copilot-plugin");
        meta.addProperty("cli_version", "0.0.0");
        meta.addProperty("source", "vscode");
        meta.addProperty("model_provider", "openai");
        sb.append(GSON.toJson(meta)).append('\n');
    }

    private static void writeResponseItem(
        @NotNull StringBuilder sb,
        @NotNull JsonObject payload,
        @NotNull String timestamp) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty(F_TYPE, "response_item");
        envelope.addProperty(F_TIMESTAMP, timestamp);
        envelope.add("payload", payload);
        sb.append(GSON.toJson(envelope)).append('\n');
    }

    private static void insertThread(@NotNull Path dbPath, @NotNull String threadId,
                                     @NotNull String rolloutPath, long createdAt) {
        String url = "jdbc:sqlite:" + dbPath;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            LOG.warn("SQLite JDBC driver not found on classpath", e);
            return;
        }
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
