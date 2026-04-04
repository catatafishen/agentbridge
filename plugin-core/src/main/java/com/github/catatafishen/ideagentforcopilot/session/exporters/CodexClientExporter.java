package com.github.catatafishen.ideagentforcopilot.session.exporters;

import com.github.catatafishen.ideagentforcopilot.session.importers.JsonlUtil;
import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

public final class CodexClientExporter {

    private static final Logger LOG = Logger.getInstance(CodexClientExporter.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final String F_TYPE = "type";
    private static final String F_CONTENT = "content";
    private static final String F_TIMESTAMP = "timestamp";
    private static final String F_RESULT = "result";

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

    /**
     * Exports a v2 session to Codex rollout format.
     *
     * @param messages    the conversation messages to export
     * @param sessionsDir the Codex sessions directory (e.g. {@code ~/.codex/sessions/})
     * @param dbPath      the Codex SQLite database path (e.g. {@code ~/.codex/codex.db})
     * @return the thread ID on success, or {@code null} on failure
     */
    @Nullable
    public static String exportSession(
        @NotNull List<SessionMessage> messages,
        @NotNull Path sessionsDir,
        @NotNull Path dbPath) {
        return exportSession(messages, sessionsDir, dbPath, null);
    }

    /**
     * Exports a v2 session to Codex rollout format with optional working directory.
     *
     * @param messages    the conversation messages to export
     * @param sessionsDir the Codex sessions directory
     * @param dbPath      the Codex SQLite database path
     * @param cwd         the working directory to record in the session metadata (nullable)
     * @return the thread ID on success, or {@code null} on failure
     */
    @Nullable
    public static String exportSession(
        @NotNull List<SessionMessage> messages,
        @NotNull Path sessionsDir,
        @NotNull Path dbPath,
        @Nullable String cwd) {
        if (messages.isEmpty()) return null;

        try {
            String threadId = UUID.randomUUID().toString();
            Path sessionDir = sessionsDir.resolve(threadId);
            Files.createDirectories(sessionDir);

            Path rolloutFile = sessionDir.resolve("rollout.jsonl");
            writeRolloutFile(messages, rolloutFile, threadId, cwd);

            if (Files.exists(dbPath)) {
                insertThread(dbPath, threadId, rolloutFile.toString());
            }

            LOG.info("Exported v2 session to Codex: " + threadId);
            return threadId;
        } catch (IOException e) {
            LOG.warn("Failed to export v2 session to Codex", e);
            return null;
        }
    }

    /**
     * Writes a Codex-format rollout JSONL file.
     * <p>
     * Native Codex rollout files start with a {@code session_meta} header line (containing the
     * thread ID that Codex parses during {@code thread/resume}), followed by content items
     * wrapped in {@code response_item} envelopes with ISO-8601 timestamps.
     */
    static void writeRolloutFile(@NotNull List<SessionMessage> messages, @NotNull Path rolloutFile,
                                 @NotNull String threadId, @Nullable String cwd) throws IOException {
        StringBuilder sb = new StringBuilder();
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        writeSessionMeta(sb, threadId, timestamp, cwd);

        for (SessionMessage msg : messages) {
            if ("separator".equals(msg.role)) continue;
            convertMessageToRolloutItems(msg, sb, timestamp);
        }
        Files.writeString(rolloutFile, sb.toString(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void writeSessionMeta(@NotNull StringBuilder sb, @NotNull String threadId,
                                         @NotNull String timestamp, @Nullable String cwd) {
        JsonObject payload = new JsonObject();
        payload.addProperty("id", threadId);
        payload.addProperty(F_TIMESTAMP, timestamp);
        if (cwd != null) {
            payload.addProperty("cwd", cwd);
        }
        payload.addProperty("originator", "intellij-copilot-plugin");
        payload.addProperty("cli_version", "0.0.0");
        payload.addProperty("source", "vscode");
        payload.addProperty("model_provider", "openai");

        JsonObject meta = new JsonObject();
        meta.addProperty(F_TIMESTAMP, timestamp);
        meta.addProperty(F_TYPE, "session_meta");
        meta.add("payload", payload);
        sb.append(GSON.toJson(meta)).append('\n');
    }

    private static void writeResponseItem(@NotNull StringBuilder sb, @NotNull JsonObject item,
                                          @NotNull String timestamp) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty(F_TIMESTAMP, timestamp);
        envelope.addProperty(F_TYPE, "response_item");
        envelope.add("payload", item);
        sb.append(GSON.toJson(envelope)).append('\n');
    }

    private static void convertMessageToRolloutItems(@NotNull SessionMessage msg,
                                                     @NotNull StringBuilder sb,
                                                     @NotNull String timestamp) {
        if ("user".equals(msg.role)) {
            convertUserMessage(msg, sb, timestamp);
        } else if ("assistant".equals(msg.role)) {
            convertAssistantMessage(msg, sb, timestamp);
        }
    }

    private static void convertUserMessage(@NotNull SessionMessage msg, @NotNull StringBuilder sb,
                                           @NotNull String timestamp) {
        StringBuilder textBuilder = new StringBuilder();
        for (JsonObject part : msg.parts) {
            String type = JsonlUtil.getStr(part, F_TYPE);
            if ("text".equals(type)) {
                String text = JsonlUtil.getStr(part, "text");
                if (text != null) textBuilder.append(text);
            }
        }

        JsonObject item = new JsonObject();
        item.addProperty(F_TYPE, "message");
        item.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject inputText = new JsonObject();
        inputText.addProperty(F_TYPE, "input_text");
        inputText.addProperty("text", textBuilder.toString());
        content.add(inputText);
        item.add(F_CONTENT, content);
        writeResponseItem(sb, item, timestamp);
    }

    private static void convertAssistantMessage(@NotNull SessionMessage msg, @NotNull StringBuilder sb,
                                                @NotNull String timestamp) {
        for (JsonObject part : msg.parts) {
            String type = JsonlUtil.getStr(part, F_TYPE);
            if (type == null) continue;

            switch (type) {
                case "text" -> writeAssistantTextItem(part, sb, timestamp);
                case "reasoning" -> writeReasoningItem(part, sb, timestamp);
                case "tool-invocation" -> writeToolItems(part, sb, timestamp);
                default -> { /* skip unknown part types */ }
            }
        }
    }

    private static void writeAssistantTextItem(@NotNull JsonObject part, @NotNull StringBuilder sb,
                                               @NotNull String timestamp) {
        String text = JsonlUtil.getStr(part, "text");
        if (text == null || text.isEmpty()) return;

        JsonObject item = new JsonObject();
        item.addProperty(F_TYPE, "message");
        item.addProperty("role", "assistant");
        item.addProperty("id", "resp_" + UUID.randomUUID().toString().substring(0, 8));
        JsonArray content = new JsonArray();
        JsonObject outputText = new JsonObject();
        outputText.addProperty(F_TYPE, "output_text");
        outputText.addProperty("text", text);
        content.add(outputText);
        item.add(F_CONTENT, content);
        writeResponseItem(sb, item, timestamp);
    }

    private static void writeReasoningItem(@NotNull JsonObject part, @NotNull StringBuilder sb,
                                           @NotNull String timestamp) {
        String text = JsonlUtil.getStr(part, "text");
        if (text == null || text.isEmpty()) return;

        JsonObject item = new JsonObject();
        item.addProperty(F_TYPE, "reasoning");
        item.addProperty("id", "rs_" + UUID.randomUUID().toString().substring(0, 8));
        JsonArray content = new JsonArray();
        JsonObject reasoningText = new JsonObject();
        reasoningText.addProperty(F_TYPE, "reasoning_text");
        reasoningText.addProperty("text", text);
        content.add(reasoningText);
        item.add(F_CONTENT, content);
        writeResponseItem(sb, item, timestamp);
    }

    private static void writeToolItems(@NotNull JsonObject part, @NotNull StringBuilder sb,
                                       @NotNull String timestamp) {
        JsonObject invocation = part.getAsJsonObject("toolInvocation");
        if (invocation == null) return;

        String state = JsonlUtil.getStr(invocation, "state");
        String callId = JsonlUtil.getStr(invocation, "toolCallId");
        if (callId == null) callId = "call_" + UUID.randomUUID().toString().substring(0, 8);

        String toolName = JsonlUtil.getStr(invocation, "toolName");
        if (toolName == null) toolName = "unknown";

        if ("call".equals(state) || F_RESULT.equals(state)) {
            JsonObject callItem = new JsonObject();
            callItem.addProperty(F_TYPE, "function_call");
            callItem.addProperty("call_id", callId);
            callItem.addProperty("name", toolName);
            callItem.addProperty("id", "fc_" + UUID.randomUUID().toString().substring(0, 8));

            JsonElement args = invocation.get("args");
            callItem.addProperty("arguments", args != null ? GSON.toJson(args) : "{}");
            writeResponseItem(sb, callItem, timestamp);
        }

        if (F_RESULT.equals(state)) {
            String result = JsonlUtil.getStr(invocation, F_RESULT);
            JsonObject outputItem = new JsonObject();
            outputItem.addProperty(F_TYPE, "function_call_output");
            outputItem.addProperty("call_id", callId);
            outputItem.addProperty("output", result != null ? result : "");
            writeResponseItem(sb, outputItem, timestamp);
        }
    }

    private static void insertThread(@NotNull Path dbPath, @NotNull String threadId, @NotNull String rolloutPath) {
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
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
            LOG.info("Inserted Codex thread record: " + threadId);
        } catch (SQLException e) {
            LOG.warn("Failed to insert Codex thread record into " + dbPath, e);
        }
    }
}
