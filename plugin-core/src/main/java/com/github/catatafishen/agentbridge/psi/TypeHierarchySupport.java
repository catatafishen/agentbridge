package com.github.catatafishen.agentbridge.psi;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.DefinitionsScopedSearch;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * Language-agnostic subtype and implementation search.
 * Uses {@link DefinitionsScopedSearch}, which delegates to language-specific
 * query executors (Java, Kotlin, TypeScript, Python, etc.) via extension points.
 * This means it works wherever the IDE has registered an implementation search,
 * without any dependency on Java PSI classes.
 * <p>
 * Contrast with {@code RefactoringJavaSupport} which uses {@code ClassInheritorsSearch}
 * and {@code JavaPsiFacade} — those require {@code com.intellij.modules.java}.
 */
public class TypeHierarchySupport {

    private TypeHierarchySupport() {
    }

    /**
     * Finds all subtypes and direct implementations of the named element at the given
     * source location. Delegates to {@link DefinitionsScopedSearch} which is language-agnostic.
     * <p>
     * Symbol resolution uses {@link ToolUtils#resolveNamedElement}: tries to locate a declaration
     * on the target line first, then falls back to reference-based resolution at any
     * whole-identifier occurrence of {@code symbol} on the line. The fallback lets the caller
     * point at either a declaration or a usage (e.g. the call site of an overridable method)
     * without breaking the lookup — important for languages like C/C++ where leaf identifiers
     * usually have no reference of their own.
     *
     * @param project  current project
     * @param filePath absolute or project-relative path to the source file
     * @param line     1-based line number where the symbol is defined OR used
     * @param symbol   symbol name (used for display and disambiguation)
     * @return formatted result text, or an error string starting with {@code "Error: "}
     */
    public static @NotNull String findSubtypes(@NotNull Project project,
                                               @NotNull String filePath,
                                               int line,
                                               @NotNull String symbol) {
        ToolUtils.LineContext ctx = ToolUtils.resolveLineContext(project, filePath, line);
        if (ctx == null) {
            return "Error: Could not read '" + filePath + "'. Check the path is correct.";
        }

        com.intellij.psi.PsiNameIdentifierOwner element = ToolUtils.resolveNamedElement(ctx, symbol);
        if (element == null) {
            return "Error: Symbol '" + symbol + "' not found at " + filePath + ":" + line;
        }

        SearchScope scope = GlobalSearchScope.projectScope(project);
        Collection<PsiElement> results = DefinitionsScopedSearch.search(element, scope).findAll();

        if (results.isEmpty()) {
            return "No subtypes or implementations found for: " + symbol;
        }

        String basePath = project.getBasePath();
        StringBuilder sb = new StringBuilder();
        sb.append("Subtypes/Implementations of ").append(symbol).append(":\n\n");
        for (PsiElement result : results) {
            if (result instanceof PsiNamedElement named) {
                sb.append("  ").append(named.getName());
            } else {
                sb.append("  (unnamed)");
            }
            ToolUtils.appendFileLocation(sb, result, basePath);
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Finds the supertypes of the named element at the given source location using
     * {@link ToolUtils#findSuperElementsViaPlatform} (a reflective wrapper around
     * {@code com.intellij.psi.impl.FindSuperElementsHelper.findSuperElements}). The helper
     * ships with the Java module — in non-Java IDEs that don't bundle Java, it returns
     * {@code null} and this method returns a clear error explaining the limitation. No
     * manual-action suggestions are emitted (autonomous agents cannot press hotkeys).
     * <p>
     * Walks the inheritance chain recursively up to {@value #MAX_SUPERTYPES_DEPTH} levels
     * (matches the Java-path depth limit) with cycle detection.
     *
     * @return formatted hierarchy text, or an error string starting with {@code "Error: "}
     */
    public static @NotNull String findSupertypes(@NotNull Project project,
                                                 @NotNull String filePath,
                                                 int line,
                                                 @NotNull String symbol) {
        ToolUtils.LineContext ctx = ToolUtils.resolveLineContext(project, filePath, line);
        if (ctx == null) {
            return "Error: Could not read '" + filePath + "'. Check the path is correct.";
        }
        com.intellij.psi.PsiNameIdentifierOwner element = ToolUtils.resolveNamedElement(ctx, symbol);
        if (element == null) {
            return "Error: Symbol '" + symbol + "' not found at " + filePath + ":" + line;
        }

        PsiElement[] firstLevel = ToolUtils.findSuperElementsViaPlatform(element);
        if (firstLevel == null) {
            return "Error: Programmatic supertypes lookup is not available in this IDE: the "
                + "platform helper (com.intellij.psi.impl.FindSuperElementsHelper) ships with "
                + "the Java module and is not present here, and the IntelliJ platform has no "
                + "other language-agnostic supertype-search API.";
        }
        if (firstLevel.length == 0) {
            return "No supertypes found for: " + symbol;
        }

        String basePath = project.getBasePath();
        StringBuilder sb = new StringBuilder();
        sb.append("Supertypes of ").append(symbol).append(":\n\n");
        java.util.Set<PsiElement> visited = new java.util.HashSet<>();
        visited.add(element);
        appendSupertypesRecursive(sb, firstLevel, "  ", basePath, visited, 1);
        return sb.toString();
    }

    private static final int MAX_SUPERTYPES_DEPTH = 10;

    private static void appendSupertypesRecursive(@NotNull StringBuilder sb,
                                                  PsiElement @NotNull [] supers,
                                                  @NotNull String indent,
                                                  @org.jetbrains.annotations.Nullable String basePath,
                                                  @NotNull java.util.Set<PsiElement> visited,
                                                  int depth) {
        for (PsiElement parent : supers) {
            if (parent == null || !visited.add(parent)) continue;
            sb.append(indent);
            if (parent instanceof PsiNamedElement named && named.getName() != null) {
                sb.append(named.getName());
            } else {
                sb.append("(unnamed)");
            }
            ToolUtils.appendFileLocation(sb, parent, basePath);
            sb.append("\n");
            if (depth >= MAX_SUPERTYPES_DEPTH) continue;
            PsiElement[] grand = ToolUtils.findSuperElementsViaPlatform(parent);
            if (grand != null && grand.length > 0) {
                appendSupertypesRecursive(sb, grand, indent + "  ", basePath, visited, depth + 1);
            }
        }
    }
}
