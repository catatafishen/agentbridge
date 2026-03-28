package com.github.catatafishen.ideagentforcopilot.psi.tools.navigation;

import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.ListProjectFilesRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lists files in a project directory, optionally filtered by glob pattern.
 */
public final class ListProjectFilesTool extends NavigationTool {

    private static final String PARAM_DIRECTORY = "directory";
    private static final String PARAM_PATTERN = "pattern";
    private static final String PARAM_MIN_SIZE = "min_size";
    private static final String PARAM_MAX_SIZE = "max_size";
    private static final String PARAM_MODIFIED_AFTER = "modified_after";
    private static final String PARAM_MODIFIED_BEFORE = "modified_before";

    public ListProjectFilesTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "list_project_files";
    }

    @Override
    public @NotNull String displayName() {
        return "List Project Files";
    }

    @Override
    public @NotNull String description() {
        return "List files in a project directory, optionally filtered by glob pattern";
    }

    @Override
    public @NotNull String kind() {
        return "read";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {PARAM_DIRECTORY, TYPE_STRING, "Optional subdirectory to list (relative to project root)", ""},
            {PARAM_PATTERN, TYPE_STRING, "Optional glob pattern (e.g., '*.java', 'src/**/*.kt')", ""},
            {"sort", TYPE_STRING, "Sort order: 'name' (default, alphabetical), 'size' (largest first), 'modified' (most recently modified first)", ""},
            {PARAM_MIN_SIZE, TYPE_INTEGER, "Only include files at least this many bytes", ""},
            {PARAM_MAX_SIZE, TYPE_INTEGER, "Only include files at most this many bytes", ""},
            {PARAM_MODIFIED_AFTER, TYPE_STRING, "Only include files modified after this time. Accepted: \"5m\", \"2026-03-28\", \"2026-03-28 16:57:30\", \"2026-03-28T16:57:30Z\"", ""},
            {PARAM_MODIFIED_BEFORE, TYPE_STRING, "Only include files modified before this time. Same formats as modified_after.", ""}
        });
    }

    @Override
    public @NotNull Object resultRenderer() {
        return ListProjectFilesRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        String dir = args.has(PARAM_DIRECTORY) ? args.get(PARAM_DIRECTORY).getAsString() : "";
        String pattern = args.has(PARAM_PATTERN) ? args.get(PARAM_PATTERN).getAsString() : "";
        String sort = args.has("sort") ? args.get("sort").getAsString() : "name";
        long minSize = args.has(PARAM_MIN_SIZE) ? args.get(PARAM_MIN_SIZE).getAsLong() : -1;
        long maxSize = args.has(PARAM_MAX_SIZE) ? args.get(PARAM_MAX_SIZE).getAsLong() : -1;

        long modifiedAfter;
        long modifiedBefore;
        try {
            modifiedAfter = args.has(PARAM_MODIFIED_AFTER)
                ? com.github.catatafishen.ideagentforcopilot.psi.TimeArgParser.parseEpochMillis(args.get(PARAM_MODIFIED_AFTER).getAsString())
                : -1;
            modifiedBefore = args.has(PARAM_MODIFIED_BEFORE)
                ? com.github.catatafishen.ideagentforcopilot.psi.TimeArgParser.parseEpochMillis(args.get(PARAM_MODIFIED_BEFORE).getAsString())
                : -1;
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        return ApplicationManager.getApplication().runReadAction(
            (Computable<String>) () -> computeFilesList(dir, pattern, sort, minSize, maxSize, modifiedAfter, modifiedBefore));
    }

    private record FileEntry(String relPath, String tag, String typeName, long size, long timestamp) {
    }

    private String computeFilesList(String dir, String pattern, String sort,
                                    long minSize, long maxSize, long modifiedAfter, long modifiedBefore) {
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;

        List<FileEntry> entries = new ArrayList<>();
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        fileIndex.iterateContent(vf -> {
            if (vf.isDirectory()) return true;
            String relPath = relativize(basePath, vf.getPath());
            if (relPath == null) return true;
            if (!dir.isEmpty() && !relPath.startsWith(dir)) return true;
            if (!pattern.isEmpty() && ToolUtils.doesNotMatchGlob(relPath, pattern)) return true;
            long size = vf.getLength();
            long ts = vf.getTimeStamp();
            if (!matchesSizeAndDateFilters(size, ts, minSize, maxSize, modifiedAfter, modifiedBefore)) return true;
            String tag = resolveTag(fileIndex, vf);
            entries.add(new FileEntry(relPath, tag, ToolUtils.fileType(vf.getName()), size, ts));
            return entries.size() < 500;
        });

        if (entries.isEmpty()) return "No files found";

        Comparator<FileEntry> comparator = switch (sort) {
            case "size" -> Comparator.comparingLong(FileEntry::size).reversed();
            case "modified" -> Comparator.comparingLong(FileEntry::timestamp).reversed();
            default -> Comparator.comparing(FileEntry::relPath);
        };
        entries.sort(comparator);

        List<String> lines = new ArrayList<>(entries.size());
        for (FileEntry e : entries) {
            lines.add(String.format("%s [%s%s, %s, %s]",
                e.relPath(), e.tag(), e.typeName(),
                ToolUtils.formatFileSize(e.size()),
                ToolUtils.formatFileTimestamp(e.timestamp())));
        }
        return entries.size() + " files:\n" + String.join("\n", lines);
    }

    private static boolean matchesSizeAndDateFilters(long size, long ts,
                                                     long minSize, long maxSize,
                                                     long modifiedAfter, long modifiedBefore) {
        if (minSize >= 0 && size < minSize) return false;
        if (maxSize >= 0 && size > maxSize) return false;
        return (modifiedAfter < 0 || ts >= modifiedAfter)
            && (modifiedBefore < 0 || ts <= modifiedBefore);
    }

    private static String resolveTag(ProjectFileIndex fileIndex, VirtualFile vf) {
        if (fileIndex.isInGeneratedSources(vf)) {
            return fileIndex.isInTestSourceContent(vf) ? "generated-test " : "generated ";
        }
        if (fileIndex.isInTestSourceContent(vf)) return "test ";
        if (fileIndex.isInSourceContent(vf)) return "source ";
        return "";
    }
}
