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

/**
 * Abstract base for code navigation tools. Provides shared constants
 * and helpers for symbol search and code exploration.
 */
public abstract class NavigationTool extends Tool {

    private static final String NAMESPACE_KEYWORD_TYPE = "CppKeyword:NAMESPACE_CPP_KEYWORD";
    private static final String DUMMY_BLOCK = "DUMMY_BLOCK";
    private static final String DUMMY_NODE = "DUMMY_NODE";

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
                .toList();
            if (!filtered.isEmpty()) return filtered;
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
                                                 @org.jetbrains.annotations.Nullable com.intellij.openapi.editor.Editor editor) {
        com.intellij.openapi.vfs.VirtualFile vf = psiFile.getVirtualFile();
        if (vf != null) {
            StructureViewBuilder svBuilder = StructureViewBuilder.PROVIDER.getStructureViewBuilder(
                psiFile.getFileType(), vf, project);
            if (svBuilder instanceof TreeBasedStructureViewBuilder treeBuilder) {
                StructureViewModel model = treeBuilder.createStructureViewModel(editor);
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
     * <p>
     * Some language plugins wrap their PSI elements in custom structure-view adapter objects, so
     * {@code svte.getValue()} may not return a {@link PsiElement} directly. In that case we try
     * common adapter accessor methods ({@code getPsiElement}, {@code getElement},
     * {@code getNavigationElement}) reflectively before giving up on the node.
     */
    private void visitStructureNode(TreeElement node, int depth, Document document, List<String> outline) {
        for (TreeElement child : node.getChildren()) {
            if (!(child instanceof StructureViewTreeElement svte)) continue;
            String label = child.getPresentation().getPresentableText();
            if (label == null || label.isEmpty()) continue;
            PsiElement psiElement = extractPsiElement(svte.getValue());
            if (psiElement == null) continue;
            int offset = Math.clamp(psiElement.getTextOffset(), 0, document.getTextLength());
            int line = document.getLineNumber(offset) + 1;
            String type = ToolUtils.classifyElement(psiElement);
            String displayLabel = (psiElement instanceof PsiModifierListOwner owner)
                ? prefixModifiers(owner, label) : label;
            outline.add(String.format("  %s%d: %s %s",
                "  ".repeat(depth), line, type != null ? type : "symbol", displayLabel));
            visitStructureNode(child, depth + 1, document, outline);
        }
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
                            outline.add(String.format("  %d: %s %s", line, type, name));
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
     * Node-type walk for CLion Nova C/C++ files.
     * <p>
     * CLion Nova's lazy parser produces no {@link PsiNamedElement} instances for C++. It uses two
     * distinct structures for top-level declarations:
     * <ul>
     *   <li><b>Type declarations</b> (class/struct/enum/union) — a single
     *       {@code ASTWrapperPsiElement/CppKeyword:*_KEYWORD} node that wraps a
     *       {@code DUMMY_NODE} (name) child and a {@code DUMMY_BLOCK} (body) child.
     *       Elements without a DUMMY_BLOCK child are forward declarations and are skipped.</li>
     *   <li><b>Function definitions</b> — the signature and body appear as consecutive siblings
     *       at the file level: a {@code CppDummyNodeImpl/DUMMY_NODE} (signature only) immediately
     *       followed by a {@code CppDummyBlockImpl/DUMMY_BLOCK} (body). Using-aliases and
     *       forward declarations are also DUMMY_NODE but have no following DUMMY_BLOCK.</li>
     * </ul>
     * Template declarations ({@code template<class T> void foo()}) also use CLASS_KEYWORD but
     * their extracted "name" contains {@code >} — filtered by {@link #isCppIdentifier}.
     * <p>
     * Declarations nested inside {@code namespace X { ... }} are also collected by recursing into
     * namespace bodies (see {@link #walkCppSymbolsIn}); inside a namespace CLion Nova represents
     * each declaration as a flat {@code DUMMY_NODE} token stream rather than a structured
     * {@code CppKeyword} node, so a different extraction path applies.
     */
    private List<String> collectOutlineEntriesByNodeType(PsiFile psiFile, Document document) {
        List<String> outline = new ArrayList<>();
        walkCppSymbolsByNodeType(psiFile, document,
            (kind, name, line) -> outline.add(String.format("  %d: %s %s", line, kind, name)));
        return outline;
    }

    /**
     * Walks CLion Nova C/C++ top-level declarations by raw PSI node type and invokes
     * {@code visitor} for each recognized symbol. Shared by {@link #collectOutlineEntriesByNodeType}
     * (outline format) and {@link #collectSymbolsFromFile} (symbol-search format).
     */
    private void walkCppSymbolsByNodeType(PsiFile psiFile, Document document, CppSymbolVisitor visitor) {
        walkCppSymbolsIn(significantChildren(psiFile), document, visitor);
    }

    /**
     * Recursively walks a list of significant sibling PSI nodes, emitting recognized C/C++ symbols.
     * Descends into namespace bodies so declarations nested in {@code namespace X { ... }} are
     * collected, not just direct file children.
     */
    private void walkCppSymbolsIn(List<PsiElement> children, Document document, CppSymbolVisitor visitor) {
        for (int i = 0; i < children.size(); i++) {
            PsiElement child = children.get(i);
            String et = child.getNode().getElementType().toString();
            String kind = cppKeywordKind(et);
            if (kind != null) {
                String name = extractTypeDeclarationName(child);
                if (name != null) {
                    visitor.visit(kind, name, lineOf(child, document));
                }
            } else if (NAMESPACE_KEYWORD_TYPE.equals(et)) {
                visitNamespace(child, childOfType(child, DUMMY_BLOCK), document, visitor);
            } else if (DUMMY_NODE.equals(et)) {
                PsiElement next = (i + 1 < children.size()) ? children.get(i + 1) : null;
                boolean nextIsBlock = next != null
                    && DUMMY_BLOCK.equals(next.getNode().getElementType().toString());
                visitNestedDeclaration(child, nextIsBlock ? next : null, document, visitor);
            }
        }
    }

    /**
     * Handles a {@code DUMMY_NODE} sibling — the form CLion Nova uses for a declaration nested
     * inside a namespace body: a flat token stream rather than the structured {@code CppKeyword}
     * node used at file top level. Emits nested type definitions (class/struct/enum/union),
     * recurses into nested namespaces, and recognizes free-function definitions. Forward
     * declarations — a {@code DUMMY_NODE} with no following {@code DUMMY_BLOCK} body — are skipped.
     *
     * @param block the following {@code DUMMY_BLOCK} body sibling, or {@code null} if absent
     */
    private void visitNestedDeclaration(PsiElement node, PsiElement block,
                                        Document document, CppSymbolVisitor visitor) {
        String firstChildType = firstChildElementType(node);
        if (NAMESPACE_KEYWORD_TYPE.equals(firstChildType)) {
            visitNamespace(node, block, document, visitor);
            return;
        }
        if (block == null) {
            return;
        }
        String typeKind = cppKeywordKind(firstChildType);
        if (typeKind != null) {
            String name = firstIdentifier(node);
            if (name != null && isCppIdentifier(name)) {
                visitor.visit(typeKind, name, lineOf(node, document));
            }
        } else {
            String name = extractFunctionName(node.getText());
            if (name != null) {
                visitor.visit("function", name, lineOf(node, document));
            }
        }
    }

    /**
     * Emits a {@code namespace} entry (when a name is present) and recurses into its body so
     * declarations nested in {@code namespace X { ... }} are collected.
     *
     * @param body the namespace's {@code DUMMY_BLOCK}, or {@code null} if absent
     */
    private void visitNamespace(PsiElement namespaceNode, PsiElement body,
                                Document document, CppSymbolVisitor visitor) {
        String name = extractTypeDeclarationName(namespaceNode);
        if (name == null) {
            name = firstIdentifier(namespaceNode);
        }
        if (name != null && isCppIdentifier(name)) {
            visitor.visit("namespace", name, lineOf(namespaceNode, document));
        }
        if (body != null) {
            walkCppSymbolsIn(significantChildren(body), document, visitor);
        }
    }

    private static int lineOf(PsiElement element, Document document) {
        return document.getLineNumber(element.getTextOffset()) + 1;
    }

    /**
     * Returns the element-type string of the first significant child of {@code node}, or {@code ""}.
     */
    private static String firstChildElementType(PsiElement node) {
        List<PsiElement> kids = significantChildren(node);
        return kids.isEmpty() ? "" : kids.getFirst().getNode().getElementType().toString();
    }

    /**
     * Returns the text of the first {@code IDENTIFIER} child of {@code node}, or {@code null}.
     */
    @org.jetbrains.annotations.Nullable
    private static String firstIdentifier(PsiElement node) {
        for (PsiElement child : significantChildren(node)) {
            if ("IDENTIFIER".equals(child.getNode().getElementType().toString())) {
                return child.getText();
            }
        }
        return null;
    }

    /**
     * Callback for {@link #walkCppSymbolsByNodeType}.
     */
    @FunctionalInterface
    private interface CppSymbolVisitor {
        void visit(String kind, String name, int line);
    }

    /**
     * Returns direct children of {@code parent}, skipping whitespace and comments.
     */
    private static List<PsiElement> significantChildren(PsiElement parent) {
        List<PsiElement> result = new ArrayList<>();
        for (PsiElement child : parent.getChildren()) {
            if (!(child instanceof com.intellij.psi.PsiWhiteSpace) && !(child instanceof com.intellij.psi.PsiComment)) {
                result.add(child);
            }
        }
        return result;
    }

    /**
     * Returns the first direct child of {@code parent} whose element type matches {@code elementType},
     * or {@code null} if none.
     */
    @org.jetbrains.annotations.Nullable
    private static PsiElement childOfType(PsiElement parent, String elementType) {
        for (PsiElement child : parent.getChildren()) {
            if (elementType.equals(child.getNode().getElementType().toString())) {
                return child;
            }
        }
        return null;
    }

    /**
     * Returns the type declaration name if {@code node} has a DUMMY_NODE name child and a
     * DUMMY_BLOCK body child (full definition); returns {@code null} for forward declarations.
     */
    @org.jetbrains.annotations.Nullable
    private static String extractTypeDeclarationName(PsiElement node) {
        String name = null;
        boolean hasBody = false;
        for (PsiElement gc : node.getChildren()) {
            String gcEt = gc.getNode().getElementType().toString();
            if (DUMMY_NODE.equals(gcEt) && name == null) {
                name = firstToken(gc.getText().trim());
            } else if (DUMMY_BLOCK.equals(gcEt)) {
                hasBody = true;
            }
        }
        return (hasBody && name != null && !name.isEmpty() && isCppIdentifier(name)) ? name : null;
    }

    /**
     * Returns the text before the first whitespace character, i.e. the first token.
     */
    private static String firstToken(String s) {
        int ws = indexOfWhitespace(s);
        return ws > 0 ? s.substring(0, ws) : s;
    }

    @org.jetbrains.annotations.Nullable
    private static String extractFunctionName(String text) {
        int paren = text.indexOf('(');
        if (paren < 0) return null;
        // Take text before '(' and find the last identifier token (handles "ReturnType ClassName::method(")
        String prefix = text.substring(0, paren).trim();
        int end = prefix.length();
        int start = end;
        while (start > 0 && (Character.isLetterOrDigit(prefix.charAt(start - 1))
            || prefix.charAt(start - 1) == '_'
            || prefix.charAt(start - 1) == ':')) {
            start--;
        }
        String name = prefix.substring(start, end);
        if (name.startsWith("::")) name = name.substring(2);
        if (name.isEmpty() || name.chars().noneMatch(Character::isLetter)) return null;
        return name;
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    private static boolean isCppIdentifier(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
    }

    /**
     * Maps a CLion Nova C++ element-type string to a display kind, or returns {@code null} if the
     * element type does not correspond to a type declaration keyword.
     */
    @org.jetbrains.annotations.Nullable
    private static String cppKeywordKind(String elementType) {
        return switch (elementType) {
            case "CppKeyword:CLASS_KEYWORD" -> "class";
            case "CppKeyword:STRUCT_KEYWORD" -> "struct";
            case "CppKeyword:UNION_KEYWORD" -> "union";
            case "CppKeyword:ENUM_KEYWORD" -> "enum";
            default -> null;
        };
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

    /**
     * Analyzes a single {@link PsiFile} for symbols of the given type, without requiring the file
     * to be in a project source root. Uses the same two-phase analysis as
     * {@link #collectSymbolsFromFile}: a {@link PsiNamedElement} walk (handles Java, Kotlin, and
     * classic C++ via {@code com.intellij.cidr.lang}) followed by the CLion Nova node-type
     * fallback for files whose lazy parser produces {@code DUMMY_NODE}/{@code DUMMY_BLOCK}
     * structures instead of {@link PsiNamedElement} instances.
     *
     * <p>Intended for IDE compatibility tests: create an in-memory C++ {@link PsiFile} via
     * {@link com.intellij.psi.PsiFileFactory#createFileFromText} using the language object from
     * {@link com.intellij.lang.Language#findLanguageByID}, then pass it here to exercise the
     * per-file symbol extraction without depending on FileTypeManager extension registration or
     * a project source root.</p>
     *
     * @param psiFile    the file to analyse; need not have an on-disk backing file
     * @param typeFilter symbol kind ({@code "class"}, {@code "method"}, …), or {@code null} for all
     * @return symbol entries in {@code relPath:line [type] name} format
     */
    public List<String> analyzeFileSymbols(PsiFile psiFile,
                                           @org.jetbrains.annotations.Nullable String typeFilter) {
        return com.intellij.openapi.application.ReadAction.computeCancellable(() -> {
            Document doc = com.intellij.psi.PsiDocumentManager.getInstance(project)
                .getDocument(psiFile);
            if (doc == null) return List.<String>of();
            com.intellij.openapi.vfs.VirtualFile vf = psiFile.getViewProvider().getVirtualFile();
            List<String> results = new ArrayList<>();
            collectSymbolsFromFile(psiFile, doc, vf, typeFilter, null,
                new java.util.HashSet<>(), results);
            return results;
        });
    }

    protected void collectSymbolsFromFile(PsiFile psiFile, Document doc, com.intellij.openapi.vfs.VirtualFile vf,
                                          String typeFilter, String basePath,
                                          java.util.Set<String> seen, List<String> results) {
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
                if (name != null && type != null && type.equals(typeFilter)) {
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
            walkCppSymbolsByNodeType(psiFile, doc, (kind, name, line) -> {
                if (kindMatchesFilter(kind, typeFilter) && seen.add(relPath + ":" + line) && results.size() < 200) {
                    results.add(String.format(FORMAT_LOCATION, relPath, line, kind, name));
                }
            });
        }
    }

    private static boolean kindMatchesFilter(String kind, String typeFilter) {
        return kind.equals(typeFilter) || ("method".equals(typeFilter) && "function".equals(kind));
    }
}
