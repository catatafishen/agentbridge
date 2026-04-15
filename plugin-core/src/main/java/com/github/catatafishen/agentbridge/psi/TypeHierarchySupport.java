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
     *
     * @param project  current project
     * @param filePath absolute or project-relative path to the source file
     * @param line     1-based line number where the symbol is defined
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

        com.intellij.psi.PsiNameIdentifierOwner element = ToolUtils.findNamedElement(ctx, symbol);
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
}
