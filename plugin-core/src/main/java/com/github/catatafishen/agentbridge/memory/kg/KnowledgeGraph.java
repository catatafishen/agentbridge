package com.github.catatafishen.agentbridge.memory.kg;

import com.github.catatafishen.agentbridge.memory.wal.WriteAheadLog;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
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
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed knowledge graph storing subject-predicate-object triples
 * with temporal validity.
 *
 * <p><b>Attribution:</b> schema and query patterns adapted from
 * <a href="https://github.com/milla-jovovich/mempalace">MemPalace</a>'s
 * knowledge_graph.py (MIT License).
 */
public final class KnowledgeGraph implements Disposable {

    private static final Logger LOG = Logger.getInstance(KnowledgeGraph.class);

    private final Path dbPath;
    private final WriteAheadLog wal;
    private Connection connection;

    public KnowledgeGraph(@NotNull Path dbPath, @NotNull WriteAheadLog wal) {
        this.dbPath = dbPath;
        this.wal = wal;
    }

    /**
     * Initialize the database: create tables and indexes if they don't exist.
     */
    public void initialize() throws IOException {
        try {
            // Explicitly load the SQLite JDBC driver so it registers with DriverManager.
            // In IntelliJ's plugin classloader environment, DriverManager.getConnection()
            // searches the system/context classloader which doesn't see plugin dependencies.
            Class.forName("org.sqlite.JDBC");
            Files.createDirectories(dbPath.getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            connection.setAutoCommit(true);
            createSchema();
            LOG.info("KnowledgeGraph initialized at " + dbPath);
        } catch (SQLException | ClassNotFoundException e) {
            throw new IOException("Failed to initialize KnowledgeGraph", e);
        }
    }

    /**
     * Add a triple to the knowledge graph.
     * Skips insertion if an identical valid triple (same subject, predicate, object)
     * already exists, preventing cross-run duplication.
     *
     * @return the auto-generated triple ID, or -1 if skipped as duplicate
     */
    public long addTriple(@NotNull KgTriple triple) throws IOException {
        if (existsValidTriple(triple.subject(), triple.predicate(), triple.object())) {
            return -1;
        }

        String sql = """
            INSERT INTO triples (subject, predicate, object, valid_from, valid_until, source_drawer, created_at, evidence)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, triple.subject());
            stmt.setString(2, triple.predicate());
            stmt.setString(3, triple.object());
            setNullableInstant(stmt, 4, triple.validFrom());
            setNullableInstant(stmt, 5, triple.validUntil());
            setNullableString(stmt, 6, triple.sourceDrawer());
            stmt.setString(7, triple.createdAt().toString());
            stmt.setString(8, triple.evidence());
            stmt.executeUpdate();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                long id = rs.getLong(1);

                JsonObject walPayload = new JsonObject();
                walPayload.addProperty("subject", triple.subject());
                walPayload.addProperty("predicate", triple.predicate());
                walPayload.addProperty("object", triple.object());
                wal.log("kg_add", String.valueOf(id), walPayload);

                return id;
            }
        } catch (SQLException e) {
            throw new IOException("Failed to add triple", e);
        }
    }

    /**
     * Check if an identical valid triple already exists (same subject, predicate, object,
     * and not invalidated). Used to prevent cross-run duplication when re-mining.
     */
    private boolean existsValidTriple(@NotNull String subject, @NotNull String predicate,
                                      @NotNull String object) throws IOException {
        String sql = """
            SELECT COUNT(*) FROM triples
            WHERE subject = ? AND predicate = ? AND object = ? AND valid_until IS NULL
            """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, subject);
            stmt.setString(2, predicate);
            stmt.setString(3, object);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            throw new IOException("Failed to check for existing triple", e);
        }
    }

    /**
     * Query triples matching the given filters. All parameters are optional.
     * Only returns currently valid triples (valid_until is NULL or in the future).
     */
    public @NotNull List<KgTriple> query(@Nullable String subject,
                                         @Nullable String predicate,
                                         @Nullable String object,
                                         int limit) throws IOException {
        StringBuilder sql = new StringBuilder(
            "SELECT id, subject, predicate, object, valid_from, valid_until, source_drawer, created_at, evidence FROM triples WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (subject != null && !subject.isEmpty()) {
            sql.append(" AND subject = ?");
            params.add(subject);
        }
        if (predicate != null && !predicate.isEmpty()) {
            sql.append(" AND predicate = ?");
            params.add(predicate);
        }
        if (object != null && !object.isEmpty()) {
            sql.append(" AND object LIKE ?");
            params.add("%" + object + "%");
        }

        // Only return currently valid triples
        sql.append(" AND (valid_until IS NULL OR valid_until > ?)");
        params.add(Instant.now().toString());

        sql.append(" ORDER BY created_at DESC LIMIT ?");
        params.add(limit);

        return executeQuery(sql.toString(), params);
    }

    /**
     * Invalidate (soft-delete) a triple by setting valid_until to now.
     *
     * @return true if a triple was invalidated
     */
    public boolean invalidateTriple(long tripleId) throws IOException {
        String sql = "UPDATE triples SET valid_until = ? WHERE id = ? AND valid_until IS NULL";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            String now = Instant.now().toString();
            stmt.setString(1, now);
            stmt.setLong(2, tripleId);
            int updated = stmt.executeUpdate();

            if (updated > 0) {
                JsonObject walPayload = new JsonObject();
                walPayload.addProperty("invalidated_at", now);
                wal.log("kg_invalidate", String.valueOf(tripleId), walPayload);
            }
            return updated > 0;
        } catch (SQLException e) {
            throw new IOException("Failed to invalidate triple", e);
        }
    }

    /**
     * Invalidate all triples matching subject + predicate (for updating facts).
     *
     * @return number of triples invalidated
     */
    public int invalidateBySubjectPredicate(@NotNull String subject, @NotNull String predicate) throws IOException {
        String sql = "UPDATE triples SET valid_until = ? WHERE subject = ? AND predicate = ? AND valid_until IS NULL";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, Instant.now().toString());
            stmt.setString(2, subject);
            stmt.setString(3, predicate);
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("Failed to invalidate triples", e);
        }
    }

    /**
     * Update evidence references in all currently valid triples, replacing old references with new ones.
     * Used when a symbol is renamed or moved to keep KG evidence current.
     *
     * @return number of triples updated
     */
    public int updateEvidence(@NotNull String oldRef, @NotNull String newRef) throws IOException {
        String sql = "UPDATE triples SET evidence = REPLACE(evidence, ?, ?) WHERE evidence LIKE ? AND valid_until IS NULL";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, oldRef);
            stmt.setString(2, newRef);
            stmt.setString(3, "%" + oldRef + "%");
            int updated = stmt.executeUpdate();

            if (updated > 0) {
                JsonObject walPayload = new JsonObject();
                walPayload.addProperty("old_ref", oldRef);
                walPayload.addProperty("new_ref", newRef);
                walPayload.addProperty("updated_count", updated);
                wal.log("kg_update_evidence", oldRef, walPayload);
            }

            return updated;
        } catch (SQLException e) {
            throw new IOException("Failed to update evidence references", e);
        }
    }

    /**
     * Get the timeline of all triples for a subject (including invalidated ones),
     * ordered by creation date.
     */
    public @NotNull List<KgTriple> getTimeline(@NotNull String subject, int limit) throws IOException {
        String sql = """
            SELECT id, subject, predicate, object, valid_from, valid_until, source_drawer, created_at, evidence
            FROM triples WHERE subject = ?
            ORDER BY created_at DESC LIMIT ?
            """;
        return executeQuery(sql, List.of(subject, limit));
    }

    /**
     * Get total number of currently valid triples.
     */
    public int getTripleCount() throws IOException {
        String sql = "SELECT COUNT(*) FROM triples WHERE valid_until IS NULL";
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IOException("Failed to count triples", e);
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private void createSchema() throws SQLException {
        try (var stmt = connection.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS triples (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    subject     TEXT NOT NULL,
                    predicate   TEXT NOT NULL,
                    object      TEXT NOT NULL,
                    valid_from  TEXT,
                    valid_until TEXT,
                    source_drawer TEXT,
                    created_at  TEXT NOT NULL
                )
                """);
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_subject ON triples(subject)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_object ON triples(object)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_predicate ON triples(predicate)");
            migrateSchema(stmt);
        }
    }

    private static void migrateSchema(@NotNull java.sql.Statement stmt) throws SQLException {
        boolean hasEvidence = false;
        boolean hasSourceCloset = false;
        boolean hasSourceDrawer = false;

        try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(triples)")) {
            while (rs.next()) {
                String colName = rs.getString("name");
                if ("evidence".equals(colName)) hasEvidence = true;
                if ("source_closet".equals(colName)) hasSourceCloset = true;
                if ("source_drawer".equals(colName)) hasSourceDrawer = true;
            }
        }

        if (!hasEvidence) {
            stmt.executeUpdate("ALTER TABLE triples ADD COLUMN evidence TEXT DEFAULT ''");
        }

        // Rename legacy "source_closet" column to "source_drawer" (MemPalace terminology → ours)
        if (hasSourceCloset && !hasSourceDrawer) {
            stmt.executeUpdate("ALTER TABLE triples RENAME COLUMN source_closet TO source_drawer");
        }

        // Compound index for dedup lookups and common query patterns
        stmt.executeUpdate(
            "CREATE INDEX IF NOT EXISTS idx_spo_valid ON triples(subject, predicate, object, valid_until)");
    }

    private @NotNull List<KgTriple> executeQuery(@NotNull String sql, @NotNull List<Object> params) throws IOException {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof String s) {
                    stmt.setString(i + 1, s);
                } else if (param instanceof Integer n) {
                    stmt.setInt(i + 1, n);
                } else if (param instanceof Long n) {
                    stmt.setLong(i + 1, n);
                }
            }
            try (ResultSet rs = stmt.executeQuery()) {
                List<KgTriple> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(resultSetToTriple(rs));
                }
                return results;
            }
        } catch (SQLException e) {
            throw new IOException("Failed to query triples", e);
        }
    }

    private static KgTriple resultSetToTriple(@NotNull ResultSet rs) throws SQLException {
        return KgTriple.builder()
            .id(rs.getLong("id"))
            .subject(rs.getString("subject"))
            .predicate(rs.getString("predicate"))
            .object(rs.getString("object"))
            .validFrom(parseNullableInstant(rs.getString("valid_from")))
            .validUntil(parseNullableInstant(rs.getString("valid_until")))
            .sourceDrawer(rs.getString("source_drawer"))
            .createdAt(Instant.parse(rs.getString("created_at")))
            .evidence(getNullableString(rs, "evidence"))
            .build();
    }

    private static @Nullable Instant parseNullableInstant(@Nullable String value) {
        if (value == null || value.isEmpty()) return null;
        return Instant.parse(value);
    }

    /**
     * Read a nullable string column, returning empty string for null.
     */
    private static @NotNull String getNullableString(@NotNull ResultSet rs, @NotNull String column) throws SQLException {
        String value = rs.getString(column);
        return value != null ? value : "";
    }

    private static void setNullableInstant(@NotNull PreparedStatement stmt, int index,
                                           @Nullable Instant value) throws SQLException {
        if (value != null) {
            stmt.setString(index, value.toString());
        } else {
            stmt.setNull(index, Types.VARCHAR);
        }
    }

    private static void setNullableString(@NotNull PreparedStatement stmt, int index,
                                          @Nullable String value) throws SQLException {
        if (value != null) {
            stmt.setString(index, value);
        } else {
            stmt.setNull(index, Types.VARCHAR);
        }
    }

    @Override
    public void dispose() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                LOG.warn("Error closing KnowledgeGraph connection", e);
            }
            connection = null;
        }
    }
}
