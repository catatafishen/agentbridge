package com.github.catatafishen.agentbridge.services;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Project-level service that records every MCP tool call in a SQLite database
 * ({@code {project}/.agentbridge/tool-stats.db}) and provides query methods for
 * the Tool Statistics UI panel.
 *
 * <p>Subscribes to {@link PsiBridgeService#TOOL_CALL_TOPIC} on the project
 * message bus. Records are appended on the calling thread (MCP handler threads)
 * and queried from the EDT for UI rendering.</p>
 */
@Service(Service.Level.PROJECT)
public final class ToolCallStatisticsService implements Disposable {

    private static final Logger LOG = Logger.getInstance(ToolCallStatisticsService.class);
    private static final String DB_FILENAME = "tool-stats.db";

    private final Project project;
    private Connection connection;
    private Runnable disconnectHandle;

    private volatile boolean initAttempted;
    private volatile boolean warnedConnectionNull;

    public ToolCallStatisticsService(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Test-only constructor that bypasses the Project requirement.
     * Use {@link #initializeWithConnection(Connection)} to set up the database.
     */
    ToolCallStatisticsService() {
        this.project = null;
    }

    /**
     * Initialize the SQLite database and subscribe to tool call events.
     * Called lazily on first access via {@code getInstance()}.
     *
     * @throws RuntimeException if initialization fails (JDBC driver not found,
     *                          database cannot be created, or subscription fails).
     *                          The caller sets {@code initAttempted = true} regardless of
     *                          success/failure to prevent retry loops — the service stays
     *                          inert (connection == null) until the IDE is restarted.
     */
    public void initialize() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SQLite JDBC driver not found on classpath", e);
        }
        String basePath = project.getBasePath();
        if (basePath == null) {
            LOG.warn("ToolCallStatisticsService: project has no base path, skipping initialization");
            return;
        }
        try {
            Path dbDir = Path.of(basePath, ".agentbridge");
            Files.createDirectories(dbDir);
            Path dbPath = dbDir.resolve(DB_FILENAME);
            initializeWithConnection(DriverManager.getConnection("jdbc:sqlite:" + dbPath));
            subscribeToToolCallEvents();
            LOG.info("ToolCallStatisticsService initialized at " + dbPath);
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Failed to initialize ToolCallStatisticsService", e);
        }
    }

    /**
     * Initialize with an externally-provided connection. Package-private for testing.
     */
    void initializeWithConnection(@NotNull Connection conn) throws SQLException {
        this.connection = conn;
        connection.setAutoCommit(true);
        createSchema();
    }

    private void createSchema() throws SQLException {
        try (var stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS tool_calls (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    tool_name     TEXT    NOT NULL,
                    category      TEXT,
                    input_size    INTEGER NOT NULL,
                    output_size   INTEGER NOT NULL,
                    duration_ms   INTEGER NOT NULL,
                    success       INTEGER NOT NULL,
                    error_message TEXT,
                    client_id     TEXT    NOT NULL,
                    timestamp     TEXT    NOT NULL
                )
                """);
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_tool_calls_timestamp ON tool_calls(timestamp)");
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_tool_calls_tool_name ON tool_calls(tool_name)");
            // Migration: add error_message column to existing databases
            migrateAddErrorMessageColumn(stmt);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS turn_stats (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id       TEXT    NOT NULL,
                    agent_id         TEXT    NOT NULL,
                    date             TEXT    NOT NULL,
                    input_tokens     INTEGER NOT NULL DEFAULT 0,
                    output_tokens    INTEGER NOT NULL DEFAULT 0,
                    tool_calls       INTEGER NOT NULL DEFAULT 0,
                    duration_ms      INTEGER NOT NULL DEFAULT 0,
                    lines_added      INTEGER NOT NULL DEFAULT 0,
                    lines_removed    INTEGER NOT NULL DEFAULT 0,
                    premium_requests REAL    NOT NULL DEFAULT 1.0,
                    timestamp        TEXT    NOT NULL
                )
                """);
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_turn_stats_date ON turn_stats(date)");
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_turn_stats_session ON turn_stats(session_id)");
        }
    }

    private void migrateAddErrorMessageColumn(java.sql.Statement stmt) {
        try {
            stmt.execute("ALTER TABLE tool_calls ADD COLUMN error_message TEXT");
            LOG.info("Migrated tool_calls table: added error_message column");
        } catch (SQLException e) {
            if (e.getMessage() == null || !e.getMessage().contains("duplicate column")) {
                LOG.warn("Unexpected error migrating tool_calls schema (error_message column)", e);
            }
            // else: duplicate column — expected for databases that have already been migrated
        }
    }

    private void subscribeToToolCallEvents() {
        disconnectHandle = PlatformApiCompat.subscribeToolCallListener(project,
            (toolName, durationMs, success, inputSizeBytes, outputSizeBytes, clientId, category, errorMessage) ->
                recordCall(new ToolCallRecord(
                    toolName, category, inputSizeBytes, outputSizeBytes,
                    durationMs, success, errorMessage, clientId, Instant.now())));
    }

    public synchronized void recordCall(@NotNull ToolCallRecord callRecord) {
        if (connection == null) {
            if (!warnedConnectionNull) {
                warnedConnectionNull = true;
                LOG.warn("ToolCallStatisticsService: dropping tool call '" + callRecord.toolName()
                    + "' — database connection is not available (initialization may have failed). "
                    + "Subsequent dropped calls will not be logged.");
            }
            return;
        }
        try {
            insertRecord(callRecord);
        } catch (SQLException e) {
            if (isDbMoved(e) && tryReconnect()) {
                try {
                    insertRecord(callRecord);
                } catch (SQLException retryEx) {
                    LOG.warn("Failed to record tool call after reconnect", retryEx);
                }
            } else {
                LOG.warn("Failed to record tool call", e);
            }
        }
    }

    private void insertRecord(@NotNull ToolCallRecord callRecord) throws SQLException {
        String sql = """
            INSERT INTO tool_calls (tool_name, category, input_size, output_size, duration_ms, success, error_message, client_id, timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, callRecord.toolName());
            stmt.setString(2, callRecord.category());
            stmt.setLong(3, callRecord.inputSizeBytes());
            stmt.setLong(4, callRecord.outputSizeBytes());
            stmt.setLong(5, callRecord.durationMs());
            stmt.setInt(6, callRecord.success() ? 1 : 0);
            stmt.setString(7, callRecord.errorMessage());
            stmt.setString(8, callRecord.clientId());
            stmt.setString(9, callRecord.timestamp().toString());
            stmt.executeUpdate();
        }
    }

    /**
     * Checks whether a tool call record already exists at the given timestamp
     * and tool name. Used by {@link ToolCallStatisticsBackfill} for deduplication.
     */
    public synchronized boolean hasRecordAt(@NotNull Instant timestamp, @NotNull String toolName) {
        if (connection == null) return false;
        String sql = "SELECT COUNT(*) FROM tool_calls WHERE timestamp = ? AND tool_name = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, timestamp.toString());
            stmt.setString(2, toolName);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            LOG.warn("Failed to check for existing record", e);
            return false;
        }
    }

    /**
     * Returns the total number of tool call records in the database.
     * Used to determine whether a backfill is needed.
     */
    public synchronized int getRecordCount() {
        if (connection == null) return 0;
        try (PreparedStatement stmt = connection.prepareStatement("SELECT COUNT(*) FROM tool_calls");
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            LOG.warn("Failed to count records", e);
            return 0;
        }
    }

    static boolean isDbMoved(@NotNull SQLException e) {
        return e.getMessage() != null && e.getMessage().contains("SQLITE_READONLY_DBMOVED");
    }

    private boolean tryReconnect() {
        if (project == null) return false;
        closeConnectionQuietly();
        try {
            String basePath = project.getBasePath();
            if (basePath == null) return false;
            Path dbPath = Path.of(basePath, ".agentbridge", DB_FILENAME);
            initializeWithConnection(DriverManager.getConnection("jdbc:sqlite:" + dbPath));
            LOG.info("Reconnected to ToolCallStatisticsService database after file move");
            return true;
        } catch (SQLException e) {
            LOG.warn("Failed to reconnect to ToolCallStatisticsService database", e);
            connection = null;
            return false;
        }
    }

    private void closeConnectionQuietly() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Best-effort close of stale connection
        } finally {
            connection = null;
        }
    }

    /**
     * Aggregated statistics for a single tool, used by the UI table.
     * {@code clientId} is absent because aggregation always collapses across clients — the
     * client filter is applied in the WHERE clause, not the GROUP BY.
     */
    public record ToolAggregate(
        @NotNull String toolName,
        @Nullable String category,
        long callCount,
        long avgDurationMs,
        long totalInputBytes,
        long totalOutputBytes,
        long avgTotalBytes,
        long errorCount
    ) {
    }

    /**
     * Appends optional WHERE-clause filters and returns the bound parameter values.
     */
    static List<String> appendFilters(StringBuilder sql, @Nullable String since, @Nullable String clientId) {
        List<String> params = new ArrayList<>();
        if (since != null) {
            sql.append(" AND timestamp >= ?");
            params.add(since);
        }
        if (clientId != null) {
            sql.append(" AND client_id = ?");
            params.add(clientId);
        }
        return params;
    }

    private static void bindParams(PreparedStatement stmt, List<String> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            stmt.setString(i + 1, params.get(i));
        }
    }

    public synchronized List<ToolAggregate> queryAggregates(@Nullable String since, @Nullable String clientId) {
        if (connection == null) return List.of();

        StringBuilder sql = new StringBuilder("""
            SELECT tool_name, category,
                   COUNT(*) AS call_count,
                   ROUND(AVG(duration_ms)) AS avg_duration,
                   SUM(input_size) AS total_input,
                   SUM(output_size) AS total_output,
                   ROUND(AVG(input_size + output_size)) AS avg_total,
                   SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) AS error_count
            FROM tool_calls
            WHERE 1=1
            """);
        List<String> params = appendFilters(sql, since, clientId);
        sql.append(" GROUP BY tool_name, category ORDER BY call_count DESC");

        List<ToolAggregate> results = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
            bindParams(stmt, params);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new ToolAggregate(
                        rs.getString("tool_name"),
                        rs.getString("category"),
                        rs.getLong("call_count"),
                        rs.getLong("avg_duration"),
                        rs.getLong("total_input"),
                        rs.getLong("total_output"),
                        rs.getLong("avg_total"),
                        rs.getLong("error_count")
                    ));
                }
            }
        } catch (SQLException e) {
            LOG.warn("Failed to query tool call aggregates", e);
        }
        return results;
    }

    /**
     * Returns distinct client IDs that have made tool calls, for the filter combo box.
     */
    public synchronized List<String> getDistinctClients() {
        if (connection == null) return List.of();
        List<String> clients = new ArrayList<>();
        try (ResultSet rs = connection.createStatement()
            .executeQuery("SELECT DISTINCT client_id FROM tool_calls ORDER BY client_id")) {
            while (rs.next()) {
                clients.add(rs.getString("client_id"));
            }
        } catch (SQLException e) {
            LOG.warn("Failed to query distinct clients", e);
        }
        return clients;
    }

    public synchronized Map<String, Long> querySummary(@Nullable String since, @Nullable String clientId) {
        if (connection == null) return Map.of();
        StringBuilder sql = new StringBuilder("""
            SELECT COUNT(*) AS total_calls,
                   COALESCE(SUM(duration_ms), 0) AS total_duration,
                   COALESCE(SUM(input_size), 0) AS total_input,
                   COALESCE(SUM(output_size), 0) AS total_output,
                   SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) AS total_errors
            FROM tool_calls
            WHERE 1=1
            """);
        List<String> params = appendFilters(sql, since, clientId);

        Map<String, Long> summary = new LinkedHashMap<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
            bindParams(stmt, params);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    summary.put("totalCalls", rs.getLong("total_calls"));
                    summary.put("totalDurationMs", rs.getLong("total_duration"));
                    summary.put("totalInputBytes", rs.getLong("total_input"));
                    summary.put("totalOutputBytes", rs.getLong("total_output"));
                    summary.put("totalErrors", rs.getLong("total_errors"));
                }
            }
        } catch (SQLException e) {
            LOG.warn("Failed to query tool call summary", e);
        }
        return summary;
    }

    /**
     * A single failed tool call with its error message, for display in the errors tab.
     */
    public record ToolError(
        @NotNull String toolName,
        @Nullable String category,
        @NotNull String clientId,
        long durationMs,
        @NotNull String errorMessage,
        @NotNull String timestamp
    ) {
    }

    /**
     * Returns recent failed tool calls with their error messages, ordered by most recent first.
     */
    public synchronized List<ToolError> queryRecentErrors(@Nullable String since, @Nullable String clientId, int limit) {
        if (connection == null) return List.of();
        StringBuilder sql = new StringBuilder("""
            SELECT tool_name, category, client_id, duration_ms, error_message, timestamp
            FROM tool_calls
            WHERE success = 0 AND error_message IS NOT NULL
            """);
        List<String> params = appendFilters(sql, since, clientId);
        sql.append(" ORDER BY timestamp DESC LIMIT ?");

        List<ToolError> results = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
            bindParams(stmt, params);
            stmt.setInt(params.size() + 1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new ToolError(
                        rs.getString("tool_name"),
                        rs.getString("category"),
                        rs.getString("client_id"),
                        rs.getLong("duration_ms"),
                        rs.getString("error_message"),
                        rs.getString("timestamp")
                    ));
                }
            }
        } catch (SQLException e) {
            LOG.warn("Failed to query recent errors", e);
        }
        return results;
    }

    /**
     * Records per-turn statistics for the usage charts.
     *
     * @param record the turn statistics to store
     */
    public synchronized void recordTurnStats(@NotNull TurnStatsRecord record) {
        if (connection == null) return;
        String sql = """
            INSERT INTO turn_stats (session_id, agent_id, date, input_tokens, output_tokens,
                                    tool_calls, duration_ms, lines_added, lines_removed,
                                    premium_requests, timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, record.sessionId());
            stmt.setString(2, record.agentId());
            stmt.setString(3, record.date());
            stmt.setLong(4, record.inputTokens());
            stmt.setLong(5, record.outputTokens());
            stmt.setInt(6, record.toolCalls());
            stmt.setLong(7, record.durationMs());
            stmt.setInt(8, record.linesAdded());
            stmt.setInt(9, record.linesRemoved());
            stmt.setDouble(10, record.premiumRequests());
            stmt.setString(11, record.timestamp());
            stmt.executeUpdate();
        } catch (SQLException e) {
            if (isDbMoved(e) && tryReconnect()) {
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setString(1, record.sessionId());
                    stmt.setString(2, record.agentId());
                    stmt.setString(3, record.date());
                    stmt.setLong(4, record.inputTokens());
                    stmt.setLong(5, record.outputTokens());
                    stmt.setInt(6, record.toolCalls());
                    stmt.setLong(7, record.durationMs());
                    stmt.setInt(8, record.linesAdded());
                    stmt.setInt(9, record.linesRemoved());
                    stmt.setDouble(10, record.premiumRequests());
                    stmt.setString(11, record.timestamp());
                    stmt.executeUpdate();
                } catch (SQLException retryEx) {
                    LOG.warn("Failed to record turn stats after reconnect", retryEx);
                }
            } else {
                LOG.warn("Failed to record turn stats", e);
            }
        }
    }

    /**
     * Checks whether a turn stats record already exists at the given timestamp.
     * Used by {@link TurnStatisticsBackfill} for deduplication.
     */
    public synchronized boolean hasTurnStatsAt(@NotNull String timestamp) {
        if (connection == null) return false;
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT 1 FROM turn_stats WHERE timestamp = ? LIMIT 1")) {
            stmt.setString(1, timestamp);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.warn("Failed to check for existing turn stats", e);
            return false;
        }
    }

    /**
     * Returns the total number of turn stats records in the database.
     * Used to determine whether a backfill is needed.
     */
    public synchronized int getTurnStatsCount() {
        if (connection == null) return 0;
        try (PreparedStatement stmt = connection.prepareStatement("SELECT COUNT(*) FROM turn_stats");
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            LOG.warn("Failed to count turn stats", e);
            return 0;
        }
    }

    /**
     * Queries daily per-agent turn statistics aggregated by date and agent_id.
     * Returns one row per (date, agentId) bucket within the specified date range.
     *
     * @param startDate inclusive start date (YYYY-MM-DD)
     * @param endDate   inclusive end date (YYYY-MM-DD)
     * @return aggregated daily stats sorted by date then agent_id
     */
    public synchronized List<DailyTurnAggregate> queryDailyTurnStats(
        @NotNull String startDate, @NotNull String endDate) {
        if (connection == null) return List.of();
        String sql = """
            SELECT date, agent_id,
                   COUNT(*)          AS turns,
                   SUM(input_tokens)     AS input_tokens,
                   SUM(output_tokens)    AS output_tokens,
                   SUM(tool_calls)       AS tool_calls,
                   SUM(duration_ms)      AS duration_ms,
                   SUM(lines_added)      AS lines_added,
                   SUM(lines_removed)    AS lines_removed,
                   SUM(premium_requests) AS premium_requests
            FROM turn_stats
            WHERE date BETWEEN ? AND ?
            GROUP BY date, agent_id
            ORDER BY date, agent_id
            """;
        List<DailyTurnAggregate> results = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, startDate);
            stmt.setString(2, endDate);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new DailyTurnAggregate(
                        LocalDate.parse(rs.getString("date")),
                        rs.getString("agent_id"),
                        rs.getInt("turns"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        rs.getInt("tool_calls"),
                        rs.getLong("duration_ms"),
                        rs.getInt("lines_added"),
                        rs.getInt("lines_removed"),
                        rs.getDouble("premium_requests")
                    ));
                }
            }
        } catch (SQLException e) {
            LOG.warn("Failed to query daily turn stats", e);
        }
        return results;
    }

    /**
     * Returns all distinct agent IDs that have turn statistics, along with
     * their display names from the sessions index.
     */
    public synchronized Set<String> getDistinctTurnAgents() {
        if (connection == null) return Set.of();
        Set<String> agents = new LinkedHashSet<>();
        try (ResultSet rs = connection.createStatement()
            .executeQuery("SELECT DISTINCT agent_id FROM turn_stats ORDER BY agent_id")) {
            while (rs.next()) {
                agents.add(rs.getString("agent_id"));
            }
        } catch (SQLException e) {
            LOG.warn("Failed to query distinct turn agents", e);
        }
        return agents;
    }

    /**
     * Returns the earliest date in the turn_stats table, or null if empty.
     */
    @Nullable
    public synchronized LocalDate getEarliestTurnDate() {
        if (connection == null) return null;
        try (ResultSet rs = connection.createStatement()
            .executeQuery("SELECT MIN(date) AS min_date FROM turn_stats")) {
            if (rs.next()) {
                String minDate = rs.getString("min_date");
                if (minDate != null) return LocalDate.parse(minDate);
            }
        } catch (SQLException e) {
            LOG.warn("Failed to query earliest turn date", e);
        }
        return null;
    }

    /**
     * Aggregated daily turn statistics for a single (date, agent) bucket.
     */
    public record DailyTurnAggregate(
        @NotNull LocalDate date,
        @NotNull String agentId,
        int turns,
        long inputTokens,
        long outputTokens,
        int toolCalls,
        long durationMs,
        int linesAdded,
        int linesRemoved,
        double premiumRequests
    ) {
    }

    /**
     * A single turn stats record for insertion.
     */
    public record TurnStatsRecord(
        @NotNull String sessionId,
        @NotNull String agentId,
        @NotNull String date,
        long inputTokens,
        long outputTokens,
        int toolCalls,
        long durationMs,
        int linesAdded,
        int linesRemoved,
        double premiumRequests,
        @NotNull String timestamp
    ) {
    }

    /**
     * Threshold below which the database is considered empty enough to warrant backfill.
     * If the DB has fewer records than this, session JSONL files are scanned.
     */
    private static final int BACKFILL_THRESHOLD = 10;

    private static void triggerBackfillIfNeeded(@NotNull ToolCallStatisticsService service,
                                                @NotNull Project project) {
        String basePath = project.getBasePath();
        if (basePath == null) return;

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            // Tool calls backfill
            if (service.getRecordCount() < BACKFILL_THRESHOLD) {
                try {
                    ToolCallStatisticsBackfill.BackfillResult result =
                        ToolCallStatisticsBackfill.backfill(service, basePath);
                    if (result.inserted() > 0) {
                        LOG.info("Tool statistics backfill: " + result);
                    }
                } catch (Exception e) {
                    LOG.warn("Tool statistics backfill failed", e);
                }
            }

            // Turn stats backfill
            if (service.getTurnStatsCount() < BACKFILL_THRESHOLD) {
                try {
                    TurnStatisticsBackfill.BackfillResult result =
                        TurnStatisticsBackfill.backfill(service, basePath);
                    if (result.inserted() > 0) {
                        LOG.info("Turn statistics backfill: " + result);
                    }
                } catch (Exception e) {
                    LOG.warn("Turn statistics backfill failed", e);
                }
            }
        });
    }

    public static ToolCallStatisticsService getInstance(@NotNull Project project) {
        ToolCallStatisticsService service = PlatformApiCompat.getService(project, ToolCallStatisticsService.class);
        if (!service.initAttempted) {
            synchronized (service) {
                if (!service.initAttempted) {
                    service.initAttempted = true;
                    try {
                        service.initialize();
                        triggerBackfillIfNeeded(service, project);
                    } catch (RuntimeException e) {
                        LOG.error("ToolCallStatisticsService initialization failed — "
                            + "tool call recording will be disabled until restart", e);
                    }
                }
            }
        }
        return service;
    }

    @Override
    public void dispose() {
        if (disconnectHandle != null) {
            disconnectHandle.run();
        }
        closeConnectionQuietly();
    }
}
