package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.intellij.ide.structureView.StructureViewBuilder;
import com.intellij.ide.structureView.StructureViewModel;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.GlobalSearchScopes;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.UsageSearchContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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

    /**
     * Finds all structural definitions (non-field {@link PsiNamedElement}s) whose simple name
     * matches the simple name extracted from {@code name}.
     *
     * <p>When {@code name} is a qualified symbol (e.g. {@code vsc::for_each_delim} or
     * {@code ProcessorA.process}), ALL candidates with the matching simple name are collected
     * and then filtered by comparing qualifier tokens against each candidate's PSI ancestor
     * name chain. This disambiguation is language-agnostic: it relies only on the PSI tree
     * structure — namespaces, classes, and modules appear as named ancestors regardless of
     * language.
     *
     * <p>If the qualifier filter produces no match (e.g. package-qualified Java names where
     * package nodes are not in the PSI tree), all candidates are returned so the caller can
     * still search references rather than silently returning nothing.
     *
     * <p>For unqualified names the word index search stops at the first structural match,
     * preserving existing behavior.
     */
    protected List<PsiElement> findDefinitions(String name, GlobalSearchScope scope) {
        String simpleName = simpleNameOf(name);
        boolean isQualified = !simpleName.equals(name);

        List<PsiElement> candidates = new ArrayList<>();
        PsiSearchHelper.getInstance(project).processElementsWithWord(
            (element, offsetInElement) -> {
                PsiElement parent = element.getParent();
                if (parent instanceof PsiNamedElement named && simpleName.equals(named.getName())) {
                    String type = ToolUtils.classifyElement(parent);
                    if (type != null && !type.equals(ToolUtils.ELEMENT_TYPE_FIELD)) {
                        candidates.add(parent);
                        if (!isQualified) return false; // unqualified: stop at first match
                    }
                }
                return true;
            },
            scope, simpleName, UsageSearchContext.IN_CODE, true
        );

        if (isQualified && candidates.size() > 1) {
            String[] qualifierTokens = qualifierTokensOf(name);
            List<PsiElement> filtered = candidates.stream()
                .filter(e -> matchesQualifier(e, qualifierTokens))
                .collect(Collectors.toList());
            if (!filtered.isEmpty()) return filtered;
            // Qualifier didn't match any ancestor chain (e.g. package-qualified Java name);
            // return all candidates so the caller can still find references.
        }
        return candidates;
    }

    /**
     * Extracts the rightmost identifier token from a possibly qualified symbol name.
     * For example: {@code vsc::for_each_delim} → {@code for_each_delim},
     * {@code com.example.MyClass} → {@code MyClass}.
     * <p>
     * The PSI word index only accepts single identifier tokens. Any non-identifier
     * character (regardless of language) acts as a qualifier separator, so this
     * extraction is language-agnostic.
     */
    protected static String simpleNameOf(String symbol) {
        for (int i = symbol.length() - 1; i >= 0; i--) {
            char c = symbol.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return symbol.substring(i + 1);
            }
        }
        return symbol;
    }

    /**
     * Extracts the qualifier name tokens from a qualified symbol.
     * For example: {@code vsc::for_each_delim} → {@code ["vsc"]},
     * {@code ProcessorA.method} → {@code ["ProcessorA"]},
     * {@code com.example.MyClass.method} → {@code ["com", "example", "MyClass"]}.
     */
    protected static String[] qualifierTokensOf(String symbol) {
        String simpleName = simpleNameOf(symbol);
        if (simpleName.equals(symbol)) return new String[0];
        String qualifier = symbol.substring(0, symbol.length() - simpleName.length());
        return Arrays.stream(qualifier.split("[^a-zA-Z0-9_]+"))
            .filter(s -> !s.isEmpty())
            .toArray(String[]::new);
    }

    /**
     * Returns true if the qualifier tokens match a suffix of the named PSI ancestors of
     * {@code element} (walking up from the element's direct parent to the containing file).
     *
     * <p>This is language-agnostic: C++ namespaces, Java/Kotlin classes, Rust modules, and
     * similar structural containers all appear as {@link PsiNamedElement}s in the PSI tree,
     * so the check works without any language-specific knowledge.
     *
     * <p>Example: method {@code "process"} inside class {@code "ProcessorA"} has ancestor
     * names {@code ["ProcessorA"]}. Qualifier tokens {@code ["ProcessorA"]} → match.
     */
    protected static boolean matchesQualifier(PsiElement element, String[] qualifierTokens) {
        if (qualifierTokens.length == 0) return true;
        List<String> ancestorNames = new ArrayList<>();
        PsiElement current = element.getParent();
        while (current != null && !(current instanceof PsiFile)) {
            if (current instanceof PsiNamedElement named && named.getName() != null) {
                ancestorNames.add(0, named.getName()); // prepend → outer-first order
            }
            current = current.getParent();
        }
        if (ancestorNames.size() < qualifierTokens.length) return false;
        int offset = ancestorNames.size() - qualifierTokens.length;
        for (int i = 0; i < qualifierTokens.length; i++) {
            if (!qualifierTokens[i].equals(ancestorNames.get(offset + i))) return false;
        }
        return true;
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
     * Relativizes a path for agent-facing output.
     * <ul>
     *   <li>JAR-internal paths ({@code .jar!/}) → {@code jar://} URL so agents can pass it back to file tools</li>
     *   <li>Files inside the project → project-relative path (normal behaviour)</li>
     *   <li>Other external paths → just the filename, to avoid leaking user-specific home-directory paths</li>
     * </ul>
     */
    protected static String safeRelativize(String basePath, String absolutePath) {
        String p = absolutePath.replace('\\', '/');
        // JAR-internal source: produce a jar:// URL that can be passed back to file tools.
        // Must check before the base-path prefix strip to avoid producing broken relative JAR paths.
        if (p.contains(ToolUtils.JAR_SEPARATOR)) return ToolUtils.JAR_URL_PREFIX + p;
        if (basePath != null) {
            String base = basePath.replace('\\', '/');
            if (p.startsWith(base + "/")) return p.substring(base.length() + 1);
        }
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

    /**
     * Builds the file outline using the IDE's own {@link StructureViewModel} for the file's language.
     * This delegates to the same source as IntelliJ's Structure panel, so every language plugin
     * automatically gets correct, language-aware structural filtering without any classification
     * code in this plugin.
     * <p>
     * Falls back to a PSI walk for file types that don't register a {@code StructureViewBuilder}.
     */
    protected List<String> collectOutlineEntries(PsiFile psiFile, Document document) {
        com.intellij.openapi.vfs.VirtualFile vf = psiFile.getVirtualFile();
        if (vf != null) {
            StructureViewBuilder svBuilder = StructureViewBuilder.PROVIDER.getStructureViewBuilder(
                psiFile.getFileType(), vf, project);
            if (svBuilder instanceof TreeBasedStructureViewBuilder treeBuilder) {
                StructureViewModel model = treeBuilder.createStructureViewModel(null);
                try {
                    List<String> outline = new java.util.ArrayList<>();
                    visitStructureNode(model.getRoot(), 0, document, outline);
                    if (!outline.isEmpty()) return outline;
                } finally {
                    Disposer.dispose(model);
                }
            }
        }
        return collectOutlineEntriesByPsiWalk(psiFile, document);
    }

    /**
     * Recursively traverses a {@link StructureViewModel} tree node, emitting one outline line per
     * element. The IDE decides what is structural; {@code classifyElement} provides the type label
     * ("class", "method", etc.) but a {@code null} result no longer excludes the element — it
     * falls back to the generic label {@code "symbol"}.
     */
    private void visitStructureNode(TreeElement node, int depth, Document document, List<String> outline) {
        for (TreeElement child : node.getChildren()) {
            if (!(child instanceof StructureViewTreeElement svte)) continue;
            Object value = svte.getValue();
            if (!(value instanceof PsiElement psiElement)) continue;
            String label = child.getPresentation().getPresentableText();
            if (label == null || label.isEmpty()) continue;
            int line = document.getLineNumber(psiElement.getTextOffset()) + 1;
            String type = ToolUtils.classifyElement(psiElement);
            String displayLabel = (psiElement instanceof PsiModifierListOwner owner)
                ? prefixModifiers(owner, label) : label;
            outline.add(String.format("  %s%d: %s %s",
                "  ".repeat(depth), line, type != null ? type : "symbol", displayLabel));
            visitStructureNode(child, depth + 1, document, outline);
        }
    }

    /**
     * PSI-walk fallback used when no {@code StructureViewBuilder} is registered for the language.
     */
    private List<String> collectOutlineEntriesByPsiWalk(PsiFile psiFile, Document document) {
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
                            outline.add(String.format("  %s%d: %s %s",
                                "  ".repeat(0), line, type, named.getName()));
                        }
                    }
                }
                super.visitElement(element);
            }
        });
        return outline;
    }

    private static String prefixModifiers(PsiModifierListOwner owner, String label) {
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
