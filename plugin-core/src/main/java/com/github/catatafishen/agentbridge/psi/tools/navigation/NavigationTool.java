package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.cpp.CppNovaPsiSupport;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.intellij.ide.structureView.StructureViewBuilder;
import com.intellij.ide.structureView.StructureViewModel;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
        """
            Search scope: 'project' (default — all project sources), \
            'production' (non-test code only — files in sources, resources, generated_sources roots), \
            'tests' (test code only — files in test_sources, test_resources roots), \
            'libraries' (only library/JDK sources — \
            use after download_sources to look up symbols in dependencies), or 'all' (project + libraries). \
            Default 'project' keeps result counts small; switch when you need symbols declared in dependency JARs.""";

    protected static final String PARAM_MAX_RESULTS = "max_results";
    protected static final String PARAM_OFFSET = "offset";
    protected static final int DEFAULT_MAX_RESULTS = 100;

    /**
     * Format for flat (non-indented) outline entries: {@code "  <line>: <type> <name>"}.
     */
    private static final String FORMAT_OUTLINE_ENTRY = "  %d: %s %s";
    /**
     * Fallback type label for structure-view nodes whose PSI element has no recognised type.
     */
    private static final String ELEMENT_TYPE_SYMBOL = "symbol";

    protected NavigationTool(Project project) {
        super(project);
    }

    /**
     * Resolves a user-supplied scope name to a {@link GlobalSearchScope}. Falls back to project scope
     * when the value is missing or unrecognised so existing callers keep their current behaviour.
     */
    protected GlobalSearchScope resolveScope(String scopeName) {
        if (scopeName == null) return GlobalSearchScope.projectScope(project);
        return switch (scopeName.toLowerCase(Locale.ROOT)) {
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

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.SEARCH;
    }

    protected void showSearchFeedback(String message) {
        if (!ToolLayerSettings.getInstance(project).getFollowAgentFiles()) return;
        EdtUtil.invokeLater(() -> PlatformApiCompat.showStatusBarInfo(project, message));
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
     * <p>For qualified symbols the qualifier filter is authoritative: only candidates whose
     * PSI ancestor chain forms a suffix of the qualifier tokens are returned. This prevents
     * a same-simple-name collision (e.g. {@code WidgetA.render} vs {@code WidgetB.render})
     * from silently leaking the wrong candidate to the caller when the intended qualifier
     * doesn't match.
     *
     * <p>Package-qualified Java names like {@code com.example.MyClass.method} are handled by
     * {@link #matchesQualifier}'s partial-suffix logic: {@code [MyClass]} is accepted as a
     * suffix of {@code [com, example, MyClass]} even though the package segments aren't PSI
     * ancestors of the method.
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

        if (isQualified) {
            String[] qualifierTokens = qualifierTokensOf(name);
            return candidates.stream()
                .filter(e -> matchesQualifier(e, qualifierTokens))
                .toList();
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
        return Arrays.stream(qualifier.split("\\W+"))
            .filter(s -> !s.isEmpty())
            .toArray(String[]::new);
    }

    /**
     * Returns true if the qualifier tokens end with the named PSI ancestors of {@code element}
     * (walking up from the element's direct parent to the containing file). That is, the
     * ancestor names must form a <b>suffix</b> of the qualifier tokens.
     *
     * <p>This is language-agnostic: C++ namespaces, Java/Kotlin classes, Rust modules, and
     * similar structural containers all appear as {@link PsiNamedElement}s in the PSI tree,
     * so the check works without any language-specific knowledge.
     *
     * <p>Two matching modes:
     * <ul>
     *   <li><b>Full match</b>: method {@code "process"} inside class {@code "ProcessorA"} has
     *       ancestor names {@code ["ProcessorA"]}. Qualifier tokens {@code ["ProcessorA"]} → match.</li>
     *   <li><b>Partial (suffix) match</b>: method {@code "method"} inside class {@code "MyClass"} in
     *       package {@code com.example} has ancestor names {@code ["MyClass"]} (packages are not
     *       PSI ancestors in Java). Qualifier tokens {@code ["com", "example", "MyClass"]} → match
     *       because {@code ["MyClass"]} is a suffix of the tokens.</li>
     * </ul>
     *
     * <p>Symbols with no named PSI ancestors (e.g. top-level functions in files where the file
     * itself is not a PsiNamedElement) never match a non-empty qualifier — this is intentional:
     * we can't verify the qualifier structurally, so we err on the side of not returning results
     * that might belong to a differently-qualified same-name symbol.
     */
    protected static boolean matchesQualifier(PsiElement element, String[] qualifierTokens) {
        if (qualifierTokens.length == 0) return true;
        List<String> ancestorNames = new ArrayList<>();
        PsiElement current = element.getParent();
        while (current != null && !(current instanceof PsiFile)) {
            if (current instanceof PsiNamedElement named && named.getName() != null) {
                ancestorNames.addFirst(named.getName()); // prepend → outer-first order
            }
            current = current.getParent();
        }
        if (ancestorNames.isEmpty() || ancestorNames.size() > qualifierTokens.length) return false;
        int offset = qualifierTokens.length - ancestorNames.size();
        for (int i = 0; i < ancestorNames.size(); i++) {
            if (!qualifierTokens[offset + i].equals(ancestorNames.get(i))) return false;
        }
        return true;
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
            // Case-insensitive prefix match for Windows (avoid drive-letter casing mismatch).
            if (p.regionMatches(true, 0, base, 0, base.length())
                && p.length() > base.length()
                && p.charAt(base.length()) == '/') {
                return p.substring(base.length() + 1);
            }
        }
        // Unknown external path: emit only the filename to avoid leaking home-dir paths
        int lastSlash = p.lastIndexOf('/');
        return lastSlash >= 0 ? p.substring(lastSlash + 1) : p;
    }

    protected void addSymbolResult(PsiElement element, String basePath,
                                   Set<String> seen, List<String> results) {
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
        return collectOutlineEntries(psiFile, document, null);
    }

    // Computable<> cast required: javac cannot resolve the Computable versus ThrowableComputable overloads
    // of ApplicationManager.runReadAction without an explicit cast. IntelliJ's type
    // inference is more aggressive than javac and incorrectly reports it as redundant.

    /**
     * Builds the file outline using the IDE's own {@link StructureViewModel} for the file's language.
     * This delegates to the same source as IntelliJ's Structure panel, so every language plugin
     * automatically gets correct, language-aware structural filtering without any classification
     * code in this plugin.
     * <p>
     * {@code editor} should be supplied when the file is open in an editor — this is required for
     * lazy-parsing IDEs like CLion Nova whose {@link StructureViewModel} only populates itself
     * when a real editor is present. Pass {@code null} for languages that build the model eagerly.
     * <p>
     * Falls back to a PSI walk for file types that don't register a {@code StructureViewBuilder}.
     */
    @SuppressWarnings("deprecation") // StructureViewBuilder.PROVIDER deprecated but no alternative
    protected List<String> collectOutlineEntries(PsiFile psiFile, Document document,
                                                 @Nullable Editor editor) {
        VirtualFile vf = psiFile.getVirtualFile();
        if (vf != null) {
            StructureViewBuilder svBuilder = StructureViewBuilder.PROVIDER.getStructureViewBuilder(
                psiFile.getFileType(), vf, project);
            if (svBuilder instanceof TreeBasedStructureViewBuilder treeBuilder) {
                StructureViewModel model = treeBuilder.createStructureViewModel(editor);
                try {
                    List<String> outline = new ArrayList<>();
                    visitStructureNode(model.getRoot(), 0, document, outline);
                    if (!outline.isEmpty()) return outline;
                } finally {
                    Disposer.dispose(model);
                }
            }
        }
        return collectOutlineEntriesByPsiWalk(psiFile, document);
    }

    private void visitStructureNode(TreeElement node, int depth, Document document, List<String> outline) {
        for (TreeElement child : node.getChildren()) {
            processStructureChild(child, depth, document, outline);
        }
    }

    /**
     * Processes a single child node of a {@link StructureViewModel} tree, emitting one outline
     * line if the child is a valid structural element with a resolvable PSI element.
     * Extracted from {@link #visitStructureNode} to keep the loop body free of multiple
     * {@code continue} guards (Sonar S135).
     */
    private void processStructureChild(TreeElement child, int depth, Document document, List<String> outline) {
        if (!(child instanceof StructureViewTreeElement svte)) return;
        String label = child.getPresentation().getPresentableText();
        if (label == null || label.isEmpty()) return;
        PsiElement psiElement = extractPsiElement(svte.getValue());
        if (psiElement == null) return;
        int offset = Math.clamp(psiElement.getTextOffset(), 0, document.getTextLength());
        int line = document.getLineNumber(offset) + 1;
        String type = ToolUtils.classifyElement(psiElement);
        String displayLabel = (psiElement instanceof PsiModifierListOwner owner)
            ? prefixModifiers(owner, label) : label;
        outline.add(String.format("  %s%d: %s %s",
            "  ".repeat(depth), line, type != null ? type : ELEMENT_TYPE_SYMBOL, displayLabel));
        visitStructureNode(child, depth + 1, document, outline);
    }

    /**
     * Extracts a {@link PsiElement} from a structure-view node value. Most language plugins return
     * the PSI element directly from {@link StructureViewTreeElement#getValue()}, but some (notably
     * CLion's Nova C/C++ engine) wrap the PSI in an adapter object. Try common accessor names
     * reflectively so we stay language-agnostic instead of depending on any specific wrapper type.
     */
    private static PsiElement extractPsiElement(Object value) {
        if (value instanceof PsiElement psi) return psi;
        if (value == null) return null;
        for (String accessor : PSI_ACCESSORS) {
            try {
                java.lang.reflect.Method m = value.getClass().getMethod(accessor);
                Object result = m.invoke(value);
                if (result instanceof PsiElement psi) return psi;
            } catch (ReflectiveOperationException ignored) {
                // Accessor not present or threw — try the next one
            }
        }
        return null;
    }

    private static final String[] PSI_ACCESSORS = {
        "getPsiElement", "getElement", "getNavigationElement",
        // CLion Nova / Cidr adapter methods
        "getPsi", "getSymbol", "getCppElement", "getDeclaration", "getEntityPtr", "getItem"
    };

    /**
     * PSI-walk fallback. Visits the PSI tree looking for {@link PsiNamedElement} instances, then
     * falls back to a node-type walk for CLion Nova C/C++ files whose lazy parser does not produce
     * {@code PsiNamedElement} instances for declarations.
     */
    private List<String> collectOutlineEntriesByPsiWalk(PsiFile psiFile, Document document) {
        List<String> outline = new ArrayList<>();
        psiFile.accept(new com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element instanceof PsiNamedElement named) {
                    String name = named.getName();
                    if (name != null && !name.isEmpty()) {
                        String type = ToolUtils.classifyElement(element);
                        if (type != null) {
                            int line = document.getLineNumber(element.getTextOffset()) + 1;
                            outline.add(String.format(FORMAT_OUTLINE_ENTRY, line, type, name));
                        }
                    }
                }
                super.visitElement(element);
            }
        });
        if (!outline.isEmpty()) return outline;
        // CLion Nova C/C++ files produce no PsiNamedElement declarations — fall back to
        // examining raw PSI node types instead.
        return collectOutlineEntriesByNodeType(psiFile, document);
    }

    /**
     * Node-type fallback for CLion Nova C/C++ files, whose lazy parser produces no
     * {@link PsiNamedElement} declarations. Delegates to {@link CppNovaPsiSupport}, the single
     * home for CLion Nova C/C++ node-shape knowledge (shared with {@code search_symbols} and
     * {@code get_symbol_info}).
     */
    private List<String> collectOutlineEntriesByNodeType(PsiFile psiFile, Document document) {
        List<String> outline = new ArrayList<>();
        CppNovaPsiSupport.walkSymbols(psiFile, document,
            (kind, name, line) -> outline.add(String.format(FORMAT_OUTLINE_ENTRY, line, kind, name)));
        return outline;
    }

    private static String prefixModifiers(PsiModifierListOwner owner, String label) {
        PsiModifierList modifierList = owner.getModifierList();
        if (modifierList == null) return label;
        List<String> modifiers = new ArrayList<>();
        addModifier(modifierList, modifiers, PsiModifier.PUBLIC, "public");
        addModifier(modifierList, modifiers, PsiModifier.PROTECTED, "protected");
        addModifier(modifierList, modifiers, PsiModifier.PRIVATE, "private");
        addModifier(modifierList, modifiers, PsiModifier.STATIC, "static");
        addModifier(modifierList, modifiers, PsiModifier.ABSTRACT, "abstract");
        addModifier(modifierList, modifiers, PsiModifier.FINAL, "final");
        return modifiers.isEmpty() ? label : String.join(" ", modifiers) + " " + label;
    }

    private static void addModifier(PsiModifierList modifierList, List<String> modifiers,
                                    String modifier, String label) {
        if (modifierList.hasModifierProperty(modifier)) {
            modifiers.add(label);
        }
    }

    protected void collectSymbolsFromFile(PsiFile psiFile, Document doc, VirtualFile vf,
                                          String typeFilter, String basePath,
                                          Set<String> seen, List<String> results) {
        String relPath = safeRelativize(basePath, vf.getPath());
        int sizeBefore = results.size();
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
                if (name != null && type != null && kindMatchesFilter(type, typeFilter)) {
                    int line = doc.getLineNumber(element.getTextOffset()) + 1;
                    if (seen.add(relPath + ":" + line)) {
                        results.add(String.format(FORMAT_LOCATION, relPath, line, type, name));
                    }
                }
                super.visitElement(element);
            }
        });
        // CLion Nova C/C++ fallback: the PsiNamedElement walk above produces nothing for C++
        // declarations. Reuse the same node-type detection used by collectOutlineEntriesByNodeType.
        if (results.size() == sizeBefore) {
            CppNovaPsiSupport.walkSymbols(psiFile, doc, (kind, name, line) -> {
                if (kindMatchesFilter(kind, typeFilter) && seen.add(relPath + ":" + line) && results.size() < 200) {
                    results.add(String.format(FORMAT_LOCATION, relPath, line, kind, name));
                }
            });
        }
    }

    private static boolean kindMatchesFilter(String kind, String typeFilter) {
        if (typeFilter == null) return true;
        return kind.equals(typeFilter)
            || (ToolUtils.ELEMENT_TYPE_METHOD.equals(typeFilter) && ToolUtils.ELEMENT_TYPE_FUNCTION.equals(kind));
    }
}
