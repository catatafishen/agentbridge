package com.github.catatafishen.ideagentforcopilot.psi.tools.navigation;

import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.SearchResultRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class SearchTextTool extends NavigationTool {

    private static final String PARAM_REGEX = "regex";
    private static final String PARAM_CASE_SENSITIVE = "case_sensitive";
    private static final String PARAM_MAX_RESULTS = "max_results";

    public SearchTextTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "search_text";
    }

    @Override
    public @NotNull String displayName() {
        return "Search Text";
    }

    @Override
    public @NotNull String description() {
        return "Search for text or regex patterns across project files using IntelliJ's editor buffers";
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
            {"query", TYPE_STRING, "Text or regex pattern to search for"},
            {"file_pattern", TYPE_STRING, "Optional glob pattern to filter files (e.g., '*.kt', '*.java')", ""},
            {PARAM_REGEX, TYPE_BOOLEAN, "If true, treat query as regex. Default: false (literal match)"},
            {PARAM_CASE_SENSITIVE, TYPE_BOOLEAN, "Case-sensitive search. Default: true"},
            {PARAM_MAX_RESULTS, TYPE_INTEGER, "Maximum results to return (default: 100)"}
        }, "query");
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SearchResultRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        if (!args.has(PARAM_QUERY) || args.get(PARAM_QUERY).isJsonNull())
            return "Error: 'query' parameter is required";
        String query = args.get(PARAM_QUERY).getAsString();
        String filePattern = args.has(PARAM_FILE_PATTERN) ? args.get(PARAM_FILE_PATTERN).getAsString() : "";
        boolean isRegex = args.has(PARAM_REGEX) && args.get(PARAM_REGEX).getAsBoolean();
        boolean caseSensitive = !args.has(PARAM_CASE_SENSITIVE) || args.get(PARAM_CASE_SENSITIVE).getAsBoolean();
        int maxResults = args.has(PARAM_MAX_RESULTS) ? args.get(PARAM_MAX_RESULTS).getAsInt() : 100;

        showSearchFeedback("🔍 Searching text: " + query);
        String result = ReadAction.compute(() ->
            performSearch(query, filePattern, isRegex, caseSensitive, maxResults));
        showSearchFeedback("✓ Text search complete: " + query);
        return result;
    }

    private String performSearch(String query, String filePattern, boolean isRegex, boolean caseSensitive, int maxResults) {
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;

        Pattern pattern = compileSearchPattern(query, isRegex, caseSensitive);
        if (pattern == null) return "Error: invalid regex: " + query;

        List<String> results = new ArrayList<>();
        int[] skippedLarge = {0};
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        fileIndex.iterateContent(vf -> {
            if (vf.isDirectory()) return true;
            String relPath = relativize(basePath, vf.getPath());
            if (relPath == null) return true;
            if (!filePattern.isEmpty() && ToolUtils.doesNotMatchGlob(relPath, filePattern)) return true;
            if (vf.getLength() > 1_000_000) {
                skippedLarge[0]++;
                return results.size() < maxResults;
            }
            searchFileForPattern(vf, pattern, relPath, results, maxResults);
            return results.size() < maxResults;
        });

        StringBuilder sb = new StringBuilder();
        if (results.isEmpty()) {
            sb.append("No matches found for '").append(query).append("'");
        } else {
            sb.append(results.size()).append(" matches:\n").append(String.join("\n", results));
        }
        if (skippedLarge[0] > 0) {
            sb.append("\n(").append(skippedLarge[0]).append(" file(s) >1 MB skipped)");
        }
        return sb.toString();
    }

    private static void searchFileForPattern(VirtualFile vf, Pattern pattern,
                                             String relPath, List<String> results, int maxResults) {
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return;
        String text = doc.getText();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && results.size() < maxResults) {
            int line = doc.getLineNumber(matcher.start()) + 1;
            String lineText = ToolUtils.getLineText(doc, line - 1);
            results.add(String.format(FORMAT_LINE_REF, relPath, line, lineText));
        }
    }

    private static Pattern compileSearchPattern(String query, boolean isRegex, boolean caseSensitive) {
        try {
            int flags = isRegex ? 0 : Pattern.LITERAL;
            if (!caseSensitive) flags |= Pattern.CASE_INSENSITIVE;
            return Pattern.compile(query, flags);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }
}
