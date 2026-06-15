package com.github.catatafishen.agentbridge.psi;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.util.SystemProperties;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shared utility methods and constants extracted from PsiBridgeService
 * for use by individual tool handler classes.
 */
public final class ToolUtils {

    // Error message constants — kept for backward compatibility.
    // New code should use ToolError.of(McpErrorCode, message) directly.
    public static final String ERROR_PREFIX = "Error: ";
    public static final String ERROR_FILE_NOT_FOUND = "File not found: ";
    public static final String ERROR_CANNOT_PARSE = "Cannot parse file: ";
    public static final String ERROR_PATH_REQUIRED = ToolError.of(McpErrorCode.MISSING_PARAM,
        "'path' parameter is required");
    public static final String JAVA_EXTENSION = ".java";
    public static final String BUILD_DIR = "build";
    public static final String JAR_URL_PREFIX = "jar://";
    public static final String JAR_SEPARATOR = ".jar!/";

    // Element type constants
    public static final String ELEMENT_TYPE_CLASS = "class";
    public static final String ELEMENT_TYPE_INTERFACE = "interface";
    public static final String ELEMENT_TYPE_ENUM = "enum";
    public static final String ELEMENT_TYPE_FIELD = "field";
    public static final String ELEMENT_TYPE_FUNCTION = "function";
    public static final String ELEMENT_TYPE_METHOD = "method";

    // PSI class name substrings used for generic multi-language classification
    private static final String PSI_PATTERN_CLASS = "Class";
    private static final String PSI_PATTERN_INTERFACE = "Interface";
    private static final String PSI_PATTERN_FUNCTION = "Function";
    private static final String PSI_PATTERN_METHOD = "Method";
    private static final String PSI_PATTERN_FIELD = "Field";

    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.util.Optional<java.lang.reflect.Method>> IS_INTERFACE_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.util.Optional<java.lang.reflect.Method>> IS_ENUM_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    // Precompiled patterns for abuse detection — using find() avoids leading/trailing .*
    // which Sonar flags for ReDoS (S5852). All patterns are anchored or use word boundaries.
    // Patterns that previously used .* between two literals are replaced with indexOf() chains
    // in helper methods (see isFindCommand, isGradlewTestCommand, isPythonPytestCommand).
    private static final java.util.regex.Pattern GRADLE_WORD_PATTERN =
        java.util.regex.Pattern.compile("\\bgradle\\s");
    private static final java.util.regex.Pattern COMPILE_TASK_PATTERN =
        java.util.regex.Pattern.compile("compile(test)?(kotlin|java)", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern TEST_RUNNER_PATTERN =
        java.util.regex.Pattern.compile("(gradlew|gradle|mvn|npm|yarn|pnpm|pytest|jest|mocha|go) test");
    private static final java.util.regex.Pattern NPX_RUNNER_PATTERN =
        java.util.regex.Pattern.compile("(npx|bunx|pnpx)\\s+(jest|vitest|mocha|ava|tap|jasmine)");
    private static final java.util.regex.Pattern NPM_RUN_TEST_PATTERN =
        java.util.regex.Pattern.compile("(npm|yarn|pnpm)\\s+run\\s+test");
    private static final java.util.regex.Pattern GRADLE_BUILD_PATTERN =
        java.util.regex.Pattern.compile("(gradlew|gradle)\\s+(build|check)(\\s|$)");
    private static final java.util.regex.Pattern GRADLEW_BUILD_PATTERN =
        java.util.regex.Pattern.compile("\\./gradlew\\s+(build|check)(\\s|$)");
    private static final java.util.regex.Pattern MVN_LIFECYCLE_PATTERN =
        java.util.regex.Pattern.compile("mvn\\s+(verify|package|install|deploy)(\\s|$)");

    /**
     * Maximum levels to walk up the PSI parent chain when searching for a named ancestor.
     * A small limit avoids incorrectly returning an enclosing method or class when the caret
     * is on an unresolved reference expression — in the "go to declaration on a declaration"
     * case, the named element is always a direct parent of the leaf identifier token.
     */
    private static final int MAX_NAMED_ANCESTOR_WALK = 3;

    /**
     * Walks up the PSI parent chain from {@code element} and returns the first
     * {@link com.intellij.psi.PsiNamedElement} ancestor, excluding {@link com.intellij.psi.PsiFile}.
     * Starts from {@code element.getParent()} (skipping the element itself, which is typically a leaf
     * identifier token rather than a named declaration).
     * Depth-limited to {@value #MAX_NAMED_ANCESTOR_WALK} levels to avoid climbing to an unrelated
     * enclosing method or class when the caret is on an unresolved reference.
     *
     * @param element the leaf PSI element at the caret position; may be {@code null}
     * @return the nearest named ancestor, or {@code null} if none found within the depth limit
     */
    @Nullable
    public static com.intellij.psi.PsiNamedElement findNearestNamedAncestor(@Nullable com.intellij.psi.PsiElement element) {
        com.intellij.psi.PsiElement current = element != null ? element.getParent() : null;
        for (int i = 0; i < MAX_NAMED_ANCESTOR_WALK && current != null; i++) {
            if (current instanceof com.intellij.psi.PsiNamedElement named
                && !(named instanceof com.intellij.psi.PsiFile)) return named;
            current = current.getParent();
        }
        return null;
    }

    private ToolUtils() {
    }

    public static String classifyElement(PsiElement element) {
        String cls = element.getClass().getSimpleName();

        // PSI elements representing a *class-like* declaration share the same class for classes,
        // interfaces, and enums (e.g. Java's PsiClass, Kotlin's KtClass). When the name matches a
        // class-like pattern, refine the result via reflective isInterface()/isEnum() probing.
        // This is the ONLY language-aware branch and it works for any language whose PSI follows
        // the (very common) PsiClass convention — no per-language prefix dispatching required.
        String byName = classifyByName(cls);
        if (ELEMENT_TYPE_CLASS.equals(byName)) {
            String refined = classifyByReflection(element);
            if (refined != null) return refined;
        }
        return byName;
    }

    /**
     * Reflectively probes {@code isInterface()} and {@code isEnum()} on the element so that
     * class-like PSI types that double as interface/enum carriers (PsiClass, KtClass, …) are
     * classified correctly without hard-coded language branches.
     */
    static String classifyByReflection(PsiElement element) {
        try {
            java.lang.reflect.Method isInterface = IS_INTERFACE_CACHE.computeIfAbsent(
                element.getClass(), c -> {
                    try {
                        return java.util.Optional.of(c.getMethod("isInterface"));
                    } catch (NoSuchMethodException e) {
                        return java.util.Optional.empty();
                    }
                }).orElse(null);
            if (isInterface != null && (boolean) isInterface.invoke(element)) return ELEMENT_TYPE_INTERFACE;
            java.lang.reflect.Method isEnum = IS_ENUM_CACHE.computeIfAbsent(
                element.getClass(), c -> {
                    try {
                        return java.util.Optional.of(c.getMethod("isEnum"));
                    } catch (NoSuchMethodException e) {
                        return java.util.Optional.empty();
                    }
                }).orElse(null);
            if (isEnum != null && (boolean) isEnum.invoke(element)) return ELEMENT_TYPE_ENUM;
        } catch (java.lang.reflect.InvocationTargetException | IllegalAccessException ignored) {
            // Reflection invocation failed — fall back to name-based classification
        }
        return null;
    }

    /**
     * Language-agnostic PSI classification by structural keywords in the class simple name.
     * <p>
     * The IntelliJ platform convention is that PSI node class names encode the element type
     * ({@code XxxFunctionDeclaration}, {@code XxxClassDeclaration}, {@code XxxFieldDeclaration},
     * etc.). This method exploits that convention so every language plugin — present or future —
     * is automatically supported without per-language prefix dispatching. Specific patterns that
     * would otherwise be filtered by the non-structural excludes (e.g. Python's
     * {@code TargetExpression}, Java's {@code PsiEnumConstant}) are checked first.
     * <p>
     * Returns {@code null} for non-structural nodes (expressions, references, statements,
     * comments, parameters, etc.) and for unrecognized PSI class names.
     */
    static String classifyByName(String cls) {
        // Specific patterns that the generic excludes would otherwise reject — checked first.
        if (cls.contains("EnumConstant")) return ELEMENT_TYPE_FIELD;            // PsiEnumConstant
        if (cls.contains("TargetExpression")) return ELEMENT_TYPE_FIELD;        // PyTargetExpression

        // Non-structural nodes — definitively excluded.
        if (cls.contains("Reference") || cls.contains("Call") || cls.contains("Expression")
            || cls.contains("Literal") || cls.contains("Statement") || cls.contains("Comment")
            || cls.contains("Import") || cls.contains("Include") || cls.contains("Parameter")
            || cls.contains("Argument") || cls.contains("Access") || cls.contains("Decorator")
            || cls.contains("WhiteSpace") || cls.contains("PackageClause") || cls.contains("Initializer")
            || cls.contains("Pointer")) {
            return null;
        }

        if (cls.contains(PSI_PATTERN_INTERFACE) || cls.contains("Trait")) return ELEMENT_TYPE_INTERFACE;
        if (cls.contains("Enum")) return ELEMENT_TYPE_ENUM;
        if (cls.contains("Struct") || cls.contains(PSI_PATTERN_CLASS)
            || cls.contains("TypeSpec") || cls.contains("TypeAlias")
            || cls.contains("ImplItem") || cls.contains("ObjectDeclaration")
            || cls.contains("Module")) {
            return ELEMENT_TYPE_CLASS;
        }
        if (cls.contains("Constructor")) return ELEMENT_TYPE_METHOD;
        if (cls.contains(PSI_PATTERN_METHOD)) return ELEMENT_TYPE_METHOD;
        if (cls.contains(PSI_PATTERN_FUNCTION)) return ELEMENT_TYPE_FUNCTION;
        if (cls.contains("Declarator") || cls.contains(PSI_PATTERN_FIELD)
            || cls.contains("Property") || cls.contains("Variable")
            || cls.contains("VarDef") || cls.contains("ConstDef") || cls.contains("ConstItem")
            || cls.contains("Constant")) {
            return ELEMENT_TYPE_FIELD;
        }
        return null;
    }

    /**
     * Resolves a JAR path (with or without {@code jar://} prefix) to a VirtualFile.
     * Returns {@code null} if the path is not a JAR path.
     */
    private static @Nullable VirtualFile resolveJarPath(String normalized) {
        if (normalized.startsWith(JAR_URL_PREFIX)) {
            String jarPath = normalized.substring(JAR_URL_PREFIX.length());
            return com.intellij.openapi.vfs.JarFileSystem.getInstance().findFileByPath(jarPath);
        }
        if (normalized.contains(JAR_SEPARATOR)) {
            return com.intellij.openapi.vfs.JarFileSystem.getInstance().findFileByPath(normalized);
        }
        return null;
    }

    public static VirtualFile resolveVirtualFile(Project project, String path) {
        String normalized = path.replace('\\', '/');

        // "." and "./" mean "project root" — resolve immediately so they don't fall
        // through to LocalFileSystem.findFileByPath which uses the JVM's CWD (which
        // on Windows defaults to C:\Windows\System32 when launched from a shortcut).
        if (".".equals(normalized) || "./".equals(normalized)) {
            String basePath = project.getBasePath();
            return basePath != null ? LocalFileSystem.getInstance().findFileByPath(basePath) : null;
        }

        VirtualFile jarFile = resolveJarPath(normalized);
        if (jarFile != null) return jarFile;

        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(normalized);
        if (vf != null) return vf;

        String basePath = project.getBasePath();
        if (basePath != null) {
            vf = LocalFileSystem.getInstance().findFileByPath(basePath + "/" + normalized);
        }
        return vf;
    }

    /**
     * Like {@link #resolveVirtualFile(Project, String)}, but falls back to a synchronous VFS refresh
     * when {@code findFileByPath} returns null. Use this when the VFS cache may be stale.
     * <p>
     * Must be called from a background thread outside any ReadAction.
     */
    public static VirtualFile refreshAndFindVirtualFile(Project project, String path) {
        String normalized = path.replace('\\', '/');

        // "." and "./" mean "project root" — resolve immediately to avoid relying on
        // the JVM CWD (which on Windows defaults to C:\Windows\System32).
        if (".".equals(normalized) || "./".equals(normalized)) {
            String basePath = project.getBasePath();
            return basePath != null
                ? LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath) : null;
        }

        VirtualFile jarFile = resolveJarPath(normalized);
        if (jarFile != null) return jarFile;

        String basePath = project.getBasePath();
        if (basePath != null) {
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath + "/" + normalized);
            if (vf != null) return vf;
        }
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(normalized);
    }

    /**
     * Fallback file-resolver that iterates the project's VFS index (including in-memory
     * {@code temp:///} files used by test fixtures).  Returns {@code null} when no match is found.
     * <p>
     * A path matches when it equals the candidate's full VFS path, or when the candidate's
     * path relative to the project base path equals the normalized input (leading slash stripped).
     */
    public static @Nullable VirtualFile findFileInProjectContent(@NotNull Project project, @NotNull String path) {
        String normalized = path.replace('\\', '/');
        String basePath = project.getBasePath();
        VirtualFile[] match = {null};
        ProjectFileIndex.getInstance(project).iterateContent(vf -> {
            if (vf.isDirectory()) return true;
            String virtualPath = vf.getPath().replace('\\', '/');
            if (virtualPath.equals(normalized)) {
                match[0] = vf;
                return false;
            }
            if (basePath != null) {
                String base = basePath.replace('\\', '/');
                // Case-insensitive prefix match for Windows (avoid drive-letter casing mismatch).
                String relative = virtualPath.regionMatches(true, 0, base, 0, base.length())
                    && virtualPath.length() > base.length()
                    && virtualPath.charAt(base.length()) == '/'
                    ? virtualPath.substring(base.length() + 1) : virtualPath;
                String needle = normalized.startsWith("/") ? normalized.substring(1) : normalized;
                if (relative.equals(needle)) {
                    match[0] = vf;
                    return false;
                }
            }
            return true;
        });
        return match[0];
    }

    public static String relativize(@Nullable String basePath, @NotNull String filePath) {
        String file = filePath.replace('\\', '/');
        // JAR-internal paths: produce a jar:// URL so agents can pass it back to file tools.
        // Must check before the base-path prefix strip to avoid producing broken relative JAR paths.
        if (file.contains(JAR_SEPARATOR)) return JAR_URL_PREFIX + file;
        if (basePath == null) return filePath;
        String base = basePath.replace('\\', '/');
        // On Windows, paths are case-insensitive — use regionMatches to compare without
        // allocating a new String, and to avoid drive-letter case mismatches (e.g. C: vs c:).
        if (file.regionMatches(true, 0, base, 0, base.length())
            && file.length() > base.length()
            && file.charAt(base.length()) == '/') {
            return file.substring(base.length() + 1);
        }
        return file;
    }

    /**
     * Appends {@code " (relative/path/to/file:lineNumber)"} to {@code sb} for the given PSI element.
     * For JAR-internal paths, produces a {@code jar://} URL so agents can pass it back to file tools.
     * Skips elements with null containing files or base paths.
     *
     * @param sb       string being built
     * @param element  PSI element whose source location to append
     * @param basePath project base path for relativizing the file path, or {@code null} to skip
     */
    public static void appendFileLocation(@NotNull StringBuilder sb, @NotNull PsiElement element,
                                          @Nullable String basePath) {
        com.intellij.psi.PsiFile file = element.getContainingFile();
        if (file == null || file.getVirtualFile() == null || basePath == null) return;
        String path = file.getVirtualFile().getPath();
        sb.append(" (").append(relativize(basePath, path));
        Document doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getDocument(file.getVirtualFile());
        if (doc != null) {
            int lineNum = doc.getLineNumber(element.getTextOffset()) + 1;
            sb.append(":").append(lineNum);
        }
        sb.append(")");
    }

    /**
     * Resolves the {@link com.intellij.psi.PsiFile} and line offset range for the given file path + line number.
     * Returns {@code null} if the file cannot be found, parsed, or the line is out of bounds.
     * <p>
     * Used to locate PSI elements at a specific source location in a language-agnostic way,
     * without requiring Java-specific APIs.
     *
     * @param project  current project
     * @param filePath absolute or project-relative path to the file
     * @param line     1-based line number
     * @return a {@link LineContext} with the PSI file and offset range, or {@code null}
     */
    @Nullable
    public static LineContext resolveLineContext(@NotNull Project project,
                                                 @NotNull String filePath,
                                                 int line) {
        VirtualFile vf = resolveVirtualFile(project, filePath);
        if (vf == null) return null;
        com.intellij.psi.PsiFile psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return null;
        Document document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
        if (document == null || line < 1 || line > document.getLineCount()) return null;
        return new LineContext(psiFile, document.getLineStartOffset(line - 1), document.getLineEndOffset(line - 1));
    }

    /**
     * Walks the PSI tree looking for a {@link com.intellij.psi.PsiNameIdentifierOwner} whose
     * {@link com.intellij.psi.PsiNameIdentifierOwner#getName() name} equals {@code name} and whose
     * text range overlaps the line bounds described by {@code ctx}.
     * <p>
     * Uses the element's full text range (not just the name identifier offset) so that
     * methods with annotations or multi-line signatures are found regardless of which line
     * the caller specifies — the annotation line, the signature line, or any line in the body.
     * <p>
     * Shared between {@link CallHierarchySupport} and {@code TypeHierarchySupport} to avoid
     * duplicating the PSI visitor pattern.
     *
     * @param ctx  line context from {@link #resolveLineContext}
     * @param name symbol name to match
     * @return the first matching element, or {@code null}
     */
    @Nullable
    public static com.intellij.psi.PsiNameIdentifierOwner findNamedElement(
        @NotNull LineContext ctx,
        @NotNull String name) {
        com.intellij.psi.PsiNameIdentifierOwner[] found = {null};
        ctx.psiFile().accept(new com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull com.intellij.psi.PsiElement element) {
                if (element instanceof com.intellij.psi.PsiNameIdentifierOwner owner
                    && name.equals(owner.getName())) {
                    com.intellij.openapi.util.TextRange range = owner.getTextRange();
                    if (range.getStartOffset() <= ctx.lineEnd() && range.getEndOffset() >= ctx.lineStart()) {
                        found[0] = owner;
                        stopWalking();
                        return;
                    }
                }
                super.visitElement(element);
            }
        });
        return found[0];
    }

    /**
     * Resolves the named element {@code symbol} at the given line context using a two-step
     * strategy that mirrors how the IDE's own navigation works:
     * <ol>
     *   <li>{@link #findNamedElement} — locate a DECLARATION named {@code symbol} on the line.</li>
     *   <li>{@link #resolveViaReference} — if no declaration exists on this line, treat each
     *   whole-identifier occurrence of {@code symbol} as a USAGE and resolve via the IDE's
     *   registered reference contributors. Language-agnostic — works for Java, Kotlin, C/C++
     *   in CLion, Python in PyCharm, Go in GoLand, JS/TS in WebStorm, etc.</li>
     * </ol>
     * Used by call-hierarchy ({@link CallHierarchySupport}) and type-hierarchy /
     * find-implementations ({@link TypeHierarchySupport}). {@code go_to_declaration} uses the
     * same conceptual approach but keeps its own implementation that returns multiple
     * {@link com.intellij.psi.PsiElement} declarations (polyvariant) rather than a single named
     * owner; consolidating it would change observable behaviour and is intentionally out of
     * scope for this helper. Must be called inside a read action.
     *
     * @return the resolved {@link com.intellij.psi.PsiNameIdentifierOwner}, or {@code null} if
     * neither a declaration nor a resolvable reference is found.
     */
    @Nullable
    public static com.intellij.psi.PsiNameIdentifierOwner resolveNamedElement(
        @NotNull LineContext ctx,
        @NotNull String symbol) {
        com.intellij.psi.PsiNameIdentifierOwner declaration = findNamedElement(ctx, symbol);
        if (declaration != null) return declaration;
        return resolveViaReference(ctx, symbol);
    }

    /**
     * Resolves {@code symbol} via reference contributors at any whole-identifier occurrence of
     * the symbol text on the target line. Each candidate offset is adjusted by
     * {@link com.intellij.codeInsight.TargetElementUtil#adjustOffset} (so trailing whitespace
     * or punctuation does not break lookup) before {@link com.intellij.psi.PsiFile#findReferenceAt}
     * is consulted. Polyvariant references are iterated until a
     * {@link com.intellij.psi.PsiNameIdentifierOwner} target is found.
     */
    @Nullable
    private static com.intellij.psi.PsiNameIdentifierOwner resolveViaReference(
        @NotNull LineContext ctx,
        @NotNull String symbol) {
        if (symbol.isEmpty()) return null;
        com.intellij.psi.PsiFile psiFile = ctx.psiFile();
        VirtualFile vf = psiFile.getVirtualFile();
        if (vf == null) return null;
        Document document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
        if (document == null) return null;

        String lineText = document.getText(new com.intellij.openapi.util.TextRange(ctx.lineStart(), ctx.lineEnd()));
        int searchFrom = 0;
        while (searchFrom <= lineText.length() - symbol.length()) {
            int symIdx = lineText.indexOf(symbol, searchFrom);
            if (symIdx < 0) break;
            if (isWholeIdentifierMatch(lineText, symIdx, symbol.length())) {
                int rawOffset = ctx.lineStart() + symIdx;
                int offset = com.intellij.codeInsight.TargetElementUtil.adjustOffset(psiFile, document, rawOffset);
                com.intellij.psi.PsiNameIdentifierOwner target = resolveReferenceTarget(psiFile, offset);
                if (target != null) return target;
            }
            searchFrom = symIdx + symbol.length();
        }
        return null;
    }

    @Nullable
    private static com.intellij.psi.PsiNameIdentifierOwner resolveReferenceTarget(
        @NotNull com.intellij.psi.PsiFile psiFile, int offset) {
        com.intellij.psi.PsiReference ref = psiFile.findReferenceAt(offset);
        if (ref != null) {
            com.intellij.psi.PsiNameIdentifierOwner target = firstNamedTarget(ref);
            if (target != null) return target;
        }
        com.intellij.psi.PsiElement elementAt = psiFile.findElementAt(offset);
        if (elementAt != null) {
            com.intellij.psi.PsiNamedElement ancestor = findNearestNamedAncestor(elementAt);
            if (ancestor instanceof com.intellij.psi.PsiNameIdentifierOwner owner) return owner;
        }
        return null;
    }

    /**
     * Iterates every resolve candidate of {@code ref} and returns the first one that is a
     * {@link com.intellij.psi.PsiNameIdentifierOwner}. For polyvariant references this matters:
     * an earlier candidate that is not a named declaration must not shadow a later one that is.
     */
    @Nullable
    private static com.intellij.psi.PsiNameIdentifierOwner firstNamedTarget(@NotNull com.intellij.psi.PsiReference ref) {
        if (ref instanceof com.intellij.psi.PsiPolyVariantReference poly) {
            for (com.intellij.psi.ResolveResult rr : poly.multiResolve(false)) {
                if (rr.getElement() instanceof com.intellij.psi.PsiNameIdentifierOwner owner) return owner;
            }
            return null;
        }
        return ref.resolve() instanceof com.intellij.psi.PsiNameIdentifierOwner owner ? owner : null;
    }

    private static boolean isWholeIdentifierMatch(@NotNull String text, int start, int length) {
        if (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) return false;
        int end = start + length;
        return end >= text.length() || !Character.isJavaIdentifierPart(text.charAt(end));
    }

    /**
     * Reflectively invokes the platform's programmatic super-element APIs and returns the
     * combined results. Tries two strategies in order:
     *
     * <ol>
     *   <li>{@code com.intellij.psi.impl.FindSuperElementsHelper.findSuperElements(PsiElement)} —
     *       designed for methods; returns the override chain for {@code PsiMethod}, empty
     *       array otherwise. Ships with the Java module.</li>
     *   <li>{@code element.getSupers()} — the standard {@code PsiClass} API for retrieving
     *       superclass + implemented interfaces; reachable reflectively on {@code PsiClass}
     *       instances.</li>
     * </ol>
     *
     * <h4>Return-value contract (matters for distinguishing "no supers" from "tooling missing"):</h4>
     * <ul>
     *   <li>{@code null} — <b>neither</b> strategy is available on this IDE / for this element
     *       (e.g., pure non-Java IDE without the Java plugin: the helper class is not on the
     *       classpath and the element type has no {@code getSupers()} method). The caller
     *       should report a "not available" / "tooling missing" error.</li>
     *   <li>Empty array — at least one strategy ran successfully but found no supers. The
     *       caller should report a clean "no super methods/types found" message, NOT a
     *       "tooling missing" error.</li>
     *   <li>Non-empty array — the supertypes / super-methods.</li>
     * </ul>
     * Other reflective failures (InvocationTargetException, ClassCastException, etc.) are
     * treated as "strategy was loadable but the call failed" — they collapse into the
     * empty-array branch when the other strategy was at least loadable, or into {@code null}
     * when neither was loadable.
     * <p>
     * Shared by {@code FindSuperMethodsTool} (method-level super chain) and
     * {@link com.github.catatafishen.agentbridge.psi.TypeHierarchySupport} (class/interface
     * supertypes) so a single reflective access point keeps the brittle bit in one place.
     */
    public static com.intellij.psi.PsiElement @Nullable [] findSuperElementsViaPlatform(@NotNull com.intellij.psi.PsiElement element) {
        StrategyResult viaHelper = invokeFindSuperElementsHelper(element);
        if (viaHelper.results() != null && viaHelper.results().length > 0) {
            return viaHelper.results();
        }
        StrategyResult viaGetSupers = invokeGetSupers(element);
        if (viaGetSupers.results() != null && viaGetSupers.results().length > 0) {
            return viaGetSupers.results();
        }
        // Neither strategy returned non-empty results. If at least one strategy was loadable
        // (regardless of whether the invocation itself succeeded), the tooling IS available —
        // report empty rather than null so the caller emits a clean "none found" message.
        if (viaHelper.loadable() || viaGetSupers.loadable()) {
            return new com.intellij.psi.PsiElement[0];
        }
        return null;
    }

    /**
     * Result of one reflective lookup attempt.
     * {@link #loadable()} is {@code true} when the target class / method exists on the
     * classpath, regardless of whether the invocation itself succeeded.
     * {@link #results()} holds the returned array when the call succeeded, or {@code null}
     * for any failure (including the not-loadable case).
     */
    private record StrategyResult(boolean loadable, com.intellij.psi.PsiElement @Nullable [] results) {
        static final StrategyResult NOT_LOADABLE = new StrategyResult(false, null);
    }

    private static StrategyResult invokeFindSuperElementsHelper(@NotNull com.intellij.psi.PsiElement element) {
        Class<?> helper;
        try {
            helper = Class.forName("com.intellij.psi.impl.FindSuperElementsHelper");
        } catch (ClassNotFoundException e) {
            return StrategyResult.NOT_LOADABLE;
        }
        java.lang.reflect.Method method;
        try {
            method = helper.getMethod("findSuperElements", com.intellij.psi.PsiElement.class);
        } catch (NoSuchMethodException e) {
            return StrategyResult.NOT_LOADABLE;
        }
        try {
            Object result = method.invoke(null, element);
            return new StrategyResult(true, (com.intellij.psi.PsiElement[]) result);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return new StrategyResult(true, null);
        }
    }

    private static StrategyResult invokeGetSupers(@NotNull com.intellij.psi.PsiElement element) {
        java.lang.reflect.Method method;
        try {
            method = element.getClass().getMethod("getSupers");
        } catch (NoSuchMethodException e) {
            return StrategyResult.NOT_LOADABLE;
        }
        try {
            Object result = method.invoke(element);
            if (result instanceof com.intellij.psi.PsiElement[] array) {
                return new StrategyResult(true, array);
            }
            if (result instanceof Object[] generic) {
                com.intellij.psi.PsiElement[] out = new com.intellij.psi.PsiElement[generic.length];
                for (int i = 0; i < generic.length; i++) {
                    if (!(generic[i] instanceof com.intellij.psi.PsiElement pe)) {
                        return new StrategyResult(true, null);
                    }
                    out[i] = pe;
                }
                return new StrategyResult(true, out);
            }
            return new StrategyResult(true, null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return new StrategyResult(true, null);
        }
    }

    /**
     * Holds a resolved PSI file and the character offsets bounding a specific source line.
     *
     * @param psiFile   the PSI file
     * @param lineStart offset of the first character on the line (inclusive)
     * @param lineEnd   offset of the last character on the line (inclusive)
     */
    public record LineContext(@NotNull com.intellij.psi.PsiFile psiFile, int lineStart, int lineEnd) {
    }

    public static String getLineText(Document doc, int lineIndex) {
        if (lineIndex < 0 || lineIndex >= doc.getLineCount()) return "";
        int start = doc.getLineStartOffset(lineIndex);
        int end = doc.getLineEndOffset(lineIndex);
        return doc.getText(new com.intellij.openapi.util.TextRange(start, end)).trim();
    }

    /**
     * Returns true if the given path does NOT match the glob pattern.
     * <p>
     * Simple patterns (no {@code /} and no {@code **}) are matched against the
     * <em>filename</em> only for backward compatibility (e.g. {@code *.java}, {@code *Test}).
     * Path patterns (containing {@code /} or {@code **}) are matched against the full
     * relative path using standard glob semantics:
     * <ul>
     *   <li>{@code **} — matches zero or more path segments (crosses {@code /})</li>
     *   <li>{@code *}  — matches any characters within a single path segment (no {@code /})</li>
     *   <li>{@code ?}  — matches exactly one non-separator character</li>
     * </ul>
     * Examples: {@code src/**}{@code /*.java} matches {@code src/main/Foo.java};
     * {@code *.java} matches {@code Foo.java} (filename only).
     */
    public static boolean doesNotMatchGlob(String path, String pattern) {
        return doesNotMatchGlob(path, pattern, globToRegex(pattern));
    }

    public static boolean doesNotMatchGlob(String path, String pattern, java.util.regex.Pattern compiled) {
        if (pattern.isEmpty()) return false;
        java.util.regex.Pattern effectivePattern = compiled != null ? compiled : globToRegex(pattern);
        String normalizedPath = path.replace('\\', '/');
        boolean isPathPattern = pattern.contains("/") || pattern.contains("**");
        String target = isPathPattern ? normalizedPath : lastSegment(normalizedPath);
        return !effectivePattern.matcher(target).matches();
    }

    public static java.util.regex.Pattern compileGlob(String pattern) {
        return globToRegex(pattern);
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static java.util.regex.Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        int len = glob.length();
        int i = 0;
        while (i < len) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < len && glob.charAt(i + 1) == '*') {
                sb.append(".*");
                i += 2; // consume **
                if (i < len && glob.charAt(i) == '/') {
                    i++; // consume the slash after **
                }
            } else if (c == '*') {
                sb.append("[^/]*");
                i++;
            } else if (c == '?') {
                sb.append("[^/]");
                i++;
            } else if (".+^${}[]()|\\".indexOf(c) >= 0) {
                sb.append('\\').append(c);
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        sb.append("$");
        return java.util.regex.Pattern.compile(sb.toString());
    }

    public static String fileType(String name) {
        String l = name.toLowerCase();
        if (l.endsWith(JAVA_EXTENSION)) return "Java";
        if (l.endsWith(".gradle") || l.endsWith(".gradle.kts")) return "Gradle";
        if (l.endsWith(".kt") || l.endsWith(".kts")) return "Kotlin";
        if (l.endsWith(".py")) return "Python";
        if (l.endsWith(".js") || l.endsWith(".jsx")) return "JavaScript";
        if (l.endsWith(".ts") || l.endsWith(".tsx")) return "TypeScript";
        if (l.endsWith(".go")) return "Go";
        if (l.endsWith(".xml")) return "XML";
        if (l.endsWith(".json")) return "JSON";
        if (l.endsWith(".yaml") || l.endsWith(".yml")) return "YAML";
        return "Other";
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    public static String formatFileTimestamp(long epochMs) {
        if (epochMs == 0) return "unknown";
        return new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(epochMs));
    }

    /**
     * Parses a date string like "2026-01-15" into epoch milliseconds (start of day UTC).
     * Returns -1 if blank or unparseable.
     */
    public static long parseDateParam(String date) {
        if (date == null || date.isBlank()) return -1;
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return sdf.parse(date).getTime();
        } catch (java.text.ParseException e) {
            return -1;
        }
    }

    /**
     * Normalize text for fuzzy matching: replace common Unicode variants with ASCII equivalents.
     * This handles em-dashes, smart quotes, non-breaking spaces, emoji, etc. that LLMs often can't reproduce exactly.
     * Uses codepoint iteration to correctly handle surrogate pairs (e.g. 4-byte emoji).
     */
    public static String normalizeForMatch(String s) {
        // First normalize line endings.
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        // Replace ALL non-ASCII codepoints with '?' for fuzzy matching.
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (cp > 127) {
                sb.append('?');
            } else {
                sb.append((char) cp);
            }
        });
        return sb.toString();
    }

    /**
     * Finds the length in the original text that corresponds to a given length in the normalized text,
     * starting from the given position. This accounts for multibyte/surrogate-pair chars that normalize
     * to a single '?' character.
     */
    public static int findOriginalLength(String original, int startIdx, int normalizedLen) {
        int origPos = startIdx;
        int normCount = 0;
        while (normCount < normalizedLen && origPos < original.length()) {
            char c = original.charAt(origPos);
            if (c == '\r' && origPos + 1 < original.length() && original.charAt(origPos + 1) == '\n') {
                // CRLF counts as 1 normalized char
                origPos += 2;
            } else if (Character.isHighSurrogate(c) && origPos + 1 < original.length()
                && Character.isLowSurrogate(original.charAt(origPos + 1))) {
                // Surrogate pair (e.g. emoji) counts as 1 normalized char
                origPos += 2;
            } else {
                origPos++;
            }
            normCount++;
        }
        return origPos - startIdx;
    }

    public static String truncateOutput(String output) {
        return truncateOutput(output, 8000, 0);
    }

    /**
     * Truncates output with pagination support.
     *
     * @param output   full output text
     * @param maxChars maximum characters to return per page
     * @param offset   character offset to start from (0 = beginning)
     * @return the page of output, with pagination hint if more data exists
     */
    public static String truncateOutput(String output, int maxChars, int offset) {
        if (output == null || output.isEmpty()) return output;
        if (offset >= output.length()) return "(offset beyond end of output, total length: " + output.length() + ")";
        String remaining = output.substring(offset);
        if (remaining.length() <= maxChars) {
            return offset > 0
                ? remaining + "\n\n(showing chars " + offset + "-" + output.length() + " of " + output.length() + ")"
                : remaining;
        }
        String page = remaining.substring(0, maxChars);
        int shown = offset + maxChars;
        return page + "\n\n...(truncated, showing chars " + offset + "-" + shown + " of " + output.length()
            + ". Use offset=" + shown + " to see more)";
    }

    /**
     * Detect if a shell command is an abuse pattern that should use a dedicated IntelliJ tool.
     * Used by RunCommandTool (test-redirect and grep-with-source-root-exemption) and
     * RunConfigurationService (program-args abuse check).
     * Hard-blocks for git, cat, sed, find, and compile are primarily enforced by the PERMISSION
     * hooks in {@code .agentbridge/hooks/run_command.json}.
     *
     * @param command the shell command string (will be lowercased and trimmed)
     * @return the abuse type ("git", "cat", "sed", "grep", "find", "test", "compile") or null if allowed
     */
    public static String detectCommandAbuseType(String command) {
        String cmd = command.toLowerCase().trim();

        // Block git — causes IntelliJ editor buffer desync
        if (isGitCommand(cmd)) {
            return "git";
        }

        // Block cat/head/tail/less/more — should use intellij_read_file for live buffer access
        if (isFileViewerCommand(cmd)) {
            return "cat";
        }

        // Block sed — should use edit_text for proper undo/redo and live buffer access
        if (cmd.startsWith("sed ") || cmd.contains("| sed") ||
            cmd.contains("&& sed") || cmd.contains("; sed")) {
            return "sed";
        }

        // Block grep/rg — should use search_text or search_symbols for live buffer search
        if (cmd.startsWith("grep ") || cmd.startsWith("rg ") ||
            cmd.contains("| grep") || cmd.contains("&& grep") || cmd.contains("; grep") ||
            cmd.contains("| rg ") || cmd.contains("&& rg ") || cmd.contains("; rg ")) {
            return "grep";
        }

        // Block find — should use list_project_files
        if (isFindCommand(cmd) ||
            cmd.startsWith("find .") || cmd.startsWith("find /")) {
            return "find";
        }

        // Block direct Gradle compile tasks — should use build_project (IntelliJ incremental compiler)
        if (isGradleCompileCommand(cmd)) return "compile";

        // Block build/check tasks that implicitly run tests — should use build_project or run_tests
        if (isBuildCommand(cmd)) return "test";

        // Block test commands — should use run_tests
        if (isTestCommand(cmd)) return "test";

        return null;
    }

    public static boolean grepTargetsOnlyOutsideSourceRoots(@Nullable Project project, @NotNull String command) {
        if (project == null) return false;
        java.util.List<String> paths = extractGrepPaths(command);
        if (paths.isEmpty()) return false;

        String basePath = project.getBasePath();
        java.nio.file.Path base = basePath != null ? java.nio.file.Path.of(basePath) : null;
        com.intellij.openapi.roots.ProjectFileIndex index =
            com.intellij.openapi.roots.ProjectFileIndex.getInstance(project);

        for (String pathStr : paths) {
            if (!isPathOutsideSourceRoots(pathStr, base, index)) return false;
        }
        return true;
    }

    private static boolean isPathOutsideSourceRoots(
        @NotNull String pathStr,
        @Nullable java.nio.file.Path base,
        @NotNull com.intellij.openapi.roots.ProjectFileIndex index
    ) {
        java.nio.file.Path resolved = resolveCommandPath(pathStr, base);
        if (resolved == null) return false;

        VirtualFile vf = LocalFileSystem.getInstance().findFileByNioFile(resolved);
        if (vf == null) {
            // File doesn't exist (yet?). Allow only when clearly outside the project root.
            return base == null || !resolved.startsWith(base);
        }
        return com.intellij.openapi.application.ReadAction.compute(() ->
            !index.isInSource(vf) || index.isInGeneratedSources(vf));
    }

    @Nullable
    private static java.nio.file.Path resolveCommandPath(@NotNull String pathStr, @Nullable java.nio.file.Path base) {
        try {
            String expanded = pathStr.startsWith("~")
                ? SystemProperties.getUserHome() + pathStr.substring(1)
                : pathStr;
            java.nio.file.Path p = java.nio.file.Path.of(expanded);
            java.nio.file.Path candidate = p.isAbsolute() || base == null ? p : base.resolve(p);
            return candidate.normalize();
        } catch (Exception e) {
            return null;
        }
    }

    static java.util.List<String> extractGrepPaths(@NotNull String command) {
        java.util.List<String> tokens = tokenizeShellCommand(command);
        int idx = findGrepCommandIndex(tokens);
        if (idx < 0) return java.util.List.of();
        return collectPathArgs(tokens, idx + 1);
    }

    private static int findGrepCommandIndex(@NotNull java.util.List<String> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equalsIgnoreCase("grep") || t.equalsIgnoreCase("rg")) return i;
        }
        return -1;
    }

    private static final java.util.Set<String> GREP_TWO_ARG_FLAGS = java.util.Set.of(
        "-e", "-f", "--regexp", "--file",
        "--include", "--exclude", "--exclude-dir", "--include-dir",
        "-A", "-B", "-C", "--after-context", "--before-context", "--context",
        "-m", "--max-count", "--max-depth", "-t", "-T", "--type", "--type-not",
        "-g", "--glob", "--iglob"
    );

    private static final java.util.Set<String> GREP_PATTERN_FLAGS = java.util.Set.of(
        "-e", "-f", "--regexp", "--file"
    );

    private static java.util.List<String> collectPathArgs(@NotNull java.util.List<String> tokens, int from) {
        boolean patternConsumed = false;
        boolean skipNext = false;
        java.util.List<String> paths = new java.util.ArrayList<>();
        for (int i = from; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (skipNext) {
                skipNext = false;
                continue;
            }
            if (t.startsWith("-") && !t.equals("-")) {
                if (GREP_TWO_ARG_FLAGS.contains(t)) {
                    if (GREP_PATTERN_FLAGS.contains(t)) patternConsumed = true;
                    skipNext = true;
                }
            } else if (!patternConsumed) {
                patternConsumed = true;
            } else if (containsGlob(t)) {
                return java.util.List.of();
            } else {
                paths.add(t);
            }
        }
        return paths;
    }

    private static boolean containsGlob(@NotNull String s) {
        return s.indexOf('*') >= 0 || s.indexOf('?') >= 0 || s.indexOf('[') >= 0;
    }

    /**
     * Tokenize a shell command string respecting single and double quotes.
     * Backslash escapes inside quotes are NOT handled — adequate for the simple paths we care about.
     */
    @NotNull
    static java.util.List<String> tokenizeShellCommand(@NotNull String command) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else cur.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    private static boolean isGradleCompileCommand(String cmd) {
        boolean isGradleCmd = cmd.contains("gradlew") || GRADLE_WORD_PATTERN.matcher(cmd).find();
        boolean hasCompileTask = COMPILE_TASK_PATTERN.matcher(cmd).find();
        return isGradleCmd && hasCompileTask;
    }

    private static boolean isTestCommand(String cmd) {
        return TEST_RUNNER_PATTERN.matcher(cmd).find() ||
            isGradlewTestCommand(cmd) ||
            isPythonPytestCommand(cmd) ||
            cmd.contains("cargo test") ||
            cmd.contains("dotnet test") ||
            cmd.startsWith("pytest ") ||
            NPX_RUNNER_PATTERN.matcher(cmd).find() ||
            NPM_RUN_TEST_PATTERN.matcher(cmd).find() ||
            isBareTestRunner(cmd);
    }

    private static boolean isBuildCommand(String cmd) {
        return GRADLE_BUILD_PATTERN.matcher(cmd).find() ||
            GRADLEW_BUILD_PATTERN.matcher(cmd).find() ||
            MVN_LIFECYCLE_PATTERN.matcher(cmd).find();
    }

    /**
     * Checks if a command is a {@code find} command with {@code -name} or {@code -type} flags.
     * Uses indexOf chains instead of regex to avoid Sonar S5852 (ReDoS) hotspots.
     */
    private static boolean isFindCommand(String cmd) {
        if (!cmd.startsWith("find ")) return false;
        return cmd.contains("-name") || cmd.contains("-type");
    }

    /**
     * Checks if a command is a {@code ./gradlew} invocation that runs tests.
     * Uses indexOf instead of regex to avoid Sonar S5852.
     */
    private static boolean isGradlewTestCommand(String cmd) {
        int idx = cmd.indexOf("./gradlew");
        return idx >= 0 && cmd.indexOf("test", idx + 9) >= 0;
    }

    /**
     * Checks if a command is a {@code python -m pytest} invocation.
     * Uses indexOf chain instead of regex to avoid Sonar S5852.
     */
    private static boolean isPythonPytestCommand(String cmd) {
        int pyIdx = cmd.indexOf("python");
        if (pyIdx < 0) return false;
        int mIdx = cmd.indexOf("-m", pyIdx + 6);
        if (mIdx < 0) return false;
        return cmd.indexOf("pytest", mIdx + 2) >= 0;
    }

    /**
     * Detects git commands including prefixed variants (env/sudo wrappers, VAR=val prefixes,
     * piped/chained commands). Uses indexOf instead of regex for Sonar S5852 compliance.
     */
    private static boolean isGitCommand(String cmd) {
        if (cmd.startsWith("git ") || cmd.equals("git")) return true;
        if (cmd.contains("&& git ") || cmd.contains("; git ") || cmd.contains("| git ")) return true;
        // sudo/env/command/nohup prefix: e.g. "sudo git status"
        if (cmd.startsWith("sudo git") || cmd.startsWith("env git") ||
            cmd.startsWith("command git") || cmd.startsWith("nohup git")) return true;
        // env with VAR=val: e.g. "env GIT_DIR=/tmp git status" or "GIT_DIR=/tmp git ..."
        // Check if "git" appears as a word (preceded by space)
        int gitIdx = cmd.indexOf(" git");
        return gitIdx > 0 && (gitIdx + 4 >= cmd.length() || cmd.charAt(gitIdx + 4) == ' ');
    }

    /**
     * Detects cat/head/tail/less/more commands (including piped variants).
     * Uses startsWith/contains instead of regex for Sonar S5852 compliance.
     */
    private static boolean isFileViewerCommand(String cmd) {
        return cmd.startsWith("cat ") || cmd.startsWith("head ") || cmd.startsWith("tail ") ||
            cmd.startsWith("less ") || cmd.startsWith("more ") ||
            cmd.contains("| cat ") || cmd.contains("&& cat ") || cmd.contains("; cat ");
    }

    /**
     * Checks if a command starts with a bare test runner name (jest, vitest, mocha, etc.).
     * Uses startsWith instead of regex for Sonar S5852 compliance.
     */
    private static boolean isBareTestRunner(String cmd) {
        String[] runners = {"jest", "vitest", "mocha", "ava", "tap", "jasmine"};
        for (String runner : runners) {
            if (cmd.equals(runner) || cmd.startsWith(runner + " ")) return true;
        }
        return false;
    }

    /**
     * Map abuse type to a human-readable error message for MCP tool responses.
     *
     * @param abuseType the detected abuse category
     * @param toolName  the tool that detected the abuse (e.g. "run_command", "run_in_terminal")
     */
    public static String getCommandAbuseMessage(String abuseType, String toolName) {
        return switch (abuseType) {
            case "git" -> "Error: git commands are not allowed via " + toolName + " (causes IntelliJ buffer desync). "
                + "Use the dedicated git tools instead: git_status, git_diff, git_log, git_commit, "
                + "git_stage, git_unstage, git_branch, git_stash, git_show, git_blame, git_push, git_remote, "
                + "git_fetch, git_pull, git_merge, git_rebase, git_cherry_pick, git_tag, git_reset.";
            case "cat" ->
                "Error: cat/head/tail/less/more are not allowed via " + toolName + " (reads stale disk files). "
                    + "Use intellij_read_file to read live editor buffers instead.";
            case "sed" -> "Error: sed is not allowed via " + toolName + " (bypasses IntelliJ editor buffers). "
                + "Use edit_text with old_str/new_str for file editing instead.";
            case "grep" -> "Error: grep/rg on project source files is not allowed via " + toolName + " (searches "
                + "stale disk files). Use search_text or search_symbols to search live editor buffers. "
                + "Note: grep IS allowed when targeting paths outside source roots (e.g. log files, downloaded "
                + "CI artifacts, build/ output) — pass an explicit file/dir argument to use it.";
            case "find" -> "Error: find commands are not allowed via " + toolName + ". "
                + "Use list_project_files to find files instead.";
            case "compile" -> "Error: Gradle compile tasks are not allowed via " + toolName + ". "
                + "Use build_project to compile via IntelliJ's incremental compiler instead.";
            case "test" -> "Error: test commands are not allowed via " + toolName + " (including build/check/verify " +
                "which implicitly run tests). Use run_tests to run tests with proper IntelliJ integration instead.";
            default -> "Error: this command is not allowed via " + toolName + ". Use dedicated IntelliJ tools instead.";
        };
    }

    /**
     * Map abuse type to a human-readable error message for MCP tool responses.
     * Uses "run_command" as the default tool name for backward compatibility.
     */
    public static String getCommandAbuseMessage(String abuseType) {
        return getCommandAbuseMessage(abuseType, "run_command");
    }
}
