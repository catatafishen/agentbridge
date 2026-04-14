package com.github.catatafishen.agentbridge.services;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for package-private static helper methods in {@link ToolCallStatisticsService}.
 */
class ToolCallStatisticsServiceStaticMethodsTest {

    // ──────────────────────────────────────────────
    // appendFilters tests
    // ──────────────────────────────────────────────

    @Test
    void appendFilters_bothNull_noFilterAppended() {
        StringBuilder sql = new StringBuilder("SELECT * FROM t WHERE 1=1");
        List<String> params = ToolCallStatisticsService.appendFilters(sql, null, null);

        assertEquals("SELECT * FROM t WHERE 1=1", sql.toString());
        assertTrue(params.isEmpty());
    }

    @Test
    void appendFilters_onlySinceSet_appendsTimestampFilter() {
        StringBuilder sql = new StringBuilder("SELECT * FROM t WHERE 1=1");
        List<String> params = ToolCallStatisticsService.appendFilters(sql, "2024-01-01T00:00:00Z", null);

        assertEquals("SELECT * FROM t WHERE 1=1 AND timestamp >= ?", sql.toString());
        assertEquals(List.of("2024-01-01T00:00:00Z"), params);
    }

    @Test
    void appendFilters_onlyClientIdSet_appendsClientFilter() {
        StringBuilder sql = new StringBuilder("SELECT * FROM t WHERE 1=1");
        List<String> params = ToolCallStatisticsService.appendFilters(sql, null, "cursor");

        assertEquals("SELECT * FROM t WHERE 1=1 AND client_id = ?", sql.toString());
        assertEquals(List.of("cursor"), params);
    }

    @Test
    void appendFilters_bothSet_appendsBothFiltersInOrder() {
        StringBuilder sql = new StringBuilder("SELECT * FROM t WHERE 1=1");
        List<String> params = ToolCallStatisticsService.appendFilters(sql, "2024-06-15T12:00:00Z", "vscode");

        assertEquals("SELECT * FROM t WHERE 1=1 AND timestamp >= ? AND client_id = ?", sql.toString());
        assertEquals(List.of("2024-06-15T12:00:00Z", "vscode"), params);
    }

    @Test
    void appendFilters_emptyStrings_treatedAsNonNull() {
        StringBuilder sql = new StringBuilder("SELECT * FROM t WHERE 1=1");
        List<String> params = ToolCallStatisticsService.appendFilters(sql, "", "");

        // Empty strings are non-null, so both filters are appended
        assertEquals("SELECT * FROM t WHERE 1=1 AND timestamp >= ? AND client_id = ?", sql.toString());
        assertEquals(List.of("", ""), params);
    }

    @Test
    void appendFilters_returnedListIsMutable() {
        StringBuilder sql = new StringBuilder("WHERE 1=1");
        List<String> params = ToolCallStatisticsService.appendFilters(sql, "x", null);

        // The returned ArrayList should be mutable (callers may add more params)
        assertDoesNotThrow(() -> params.add("extra"));
        assertEquals(2, params.size());
    }

    // ──────────────────────────────────────────────
    // isDbMoved tests
    // ──────────────────────────────────────────────

    @Test
    void isDbMoved_messageContainsDbMoved_returnsTrue() {
        SQLException ex = new SQLException("[SQLITE_READONLY_DBMOVED] database file has been moved");
        assertTrue(ToolCallStatisticsService.isDbMoved(ex));
    }

    @Test
    void isDbMoved_messageExactMatch_returnsTrue() {
        SQLException ex = new SQLException("SQLITE_READONLY_DBMOVED");
        assertTrue(ToolCallStatisticsService.isDbMoved(ex));
    }

    @Test
    void isDbMoved_unrelatedMessage_returnsFalse() {
        SQLException ex = new SQLException("table not found");
        assertFalse(ToolCallStatisticsService.isDbMoved(ex));
    }

    @Test
    void isDbMoved_nullMessage_returnsFalse() {
        SQLException ex = new SQLException((String) null);
        assertFalse(ToolCallStatisticsService.isDbMoved(ex));
    }

    @Test
    void isDbMoved_emptyMessage_returnsFalse() {
        SQLException ex = new SQLException("");
        assertFalse(ToolCallStatisticsService.isDbMoved(ex));
    }

    @Test
    void isDbMoved_partialMatch_returnsFalse() {
        SQLException ex = new SQLException("SQLITE_READONLY");
        assertFalse(ToolCallStatisticsService.isDbMoved(ex));
    }

    @Test
    void initialize_nullBasePath_doesNotThrowAndLeavesQueriesEmpty() {
        Project project = Mockito.mock(Project.class);
        Mockito.when(project.getBasePath()).thenReturn(null);

        ToolCallStatisticsService service = new ToolCallStatisticsService(project);

        assertDoesNotThrow(service::initialize);
        assertTrue(service.queryAggregates(null, null).isEmpty());
        assertTrue(service.getDistinctClients().isEmpty());
        assertTrue(service.querySummary(null, null).isEmpty());
    }

    @Test
    void recordCall_withoutInitializedConnection_isNoOp() {
        Project project = Mockito.mock(Project.class);
        ToolCallStatisticsService service = new ToolCallStatisticsService(project);

        assertDoesNotThrow(() -> service.recordCall(new ToolCallRecord(
            "read_file", "FILE", 10, 20, 30, true, null, "copilot", java.time.Instant.now())));
        assertTrue(service.queryAggregates(null, null).isEmpty());
    }
}
