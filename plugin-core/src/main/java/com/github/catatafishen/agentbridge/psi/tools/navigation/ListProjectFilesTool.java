package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.ListProjectFilesRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lists files in a project directory, optionally filtered by glob pattern.
 */
public final class ListProjectFilesTool extends NavigationTool {

    private static final String PARAM_DIRECTORY = "directory";
    private static final String PARAM_FILE_PATTERN = "file_pattern";
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
        return """
            List files in a project directory. All parameters are optional — call with no \
            arguments to list all project files. This is the recommended first step when \
            exploring an unfamiliar project, as it reveals the actual languages and structure \
            before filtering. Use 'file_pattern' only once you already know the file types present.""";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return FileCategory.withScopeParam(schema(
            Param.optional(PARAM_DIRECTORY, TYPE_STRING, "Optional subdirectory to list (relative to project root)", ""),
            Param.optional(PARAM_FILE_PATTERN, TYPE_STRING, "Optional glob pattern (e.g., '*.java', 'src/**/*.kt'). Omit when exploring an unfamiliar project — guessing a pattern for the wrong language returns nothing", ""),
            Param.optional("sort", TYPE_STRING, "Sort order: 'name' (default, alphabetical), 'size' (largest first), 'modified' (most recently modified first)", ""),
            Param.optional(PARAM_MIN_SIZE, TYPE_INTEGER, "Only include files at least this many bytes", ""),
            Param.optional(PARAM_MAX_SIZE, TYPE_INTEGER, "Only include files at most this many bytes", ""),
            Param.optional(PARAM_MODIFIED_AFTER, TYPE_STRING, "Only include files modified after this time. Accepted: \"5m\", \"2026-03-28\", \"2026-03-28 16:57:30\", \"2026-03-28T16:57:30Z\"", ""),
            Param.optional(PARAM_MODIFIED_BEFORE, TYPE_STRING, "Only include files modified before this time. Same formats as modified_after.", "")
        ));
    }

    @Override
    public @NotNull Object resultRenderer() {
        return ListProjectFilesRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        String dirRaw = args.has(PARAM_DIRECTORY) ? args.get(PARAM_DIRECTORY).getAsString() : "";
        // "." and "./" mean "project root" — resolve to empty string so no filtering
        // is applied (shows all project files). Without this, the prefix filter
        // relPath.startsWith(".") would never match any project-relative path.
        String dir = (".".equals(dirRaw) || "./".equals(dirRaw)) ? "" : dirRaw;
        final String pattern;
        pattern = (args.has(PARAM_FILE_PATTERN) && !args.get(PARAM_FILE_PATTERN).isJsonNull())
            ? args.get(PARAM_FILE_PATTERN).getAsString()
            : "";
        String sort = args.has("sort") ? args.get("sort").getAsString() : "name";
        long minSize = args.has(PARAM_MIN_SIZE) ? args.get(PARAM_MIN_SIZE).getAsLong() : -1;
        long maxSize = args.has(PARAM_MAX_SIZE) ? args.get(PARAM_MAX_SIZE).getAsLong() : -1;

        long modifiedAfter;
        long modifiedBefore;
        try {
            modifiedAfter = args.has(PARAM_MODIFIED_AFTER)
                ? com.github.catatafishen.agentbridge.psi.TimeArgParser.parseEpochMillis(args.get(PARAM_MODIFIED_AFTER).getAsString())
                : -1;
            modifiedBefore = args.has(PARAM_MODIFIED_BEFORE)
                ? com.github.catatafishen.agentbridge.psi.TimeArgParser.parseEpochMillis(args.get(PARAM_MODIFIED_BEFORE).getAsString())
                : -1;
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        FileCategory.Selection scope = FileCategory.parseScope(args);
        if (scope.error() != null) return scope.error();
        if (!scope.includesProject()) {
            return "Error: list_project_files lists project files only. Use find_file with scope=[\"libraries\"] "
                + "to look up dependency files by name.";
        }

        // Cast is required: without it, the call is ambiguous between Computable<T> and
        // ThrowableComputable<T,E> overloads of runReadAction, causing a compile error.
        // The IntelliJ daemon incorrectly flags it as redundant (false positive).
        return ApplicationManager.getApplication().runReadAction(
            (Computable<String>) () -> computeFilesList(dir, pattern, sort,
                new SizeDateFilter(minSize, maxSize, modifiedAfter, modifiedBefore), scope));
    }

    private record FileEntry(String relPath, String tag, String typeName, long size, long timestamp) {
    }

    /**
     * Size and timestamp bounds; a negative value means "no bound". Package-private for tests.
     */
    record SizeDateFilter(long minSize, long maxSize, long modifiedAfter, long modifiedBefore) {
        boolean matches(long size, long ts) {
            if (minSize >= 0 && size < minSize) return false;
            if (maxSize >= 0 && size > maxSize) return false;
            return (modifiedAfter < 0 || ts >= modifiedAfter)
                && (modifiedBefore < 0 || ts <= modifiedBefore);
        }
    }

    private String computeFilesList(String dir, String pattern, String sort, SizeDateFilter filter,
                                    FileCategory.Selection scope) {
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;

        List<FileEntry> entries = new ArrayList<>();
        var compiledGlob = pattern.isEmpty() ? null : ToolUtils.compileGlob(pattern);
        ProjectFileScanner.scan(project, scope, (vf, category) -> {
            String relPath = relativize(basePath, vf.getPath());
            if (relPath == null) return true;
            if (!isUnderDirectory(relPath, dir)) return true;
            if (!pattern.isEmpty() && ToolUtils.doesNotMatchGlob(relPath, pattern, compiledGlob)) return true;
            long size = vf.getLength();
            long ts = vf.getTimeStamp();
            if (!filter.matches(size, ts)) return true;
            entries.add(new FileEntry(relPath, category.id() + " ", ToolUtils.fileType(vf.getName()), size, ts));
            return entries.size() < 500;
        });

        String excludedSection = formatExcludedDirs(excludedDirsUnder(basePath, dir));
        if (entries.isEmpty()) return "No files found" + excludedSection + FileCategory.skippedNote(scope);

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
        return entries.size() + " files:\n" + String.join("\n", lines) + excludedSection;
    }

    /**
     * Project-relative paths of excluded roots inside {@code dir} (or the whole project when empty).
     */
    private List<String> excludedDirsUnder(String basePath, String dir) {
        List<String> out = new ArrayList<>();
        for (var root : ProjectFileScanner.excludedRoots(project)) {
            String rel = relativize(basePath, root.getPath());
            if (rel != null && isUnderDirectory(rel, dir)) out.add(rel);
        }
        return out;
    }

    /**
     * True when {@code relPath} is {@code dir} itself or lies inside it. Matches whole path segments,
     * so "src" does not match "src-old/x". An empty {@code dir} matches everything.
     */
    static boolean isUnderDirectory(String relPath, String dir) {
        String d = dir.replace('\\', '/');
        while (d.endsWith("/")) d = d.substring(0, d.length() - 1);
        if (d.isEmpty()) return true;
        return relPath.equals(d) || relPath.startsWith(d + "/");
    }

    /**
     * Lists excluded directories (names only, contents are not read) so the agent knows they exist.
     * Empty when there are none.
     */
    static String formatExcludedDirs(List<String> relPaths) {
        if (relPaths.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\nExcluded directories (not indexed, contents not listed):");
        for (String p : relPaths) sb.append("\n").append(p).append("/ [excluded]");
        return sb.append("\n").append(FileCategory.EXCLUDED_HINT).toString();
    }
}
