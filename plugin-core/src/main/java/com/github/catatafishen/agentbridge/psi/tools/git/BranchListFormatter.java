package com.github.catatafishen.agentbridge.psi.tools.git;

import org.jetbrains.annotations.NotNull;

/**
 * Formats the output of {@code git for-each-ref} into a human-readable branch table.
 * Pure formatting logic — no IntelliJ or git dependencies, fully testable.
 */
public final class BranchListFormatter {

    private BranchListFormatter() {
    }

    public static @NotNull String formatBranchTable(@NotNull String forEachRefOutput) {
        if (forEachRefOutput.isBlank()) {
            return "(no branches)";
        }

        String[] lines = forEachRefOutput.split("\n");
        StringBuilder sb = new StringBuilder();

        for (String line : lines) {
            if (line.length() < 3) {
                if (!line.isBlank()) sb.append(line).append('\n');
            } else {
                appendFormattedBranch(sb, line);
            }
        }

        return sb.toString().stripTrailing();
    }

    private static void appendFormattedBranch(@NotNull StringBuilder sb, @NotNull String line) {
        String prefix = line.substring(0, 2);
        String rest = line.substring(2);
        String[] parts = rest.split("\\|", -1);

        if (parts.length < 3) {
            sb.append(line).append('\n');
            return;
        }

        String name = parts[0].trim();
        String hash = parts[1].trim();
        String date = parts[2].trim();
        String upstream = parts.length > 3 ? parts[3].trim() : "";
        String track = parts.length > 4 ? parts[4].trim() : "";

        sb.append(prefix).append(name);
        sb.append("  ").append(hash);
        sb.append("  (").append(date).append(')');

        if (!upstream.isEmpty()) {
            sb.append("  -> ").append(upstream);
        }
        if (!track.isEmpty()) {
            sb.append(' ').append(track);
        }
        sb.append('\n');
    }
}
