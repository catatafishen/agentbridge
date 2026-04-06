package com.github.catatafishen.ideagentforcopilot.session.importers;

import com.github.catatafishen.ideagentforcopilot.session.v2.SessionMessage;
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
import java.sql.*;
import java.util.*;

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
    public static List<SessionMessage> importLatestThread(@NotNull Path dbPath) {
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
    public static List<SessionMessage> importRolloutFile(@NotNull Path rolloutFile) {
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
    static List<SessionMessage> convertRolloutLines(@NotNull List<String> lines) {
        List<SessionMessage> messages = new ArrayList<>();
        List<JsonObject> pendingAssistantParts = new ArrayList<>();
        Map<String, JsonObject> pendingCalls = new LinkedHashMap<>();
        long now = System.currentTimeMillis();

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
                case "message" -> handleMessage(item, messages, pendingAssistantParts, now);
                case "function_call" -> handleFunctionCall(item, pendingAssistantParts, pendingCalls);
                case "function_call_output" -> handleFunctionCallOutput(item, pendingAssistantParts, pendingCalls);
                case "reasoning" -> handleReasoning(item, pendingAssistantParts);
                default -> LOG.debug("Skipping unknown Codex rollout item type: " + type);
            }
        }

        flushAssistantParts(pendingAssistantParts, messages, now);
        return messages;
    }

    private static void handleMessage(
            @NotNull JsonObject item,
            @NotNull List<SessionMessage> messages,
            @NotNull List<JsonObject> pendingAssistantParts,
            long now
    ) {
        String role = JsonlUtil.getStr(item, "role");
        if ("user".equals(role)) {
            flushAssistantParts(pendingAssistantParts, messages, now);

            String text = extractContentText(item);
            JsonObject textPart = new JsonObject();
            textPart.addProperty("type", "text");
            textPart.addProperty("text", text != null ? text : "");
            messages.add(new SessionMessage(
                    UUID.randomUUID().toString(), "user", List.of(textPart), now, null, null));
        } else if ("assistant".equals(role)) {
            String text = extractContentText(item);
            if (text != null && !text.isEmpty()) {
                JsonObject textPart = new JsonObject();
                textPart.addProperty("type", "text");
                textPart.addProperty("text", text);
                pendingAssistantParts.add(textPart);
            }
        }
    }

    private static void handleFunctionCall(
            @NotNull JsonObject item,
            @NotNull List<JsonObject> pendingAssistantParts,
            @NotNull Map<String, JsonObject> pendingCalls
    ) {
        String callId = JsonlUtil.getStr(item, "call_id");
        String name = JsonlUtil.getStr(item, "name");
        String arguments = JsonlUtil.getStr(item, "arguments");

        JsonObject toolPart = new JsonObject();
        toolPart.addProperty("type", "tool-invocation");

        JsonObject invocation = new JsonObject();
        invocation.addProperty("state", "call");
        invocation.addProperty("toolCallId", callId != null ? callId : UUID.randomUUID().toString());
        invocation.addProperty("toolName", name != null ? name : "unknown");
        if (arguments != null) {
            try {
                invocation.add("args", GSON.fromJson(arguments, JsonObject.class));
            } catch (Exception e) {
                JsonObject argsObj = new JsonObject();
                argsObj.addProperty("raw", arguments);
                invocation.add("args", argsObj);
            }
        }
        toolPart.add("toolInvocation", invocation);
        pendingAssistantParts.add(toolPart);

        if (callId != null) {
            pendingCalls.put(callId, toolPart);
        }
    }

    private static void handleFunctionCallOutput(
            @NotNull JsonObject item,
            @NotNull List<JsonObject> pendingAssistantParts,
            @NotNull Map<String, JsonObject> pendingCalls
    ) {
        String callId = JsonlUtil.getStr(item, "call_id");
        String output = JsonlUtil.getStr(item, "output");

        if (callId != null && pendingCalls.containsKey(callId)) {
            JsonObject callPart = pendingCalls.remove(callId);
            JsonObject invocation = callPart.getAsJsonObject("toolInvocation");
            if (invocation != null) {
                invocation.addProperty("state", "result");
                invocation.addProperty("result", output != null ? output : "");
                return;
            }
        }

        JsonObject toolPart = new JsonObject();
        toolPart.addProperty("type", "tool-invocation");

        JsonObject invocation = new JsonObject();
        invocation.addProperty("state", "result");
        invocation.addProperty("toolCallId", callId != null ? callId : UUID.randomUUID().toString());
        invocation.addProperty("toolName", "unknown");
        invocation.addProperty("result", output != null ? output : "");
        toolPart.add("toolInvocation", invocation);
        pendingAssistantParts.add(toolPart);
    }

    private static void handleReasoning(
            @NotNull JsonObject item,
            @NotNull List<JsonObject> pendingAssistantParts
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
            JsonObject part = new JsonObject();
            part.addProperty("type", "reasoning");
            part.addProperty("text", sb.toString());
            pendingAssistantParts.add(part);
        }
    }

    private static void flushAssistantParts(
            @NotNull List<JsonObject> parts,
            @NotNull List<SessionMessage> messages,
            long now
    ) {
        if (parts.isEmpty()) return;
        messages.add(new SessionMessage(
                UUID.randomUUID().toString(), "assistant", List.copyOf(parts), now, "Codex", null));
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
