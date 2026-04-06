package com.github.catatafishen.agentbridge.psi.java;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * Java-specific class outline logic isolated from CodeNavigationTools so that
 * the plugin passes verification on non-Java IDEs (PyCharm, WebStorm, GoLand).
 * <p>
 * This class references {@code com.intellij.psi.JavaPsiFacade}, {@code PsiClass},
 * {@code PsiMethod}, {@code PsiField}, and {@code PsiShortNamesCache} which only exist
 * when {@code com.intellij.modules.java} is present. It is only ever loaded when
 * that module is confirmed available, preventing {@link NoClassDefFoundError} in
 * non-Java IDEs.
 */
public class CodeNavigationJavaSupport {

    private CodeNavigationJavaSupport() {
    }

    @Nullable
    private static PsiClass resolveClass(Project project, String className) {
        var facade = JavaPsiFacade.getInstance(project);
        var scope = GlobalSearchScope.allScope(project);
        var psiClass = facade.findClass(className, scope);
        if (psiClass != null) return psiClass;

        var cache = PsiShortNamesCache.getInstance(project);
        var shortName = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;
        var classes = cache.getClassesByName(shortName, scope);
        return classes.length > 0 ? classes[0] : null;
    }

    private static void appendClassHeader(PsiClass psiClass, String className, StringBuilder sb) {
        String kind = ToolUtils.classifyElement(psiClass);
        String qName = psiClass.getQualifiedName();
        sb.append(kind != null ? kind : "class").append(" ").append(qName != null ? qName : className);

        var superClass = psiClass.getSuperClass();
        if (superClass != null && !"java.lang.Object".equals(superClass.getQualifiedName())) {
            sb.append(" extends ").append(superClass.getQualifiedName());
        }
        var interfaces = psiClass.getInterfaces();
        if (interfaces.length > 0) {
            sb.append(" implements ");
            for (int i = 0; i < interfaces.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(interfaces[i].getQualifiedName());
            }
        }
        sb.append("\n\n");
    }

    /**
     * Computes a structured outline of a Java class by fully qualified name.
     * Must be called inside a read action.
     */
    public static String computeClassOutline(Project project, String className, boolean includeInherited) {
        try {
            var psiClass = resolveClass(project, className);
            if (psiClass == null) return "Class not found: " + className;

            StringBuilder sb = new StringBuilder();
            appendClassHeader(psiClass, className, sb);
            appendConstructors(psiClass, sb);
            appendMethods(psiClass, sb, includeInherited);
            appendFields(psiClass, sb, includeInherited);
            appendInnerClasses(psiClass, sb);

            return sb.toString().trim();
        } catch (Exception e) {
            return "Error resolving class " + className + ": " + e.getMessage();
        }
    }

    private static void appendConstructors(PsiClass psiClass, StringBuilder sb) {
        var constructors = psiClass.getConstructors();
        if (constructors.length > 0) {
            sb.append("Constructors:\n");
            for (var ctor : constructors) {
                sb.append("  ").append(formatMethodSignature(ctor)).append("\n");
            }
            sb.append("\n");
        }
    }

    private static boolean shouldIncludeMethod(PsiMethod method, PsiClass psiClass, boolean includeInherited) {
        if (method.isConstructor()) return false;
        var containingClass = method.getContainingClass();
        if (!includeInherited && containingClass != psiClass) return false;
        return containingClass == null || !"java.lang.Object".equals(containingClass.getQualifiedName());
    }

    private static void appendMethods(PsiClass psiClass, StringBuilder sb, boolean includeInherited) {
        var methods = includeInherited ? psiClass.getAllMethods() : psiClass.getMethods();
        if (methods.length > 0) {
            sb.append("Methods:\n");
            Set<String> seen = new HashSet<>();
            for (var method : methods) {
                if (!shouldIncludeMethod(method, psiClass, includeInherited)) continue;
                String sig = formatMethodSignature(method);
                if (seen.add(sig)) {
                    sb.append("  ").append(sig).append("\n");
                }
            }
            sb.append("\n");
        }
    }

    private static void appendFields(PsiClass psiClass, StringBuilder sb, boolean includeInherited) {
        var fields = includeInherited ? psiClass.getAllFields() : psiClass.getFields();
        if (fields.length > 0) {
            sb.append("Fields:\n");
            for (var field : fields) {
                var containingClass = field.getContainingClass();
                if (!includeInherited && containingClass != psiClass) continue;
                sb.append("  ").append(formatFieldSignature(field)).append("\n");
            }
            sb.append("\n");
        }
    }

    private static void appendInnerClasses(PsiClass psiClass, StringBuilder sb) {
        var innerClasses = psiClass.getInnerClasses();
        if (innerClasses.length > 0) {
            sb.append("Inner classes:\n");
            for (var inner : innerClasses) {
                String innerKind = ToolUtils.classifyElement(inner);
                sb.append("  ").append(innerKind != null ? innerKind : "class").append(" ");
                sb.append(inner.getName()).append("\n");
            }
        }
    }

    private static String formatMethodSignature(PsiMethod method) {
        StringBuilder sig = new StringBuilder();
        var modifiers = method.getModifierList();
        if (modifiers.hasModifierProperty("public")) sig.append("public ");
        else if (modifiers.hasModifierProperty("protected")) sig.append("protected ");
        else if (modifiers.hasModifierProperty("private")) sig.append("private ");
        if (modifiers.hasModifierProperty("static")) sig.append("static ");
        if (modifiers.hasModifierProperty("abstract")) sig.append("abstract ");
        var returnType = method.getReturnType();
        if (returnType != null) {
            sig.append(returnType.getPresentableText()).append(" ");
        }
        sig.append(method.getName()).append("(");
        var params = method.getParameterList().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(params[i].getType().getPresentableText());
            sig.append(" ").append(params[i].getName());
        }
        sig.append(")");
        var throwsList = method.getThrowsList().getReferencedTypes();
        if (throwsList.length > 0) {
            sig.append(" throws ");
            for (int i = 0; i < throwsList.length; i++) {
                if (i > 0) sig.append(", ");
                sig.append(throwsList[i].getPresentableText());
            }
        }
        return sig.toString();
    }

    private static String formatFieldSignature(PsiField field) {
        StringBuilder sig = new StringBuilder();
        var modifiers = field.getModifierList();
        if (modifiers != null) {
            if (modifiers.hasModifierProperty("public")) sig.append("public ");
            else if (modifiers.hasModifierProperty("protected")) sig.append("protected ");
            else if (modifiers.hasModifierProperty("private")) sig.append("private ");
            if (modifiers.hasModifierProperty("static")) sig.append("static ");
            if (modifiers.hasModifierProperty("final")) sig.append("final ");
        }
        sig.append(field.getType().getPresentableText()).append(" ").append(field.getName());
        return sig.toString();
    }
}
