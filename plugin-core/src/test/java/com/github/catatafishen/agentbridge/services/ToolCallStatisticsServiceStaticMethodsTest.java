package com.github.catatafishen.agentbridge.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

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

    static Stream<Arguments> isDbMovedCases() {
        return Stream.of(
            Arguments.of("[SQLITE_READONLY_DBMOVED] database file has been moved", true),
            Arguments.of("SQLITE_READONLY_DBMOVED", true),
            Arguments.of("table not found", false),
            Arguments.of(null, false),
            Arguments.of("", false),
            Arguments.of("SQLITE_READONLY", false)
        );
    }

    @ParameterizedTest(name = "isDbMoved(\"{0}\") = {1}")
    @MethodSource("isDbMovedCases")
    void isDbMoved_parameterized(String message, boolean expected) {
        SQLException ex = new SQLException(message);
        assertEquals(expected, ToolCallStatisticsService.isDbMoved(ex));
    }
}
