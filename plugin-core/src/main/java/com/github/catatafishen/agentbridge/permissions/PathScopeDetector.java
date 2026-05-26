package com.github.catatafishen.agentbridge.permissions;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Set;

/**
 * Derives a {@link PathScope} for a tool call.
 * <p>
 * Stateless and framework-light: callers extract the relevant argument values
 * (path string, search scope string) from their own argument representation
 * and pass them in. Keeps this class testable without JSON or IntelliJ fixtures.
 * <p>
 * Resolution rules:
 * <ul>
 *   <li>Path-bearing tools ({@code read_file}, {@code list_directory_tree}, …) —
 *       use {@link #detectByPath(String, String, String)} with the project root
 *       and any attached external directory roots.</li>
 *   <li>Search-scope tools ({@code search_text}, {@code search_symbols}, …) —
 *       use {@link #detectBySearchScope(String)} with the {@code scope} argument
 *       value ({@code "project"}, {@code "libraries"}, {@code "all"}, …).</li>
 *   <li>{@code attach_external_dir} — always {@link PathScope#OUTSIDE_PROJECT}.</li>
 *   <li>Other tools — pass {@link PathScope#NOT_APPLICABLE} directly.</li>
 * </ul>
 */
public final class PathScopeDetector {

    /** Tools whose scope depends on a path argument and the project root. */
    public static final Set<String> PATH_BEARING_TOOLS = Set.of(
            "read_file",
            "get_file_outline",
            "find_file",
            "list_directory_tree",
            "list_project_files"
    );

    /** Tools whose scope depends on a {@code scope} string argument. */
    public static final Set<String> SEARCH_SCOPE_TOOLS = Set.of(
            "search_text",
            "search_symbols",
            "find_references",
            "find_implementations"
    );

    /** Tools that always operate outside the project. */
    public static final Set<String> ALWAYS_OUTSIDE_TOOLS = Set.of(
            "attach_external_dir"
    );

    private PathScopeDetector() {
        // utility
    }

    /**
     * Convenience: classify a tool ID without inspecting arguments. Returns
     * {@link PathScope#NOT_APPLICABLE} for tools without a scope distinction,
     * {@link PathScope#OUTSIDE_PROJECT} for {@link #ALWAYS_OUTSIDE_TOOLS}, and
     * {@code null} for tools whose scope can only be determined from arguments —
     * callers must then invoke {@link #detectByPath} or {@link #detectBySearchScope}.
     */
    public static @Nullable PathScope classifyTool(String toolId) {
        if (ALWAYS_OUTSIDE_TOOLS.contains(toolId)) {
            return PathScope.OUTSIDE_PROJECT;
        }
        if (PATH_BEARING_TOOLS.contains(toolId) || SEARCH_SCOPE_TOOLS.contains(toolId)) {
            return null;
        }
        return PathScope.NOT_APPLICABLE;
    }

    /**
     * Determine the scope for a path-bearing tool call.
     *
     * @param path             the {@code path} / {@code directory} argument (may be null or blank — treated as inside)
     * @param projectRoot      absolute project root, or null if no project is open
     * @param attachedRootsCsv comma-separated absolute paths of attached external directories
     *                         (treated as inside-project). May be null or blank.
     */
    public static PathScope detectByPath(@Nullable String path,
                                         @Nullable String projectRoot,
                                         @Nullable String attachedRootsCsv) {
        if (path == null || path.isBlank()) {
            return PathScope.INSIDE_PROJECT;
        }
        Path absolute = absolutize(path, projectRoot);
        if (absolute == null) {
            // Relative path with no project root — treat as inside.
            return PathScope.INSIDE_PROJECT;
        }
        if (projectRoot != null && !projectRoot.isBlank() && isUnder(absolute, projectRoot)) {
            return PathScope.INSIDE_PROJECT;
        }
        if (attachedRootsCsv != null && !attachedRootsCsv.isBlank()) {
            for (String root : attachedRootsCsv.split(",")) {
                String trimmed = root.trim();
                if (!trimmed.isEmpty() && isUnder(absolute, trimmed)) {
                    return PathScope.INSIDE_PROJECT;
                }
            }
        }
        return PathScope.OUTSIDE_PROJECT;
    }

    /**
     * Determine the scope for a search-scope tool call.
     *
     * @param scopeArg the {@code scope} argument value ({@code "project"}, {@code "production"},
     *                 {@code "tests"}, {@code "libraries"}, {@code "all"}). Null or unrecognised
     *                 values default to {@link PathScope#INSIDE_PROJECT}.
     */
    public static PathScope detectBySearchScope(@Nullable String scopeArg) {
        if (scopeArg == null) {
            return PathScope.INSIDE_PROJECT;
        }
        return switch (scopeArg.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "libraries", "all" -> PathScope.OUTSIDE_PROJECT;
            default -> PathScope.INSIDE_PROJECT;
        };
    }

    private static @Nullable Path absolutize(String path, @Nullable String projectRoot) {
        try {
            Path p = Path.of(path);
            if (p.isAbsolute()) {
                return p.normalize();
            }
            if (projectRoot != null && !projectRoot.isBlank()) {
                return Path.of(projectRoot).resolve(p).normalize();
            }
            return null;
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    private static boolean isUnder(Path absolute, String rootPath) {
        try {
            Path root = Path.of(rootPath).toAbsolutePath().normalize();
            return absolute.startsWith(root);
        } catch (java.nio.file.InvalidPathException e) {
            return false;
        }
    }
}
