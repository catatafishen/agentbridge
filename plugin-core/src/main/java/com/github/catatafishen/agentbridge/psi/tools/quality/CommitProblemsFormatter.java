package com.github.catatafishen.agentbridge.psi.tools.quality;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure formatting logic for {@code get_commit_problems}: severity filtering, ordering,
 * per-file summary and pagination. Free of IntelliJ dependencies so it can be unit-tested directly.
 */
final class CommitProblemsFormatter {

    static final String SEVERITY_ERROR = "ERROR";
    static final String SEVERITY_WARNING = "WARNING";

    private CommitProblemsFormatter() {
    }

    /**
     * One problem found in a changed file.
     *
     * @param path     project-relative path
     * @param line     1-based line
     * @param severity severity name, e.g. {@code ERROR} or {@code WARNING}
     * @param message  problem description
     * @param fixes    quick-fix names (empty when not requested or none available)
     */
    record Entry(@NotNull String path, int line, @NotNull String severity,
                 @NotNull String message, @NotNull List<String> fixes) {
        Entry withFixes(@NotNull List<String> newFixes) {
            return new Entry(path, line, severity, message, newFixes);
        }
    }

    /**
     * Page request.
     *
     * @param errorsOnly  keep only {@code ERROR} entries
     * @param summaryOnly print only the summary, no individual problems
     * @param offset      number of (filtered, sorted) problems to skip
     * @param limit       maximum number of problems on the page
     */
    record Page(boolean errorsOnly, boolean summaryOnly, int offset, int limit) {
    }

    /**
     * Filters entries by severity and orders them: errors first, then by path and line.
     */
    static @NotNull List<Entry> filterAndSort(@NotNull List<Entry> entries, boolean errorsOnly) {
        List<Entry> result = new ArrayList<>();
        for (Entry e : entries) {
            if (!errorsOnly || SEVERITY_ERROR.equals(e.severity())) result.add(e);
        }
        result.sort(Comparator
            .comparingInt((Entry e) -> severityRank(e.severity()))
            .thenComparing(Entry::path)
            .thenComparingInt(Entry::line));
        return result;
    }

    /**
     * Returns the entries that make up the requested page of an already filtered and sorted list.
     */
    static @NotNull List<Entry> pageOf(@NotNull List<Entry> sorted, int offset, int limit) {
        if (offset >= sorted.size() || limit <= 0) return List.of();
        return sorted.subList(offset, Math.min(sorted.size(), offset + limit));
    }

    /**
     * Renders the full response for a filtered, sorted list.
     *
     * @param sorted      output of {@link #filterAndSort}
     * @param fileCount   number of changed files that were analyzed
     * @param pageEntries the entries to print in detail (output of {@link #pageOf}, possibly with fixes)
     */
    static @NotNull String format(@NotNull List<Entry> sorted, int fileCount,
                                  @NotNull List<Entry> pageEntries, @NotNull Page page) {
        if (sorted.isEmpty()) {
            return "No " + (page.errorsOnly() ? "errors" : "problems")
                + " found in " + fileCount + " changed file(s).";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(sorted.size()).append(" problems in ").append(countFiles(sorted))
            .append(" of ").append(fileCount).append(" changed file(s): ")
            .append(severityTotals(sorted)).append('\n');
        sb.append("By file:\n");
        perFileCounts(sorted).forEach((path, counts) ->
            sb.append("  ").append(path).append(" (").append(counts).append(")\n"));

        if (page.summaryOnly()) {
            sb.append("\n[Summary only. Omit summary_only to list the problems.]");
            return sb.toString();
        }
        if (pageEntries.isEmpty()) {
            sb.append("\nNo problems at offset ").append(page.offset())
                .append(" (total ").append(sorted.size()).append(").");
            return sb.toString();
        }

        int from = page.offset() + 1;
        int to = page.offset() + pageEntries.size();
        sb.append("\nShowing ").append(from).append('-').append(to)
            .append(" of ").append(sorted.size()).append(":\n");
        for (Entry e : pageEntries) {
            sb.append(String.format("%s:%d [%s] %s", e.path(), e.line(), e.severity(), e.message()));
            sb.append(GetHighlightsTool.formatFixLines(e.fixes())).append('\n');
        }
        if (to < sorted.size()) {
            sb.append("[").append(sorted.size() - to).append(" more. Next page: offset=").append(to).append("]");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Note appended when quick-fixes could not be determined for some files, or an empty string when
     * {@code unavailablePaths} is empty.
     *
     * @param reason why the fixes are missing; shown to the caller so that a missing fix is never mistaken for "none exist"
     */
    static @NotNull String fixesUnavailableNote(@NotNull List<String> unavailablePaths, String reason) {
        if (unavailablePaths.isEmpty()) return "";
        return "\n\n[Quick-fixes not determined for " + unavailablePaths.size() + " file(s): "
            + String.join(", ", unavailablePaths) + ". Reason: " + (reason == null ? "unknown" : reason) + "]";
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case SEVERITY_ERROR -> 0;
            case SEVERITY_WARNING -> 1;
            default -> 2;
        };
    }

    private static int countFiles(List<Entry> entries) {
        return (int) entries.stream().map(Entry::path).distinct().count();
    }

    private static String severityTotals(List<Entry> entries) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (Entry e : entries) totals.merge(e.severity(), 1, Integer::sum);
        List<String> parts = new ArrayList<>();
        totals.forEach((severity, count) -> parts.add(count + " " + severity));
        return String.join(", ", parts);
    }

    /**
     * Per-file counts, files with the most errors first, then by total, then by name.
     */
    private static Map<String, String> perFileCounts(List<Entry> entries) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (Entry e : entries) {
            int[] c = counts.computeIfAbsent(e.path(), k -> new int[2]);
            if (SEVERITY_ERROR.equals(e.severity())) c[0]++;
            else c[1]++;
        }
        List<Map.Entry<String, int[]>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Comparator
            .comparingInt((Map.Entry<String, int[]> en) -> -en.getValue()[0])
            .thenComparingInt(en -> -(en.getValue()[0] + en.getValue()[1]))
            .thenComparing(Map.Entry::getKey));
        Map<String, String> result = new LinkedHashMap<>();
        for (var en : sorted) {
            int[] c = en.getValue();
            List<String> parts = new ArrayList<>();
            if (c[0] > 0) parts.add(c[0] + " ERROR");
            if (c[1] > 0) parts.add(c[1] + " other");
            result.put(en.getKey(), String.join(", ", parts));
        }
        return result;
    }
}
