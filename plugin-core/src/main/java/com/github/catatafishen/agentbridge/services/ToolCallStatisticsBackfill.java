package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.session.exporters.ExportUtils;
import com.github.catatafishen.agentbridge.session.v2.SessionFileRotation;
import com.github.catatafishen.agentbridge.session.v2.SessionStoreV2;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Backfills tool call statistics from session JSONL files into the SQLite database.
 *
 * <p>Session files contain {@code "type":"tool"} entries with tool name, arguments,
 * result, status, timestamp, and agent. This class scans those entries and inserts
 * them as {@link ToolCallRecord}s, recovering statistics that were lost when
 * {@code ToolCallStatisticsService} failed to initialize or record calls.</p>
 *
 * <p>Backfill is idempotent: it skips entries whose timestamp already exists in the
 * database (uses the timestamp + tool name for deduplication).</p>
 */
public final class ToolCallStatisticsBackfill {

    private static final Logger LOG = Logger.getInstance(ToolCallStatisticsBackfill.class);
    private static final String MCP_HANDLED = "mcpHandled";

    private ToolCallStatisticsBackfill() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Result of a backfill operation, reporting how many records were inserted
     * and how many were skipped (already present).
     */
    public record BackfillResult(int inserted, int skipped, int errors) {
        @NotNull
        @Override
        public String toString() {
            return "BackfillResult{inserted=" + inserted + ", skipped=" + skipped
                + ", errors=" + errors + "}";
        }
    }

    /**
     * Scans all session JSONL files and backfills tool call records into the
     * given service. Skips entries that already exist (by timestamp + tool name).
     *
     * @param service  the statistics service to insert records into
     * @param basePath the project base path (for locating session files)
     * @return the backfill result with counts
     */
    public static BackfillResult backfill(@NotNull ToolCallStatisticsService service,
                                          @NotNull String basePath) {
        List<SessionStoreV2.SessionRecord> sessions = SessionStoreV2.listSessionsFromJsonlIndex(basePath);
        if (sessions.isEmpty()) {
            LOG.info("Backfill: no sessions found");
            return new BackfillResult(0, 0, 0);
        }

        Map<String, String> sessionToClient = buildSessionClientMap(sessions);
        File sessionsDir = new File(basePath, ExportUtils.LEGACY_SESSIONS_DIR);
        int inserted = 0;
        int skipped = 0;
        int errors = 0;

        for (SessionStoreV2.SessionRecord session : sessions) {
            List<Path> jsonlFiles = SessionFileRotation.listAllFiles(sessionsDir, session.id());
            if (jsonlFiles.isEmpty()) continue;

            String clientId = sessionToClient.get(session.id());
            BackfillResult sessionResult = backfillSession(service, jsonlFiles, clientId);
            inserted += sessionResult.inserted();
            skipped += sessionResult.skipped();
            errors += sessionResult.errors();
        }

        LOG.info("Backfill complete: " + inserted + " inserted, "
            + skipped + " skipped, " + errors + " errors");
        return new BackfillResult(inserted, skipped, errors);
    }

    private static Map<String, String> buildSessionClientMap(
        @NotNull List<SessionStoreV2.SessionRecord> sessions) {
        Map<String, String> map = new HashMap<>();
        for (SessionStoreV2.SessionRecord session : sessions) {
            map.put(session.id(), AgentIdMapper.toAgentId(session.agent()));
        }
        return map;
    }

    private static BackfillResult backfillSession(@NotNull ToolCallStatisticsService service,
                                                  @NotNull List<Path> jsonlFiles,
                                                  @NotNull String clientId) {
        int inserted = 0;
        int skipped = 0;
        int errors = 0;

        for (Path jsonlPath : jsonlFiles) {
            try (BufferedReader reader = Files.newBufferedReader(jsonlPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.contains("\"type\":\"tool\"")) continue;
                    EntryResult entryResult = processToolEntry(service, line, clientId);
                    switch (entryResult) {
                        case INSERTED -> inserted++;
                        case SKIPPED -> skipped++;
                        case ERROR -> errors++;
                        case IGNORED -> { /* non-tool / unparseable lines are skipped silently */ }
                    }
                }
            } catch (IOException e) {
                LOG.warn("Backfill: failed to read " + jsonlPath.getFileName(), e);
            }
        }

        return new BackfillResult(inserted, skipped, errors);
    }

    private enum EntryResult {INSERTED, SKIPPED, ERROR, IGNORED}

    private static EntryResult processToolEntry(@NotNull ToolCallStatisticsService service,
                                                @NotNull String line,
                                                @NotNull String clientId) {
        ToolCallRecord toolRecord;
        try {
            toolRecord = parseToolEntry(line, clientId);
        } catch (Exception e) {
            LOG.debug("Backfill: malformed tool entry: " + e.getMessage());
            return EntryResult.ERROR;
        }

        if (toolRecord == null) return EntryResult.IGNORED;

        if (service.hasRecordAt(toolRecord.timestamp(), toolRecord.toolName())) {
            return EntryResult.SKIPPED;
        }
        service.recordCall(toolRecord);
        return EntryResult.INSERTED;
    }

    @Nullable
    private static ToolCallRecord parseToolEntry(@NotNull String line,
                                                 @NotNull String clientId) {
        JsonObject obj = JsonParser.parseString(line).getAsJsonObject();

        String type = getStr(obj, "type");
        if (!"tool".equals(type)) return null;

        // The display "title" is whatever the agent supplied for the chip label
        // (e.g. "Tail full log", "Run summary"). Each call gets a unique non-deterministic
        // string, which makes aggregation worthless. Use the canonical MCP tool id from
        // the "pluginTool" field instead. Skip entries that aren't MCP tool calls.
        String pluginTool = getStr(obj, "pluginTool");
        String displayName = getStr(obj, "title");
        if (pluginTool.isEmpty()) {
            // Legacy entries with mcpHandled=true but no explicit pluginTool: title was the bare id.
            boolean mcpHandled = obj.has(MCP_HANDLED) && !obj.get(MCP_HANDLED).isJsonNull()
                && obj.get(MCP_HANDLED).getAsBoolean();
            if (mcpHandled && !displayName.isEmpty()) {
                pluginTool = displayName;
            } else {
                return null;
            }
        }
        String toolName = stripMcpPrefix(pluginTool);
        if (toolName.isEmpty()) return null;

        String timestampStr = getStr(obj, "timestamp");
        if (timestampStr.isEmpty()) return null;

        Instant timestamp = Instant.parse(timestampStr);

        String status = getStr(obj, "status");
        boolean success = "completed".equals(status);

        String arguments = getStr(obj, "arguments");
        String result = getStr(obj, "result");
        long inputSize = arguments.getBytes(StandardCharsets.UTF_8).length;
        long outputSize = result.getBytes(StandardCharsets.UTF_8).length;

        String kind = getStr(obj, "kind");
        String category = kind.isEmpty() ? null : kind;

        String errorMessage = null;
        if (!success && !result.isEmpty()) {
            errorMessage = result.length() > 500 ? result.substring(0, 500) : result;
        }

        // Preserve the original chip title in display_name for debugging/UI grouping
        // — but only when it differs from the canonical id (avoids redundant data).
        String displayForDb = displayName.isEmpty() || displayName.equals(toolName) ? null : displayName;

        return new ToolCallRecord(
            toolName, category, inputSize, outputSize,
            0, // durationMs not available in JSONL entries
            success, errorMessage, clientId, timestamp, displayForDb);
    }

    /**
     * Strip the {@code agentbridge-} / {@code agentbridge_} / {@code @agentbridge/}
     * prefix that some agents add to MCP tool ids before invocation. The DB stores
     * the bare canonical id (e.g. {@code read_file}) to match the live recording path
     * which receives the bare name from the JSON-RPC {@code tools/call} request.
     */
    @NotNull
    static String stripMcpPrefix(@NotNull String pluginTool) {
        String s = pluginTool.trim();
        if (s.startsWith("@agentbridge/")) return s.substring("@agentbridge/".length());
        if (s.startsWith("agentbridge-")) return s.substring("agentbridge-".length());
        if (s.startsWith("agentbridge_")) return s.substring("agentbridge_".length());
        return s;
    }

    @NotNull
    private static String getStr(@NotNull JsonObject obj, @NotNull String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return "";
    }
}
