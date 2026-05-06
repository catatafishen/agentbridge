package com.github.catatafishen.agentbridge.ui.statistics;

import com.github.catatafishen.agentbridge.session.db.ConversationStatistics.ToolAggregate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.table.TableRowSorter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ToolStatisticsTableModel} — byte formatting, column types,
 * and numeric sort correctness.
 */
@DisplayName("ToolStatisticsTableModel")
class ToolStatisticsTableModelTest {

    @Test
    @DisplayName("0 bytes → '0 B'")
    void formatBytes_zero() {
        assertEquals("0 B", ToolStatisticsTableModel.formatBytes(0));
    }

    @Test
    @DisplayName("512 bytes → '512 B'")
    void formatBytes_512() {
        assertEquals("512 B", ToolStatisticsTableModel.formatBytes(512));
    }

    @Test
    @DisplayName("1023 bytes → '1023 B'")
    void formatBytes_1023() {
        assertEquals("1023 B", ToolStatisticsTableModel.formatBytes(1023));
    }

    @Test
    @DisplayName("1024 bytes → '1.0 KB'")
    void formatBytes_1024() {
        assertEquals("1.0 KB", ToolStatisticsTableModel.formatBytes(1024));
    }

    @Test
    @DisplayName("1536 bytes → '1.5 KB'")
    void formatBytes_1536() {
        assertEquals("1.5 KB", ToolStatisticsTableModel.formatBytes(1536));
    }

    @Test
    @DisplayName("1048576 bytes → '1.0 MB'")
    void formatBytes_oneMegabyte() {
        assertEquals("1.0 MB", ToolStatisticsTableModel.formatBytes(1_048_576));
    }

    @Test
    @DisplayName("1572864 bytes → '1.5 MB'")
    void formatBytes_oneAndHalfMegabytes() {
        assertEquals("1.5 MB", ToolStatisticsTableModel.formatBytes(1_572_864));
    }

    @Test
    @DisplayName("byte columns return Long values for numeric sorting")
    void byteColumnsReturnLong() {
        var model = new ToolStatisticsTableModel();
        model.setData(List.of(aggregate("tool_a", 10, 500, 99_000, 1_100_000, 50_000, 0)));

        assertInstanceOf(Long.class, model.getValueAt(0, ToolStatisticsTableModel.COL_AVG_DATA));
        assertInstanceOf(Long.class, model.getValueAt(0, ToolStatisticsTableModel.COL_TOTAL_INPUT));
        assertInstanceOf(Long.class, model.getValueAt(0, ToolStatisticsTableModel.COL_TOTAL_OUTPUT));
    }

    @Test
    @DisplayName("error rate column returns Double for numeric sorting")
    void errorRateReturnsDouble() {
        var model = new ToolStatisticsTableModel();
        model.setData(List.of(aggregate("tool_a", 10, 500, 100, 200, 50, 2)));

        Object errorRate = model.getValueAt(0, ToolStatisticsTableModel.COL_ERROR_RATE);
        assertInstanceOf(Double.class, errorRate);
        assertEquals(20.0, (Double) errorRate, 0.001);
    }

    @Test
    @DisplayName("error rate is 0.0 when call count is zero")
    void errorRateZeroCalls() {
        var model = new ToolStatisticsTableModel();
        model.setData(List.of(aggregate("tool_a", 0, 0, 0, 0, 0, 0)));

        assertEquals(0.0, (Double) model.getValueAt(0, ToolStatisticsTableModel.COL_ERROR_RATE), 0.001);
    }

    @Test
    @DisplayName("column classes are numeric for sortable columns")
    void columnClassesAreNumeric() {
        var model = new ToolStatisticsTableModel();
        assertEquals(Long.class, model.getColumnClass(ToolStatisticsTableModel.COL_AVG_DATA));
        assertEquals(Long.class, model.getColumnClass(ToolStatisticsTableModel.COL_TOTAL_INPUT));
        assertEquals(Long.class, model.getColumnClass(ToolStatisticsTableModel.COL_TOTAL_OUTPUT));
        assertEquals(Double.class, model.getColumnClass(ToolStatisticsTableModel.COL_ERROR_RATE));
    }

    @Test
    @DisplayName("TableRowSorter sorts byte columns numerically — 1 MB > 99 KB")
    void rowSorterSortsByteColumnsNumerically() {
        var model = new ToolStatisticsTableModel();
        // 99 KB = 101_376 bytes, 1 MB = 1_048_576 bytes
        model.setData(List.of(
            aggregate("big_kb", 5, 100, 101_376, 101_376, 101_376, 0),
            aggregate("small_mb", 5, 100, 1_048_576, 1_048_576, 1_048_576, 0)
        ));

        var sorter = new TableRowSorter<>(model);
        // Sort ascending by Total Input (col 5)
        sorter.setSortKeys(List.of(new RowSorter.SortKey(
            ToolStatisticsTableModel.COL_TOTAL_INPUT, SortOrder.ASCENDING)));
        sorter.sort();

        // Row 0 after sorting should be the smaller value (99 KB)
        int firstModelRow = sorter.convertRowIndexToModel(0);
        int secondModelRow = sorter.convertRowIndexToModel(1);
        long firstBytes = (Long) model.getValueAt(firstModelRow, ToolStatisticsTableModel.COL_TOTAL_INPUT);
        long secondBytes = (Long) model.getValueAt(secondModelRow, ToolStatisticsTableModel.COL_TOTAL_INPUT);

        assertTrue(firstBytes < secondBytes,
            "Expected 99 KB (" + firstBytes + ") < 1 MB (" + secondBytes + ") after ascending sort");
    }

    private static ToolAggregate aggregate(String name, long calls, long avgDuration,
                                           long totalInput, long totalOutput,
                                           long avgTotal, long errors) {
        return new ToolAggregate(name, null, calls, avgDuration, totalInput, totalOutput, avgTotal, errors);
    }
}
