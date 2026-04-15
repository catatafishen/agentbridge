package com.github.catatafishen.agentbridge.psi;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * Language-agnostic call hierarchy support.
 * <p>
 * Finds all callers of any named PSI element (method, function, procedure, etc.) by:
 * <ol>
 *   <li>Resolving the named element by name + file + line using {@link PsiNameIdentifierOwner}</li>
 *   <li>Searching for all references using the platform-level {@link ReferencesSearch}</li>
 * </ol>
 * Works across all JetBrains IDEs: IntelliJ IDEA (Java/Kotlin), PyCharm (Python),
 * GoLand (Go), WebStorm (JS/TS), CLion (C/C++), etc.
 */
public class CallHierarchySupport {

    private CallHierarchySupport() {
    }

    /**
     * Finds all callers of the named element at the given file/line.
     * Must be called inside a read action.
     */
    public static String getCallHierarchy(@NotNull Project project, @NotNull String elementName,
                                          @NotNull String filePath, int line) {
        PsiNameIdentifierOwner element = resolveNamedElementAtLocation(project, filePath, line, elementName);
        if (element == null) {
            return "Error: Could not find '" + elementName + "' at " + filePath + ":" + line;
        }

        Collection<PsiReference> references = ReferencesSearch.search(
            element, GlobalSearchScope.projectScope(project)).findAll();
        if (references.isEmpty()) {
            return "No callers found for: " + formatElementSignature(element);
        }

        String basePath = project.getBasePath();
        StringBuilder sb = new StringBuilder();
        sb.append("Callers of ").append(formatElementSignature(element)).append(":\n\n");
        for (PsiReference ref : references) {
            appendCallerInfo(sb, ref, basePath);
        }
        return sb.toString();
    }

    private static PsiNameIdentifierOwner resolveNamedElementAtLocation(@NotNull Project project,
                                                                        @NotNull String filePath,
                                                                        int line,
                                                                        @NotNull String elementName) {
        ToolUtils.LineContext ctx = ToolUtils.resolveLineContext(project, filePath, line);
        if (ctx == null) return null;

        PsiNameIdentifierOwner[] found = {null};
        ctx.psiFile().accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element instanceof PsiNameIdentifierOwner owner && elementName.equals(owner.getName())) {
                    int offset = owner.getTextOffset();
                    if (offset >= ctx.lineStart() && offset <= ctx.lineEnd()) {
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

    private static void appendCallerInfo(@NotNull StringBuilder sb, @NotNull PsiReference ref,
                                         @Nullable String basePath) {
        PsiElement element = ref.getElement();
        // Walk up the PSI tree to find the nearest named containing element (function/method/class)
        PsiNamedElement containingNamed = PsiTreeUtil.getParentOfType(element, PsiNamedElement.class);

        sb.append("  ");
        sb.append(containingNamed != null ? containingNamed.getName() : "(top level)");
        appendFileLocation(sb, element, basePath);
        sb.append("\n");
    }

    private static void appendFileLocation(@NotNull StringBuilder sb, @NotNull PsiElement element,
                                           @Nullable String basePath) {
        ToolUtils.appendFileLocation(sb, element, basePath);
    }

    private static @NotNull String formatElementSignature(@NotNull PsiNameIdentifierOwner element) {
        PsiElement parent = element.getParent();
        String name = element.getName();
        if (name == null) name = "(unknown)";
        if (parent instanceof PsiNamedElement namedParent && namedParent.getName() != null) {
            return namedParent.getName() + "." + name + "()";
        }
        return name + "()";
    }
}
