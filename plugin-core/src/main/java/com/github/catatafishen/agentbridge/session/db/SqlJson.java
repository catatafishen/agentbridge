package com.github.catatafishen.agentbridge.session.db;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * Binds a list of values as one parameter, so {@code IN (...)} lists can use a constant SQL string:
 * {@code WHERE id IN (SELECT value FROM json_each(?))}.
 */
final class SqlJson {

    /**
     * Constant sub-select for a list bound with {@link #array(Collection)}.
     */
    static final String EACH = "SELECT value FROM json_each(?)";

    private SqlJson() {
    }

    @NotNull
    static String array(@NotNull Collection<String> values) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String v : values) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"');
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c == '"' || c == '\\') sb.append('\\').append(c);
                else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                else sb.append(c);
            }
            sb.append('"');
        }
        return sb.append(']').toString();
    }
}
