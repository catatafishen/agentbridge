package com.github.catatafishen.agentbridge.ui.statistics;

import com.github.catatafishen.agentbridge.session.db.ConversationStatistics.ToolAggregate;
import org.jetbrains.annotations.NotNull;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Table model for the Tool Statistics tab. Columns: Tool, Category, Calls,
 * Avg Duration (ms), Avg Data, Total Input, Total Output, Error Rate (%).
 * The Client column is omitted — it is redundant because the client filter
 * is shown in the toolbar dropdown above the table.
 *
 * <p>Numeric columns (Calls, Avg Duration, byte sizes, Error Rate) return raw
 * {@code Long} or {@code Double} values so that {@code TableRowSorter} sorts
 * them numerically. The panel applies custom cell renderers for display formatting.</p>
 */
public class ToolStatisticsTableModel extends AbstractTableModel {

    static final int COL_TOOL = 0;
    static final int COL_CATEGORY = 1;
    static final int COL_CALLS = 2;
    static final int COL_AVG_DURATION = 3;
    static final int COL_AVG_DATA = 4;
    static final int COL_TOTAL_INPUT = 5;
    static final int COL_TOTAL_OUTPUT = 6;
    static final int COL_ERROR_RATE = 7;

    private static final String[] COLUMN_NAMES = {
        "Tool", "Category", "Calls", "Avg Duration (ms)",
        "Avg Data", "Total Input", "Total Output", "Error Rate (%)"
    };

    private static final Class<?>[] COLUMN_TYPES = {
        String.class, String.class, Long.class, Long.class,
        Long.class, Long.class, Long.class, Double.class
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
            case COL_TOOL -> row.toolName();
            case COL_CATEGORY -> row.category() != null ? row.category() : "—";
            case COL_CALLS -> row.callCount();
            case COL_AVG_DURATION -> row.avgDurationMs();
            case COL_AVG_DATA -> row.avgTotalBytes();
            case COL_TOTAL_INPUT -> row.totalInputBytes();
            case COL_TOTAL_OUTPUT -> row.totalOutputBytes();
            case COL_ERROR_RATE -> row.callCount() > 0
                ? 100.0 * row.errorCount() / row.callCount()
                : 0.0;
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
