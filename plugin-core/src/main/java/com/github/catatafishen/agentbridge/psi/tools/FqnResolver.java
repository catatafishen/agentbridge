package com.github.catatafishen.agentbridge.psi.tools;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves fully-qualified names (FQNs) to PSI elements.
 * <p>
 * Uses reflection to access {@code JavaPsiFacade} so this works in any JetBrains IDE
 * without a compile-time dependency on the Java plugin. Returns {@code null} when:
 * <ul>
 *   <li>The Java plugin is not available (e.g., WebStorm, PyCharm)</li>
 *   <li>The FQN does not match any class in the project or libraries</li>
 * </ul>
 * Callers should fall back to file+line-based resolution when FQN resolution fails.
 */
public final class FqnResolver {

    private static final Logger LOG = Logger.getInstance(FqnResolver.class);
    private static final String GET_INSTANCE = "getInstance";

    private FqnResolver() {
    }

    /**
     * Heuristic: returns {@code true} if the string looks like a fully-qualified name
     * (contains at least one dot and does not look like a file path).
     */
    public static boolean looksLikeFqn(@NotNull String symbol) {
        if (!symbol.contains(".")) return false;
        // File paths contain slashes or common extensions
        if (symbol.contains("/") || symbol.contains("\\")) return false;
        if (symbol.endsWith(".java") || symbol.endsWith(".kt") || symbol.endsWith(".py")
            || symbol.endsWith(".ts") || symbol.endsWith(".js")) return false;
        // Must start with a letter (package names do)
        return Character.isLetter(symbol.charAt(0));
    }

    /**
     * Splits a symbol string into class name and optional member name.
     * Tries to resolve as a full class first; if that fails, splits at the last dot.
     *
     * @return array of [className, memberName] where memberName may be null
     */
    public static String @NotNull [] splitSymbolParts(@NotNull String symbol, @NotNull Project project) {
        // Try resolving the full string as a class first
        if (resolveClass(symbol, project) != null) {
            return new String[]{symbol, null};
        }
        // Split at last dot: everything before is the class, after is the member
        int lastDot = symbol.lastIndexOf('.');
        if (lastDot > 0) {
            return new String[]{symbol.substring(0, lastDot), symbol.substring(lastDot + 1)};
        }
        return new String[]{symbol, null};
    }

    /**
     * Resolves a fully-qualified class name to a PSI element.
     * Uses reflection to call {@code JavaPsiFacade.findClass()}.
     *
     * @return the resolved PsiElement, or null if not found or Java plugin unavailable
     */
    public static @Nullable PsiElement resolveClass(@NotNull String fqn, @NotNull Project project) {
        try {
            Class<?> facadeClass = Class.forName("com.intellij.psi.JavaPsiFacade");
            Object facade = facadeClass.getMethod(GET_INSTANCE, Project.class).invoke(null, project);
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);
            return (PsiElement) facadeClass
                .getMethod("findClass", String.class, GlobalSearchScope.class)
                .invoke(facade, fqn, scope);
        } catch (ClassNotFoundException e) {
            LOG.debug("Java plugin not available for FQN resolution");
            return null;
        } catch (Exception e) {
            LOG.warn("FQN resolution failed for: " + fqn, e);
            return null;
        }
    }

    /**
     * Resolves a fully-qualified symbol (class or class.member) to a PSI element.
     * For class-only FQNs, returns the class. For class.member FQNs, returns the member.
     *
     * @return the resolved PsiElement, or null if not found
     */
    public static @Nullable PsiElement resolve(@NotNull String fqn, @NotNull Project project) {
        String[] parts = splitSymbolParts(fqn, project);
        String className = parts[0];
        String memberName = parts[1];

        PsiElement resolvedClass = resolveClass(className, project);
        if (resolvedClass == null) return null;
        if (memberName == null) return resolvedClass;

        return findMemberInClass(resolvedClass, memberName);
    }

    /**
     * Searches for a named member (method, field, inner class) within a class element.
     * Searches children first, then grandchildren (for methods inside class bodies).
     */
    static @Nullable PsiElement findMemberInClass(@NotNull PsiElement classElement, @NotNull String memberName) {
        // Direct children first
        for (PsiElement child : classElement.getChildren()) {
            if (child instanceof PsiNamedElement named && memberName.equals(named.getName())) {
                return child;
            }
        }
        // Grandchildren (methods inside class body elements)
        for (PsiElement child : classElement.getChildren()) {
            if (child instanceof PsiNamedElement) {
                for (PsiElement grandchild : child.getChildren()) {
                    if (grandchild instanceof PsiNamedElement named && memberName.equals(named.getName())) {
                        return grandchild;
                    }
                }
            }
        }
        return null;
    }
}
