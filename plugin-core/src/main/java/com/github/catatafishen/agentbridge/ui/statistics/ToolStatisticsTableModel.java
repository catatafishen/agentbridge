package com.github.catatafishen.agentbridge.ui.statistics;

import com.github.catatafishen.agentbridge.services.ToolCallStatisticsService.ToolAggregate;
import org.jetbrains.annotations.NotNull;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Table model for the Tool Statistics tab. Columns: Tool, Category, Client,
 * Calls, Avg Duration (ms), Total Input, Total Output, Error Rate (%).
 */
public class ToolStatisticsTableModel extends AbstractTableModel {

    private static final String[] COLUMN_NAMES = {
        "Tool", "Category", "Client", "Calls", "Avg Duration (ms)",
        "Total Input", "Total Output", "Error Rate (%)"
    };

    private static final Class<?>[] COLUMN_TYPES = {
        String.class, String.class, String.class, Long.class, Long.class,
        String.class, String.class, String.class
    };

    private final List<ToolAggregate> data = new ArrayList<>();

    @Override
    public int getRowCount() {
        return data.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMN_NAMES.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMN_NAMES[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return COLUMN_TYPES[columnIndex];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        ToolAggregate row = data.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> row.toolName();
            case 1 -> row.category() != null ? row.category() : "—";
            case 2 -> row.clientId();
            case 3 -> row.callCount();
            case 4 -> row.avgDurationMs();
            case 5 -> formatBytes(row.totalInputBytes());
            case 6 -> formatBytes(row.totalOutputBytes());
            case 7 -> row.callCount() > 0
                ? String.format("%.1f", 100.0 * row.errorCount() / row.callCount())
                : "0.0";
            default -> "";
        };
    }

    public void setData(@NotNull List<ToolAggregate> aggregates) {
        data.clear();
        data.addAll(aggregates);
        fireTableDataChanged();
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
