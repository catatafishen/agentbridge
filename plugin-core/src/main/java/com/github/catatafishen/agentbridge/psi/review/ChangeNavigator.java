package com.github.catatafishen.agentbridge.psi.review;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Phase 5 — cross-file change navigation for the agent edit review session.
 * <p>
 * Provides a deterministic, alphabetically-ordered view of all outstanding changes
 * across every file in the current review session. Given a caret position, it picks
 * the next (or previous) change, wrapping to the first/last file once the ends are
 * reached so users can cycle through everything with a single keystroke.
 * <p>
 * The core matching logic ({@link #findNext} and {@link #findPrevious}) is pure so it
 * can be unit-tested without a Project or VFS.
 */
public final class ChangeNavigator {

    /**
     * A single navigable change: the path that owns it and the range itself.
     */
    public record Location(@NotNull String path, @NotNull ChangeRange range) {
    }

    private ChangeNavigator() {
    }

    /**
     * Snapshots the current session's changes grouped by path, with each file's
     * ranges in document order. Files with no outstanding changes are omitted.
     */
    public static @NotNull NavigableMap<String, List<ChangeRange>> collectOrderedChanges(
        @NotNull Project project) {

        NavigableMap<String, List<ChangeRange>> result = new TreeMap<>();
        AgentEditSession session = AgentEditSession.getInstance(project);
        if (!session.isActive()) return result;

        for (String path : session.getModifiedFilePaths()) {
            VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(path);
            if (vf == null || !vf.isValid()) continue;
            List<ChangeRange> ranges = session.computeRanges(vf);
            if (!ranges.isEmpty()) {
                result.put(path, ranges);
            }
        }
        return result;
    }

    /**
     * Finds the change immediately after the caret.
     * <p>
     * Search order:
     * <ol>
     *   <li>Ranges in {@code currentPath} whose {@code startLine > currentLine}</li>
     *   <li>First range of the next path alphabetically</li>
     *   <li>First range of the first path (wrap-around)</li>
     * </ol>
     * Returns {@link Optional#empty()} only when there are no changes anywhere.
     */
    public static @NotNull Optional<Location> findNext(
        @NotNull NavigableMap<String, List<ChangeRange>> byPath,
        @Nullable String currentPath,
        int currentLine) {

        if (byPath.isEmpty()) return Optional.empty();

        if (currentPath != null) {
            List<ChangeRange> inCurrent = byPath.get(currentPath);
            if (inCurrent != null) {
                for (ChangeRange r : inCurrent) {
                    if (r.startLine() > currentLine) {
                        return Optional.of(new Location(currentPath, r));
                    }
                }
            }
        }

        Map.Entry<String, List<ChangeRange>> nextEntry = currentPath == null
            ? byPath.firstEntry()
            : byPath.higherEntry(currentPath);
        if (nextEntry == null) nextEntry = byPath.firstEntry();

        if (nextEntry != null && !nextEntry.getValue().isEmpty()) {
            return Optional.of(new Location(nextEntry.getKey(), nextEntry.getValue().get(0)));
        }
        return Optional.empty();
    }

    /**
     * Finds the change immediately before the caret.
     * <p>
     * Mirror of {@link #findNext}: searches backwards in the current file, then the
     * previous file alphabetically, wrapping to the last file if needed.
     */
    public static @NotNull Optional<Location> findPrevious(
        @NotNull NavigableMap<String, List<ChangeRange>> byPath,
        @Nullable String currentPath,
        int currentLine) {

        if (byPath.isEmpty()) return Optional.empty();

        if (currentPath != null) {
            List<ChangeRange> inCurrent = byPath.get(currentPath);
            if (inCurrent != null) {
                ChangeRange best = null;
                for (ChangeRange r : inCurrent) {
                    if (r.startLine() < currentLine) {
                        best = r;
                    } else {
                        break;
                    }
                }
                if (best != null) return Optional.of(new Location(currentPath, best));
            }
        }

        Map.Entry<String, List<ChangeRange>> prevEntry = currentPath == null
            ? byPath.lastEntry()
            : byPath.lowerEntry(currentPath);
        if (prevEntry == null) prevEntry = byPath.lastEntry();

        if (prevEntry != null && !prevEntry.getValue().isEmpty()) {
            List<ChangeRange> list = prevEntry.getValue();
            return Optional.of(new Location(prevEntry.getKey(), list.get(list.size() - 1)));
        }
        return Optional.empty();
    }

    /**
     * Returns the change range at or containing the given line in {@code path}, if any.
     * Used by Phase 4 (inline diff popup) to determine which range to show.
     */
    public static @NotNull Optional<ChangeRange> findEnclosing(
        @NotNull NavigableMap<String, List<ChangeRange>> byPath,
        @NotNull String path,
        int line) {

        List<ChangeRange> ranges = byPath.get(path);
        if (ranges == null) return Optional.empty();
        for (ChangeRange r : ranges) {
            int end = r.type() == ChangeType.DELETED
                ? r.startLine() + 1
                : r.endLine();
            if (line >= r.startLine() && line < Math.max(end, r.startLine() + 1)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
