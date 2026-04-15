package com.github.catatafishen.agentbridge.psi.java;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.search.searches.OverridingMethodsSearch;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    private static final String JAR_INDICATOR = ".jar!";
    private static final String HEADER_SUFFIX = ":\n\n";

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

    /**
     * Finds all implementations of a class/interface or overrides of a method.
     *
     * @param project    current project
     * @param symbolName class, interface, or method name
     * @param filePath   optional file path for method context
     * @param line       optional line number to disambiguate the method
     * @return formatted list of implementations, or an error/empty message
     */
    public static String findImplementations(Project project, String symbolName,
                                             @Nullable String filePath, int line) {
        PsiClass psiClass = resolveClassByName(project, symbolName);
        if (psiClass != null && (psiClass.isInterface()
            || psiClass.hasModifierProperty(PsiModifier.ABSTRACT))) {
            return findClassImplementations(project, psiClass);
        }

        if (filePath != null && line > 0) {
            String methodResult = findMethodOverrides(project, symbolName, filePath, line);
            if (methodResult != null) return methodResult;
        }

        if (psiClass != null) {
            return findClassImplementations(project, psiClass);
        }

        return "No implementations found for: " + symbolName;
    }

    private static String findClassImplementations(Project project, PsiClass psiClass) {
        var inheritors = ClassInheritorsSearch.search(
            psiClass, GlobalSearchScope.projectScope(project), true).findAll();
        if (inheritors.isEmpty()) {
            String name = psiClass.getQualifiedName();
            return "No implementations found for: " + (name != null ? name : psiClass.getName());
        }
        String basePath = project.getBasePath();
        StringBuilder sb = new StringBuilder();
        String qName = psiClass.getQualifiedName();
        sb.append("Implementations of ").append(qName != null ? qName : psiClass.getName()).append(HEADER_SUFFIX);
        for (PsiClass inheritor : inheritors) {
            appendClassLocation(sb, inheritor, basePath);
        }
        return sb.toString();
    }

    private static @Nullable String findMethodOverrides(Project project, String methodName,
                                                        String filePath, int line) {
        PsiMethod method = resolveMethodAtLocation(project, filePath, line, methodName);
        if (method == null) return null;

        var overrides = OverridingMethodsSearch.search(method,
            GlobalSearchScope.projectScope(project), true).findAll();
        if (overrides.isEmpty()) {
            return "No overriding methods found for: " + methodName;
        }

        String basePath = project.getBasePath();
        StringBuilder sb = new StringBuilder();
        sb.append("Overrides of ").append(formatMethodSignature(method)).append(HEADER_SUFFIX);
        for (PsiMethod override : overrides) {
            appendMethodLocation(sb, override, basePath);
        }
        return sb.toString();
    }

    private static @Nullable PsiMethod resolveMethodAtLocation(Project project, String filePath,
                                                               int line, String methodName) {
        ToolUtils.LineContext ctx = com.github.catatafishen.agentbridge.psi.ToolUtils.resolveLineContext(project, filePath, line);
        if (ctx == null) return null;

        PsiMethod[] found = {null};
        ctx.psiFile().accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element instanceof PsiMethod m && methodName.equals(m.getName())) {
                    int offset = m.getTextOffset();
                    if (offset >= ctx.lineStart() && offset <= ctx.lineEnd()) {
                        found[0] = m;
                        stopWalking();
                        return;
                    }
                }
                super.visitElement(element);
            }
        });
        return found[0];
    }

    private static void appendClassLocation(StringBuilder sb, PsiClass cls, String basePath) {
        String name = cls.getQualifiedName();
        sb.append("  ").append(cls.isInterface() ? "interface " : "class ")
            .append(name != null ? name : cls.getName());
        PsiFile file = cls.getContainingFile();
        if (file != null && file.getVirtualFile() != null && basePath != null) {
            String path = file.getVirtualFile().getPath();
            if (!path.contains(JAR_INDICATOR)) {
                sb.append(" (").append(ToolUtils.relativize(basePath, path));
                Document doc = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
                if (doc != null) {
                    int lineNum = doc.getLineNumber(cls.getTextOffset()) + 1;
                    sb.append(":").append(lineNum);
                }
                sb.append(")");
            }
        }
        sb.append("\n");
    }

    private static void appendMethodLocation(StringBuilder sb, PsiMethod method, String basePath) {
        PsiClass containingClass = method.getContainingClass();
        String className = containingClass != null ? containingClass.getQualifiedName() : null;
        sb.append("  ");
        if (className != null) sb.append(className).append(".");
        sb.append(method.getName()).append("()");
        appendFileLocation(sb, method, basePath);
        sb.append("\n");
    }

    private static void appendFileLocation(StringBuilder sb, PsiElement element, String basePath) {
        com.github.catatafishen.agentbridge.psi.ToolUtils.appendFileLocation(sb, element, basePath);
    }

    private static String formatMethodSignature(PsiMethod method) {
        PsiClass containingClass = method.getContainingClass();
        String className = containingClass != null ? containingClass.getQualifiedName() : null;
        if (className != null) {
            return className + "." + method.getName() + "()";
        }
        return method.getName() + "()";
    }

    private static String getClassFile(PsiClass cls, String basePath) {
        PsiFile file = cls.getContainingFile();
        if (file != null && file.getVirtualFile() != null && basePath != null) {
            String path = file.getVirtualFile().getPath();
            if (path.contains(JAR_INDICATOR)) return "";
            return " (" + ToolUtils.relativize(basePath, path) + ")";
        }
        return "";
    }
}
