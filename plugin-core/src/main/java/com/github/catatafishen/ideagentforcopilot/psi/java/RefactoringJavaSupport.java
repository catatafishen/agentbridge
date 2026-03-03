package com.github.catatafishen.ideagentforcopilot.psi.java;

import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.search.searches.ClassInheritorsSearch;

import java.util.HashSet;
import java.util.Set;

/**
 * Java-specific type hierarchy support, isolated so the plugin
 * passes verification on non-Java IDEs (e.g. PyCharm, CLion).
 * <p>
 * All Java PSI references ({@code PsiClass}, {@code JavaPsiFacade},
 * {@code PsiShortNamesCache}, {@code ClassInheritorsSearch}) are
 * confined to this class. It is only loaded when
 * {@code com.intellij.modules.java} is available.
 */
public class RefactoringJavaSupport {

    private RefactoringJavaSupport() {
    }

    /**
     * Builds a human-readable type hierarchy string for the given symbol.
     *
     * @param project    current project
     * @param symbolName fully-qualified or short class/interface name
     * @param direction  "supertypes", "subtypes", or "both"
     * @return formatted hierarchy text, or an error message
     */
    public static String getTypeHierarchy(Project project, String symbolName, String direction) {
        PsiClass psiClass = resolveClassByName(project, symbolName);
        if (psiClass == null) {
            return "Error: Class/interface '" + symbolName + "' not found. " +
                "Use search_symbols to find the correct name.";
        }

        StringBuilder sb = new StringBuilder();
        String basePath = project.getBasePath();

        String qualifiedName = psiClass.getQualifiedName();
        sb.append("Type hierarchy for: ").append(qualifiedName != null ? qualifiedName : symbolName);
        sb.append(psiClass.isInterface() ? " (interface)" : " (class)").append("\n\n");

        if ("supertypes".equals(direction) || "both".equals(direction)) {
            sb.append("Supertypes:\n");
            appendSupertypes(psiClass, sb, basePath, "  ", new HashSet<>(), 0);
            sb.append("\n");
        }

        if ("subtypes".equals(direction) || "both".equals(direction)) {
            appendSubtypes(psiClass, sb, basePath);
        }

        return sb.toString();
    }

    private static PsiClass resolveClassByName(Project project, String symbolName) {
        JavaPsiFacade javaPsiFacade = JavaPsiFacade.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);

        PsiClass psiClass = javaPsiFacade.findClass(symbolName, scope);
        if (psiClass != null) return psiClass;

        PsiClass[] classes = javaPsiFacade.findClasses(symbolName, scope);
        if (classes.length == 0) {
            PsiShortNamesCache shortNameCache = PsiShortNamesCache.getInstance(project);
            classes = shortNameCache.getClassesByName(symbolName, scope);
        }
        return classes.length > 0 ? classes[0] : null;
    }

    private static void appendSubtypes(PsiClass psiClass, StringBuilder sb, String basePath) {
        sb.append("Subtypes/Implementations:\n");
        var searcher = ClassInheritorsSearch.search(
            psiClass, GlobalSearchScope.projectScope(psiClass.getProject()), true);
        var inheritors = searcher.findAll();
        if (inheritors.isEmpty()) {
            sb.append("  (none found in project scope)\n");
            return;
        }
        for (PsiClass inheritor : inheritors) {
            String iName = inheritor.getQualifiedName();
            String iFile = getClassFile(inheritor, basePath);
            sb.append("  ").append(inheritor.isInterface() ? "interface " : "class ")
                .append(iName != null ? iName : inheritor.getName())
                .append(iFile).append("\n");
        }
    }

    private static void appendSupertypes(PsiClass psiClass, StringBuilder sb,
                                         String basePath, String indent, Set<String> visited, int depth) {
        if (depth > 10) return;
        String qn = psiClass.getQualifiedName();
        if (qn != null && !visited.add(qn)) return;

        PsiClass superClass = psiClass.getSuperClass();
        if (superClass != null && !"java.lang.Object".equals(superClass.getQualifiedName())) {
            String superName = superClass.getQualifiedName();
            String file = getClassFile(superClass, basePath);
            sb.append(indent).append("extends ").append(superName != null ? superName : superClass.getName())
                .append(file).append("\n");
            appendSupertypes(superClass, sb, basePath, indent + "  ", visited, depth + 1);
        }

        for (PsiClass iface : psiClass.getInterfaces()) {
            String ifaceName = iface.getQualifiedName();
            if ("java.lang.Object".equals(ifaceName)) continue;
            String file = getClassFile(iface, basePath);
            sb.append(indent).append("implements ").append(ifaceName != null ? ifaceName : iface.getName())
                .append(file).append("\n");
            appendSupertypes(iface, sb, basePath, indent + "  ", visited, depth + 1);
        }
    }

    private static String getClassFile(PsiClass cls, String basePath) {
        PsiFile file = cls.getContainingFile();
        if (file != null && file.getVirtualFile() != null && basePath != null) {
            String path = file.getVirtualFile().getPath();
            if (path.contains(".jar!")) return "";
            return " (" + ToolUtils.relativize(basePath, path) + ")";
        }
        return "";
    }
}
