package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.intellij.util.diff.Diff;
import com.intellij.util.diff.FilesTooBigForDiffException;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates unified-diff text from two document snapshots using IntelliJ's line-diff engine.
 * Output is compatible with {@code GitDiffRenderer} (diff --git header format).
 */
final class DiffUtils {

    private static final int CONTEXT_LINES = 3;

    private record ChangeRange(int line0, int deleted, int line1, int inserted) {
    }

    private DiffUtils() {
    }

    /**
     * Returns a unified diff string between {@code before} and {@code after}.
     * Returns an empty string if the two strings are identical.
     * The output starts with {@code diff --git a/filePath b/filePath} so it can be
     * rendered by {@code GitDiffRenderer}.
     */
    @NotNull
    static String unifiedDiff(@NotNull String before, @NotNull String after,
                              @NotNull String filePath) {
        if (before.equals(after)) return "";

        String[] beforeLines = Diff.splitLines(before);
        String[] afterLines = Diff.splitLines(after);

        try {
            Diff.Change change = Diff.buildChanges(beforeLines, afterLines);
            if (change == null) return "";

            List<ChangeRange> ranges = buildChangeRanges(change);
            List<List<ChangeRange>> hunks = groupIntoHunks(ranges);

            StringBuilder sb = new StringBuilder();
            sb.append("diff --git a/").append(filePath).append(" b/").append(filePath).append('\n');
            sb.append("--- a/").append(filePath).append('\n');
            sb.append("+++ b/").append(filePath).append('\n');
            for (List<ChangeRange> hunk : hunks) {
                renderHunk(sb, hunk, beforeLines, afterLines);
            }
            return sb.toString();
        } catch (FilesTooBigForDiffException e) {
            return "(diff unavailable: files too large to compare)";
        }
    }

    private static List<ChangeRange> buildChangeRanges(Diff.Change change) {
        List<ChangeRange> ranges = new ArrayList<>();
        for (Diff.Change c = change; c != null; c = c.link) {
            ranges.add(new ChangeRange(c.line0, c.deleted, c.line1, c.inserted));
        }
        return ranges;
    }

    private static List<List<ChangeRange>> groupIntoHunks(List<ChangeRange> ranges) {
        List<List<ChangeRange>> hunks = new ArrayList<>();
        List<ChangeRange> current = new ArrayList<>();
        for (ChangeRange cr : ranges) {
            if (current.isEmpty()) {
                current.add(cr);
            } else {
                ChangeRange last = current.getLast();
                if (cr.line0() <= last.line0() + last.deleted() + 2 * CONTEXT_LINES) {
                    current.add(cr);
                } else {
                    hunks.add(List.copyOf(current));
                    current.clear();
                    current.add(cr);
                }
            }
        }
        if (!current.isEmpty()) hunks.add(List.copyOf(current));
        return hunks;
    }

    private static void renderHunk(StringBuilder sb, List<ChangeRange> hunk,
                                   String[] beforeLines, String[] afterLines) {
        ChangeRange first = hunk.getFirst();
        ChangeRange last = hunk.getLast();

        int startBefore = Math.max(0, first.line0() - CONTEXT_LINES);
        int endBefore = Math.min(beforeLines.length, last.line0() + last.deleted() + CONTEXT_LINES);
        int startAfter = Math.max(0, first.line1() - CONTEXT_LINES);
        int endAfter = Math.min(afterLines.length, last.line1() + last.inserted() + CONTEXT_LINES);

        sb.append("@@ -").append(startBefore + 1).append(',').append(endBefore - startBefore)
            .append(" +").append(startAfter + 1).append(',').append(endAfter - startAfter)
            .append(" @@\n");

        int bi = startBefore;
        int ai = startAfter;
        for (ChangeRange cr : hunk) {
            while (bi < cr.line0()) {
                sb.append(' ').append(beforeLines[bi]).append('\n');
                bi++;
                ai++;
            }
            for (int i = 0; i < cr.deleted(); i++) {
                sb.append('-').append(beforeLines[bi + i]).append('\n');
            }
            for (int i = 0; i < cr.inserted(); i++) {
                sb.append('+').append(afterLines[ai + i]).append('\n');
            }
            bi += cr.deleted();
            ai += cr.inserted();
        }
        while (bi < endBefore) {
            sb.append(' ').append(beforeLines[bi]).append('\n');
            bi++;
            ai++;
        }
    }
}
