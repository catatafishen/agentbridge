package com.github.catatafishen.agentbridge.services;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ToolCallStatisticsToolNameRepair} — the one-shot cleanup that
 * fixes legacy rows where {@code tool_name} stored the agent-supplied chip title
 * instead of the canonical MCP tool id.
 */
class ToolCallStatisticsToolNameRepairTest {

    @TempDir
    Path tempDir;

    private Connection connection;
    private java.util.Set<String> knownIds;
    private java.util.function.Function<String, ToolDefinition> displayNameLookup;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path dbPath = tempDir.resolve("tool-stats.db");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement stmt = connection.createStatement()) {
            // Mirror the production schema columns we touch
            stmt.execute("""
                CREATE TABLE tool_calls (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    tool_name TEXT NOT NULL,
                    display_name TEXT
                )
                """);
        }

        knownIds = new java.util.HashSet<>(java.util.List.of(
            "read_file", "search_text", "git_status", "write_file"));
        displayNameLookup = name -> switch (name) {
            case "Read File" -> stub("read_file");
            case "Search Text" -> stub("search_text");
            case "Git Status" -> stub("git_status");
            case "Write File" -> stub("write_file");
            default -> null;
        };
    }

    private static ToolDefinition stub(String id) {
        return new StubTool(id, id);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    @DisplayName("canonical rows are skipped untouched")
    void canonicalRowsLeftAlone() throws Exception {
        insertRow("read_file", null);
        insertRow("search_text", null);

        var result = ToolCallStatisticsToolNameRepair.repair(connection, knownIds, displayNameLookup);

        assertEquals(2, result.scanned());
        assertEquals(2, result.skipped());
        assertEquals(0, result.repaired());
        assertEquals(0, result.deleted());
        assertEquals(2, countRows());
    }

    @Test
    @DisplayName("prefixed names are stripped to canonical id")
    void prefixedNamesAreRepaired() throws Exception {
        insertRow("agentbridge-read_file", null);
        insertRow("agentbridge_search_text", null);
        insertRow("@agentbridge/git_status", null);

        var result = ToolCallStatisticsToolNameRepair.repair(connection, knownIds, displayNameLookup);

        assertEquals(3, result.repaired());
        assertEquals(0, result.deleted());
        Map<String, String> rows = loadRows();
        assertTrue(rows.containsKey("read_file"));
        assertTrue(rows.containsKey("search_text"));
        assertTrue(rows.containsKey("git_status"));
        // Original prefixed name preserved in display_name
        assertEquals("agentbridge-read_file", rows.get("read_file"));
    }

    @Test
    @DisplayName("display-name rows are mapped back to canonical id")
    void displayNameRowsAreRepaired() throws Exception {
        insertRow("Read File", null);
        insertRow("Git Status", null);

        var result = ToolCallStatisticsToolNameRepair.repair(connection, knownIds, displayNameLookup);

        assertEquals(2, result.repaired());
        Map<String, String> rows = loadRows();
        assertTrue(rows.containsKey("read_file"));
        assertEquals("Read File", rows.get("read_file"));
        assertTrue(rows.containsKey("git_status"));
    }

    @Test
    @DisplayName("unmappable rows (free-form titles) are deleted")
    void unmappableRowsAreDeleted() throws Exception {
        insertRow("Tail full log", null);
        insertRow("Run summary", null);
        insertRow("Some weird agent-only thing", null);

        var result = ToolCallStatisticsToolNameRepair.repair(connection, knownIds, displayNameLookup);

        assertEquals(3, result.deleted());
        assertEquals(0, result.repaired());
        assertEquals(0, countRows());
    }

    @Test
    @DisplayName("mixed table: keeps canonical, repairs prefixed/display, deletes garbage")
    void mixedTableHandledCorrectly() throws Exception {
        insertRow("read_file", null);                  // skipped
        insertRow("agentbridge-search_text", null);    // repaired (prefix)
        insertRow("Git Status", null);                 // repaired (display name)
        insertRow("Tail full log", null);              // deleted

        var result = ToolCallStatisticsToolNameRepair.repair(connection, knownIds, displayNameLookup);

        assertEquals(4, result.scanned());
        assertEquals(1, result.skipped());
        assertEquals(2, result.repaired());
        assertEquals(1, result.deleted());
        assertEquals(3, countRows());
    }

    @Test
    @DisplayName("repair is idempotent — second run is a no-op")
    void repairIsIdempotent() throws Exception {
        insertRow("agentbridge-read_file", null);
        insertRow("Tail full log", null);

        var first = ToolCallStatisticsToolNameRepair.repair(connection, knownIds, displayNameLookup);
        assertEquals(1, first.repaired());
        assertEquals(1, first.deleted());
        assertFalse(first.alreadyRun());

        // Add another polluted row — the second run must NOT touch it
        insertRow("Another bogus title", null);

        var second = ToolCallStatisticsToolNameRepair.repair(connection, knownIds, displayNameLookup);
        assertTrue(second.alreadyRun());
        assertEquals(0, second.scanned());
        // The new bogus row should still be there
        assertEquals(2, countRows());
    }

    @Test
    @DisplayName("existing display_name is preserved, not overwritten")
    void existingDisplayNameNotOverwritten() throws Exception {
        insertRow("agentbridge-read_file", "Pre-existing label");

        ToolCallStatisticsToolNameRepair.repair(connection, knownIds, displayNameLookup);

        Map<String, String> rows = loadRows();
        assertEquals("Pre-existing label", rows.get("read_file"));
    }

    @Test
    @DisplayName("when canonical equals previous (no prefix to strip), display_name stays null")
    void noDisplayNameWhenAlreadyCanonical() throws Exception {
        // "Read File" → mapped to read_file via display name. Original was a display name,
        // so we DO preserve it as display_name (it's not equal to "read_file").
        insertRow("Read File", null);

        ToolCallStatisticsToolNameRepair.repair(connection, knownIds, displayNameLookup);

        Map<String, String> rows = loadRows();
        assertEquals("Read File", rows.get("read_file"));
    }

    @Test
    @DisplayName("empty registry: skips repair without setting marker, allows future retry")
    void emptyRegistryDoesNotMarkAsRun() throws Exception {
        java.util.Set<String> empty = java.util.Set.of();
        insertRow("read_file", null);
        insertRow("Tail full log", null);

        var result = ToolCallStatisticsToolNameRepair.repair(connection, empty, name -> null);
        assertFalse(result.alreadyRun());
        assertEquals(0, result.scanned());
        assertEquals(2, countRows(), "Nothing should have been deleted");

        // Now run with a real registry — should still execute
        var second = ToolCallStatisticsToolNameRepair.repair(connection, knownIds, displayNameLookup);
        assertFalse(second.alreadyRun());
        assertEquals(1, second.deleted());
    }

    // ── Helpers ──────────────────────────────────────────────

    private void insertRow(String toolName, @Nullable String displayName) throws Exception {
        try (PreparedStatement stmt = connection.prepareStatement(
            "INSERT INTO tool_calls (tool_name, display_name) VALUES (?, ?)")) {
            stmt.setString(1, toolName);
            stmt.setString(2, displayName);
            stmt.executeUpdate();
        }
    }

    private int countRows() throws Exception {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM tool_calls")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Returns a map of tool_name → display_name for every row. */
    private Map<String, String> loadRows() throws Exception {
        Map<String, String> result = new java.util.HashMap<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT tool_name, display_name FROM tool_calls")) {
            while (rs.next()) {
                result.put(rs.getString("tool_name"), rs.getString("display_name"));
            }
        }
        return result;
    }

    /** Minimal ToolDefinition stub for registry tests. */
    private record StubTool(String id, String displayName) implements ToolDefinition {
        @Override @NotNull public String id() { return id; }
        @Override @NotNull public Kind kind() { return Kind.READ; }
        @Override @NotNull public String displayName() { return displayName; }
        @Override @NotNull public String description() { return ""; }
        @Override @NotNull public ToolRegistry.Category category() { return ToolRegistry.Category.OTHER; }
    }
}
