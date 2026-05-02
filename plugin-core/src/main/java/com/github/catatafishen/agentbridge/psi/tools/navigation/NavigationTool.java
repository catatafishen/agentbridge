package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.GlobalSearchScopes;
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
    protected static final String SCOPE_PRODUCTION = "production";
    protected static final String SCOPE_TESTS = "tests";
    protected static final String SCOPE_LIBRARIES = "libraries";
    protected static final String SCOPE_ALL = "all";
    protected static final String SCOPE_DESCRIPTION =
        "Search scope: 'project' (default — all project sources), "
            + "'production' (non-test code only — files in sources, resources, generated_sources roots), "
            + "'tests' (test code only — files in test_sources, test_resources roots), "
            + "'libraries' (only library/JDK sources — "
            + "use after download_sources to look up symbols in dependencies), or 'all' (project + libraries). "
            + "Default 'project' keeps result counts small; switch when you need symbols declared in dependency JARs.";

    protected static final String PARAM_MAX_RESULTS = "max_results";
    protected static final String PARAM_OFFSET = "offset";
    protected static final int DEFAULT_MAX_RESULTS = 100;

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
            case SCOPE_PRODUCTION -> GlobalSearchScopes.projectProductionScope(project);
            case SCOPE_TESTS -> GlobalSearchScopes.projectTestScope(project);
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

    /**
     * Reads pagination params (max_results, offset) from tool arguments.
     * Returns an int array: [maxResults, offset].
     */
    protected int[] readPaginationParams(com.google.gson.JsonObject args, int defaultMax) {
        int maxResults = args.has(PARAM_MAX_RESULTS) ? args.get(PARAM_MAX_RESULTS).getAsInt() : defaultMax;
        int offset = args.has(PARAM_OFFSET) ? args.get(PARAM_OFFSET).getAsInt() : 0;
        return new int[]{maxResults, offset};
    }

    /**
     * Builds a pagination footer when results were limited.
     * Returns empty string if all results fit, otherwise returns hint with next offset.
     */
    protected static String paginationFooter(int totalFound, int offset, int maxResults) {
        int nextOffset = offset + maxResults;
        if (totalFound <= offset + maxResults) return "";
        return "\n\n(Showing " + maxResults + " of " + totalFound + " results. "
            + "Use offset=" + nextOffset + " to see more)";
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

    protected List<String> collectOutlineEntries(PsiFile psiFile, Document document) {
        List<String> outline = new java.util.ArrayList<>();
        psiFile.accept(new com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element instanceof PsiNamedElement named) {
                    String name = named.getName();
                    if (name != null && !name.isEmpty()) {
                        String type = ToolUtils.classifyElement(element);
                        if (type != null) {
                            int line = document.getLineNumber(element.getTextOffset()) + 1;
                            int depth = structuralAncestorDepth(element);
                            outline.add(String.format("  %s%d: %s %s",
                                "  ".repeat(depth), line, type, outlineLabel(named)));
                        }
                    }
                }
                super.visitElement(element);
            }
        });
        return outline;
    }

    private int structuralAncestorDepth(PsiElement element) {
        int depth = 0;
        PsiElement parent = element.getParent();
        while (parent != null) {
            if (parent instanceof PsiNamedElement && ToolUtils.classifyElement(parent) != null) {
                depth++;
            }
            parent = parent.getParent();
        }
        return depth;
    }

    private String outlineLabel(PsiNamedElement element) {
        String name = element.getName();
        StringBuilder label = new StringBuilder(name == null ? "<anonymous>" : name);
        if (element instanceof PsiMethod method) {
            appendMethodSignature(label, method);
        } else if (element instanceof PsiField field) {
            label.append(": ").append(field.getType().getPresentableText());
        } else if (element instanceof PsiClass psiClass) {
            String qualifiedName = psiClass.getQualifiedName();
            if (qualifiedName != null && !qualifiedName.equals(name)) {
                label.append(" [").append(qualifiedName).append(']');
            }
        }
        return prefixModifiers(element, label.toString());
    }

    private static void appendMethodSignature(StringBuilder label, PsiMethod method) {
        label.append('(');
        java.util.List<String> parameters = new java.util.ArrayList<>();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            parameters.add(parameter.getType().getPresentableText());
        }
        label.append(String.join(", ", parameters)).append(')');
        if (!method.isConstructor() && method.getReturnType() != null) {
            label.append(": ").append(method.getReturnType().getPresentableText());
        }
    }

    private static String prefixModifiers(PsiNamedElement element, String label) {
        if (!(element instanceof PsiModifierListOwner owner)) return label;
        PsiModifierList modifierList = owner.getModifierList();
        if (modifierList == null) return label;
        java.util.List<String> modifiers = new java.util.ArrayList<>();
        addModifier(modifierList, modifiers, PsiModifier.PUBLIC, "public");
        addModifier(modifierList, modifiers, PsiModifier.PROTECTED, "protected");
        addModifier(modifierList, modifiers, PsiModifier.PRIVATE, "private");
        addModifier(modifierList, modifiers, PsiModifier.STATIC, "static");
        addModifier(modifierList, modifiers, PsiModifier.ABSTRACT, "abstract");
        addModifier(modifierList, modifiers, PsiModifier.FINAL, "final");
        return modifiers.isEmpty() ? label : String.join(" ", modifiers) + " " + label;
    }

    private static void addModifier(PsiModifierList modifierList, java.util.List<String> modifiers,
                                    String modifier, String label) {
        if (modifierList.hasModifierProperty(modifier)) {
            modifiers.add(label);
        }
    }

    protected void collectSymbolsFromFile(PsiFile psiFile, Document doc, com.intellij.openapi.vfs.VirtualFile vf,
                                          String typeFilter, String basePath,
                                          java.util.Set<String> seen, List<String> results) {
        String relPath = safeRelativize(basePath, vf.getPath());
        psiFile.accept(new com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
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
}
