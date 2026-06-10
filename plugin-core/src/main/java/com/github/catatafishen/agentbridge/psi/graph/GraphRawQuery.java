package com.github.catatafishen.agentbridge.psi.graph;

import com.github.catatafishen.agentbridge.session.db.ConversationDatabase;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.github.catatafishen.agentbridge.psi.graph.GraphSqlSupport.withQueryOnly;

/**
 * Read-only escape hatch for arbitrary SQL queries against the conversation/graph database.
 *
 * <p>Used by the {@code QueryCodeGraphTool} MCP tool which lets agents run ad-hoc SQL for
 * impact analysis. The query runs under {@code PRAGMA query_only = ON} so any write
 * operation (INSERT, UPDATE, DELETE, DDL) is rejected at the SQLite engine level.
 *
 * <p>Package-private — invoked by {@link CodeGraphStore}.
 */
final class GraphRawQuery {

    private GraphRawQuery() {
    }

    @NotNull
    @SuppressWarnings("SqlSourceToSinkFlow")
    // Intentional escape hatch: the SQL is supplied by the MCP agent. PRAGMA query_only=ON
    // blocks any write operation at the engine level; values are bound parameters.
    static List<Map<String, Object>> queryRaw(@NotNull Project project,
                                              @NotNull String sql,
                                              @NotNull Object... params) throws SQLException {
        ConversationDatabase db = ConversationDatabase.getInstance(project);
        return Objects.requireNonNull(db.withConnection(conn -> withQueryOnly(conn, () -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int colCount = md.getColumnCount();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= colCount; i++) {
                            row.put(md.getColumnLabel(i), rs.getObject(i));
                        }
                        rows.add(row);
                    }
                }
            }
            return rows;
        })));
    }
}
