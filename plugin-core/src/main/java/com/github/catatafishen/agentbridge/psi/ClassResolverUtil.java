package com.github.catatafishen.agentbridge.psi;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.UsageSearchContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for resolving class names to fully-qualified names and their containing modules.
 * Used by {@link RunConfigurationService} and the testing tools for class resolution
 * without depending on the former {@code RefactoringTools} handler.
 */
public final class ClassResolverUtil {

    private static final String TYPE_CLASS = "class";

    /**
     * Functional interface for class resolution, used by {@link RunConfigurationService}.
     */
    public interface ClassResolver {
        @SuppressWarnings("unused")
            // parameter used by implementations
        ClassInfo resolveClass(String className);
    }

    public record ClassInfo(String fqn, Module module) {
    }

    private ClassResolverUtil() {
    }

    /**
     * Resolves a class name (simple or fully-qualified) to its FQN and containing module.
     */
    public static ClassInfo resolveClass(Project project, String className) {
        return ApplicationManager.getApplication().runReadAction((Computable<ClassInfo>) () -> {
            String searchName = className.contains(".")
                ? className.substring(className.lastIndexOf('.') + 1) : className;
            List<ClassInfo> matches = new ArrayList<>();
            PsiSearchHelper.getInstance(project).processElementsWithWord(
                (element, offset) -> processClassCandidate(project, element, searchName, className, matches),
                GlobalSearchScope.projectScope(project),
                searchName,
                UsageSearchContext.IN_CODE,
                true
            );
            return matches.isEmpty() ? new ClassInfo(className, null) : matches.getFirst();
        });
    }

    @SuppressWarnings({"java:S3516", "SameReturnValue"}) // always returns true to continue PSI search
    private static boolean processClassCandidate(Project project, PsiElement element, String searchName,
                                                 String className, List<ClassInfo> matches) {
        String type = ToolUtils.classifyElement(element);
        if (!TYPE_CLASS.equals(type) || !(element instanceof PsiNamedElement named)
            || !searchName.equals(named.getName())) {
            return true;
        }
        try {
            var getQualifiedName = element.getClass().getMethod("getQualifiedName");
            String fqn = (String) getQualifiedName.invoke(element);
            if (fqn != null && (!className.contains(".") || fqn.equals(className))) {
                VirtualFile vf = element.getContainingFile().getVirtualFile();
                Module mod = vf != null
                    ? ProjectFileIndex.getInstance(project).getModuleForFile(vf) : null;
                matches.add(new ClassInfo(fqn, mod));
            }
        } catch (NoSuchMethodException | java.lang.reflect.InvocationTargetException
                 | IllegalAccessException ignored) {
            // Reflection method not available or failed
        }
        return true;
    }
}
