package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.codeStyle.MinusculeMatcher;
import com.intellij.psi.codeStyle.NameUtil;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class FindFileTool extends NavigationTool {

    private static final String PARAM_LIMIT = "limit";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

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
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_QUERY, TYPE_STRING, "File name query. Supports substring, camel-case (e.g. 'US' for UserService), and wildcard patterns."),
            Param.optional(PARAM_SCOPE, TYPE_STRING, SCOPE_DESCRIPTION, SCOPE_PROJECT),
            Param.optional(PARAM_LIMIT, TYPE_INTEGER, "Maximum files to return (default 50, max 500)", String.valueOf(DEFAULT_LIMIT))
        );
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        if (!args.has(PARAM_QUERY) || args.get(PARAM_QUERY).isJsonNull()) {
            return ToolUtils.ERROR_PREFIX + "'query' parameter is required";
        }
        String query = args.get(PARAM_QUERY).getAsString().trim();
        if (query.isEmpty()) {
            return ToolUtils.ERROR_PREFIX + "Query cannot be empty";
        }
        int limit = readLimit(args);
        String scopeName = readScopeParam(args);
        return ApplicationManager.getApplication().runReadAction(
            (Computable<String>) () -> computeMatches(query, scopeName, limit));
    }

    private int readLimit(JsonObject args) {
        if (!args.has(PARAM_LIMIT) || args.get(PARAM_LIMIT).isJsonNull()) {
            return DEFAULT_LIMIT;
        }
        int requested = args.get(PARAM_LIMIT).getAsInt();
        return Math.clamp(requested, 1, MAX_LIMIT);
    }

    private String computeMatches(String query, String scopeName, int limit) {
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;

        GlobalSearchScope scope = resolveScope(scopeName);
        MatchPredicate predicate = MatchPredicate.create(query);
        int collectLimit = Math.clamp(Math.multiplyExact(limit, 5), DEFAULT_LIMIT, MAX_LIMIT);
        Map<String, FileMatch> matchesByPath = new LinkedHashMap<>();
        FilenameIndex.processAllFileNames(
            name -> processFilename(name, basePath, scope, predicate, matchesByPath, collectLimit),
            scope,
            null
        );

        if (matchesByPath.isEmpty()) return "No files found";

        List<FileMatch> matches = new ArrayList<>(matchesByPath.values());
        matches.sort(Comparator
            .comparingInt(FileMatch::score).reversed()
            .thenComparing(FileMatch::path));
        List<String> lines = matches.stream()
            .limit(limit)
            .map(FileMatch::format)
            .toList();
        return lines.size() + " files:\n" + String.join("\n", lines);
    }

    private boolean processFilename(String name, String basePath, GlobalSearchScope scope,
                                    MatchPredicate predicate, Map<String, FileMatch> matchesByPath, int limit) {
        if (!predicate.matchesName(name)) return true;

        return FilenameIndex.processFilesByNames(Set.of(name), false, scope, null, file -> {
            if (matchesByPath.size() >= limit) return false;
            addFileMatch(file, basePath, scope, predicate, matchesByPath);
            return matchesByPath.size() < limit;
        });
    }

    private void addFileMatch(VirtualFile file, String basePath, GlobalSearchScope scope,
                              MatchPredicate predicate, Map<String, FileMatch> matchesByPath) {
        if (file.isDirectory() || !scope.contains(file)) return;

        String path = safeRelativize(basePath, file.getPath());
        if (!predicate.matchesPath(path)) return;

        String directory = file.getParent() == null ? "" : safeRelativize(basePath, file.getParent().getPath());
        matchesByPath.putIfAbsent(path, new FileMatch(file.getName(), path, directory, predicate.score(file.getName())));
    }

    private record FileMatch(String name, String path, String directory, int score) {
        private String format() {
            return directory.isEmpty()
                ? path + " [" + name + "]"
                : path + " [" + name + ", dir=" + directory + "]";
        }
    }

    private record MatchPredicate(
        String rawQuery,
        String lowerQuery,
        MinusculeMatcher matcher,
        Pattern globPattern,
        boolean pathPattern) {

        private static MatchPredicate create(String query) {
            Pattern glob = hasWildcard(query) ? ToolUtils.compileGlob(query) : null;
            return new MatchPredicate(
                query,
                query.toLowerCase(Locale.ROOT),
                NameUtil.buildMatcher("*" + query, NameUtil.MatchingCaseSensitivity.NONE),
                glob,
                query.indexOf('/') >= 0 || query.indexOf('\\') >= 0
            );
        }

        private static boolean hasWildcard(String query) {
            return query.indexOf('*') >= 0 || query.indexOf('?') >= 0;
        }

        private boolean matchesName(String name) {
            if (pathPattern) return true;
            return globPattern != null
                ? globPattern.matcher(name).matches()
                : name.toLowerCase(Locale.ROOT).contains(lowerQuery) || matcher.matches(name);
        }

        private boolean matchesPath(String path) {
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
