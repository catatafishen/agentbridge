package com.github.catatafishen.agentbridge.ui.statistics;

import com.github.catatafishen.agentbridge.services.ToolCallStatisticsService.ToolAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ToolStatisticsTableModel} — column rendering, byte formatting, error rate.
 */
class ToolStatisticsTableModelTest {

    private ToolStatisticsTableModel model;

    @BeforeEach
    void setUp() {
        model = new ToolStatisticsTableModel();
    }

    @Test
    void emptyModelHasZeroRows() {
        assertEquals(0, model.getRowCount());
        assertEquals(8, model.getColumnCount());
    }

    @Test
    void columnNames() {
        assertEquals("Tool", model.getColumnName(0));
        assertEquals("Category", model.getColumnName(1));
        assertEquals("Client", model.getColumnName(2));
        assertEquals("Calls", model.getColumnName(3));
        assertEquals("Avg Duration (ms)", model.getColumnName(4));
        assertEquals("Total Input", model.getColumnName(5));
        assertEquals("Total Output", model.getColumnName(6));
        assertEquals("Error Rate (%)", model.getColumnName(7));
    }

    @Test
    void setDataPopulatesRows() {
        model.setData(List.of(
            new ToolAggregate("read_file", "FILE", "copilot", 10, 42, 2048, 8192, 1),
            new ToolAggregate("write_file", "FILE", "opencode", 5, 100, 512, 1024, 0)
        ));
        assertEquals(2, model.getRowCount());
    }

    @Test
    void getValueAtReturnsCorrectValues() {
        model.setData(List.of(
            new ToolAggregate("search_text", "NAV", "copilot", 25, 75, 10240, 2097152, 3)
        ));

        assertEquals("search_text", model.getValueAt(0, 0));
        assertEquals("NAV", model.getValueAt(0, 1));
        assertEquals("copilot", model.getValueAt(0, 2));
        assertEquals(25L, model.getValueAt(0, 3));
        assertEquals(75L, model.getValueAt(0, 4));
        assertEquals("10.0 KB", model.getValueAt(0, 5));
        assertEquals("2.0 MB", model.getValueAt(0, 6));
        assertEquals("12.0", model.getValueAt(0, 7)); // 3/25 * 100
    }

    @Test
    void nullCategoryShowsDash() {
        model.setData(List.of(
            new ToolAggregate("custom", null, "copilot", 1, 10, 100, 200, 0)
        ));
        assertEquals("—", model.getValueAt(0, 1));
    }

    @Test
    void zeroCallsShowsZeroErrorRate() {
        model.setData(List.of(
            new ToolAggregate("tool", "CAT", "client", 0, 0, 0, 0, 0)
        ));
        assertEquals("0.0", model.getValueAt(0, 7));
    }

    @Test
    void formatBytesSmall() {
        assertEquals("0 B", ToolStatisticsTableModel.formatBytes(0));
        assertEquals("512 B", ToolStatisticsTableModel.formatBytes(512));
        assertEquals("1023 B", ToolStatisticsTableModel.formatBytes(1023));
    }

    @Test
    void formatBytesKilobytes() {
        assertEquals("1.0 KB", ToolStatisticsTableModel.formatBytes(1024));
        assertEquals("1.5 KB", ToolStatisticsTableModel.formatBytes(1536));
        assertEquals("999.0 KB", ToolStatisticsTableModel.formatBytes(999 * 1024));
    }

    @Test
    void formatBytesMegabytes() {
        assertEquals("1.0 MB", ToolStatisticsTableModel.formatBytes(1024 * 1024));
        assertEquals("2.5 MB", ToolStatisticsTableModel.formatBytes((long) (2.5 * 1024 * 1024)));
    }

    @Test
    void setDataClearsPrevious() {
        model.setData(List.of(
            new ToolAggregate("a", null, "c", 1, 1, 1, 1, 0),
            new ToolAggregate("b", null, "c", 1, 1, 1, 1, 0)
        ));
        assertEquals(2, model.getRowCount());

        model.setData(List.of(
            new ToolAggregate("x", null, "c", 1, 1, 1, 1, 0)
        ));
        assertEquals(1, model.getRowCount());
        assertEquals("x", model.getValueAt(0, 0));
    }

    @Test
    void columnTypes() {
        assertEquals(String.class, model.getColumnClass(0));
        assertEquals(String.class, model.getColumnClass(1));
        assertEquals(String.class, model.getColumnClass(2));
        assertEquals(Long.class, model.getColumnClass(3));
        assertEquals(Long.class, model.getColumnClass(4));
        assertEquals(String.class, model.getColumnClass(5));
        assertEquals(String.class, model.getColumnClass(6));
        assertEquals(String.class, model.getColumnClass(7));
    }
}
