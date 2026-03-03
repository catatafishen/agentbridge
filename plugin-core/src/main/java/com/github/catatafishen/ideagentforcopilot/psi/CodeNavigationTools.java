package com.github.catatafishen.ideagentforcopilot.psi;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.UsageSearchContext;
import com.intellij.psi.search.searches.ReferencesSearch;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Handles code navigation tool calls: search_symbols, get_file_outline,
 * find_references, and list_project_files.
 */
class CodeNavigationTools extends AbstractToolHandler {

    private static final String ERROR_NO_PROJECT_PATH = "No project base path";
    private static final String PARAM_SYMBOL = "symbol";
    private static final String PARAM_FILE_PATTERN = "file_pattern";
    private static final String FORMAT_LOCATION = "%s:%d [%s] %s";
    private static final String FORMAT_LINE_REF = "%s:%d: %s";
    private static final String PARAM_QUERY = "query";

    CodeNavigationTools(Project project) {
        super(project);
        register("search_symbols", this::searchSymbols);
        register("get_file_outline", this::getFileOutline);
        if (isPluginInstalled("com.intellij.modules.java")) {
            register("get_class_outline", this::getClassOutline);
        }
        register("find_references", this::findReferences);
        register("list_project_files", this::listProjectFiles);
        register("search_text", this::searchText);
    }

    // ---- list_project_files ----

    String listProjectFiles(JsonObject args) {
        String dir = args.has("directory") ? args.get("directory").getAsString() : "";
        String pattern = args.has("pattern") ? args.get("pattern").getAsString() : "";
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> computeProjectFilesList(dir, pattern));
    }

    String computeProjectFilesList(String dir, String pattern) {
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;

        List<String> files = new ArrayList<>();
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        fileIndex.iterateContent(vf -> {
            if (vf.isDirectory()) return true;
            String relPath = relativize(basePath, vf.getPath());
            if (relPath == null) return true;
            if (!dir.isEmpty() && !relPath.startsWith(dir)) return true;
            if (!pattern.isEmpty() && ToolUtils.doesNotMatchGlob(vf.getName(), pattern)) return true;
            String tag = fileIndex.isExcluded(vf) ? "excluded "
                : fileIndex.isInGeneratedSources(vf) ? "generated "
                : fileIndex.isInTestSourceContent(vf) ? "test " : "";
            files.add(String.format("%s [%s%s]", relPath, tag, ToolUtils.fileType(vf.getName())));
            return files.size() < 500;
        });

        if (files.isEmpty()) return "No files found";
        Collections.sort(files);
        return files.size() + " files:\n" + String.join("\n", files);
    }

    // ---- get_file_outline ----

    String getFileOutline(JsonObject args) {
        if (!args.has("path") || args.get("path").isJsonNull())
            return ToolUtils.ERROR_PATH_REQUIRED;
        String pathStr = args.get("path").getAsString();

        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) return ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;

            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return ToolUtils.ERROR_CANNOT_PARSE + pathStr;

            Document document = FileDocumentManager.getInstance().getDocument(vf);
            if (document == null) return "Cannot read file: " + pathStr;

            List<String> outline = collectOutlineEntries(psiFile, document);

            if (outline.isEmpty()) return "No structural elements found in " + pathStr;
            String basePath = project.getBasePath();
            String display = basePath != null ? relativize(basePath, vf.getPath()) : pathStr;
            return "Outline of " + (display != null ? display : pathStr) + ":\n"
                + String.join("\n", outline);
        });
    }

    List<String> collectOutlineEntries(PsiFile psiFile, Document document) {
        List<String> outline = new ArrayList<>();
        psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element instanceof PsiNamedElement named) {
                    String name = named.getName();
                    if (name != null && !name.isEmpty()) {
                        String type = ToolUtils.classifyElement(element);
                        if (type != null) {
                            int line = document.getLineNumber(element.getTextOffset()) + 1;
                            outline.add(String.format("  %d: %s %s", line, type, name));
                        }
                    }
                }
                super.visitElement(element);
            }
        });
        return outline;
    }

    // ---- get_class_outline ----

    /**
     * Get the outline of a class by fully qualified name, including library/JDK classes.
     * Delegates to {@link com.github.catatafishen.ideagentforcopilot.psi.java.CodeNavigationJavaSupport} which uses Java-only PSI classes.
     * Only registered when {@code com.intellij.modules.java} is present.
     */
    String getClassOutline(JsonObject args) {
        String className = args.has("class_name") ? args.get("class_name").getAsString() : "";
        if (className.isEmpty()) return "Error: 'class_name' parameter is required";
        boolean includeInherited = args.has("include_inherited")
            && args.get("include_inherited").getAsBoolean();

        return ApplicationManager.getApplication().runReadAction((Computable<String>) () ->
            com.github.catatafishen.ideagentforcopilot.psi.java.CodeNavigationJavaSupport.computeClassOutline(project, className, includeInherited)
        );
    }

    // ---- search_symbols ----

    String searchSymbols(JsonObject args) {
        String query = args.has(PARAM_QUERY) ? args.get(PARAM_QUERY).getAsString() : "";
        String typeFilter = args.has("type") ? args.get("type").getAsString() : "";

        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            if (query.isEmpty() || "*".equals(query)) {
                return searchSymbolsWildcard(typeFilter);
            }
            return searchSymbolsExact(query, typeFilter);
        });
    }

    String searchSymbolsWildcard(String typeFilter) {
        if (typeFilter.isEmpty())
            return "Provide a 'type' filter (class, interface, method, field) when using wildcard query";

        List<String> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String basePath = project.getBasePath();
        int[] fileCount = {0};

        ProjectFileIndex.getInstance(project).iterateContent(vf -> {
            if (vf.isDirectory() || (!vf.getName().endsWith(ToolUtils.JAVA_EXTENSION) && !vf.getName().endsWith(".kt")))
                return true;
            fileCount[0]++;
            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return true;
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc == null) return true;

            collectSymbolsFromFile(psiFile, doc, vf, typeFilter, basePath, seen, results);
            return results.size() < 200;
        });

        if (results.isEmpty())
            return "No " + typeFilter + " symbols found (scanned " + fileCount[0]
                + " source files using AST analysis). This is a definitive result ? no grep needed.";
        return results.size() + " " + typeFilter + " symbols:\n" + String.join("\n", results);
    }

    void collectSymbolsFromFile(PsiFile psiFile, Document doc, VirtualFile vf,
                                String typeFilter, String basePath,
                                Set<String> seen, List<String> results) {
        String relPath = basePath != null ? relativize(basePath, vf.getPath()) : vf.getPath();
        psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (results.size() >= 200) return;
                if (!(element instanceof PsiNamedElement named)) {
                    super.visitElement(element);
                    return;
                }
                String name = named.getName();
                String type = ToolUtils.classifyElement(element);
                if (name != null && type != null && type.equals(typeFilter)) {
                    int line = doc.getLineNumber(element.getTextOffset()) + 1;
                    if (seen.add(relPath + ":" + line)) {
                        results.add(String.format(FORMAT_LOCATION, relPath, line, type, name));
                    }
                }
                super.visitElement(element);
            }
        });
    }

    String searchSymbolsExact(String query, String typeFilter) {
        List<String> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String basePath = project.getBasePath();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

        PsiSearchHelper.getInstance(project).processElementsWithWord(
            (element, offsetInElement) -> {
                PsiElement parent = element.getParent();
                if (parent instanceof PsiNamedElement named && query.equals(named.getName())) {
                    String type = ToolUtils.classifyElement(parent);
                    if (type != null && (typeFilter.isEmpty() || type.equals(typeFilter))) {
                        addSymbolResult(parent, basePath, seen, results);
                    }
                }
                return results.size() < 50;
            },
            scope, query, UsageSearchContext.IN_CODE, true
        );

        if (results.isEmpty()) return "No symbols found matching '" + query + "'";
        return String.join("\n", results);
    }

    void addSymbolResult(PsiElement element, String basePath,
                         Set<String> seen, List<String> results) {
        PsiFile file = element.getContainingFile();
        if (file == null || file.getVirtualFile() == null) return;

        Document doc = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
        if (doc == null) return;

        int line = doc.getLineNumber(element.getTextOffset()) + 1;
        String relPath = basePath != null
            ? relativize(basePath, file.getVirtualFile().getPath())
            : file.getVirtualFile().getPath();
        if (seen.add(relPath + ":" + line)) {
            String lineText = ToolUtils.getLineText(doc, line - 1);
            String type = ToolUtils.classifyElement(element);
            results.add(String.format(FORMAT_LOCATION, relPath, line, type, lineText));
        }
    }

    // ---- find_references ----

    String findReferences(JsonObject args) {
        if (!args.has(PARAM_SYMBOL) || args.get(PARAM_SYMBOL).isJsonNull())
            return "Error: 'symbol' parameter is required";
        String symbol = args.get(PARAM_SYMBOL).getAsString();
        String filePattern = args.has(PARAM_FILE_PATTERN) ? args.get(PARAM_FILE_PATTERN).getAsString() : "";

        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            List<String> results = new ArrayList<>();
            String basePath = project.getBasePath();
            GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

            // Try to find the definition first for accurate ReferencesSearch
            PsiElement definition = findDefinition(symbol, scope);

            if (definition != null) {
                collectPsiReferences(definition, scope, filePattern, basePath, results);
            }

            // Fall back to word search if no PSI references found
            if (results.isEmpty()) {
                collectWordReferences(symbol, scope, filePattern, basePath, results);
            }

            if (results.isEmpty()) return "No references found for '" + symbol + "'";
            return results.size() + " references found:\n" + String.join("\n", results);
        });
    }

    void collectPsiReferences(PsiElement definition, GlobalSearchScope scope,
                              String filePattern, String basePath, List<String> results) {
        for (PsiReference ref : ReferencesSearch.search(definition, scope).findAll()) {
            if (results.size() >= 100) break;

            PsiElement refEl = ref.getElement();
            PsiFile file = refEl.getContainingFile();
            if (file != null && file.getVirtualFile() != null
                && (filePattern.isEmpty() || !ToolUtils.doesNotMatchGlob(file.getName(), filePattern))) {
                Document doc = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
                if (doc != null) {
                    int line = doc.getLineNumber(refEl.getTextOffset()) + 1;
                    String relPath = basePath != null
                        ? relativize(basePath, file.getVirtualFile().getPath())
                        : file.getVirtualFile().getPath();
                    String lineText = ToolUtils.getLineText(doc, line - 1);
                    results.add(String.format(FORMAT_LINE_REF, relPath, line, lineText));
                }
            }
        }
    }

    void collectWordReferences(String symbol, GlobalSearchScope scope,
                               String filePattern, String basePath, List<String> results) {
        PsiSearchHelper.getInstance(project).processElementsWithWord(
            (element, offsetInElement) -> {
                PsiFile file = element.getContainingFile();
                if (file == null || file.getVirtualFile() == null) return true;
                if (!filePattern.isEmpty() && ToolUtils.doesNotMatchGlob(file.getName(), filePattern))
                    return true;

                Document doc = FileDocumentManager.getInstance()
                    .getDocument(file.getVirtualFile());
                if (doc != null) {
                    int line = doc.getLineNumber(element.getTextOffset()) + 1;
                    String relPath = basePath != null
                        ? relativize(basePath, file.getVirtualFile().getPath())
                        : file.getVirtualFile().getPath();
                    String lineText = ToolUtils.getLineText(doc, line - 1);
                    String entry = String.format(FORMAT_LINE_REF, relPath, line, lineText);
                    if (!results.contains(entry)) results.add(entry);
                }
                return results.size() < 100;
            },
            scope, symbol, UsageSearchContext.IN_CODE, true
        );
    }

    // ---- shared helpers ----

    private PsiElement findDefinition(String name, GlobalSearchScope scope) {
        PsiElement[] result = {null};
        PsiSearchHelper.getInstance(project).processElementsWithWord(
            (element, offsetInElement) -> {
                PsiElement parent = element.getParent();
                if (parent instanceof PsiNamedElement named && name.equals(named.getName())) {
                    String type = ToolUtils.classifyElement(parent);
                    if (type != null && !type.equals(ToolUtils.ELEMENT_TYPE_FIELD)) {
                        result[0] = parent;
                        return false; // found one, stop
                    }
                }
                return true;
            },
            scope, name, UsageSearchContext.IN_CODE, true
        );
        return result[0];
    }

    // ---- search_text ----

    private void searchFileForPattern(VirtualFile vf, String filePattern, java.util.regex.Pattern pattern,
                                      String basePath, List<String> results, int maxResults) {
        if (vf.isDirectory() || vf.getLength() > 1_000_000) return;
        if (!filePattern.isEmpty() && ToolUtils.doesNotMatchGlob(vf.getName(), filePattern)) return;

        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return;

        String text = doc.getText();
        String relPath = relativize(basePath, vf.getPath());
        if (relPath == null) return;

        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find() && results.size() < maxResults) {
            int line = doc.getLineNumber(matcher.start()) + 1;
            String lineText = ToolUtils.getLineText(doc, line - 1);
            results.add(String.format(FORMAT_LINE_REF, relPath, line, lineText));
        }
    }

    /**
     * Full-text regex/literal search across project files, reading from IntelliJ buffers
     * (not disk). This replaces the need for external grep/ripgrep tools.
     */
    String searchText(JsonObject args) {
        if (!args.has(PARAM_QUERY) || args.get(PARAM_QUERY).isJsonNull())
            return "Error: 'query' parameter is required";
        String query = args.get(PARAM_QUERY).getAsString();
        String filePattern = args.has(PARAM_FILE_PATTERN) ? args.get(PARAM_FILE_PATTERN).getAsString() : "";
        boolean isRegex = args.has("regex") && args.get("regex").getAsBoolean();
        boolean caseSensitive = !args.has("case_sensitive") || args.get("case_sensitive").getAsBoolean();
        int maxResults = args.has("max_results") ? args.get("max_results").getAsInt() : 100;

        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> performSearch(query, filePattern, isRegex, caseSensitive, maxResults));
    }

    private String performSearch(String query, String filePattern, boolean isRegex, boolean caseSensitive, int maxResults) {
        String basePath = project.getBasePath();
        if (basePath == null) return ERROR_NO_PROJECT_PATH;

        java.util.regex.Pattern pattern = compileSearchPattern(query, isRegex, caseSensitive);
        if (pattern == null) return "Error: invalid regex: " + query;

        List<String> results = new ArrayList<>();
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        fileIndex.iterateContent(vf -> {
            searchFileForPattern(vf, filePattern, pattern, basePath, results, maxResults);
            return results.size() < maxResults;
        });

        if (results.isEmpty()) return "No matches found for '" + query + "'";
        return results.size() + " matches:\n" + String.join("\n", results);
    }

    private java.util.regex.Pattern compileSearchPattern(String query, boolean isRegex, boolean caseSensitive) {
        try {
            int flags = isRegex ? 0 : java.util.regex.Pattern.LITERAL;
            if (!caseSensitive) flags |= java.util.regex.Pattern.CASE_INSENSITIVE;
            return java.util.regex.Pattern.compile(query, flags);
        } catch (java.util.regex.PatternSyntaxException e) {
            return null;
        }
    }
}
