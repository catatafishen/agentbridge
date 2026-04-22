package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.UsageSearchContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Abstract base for code navigation tools. Provides shared constants
 * and helpers for symbol search and code exploration.
 */
public abstract class NavigationTool extends Tool {

    protected static final String ERROR_NO_PROJECT_PATH = "No project base path";
    protected static final String PARAM_SYMBOL = "symbol";
    protected static final String PARAM_FILE_PATTERN = "file_pattern";
    protected static final String FORMAT_LOCATION = "%s:%d [%s] %s";
    protected static final String FORMAT_LINE_REF = "%s:%d: %s";
    protected static final String PARAM_QUERY = "query";
    protected static final String PARAM_SCOPE = "scope";
    protected static final String SCOPE_PROJECT = "project";
    protected static final String SCOPE_LIBRARIES = "libraries";
    protected static final String SCOPE_ALL = "all";
    protected static final String SCOPE_DESCRIPTION =
        "Search scope: 'project' (default — only project sources, fastest), 'libraries' (only library/JDK sources — "
            + "use after download_sources to look up symbols in dependencies), or 'all' (project + libraries). "
            + "Default 'project' keeps result counts small; switch when you need symbols declared in dependency JARs.";

    protected NavigationTool(Project project) {
        super(project);
    }

    /**
     * Resolves a user-supplied scope name to a {@link GlobalSearchScope}. Falls back to project scope
     * when the value is missing or unrecognised so existing callers keep their current behaviour.
     */
    protected GlobalSearchScope resolveScope(String scopeName) {
        if (scopeName == null) return GlobalSearchScope.projectScope(project);
        return switch (scopeName.toLowerCase(java.util.Locale.ROOT)) {
            case SCOPE_LIBRARIES -> com.intellij.psi.search.ProjectScope.getLibrariesScope(project);
            case SCOPE_ALL -> GlobalSearchScope.allScope(project);
            default -> GlobalSearchScope.projectScope(project);
        };
    }

    protected String readScopeParam(com.google.gson.JsonObject args) {
        return args.has(PARAM_SCOPE) && !args.get(PARAM_SCOPE).isJsonNull()
            ? args.get(PARAM_SCOPE).getAsString()
            : SCOPE_PROJECT;
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.SEARCH;
    }

    protected void showSearchFeedback(String message) {
        if (!ToolLayerSettings.getInstance(project).getFollowAgentFiles()) return;
        EdtUtil.invokeLater(() -> {
            var statusBar = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project);
            if (statusBar != null) {
                statusBar.setInfo(message);
            }
        });
    }

    protected PsiElement findDefinition(String name, GlobalSearchScope scope) {
        PsiElement[] result = {null};
        PsiSearchHelper.getInstance(project).processElementsWithWord(
            (element, offsetInElement) -> {
                PsiElement parent = element.getParent();
                if (parent instanceof PsiNamedElement named && name.equals(named.getName())) {
                    String type = ToolUtils.classifyElement(parent);
                    if (type != null && !type.equals(ToolUtils.ELEMENT_TYPE_FIELD)) {
                        result[0] = parent;
                        return false;
                    }
                }
                return true;
            },
            scope, name, UsageSearchContext.IN_CODE, true
        );
        return result[0];
    }

    protected String buildReferenceEntry(com.intellij.psi.PsiReference ref, String filePattern,
                                         java.util.regex.Pattern compiledGlob, String basePath) {
        PsiElement refEl = ref.getElement();
        PsiFile file = refEl.getContainingFile();
        if (file == null || file.getVirtualFile() == null) return null;
        String relPath = safeRelativize(basePath, file.getVirtualFile().getPath());
        if (!filePattern.isEmpty() && ToolUtils.doesNotMatchGlob(relPath, filePattern, compiledGlob)) return null;
        Document doc = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
        if (doc == null) return null;
        int line = doc.getLineNumber(refEl.getTextOffset()) + 1;
        String lineText = ToolUtils.getLineText(doc, line - 1);
        return String.format(FORMAT_LINE_REF, relPath, line, lineText);
    }

    /**
     * Returns a safe, non-sensitive relative path for display:
     * <ul>
     *   <li>Files inside the project → project-relative path (normal behaviour)</li>
     *   <li>JAR-internal paths ({@code .jar!/}) → the in-JAR portion only (e.g. {@code com/example/SomeClass.java})</li>
     *   <li>Other external paths → just the filename, to avoid leaking user-specific home-directory paths</li>
     * </ul>
     */
    protected static String safeRelativize(String basePath, String absolutePath) {
        String p = absolutePath.replace('\\', '/');
        if (basePath != null) {
            String base = basePath.replace('\\', '/');
            if (p.startsWith(base + "/")) return p.substring(base.length() + 1);
        }
        // JAR-internal source: strip the on-disk path, keep the in-JAR relative path
        int jarSep = p.lastIndexOf(".jar!/");
        if (jarSep >= 0) return p.substring(jarSep + ".jar!/".length());
        // Unknown external path: emit only the filename to avoid leaking home-dir paths
        int lastSlash = p.lastIndexOf('/');
        return lastSlash >= 0 ? p.substring(lastSlash + 1) : p;
    }

    protected void addSymbolResult(PsiElement element, String basePath,
                                   java.util.Set<String> seen, List<String> results) {
        PsiFile file = element.getContainingFile();
        if (file == null || file.getVirtualFile() == null) return;
        Document doc = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
        if (doc == null) return;
        int line = doc.getLineNumber(element.getTextOffset()) + 1;
        String relPath = safeRelativize(basePath, file.getVirtualFile().getPath());
        if (seen.add(relPath + ":" + line)) {
            String lineText = ToolUtils.getLineText(doc, line - 1);
            String type = ToolUtils.classifyElement(element);
            results.add(String.format(FORMAT_LOCATION, relPath, line, type, lineText));
        }
    }
}
