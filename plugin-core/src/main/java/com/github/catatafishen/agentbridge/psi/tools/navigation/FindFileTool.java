package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.codeStyle.MinusculeMatcher;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class FindFileTool extends NavigationTool {

    private static final String PARAM_QUERY = "query";
    private static final String PARAM_MAX_RESULTS = "max_results";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;
    private static final int NAME_BATCH_SIZE = 25;
    // Safety bound for very permissive queries (single character, path patterns) on huge projects.
    private static final int MAX_NAMES = 100_000;
    private static final Comparator<FileMatch> FILE_ORDER = Comparator
        .comparingInt(FileMatch::score).reversed()
        .thenComparing(FileMatch::path);

    public FindFileTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "find_file";
    }

    @Override
    public @NotNull String displayName() {
        return "Find File";
    }

    @Override
    public @NotNull String description() {
        return "Find files by name using IntelliJ's filename index. Supports substring, camel-case, and wildcard matching.";
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
    public boolean requiresIndex() {
        return true;
    }

    @Override
    public long indexWaitTimeoutMs() {
        return 300_000L; // 5 minutes — initial project indexing can take well over 30 s
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return FileCategory.withScopeParam(schema(
            Param.required(PARAM_QUERY, TYPE_STRING, "File name query. Supports substring, camel-case (e.g. 'US' for UserService), and wildcard patterns."),
            Param.optional(PARAM_MAX_RESULTS, TYPE_INTEGER, "Maximum files to return (default 50, max 500)", String.valueOf(DEFAULT_LIMIT))
        ));
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        if (!args.has(PARAM_QUERY) || args.get(PARAM_QUERY).isJsonNull())
            return ToolUtils.ERROR_PREFIX + "'query' parameter is required";
        String query = args.get(PARAM_QUERY).getAsString().trim();
        if (query.isEmpty())
            return ToolUtils.ERROR_PREFIX + "'query' parameter is required";
        final String finalQuery = query;
        int limit = readLimit(args);
        FileCategory.Selection selection = FileCategory.parseScope(args);
        if (selection.error() != null) return selection.error();
        // NonBlockingReadAction: iterating the filename index can hold the read lock for a long time
        // on large projects. runReadAction() would block ALL write actions (EDT, indexing, daemon
        // analysis) for the entire duration and cause "IDE not responding" freezes.
        // executeSynchronously() yields to write actions when they need to run, then restarts the
        // search (computeMatches creates fresh collections, so restart is safe).
        return ReadAction.nonBlocking(() -> computeMatches(finalQuery, limit, selection))
            .executeSynchronously();
    }

    private int readLimit(JsonObject args) {
        if (args.has(PARAM_MAX_RESULTS) && !args.get(PARAM_MAX_RESULTS).isJsonNull())
            return Math.clamp(args.get(PARAM_MAX_RESULTS).getAsInt(), 1, MAX_LIMIT);
        return DEFAULT_LIMIT;
    }

    private String computeMatches(String query, int limit, FileCategory.Selection selection) {
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;

        GlobalSearchScope scope = resolveScope(selection);
        MatchPredicate predicate = MatchPredicate.create(query);
        Map<String, FileMatch> matchesByPath = collectMatches(scope, predicate, basePath, limit);

        if (matchesByPath.isEmpty()) return "No files found" + FileCategory.skippedNote(selection);

        List<String> lines = matchesByPath.values().stream()
            .sorted(FILE_ORDER)
            .limit(limit)
            .map(FileMatch::format)
            .toList();
        return lines.size() + " files:\n" + String.join("\n", lines);
    }

    /**
     * Looks files up in small batches in rank order instead of one huge unordered lookup: the index returns
     * names and files in no meaningful order, so truncating that unordered stream would drop the best matches
     * (e.g. an exact name) before ranking. One lookup per name was the freeze culprit on large projects, hence
     * batches rather than single names.
     */
    private Map<String, FileMatch> collectMatches(GlobalSearchScope scope, MatchPredicate predicate,
                                                  String basePath, int limit) {
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        int collectFileLimit = Math.clamp(Math.multiplyExact(limit, 5), DEFAULT_LIMIT, MAX_LIMIT);
        Map<String, FileMatch> matchesByPath = new LinkedHashMap<>();
        List<String> rankedNames = rankedMatchingNames(predicate, scope);
        for (int from = 0; from < rankedNames.size(); from += NAME_BATCH_SIZE) {
            if (matchesByPath.size() >= collectFileLimit
                || canStopBeforeBatch(matchesByPath, limit, predicate.score(rankedNames.get(from)))) {
                break;
            }
            Set<String> batch = new LinkedHashSet<>(
                rankedNames.subList(from, Math.min(rankedNames.size(), from + NAME_BATCH_SIZE)));
            FilenameIndex.processFilesByNames(batch, false, scope, null, file -> {
                // The scope already restricts to the selected categories; contains() re-check guards
                // against index entries outside it.
                if (!file.isDirectory() && scope.contains(file)) {
                    addFileMatch(file, ProjectFileScanner.categorize(fileIndex, file), basePath, predicate,
                        matchesByPath);
                }
                return true;
            });
        }
        return matchesByPath;
    }

    /**
     * Returns all indexed file names accepted by the predicate, best match first. Ties are broken by name so
     * the order is the same on every call, independent of the index iteration order.
     */
    private static List<String> rankedMatchingNames(MatchPredicate predicate, GlobalSearchScope scope) {
        List<ScoredName> names = new ArrayList<>();
        FilenameIndex.processAllFileNames(name -> {
            if (predicate.matchesName(name)) {
                names.add(new ScoredName(name, predicate.score(name)));
            }
            return names.size() < MAX_NAMES;
        }, scope, null);
        names.sort(Comparator.comparingInt(ScoredName::score).reversed().thenComparing(ScoredName::name));
        return names.stream().map(ScoredName::name).toList();
    }

    /**
     * True once {@code limit} files are collected and every remaining name scores strictly lower than the
     * weakest of the best {@code limit} files, so no further batch can change the final result.
     */
    private static boolean canStopBeforeBatch(Map<String, FileMatch> matchesByPath, int limit, int nextScore) {
        if (matchesByPath.size() < limit) return false;
        int limitThScore = matchesByPath.values().stream()
            .map(FileMatch::score)
            .sorted(Comparator.reverseOrder())
            .skip(limit - 1L)
            .findFirst()
            .orElse(Integer.MIN_VALUE);
        return nextScore < limitThScore;
    }

    private record ScoredName(String name, int score) {
    }

    private void addFileMatch(VirtualFile file, FileCategory category, String basePath,
                              MatchPredicate predicate, Map<String, FileMatch> matchesByPath) {
        String path = safeRelativize(basePath, file.getPath());
        if (!predicate.matchesPath(path, file.getPath())) return;

        String directory = file.getParent() == null ? "" : safeRelativize(basePath, file.getParent().getPath());
        matchesByPath.putIfAbsent(path,
            new FileMatch(file.getName(), path, directory, category, predicate.score(file.getName())));
    }

    private record FileMatch(String name, String path, String directory, FileCategory category, int score) {
        private String format() {
            return directory.isEmpty()
                ? path + " [" + name + ", " + category.id() + "]"
                : path + " [" + name + ", " + category.id() + ", dir=" + directory + "]";
        }
    }

    private record MatchPredicate(
        String rawQuery,
        String lowerQuery,
        MinusculeMatcher matcher,
        Pattern globPattern,
        boolean pathPattern,
        String lowerPathQuery) {

        private static MatchPredicate create(String query) {
            Pattern glob = hasWildcard(query) ? ToolUtils.compileGlob(query) : null;
            boolean isPath = query.indexOf('/') >= 0 || query.indexOf('\\') >= 0;
            // A path query without wildcards (e.g. "src/Foo.java") is matched by path suffix/infix; its file
            // name part drives the name lookup and ranking. Without this, every file would match.
            String normalized = query.replace('\\', '/');
            String nameQuery = isPath && glob == null ? nameFromPath(normalized) : query;
            return new MatchPredicate(
                nameQuery,
                nameQuery.toLowerCase(Locale.ROOT),
                PlatformApiCompat.buildFilenameMatcher("*" + nameQuery),
                glob,
                isPath,
                isPath && glob == null ? normalized.toLowerCase(Locale.ROOT) : null
            );
        }

        private static boolean hasWildcard(String query) {
            return query.indexOf('*') >= 0 || query.indexOf('?') >= 0;
        }

        private boolean matchesName(String name) {
            if (pathPattern && globPattern != null) return true;
            return globPattern != null
                ? globPattern.matcher(name).matches()
                : name.toLowerCase(Locale.ROOT).contains(lowerQuery) || matcher.matches(name);
        }

        private boolean matchesPath(String path, String absolutePath) {
            if (lowerPathQuery != null)
                return absolutePath.replace('\\', '/').toLowerCase(Locale.ROOT).contains(lowerPathQuery);
            return globPattern == null || globPattern.matcher(path).matches() || globPattern.matcher(nameFromPath(path)).matches();
        }

        private int score(String name) {
            if (name.equals(rawQuery)) return Integer.MAX_VALUE;
            if (name.equalsIgnoreCase(rawQuery)) return Integer.MAX_VALUE - 1;
            return matcher.matchingDegree(name);
        }

        private static String nameFromPath(String path) {
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        }
    }
}
