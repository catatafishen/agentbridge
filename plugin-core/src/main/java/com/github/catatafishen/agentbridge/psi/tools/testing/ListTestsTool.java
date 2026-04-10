package com.github.catatafishen.agentbridge.psi.tools.testing;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.ListTestsRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists test classes and methods in the project.
 */
public final class ListTestsTool extends TestingTool {

    private static final String PARAM_FILE_PATTERN = "file_pattern";

    public ListTestsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "list_tests";
    }

    @Override
    public @NotNull String displayName() {
        return "List Tests";
    }

    @Override
    public @NotNull String description() {
        return "List test classes and methods in the project. Returns fully-qualified test names with file paths and line numbers. " +
            "Use file_pattern to filter (e.g., '*IntegrationTest*'). Use run_tests to execute discovered tests.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.optional(PARAM_FILE_PATTERN, TYPE_STRING, "Optional glob pattern to filter test files (e.g., '*IntegrationTest*')", "")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return ListTestsRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        String filePattern = args.has(PARAM_FILE_PATTERN) ? args.get(PARAM_FILE_PATTERN).getAsString() : "";

        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            List<String> tests = new ArrayList<>();
            String basePath = project.getBasePath();
            ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
            var compiledGlob = filePattern.isEmpty() ? null : ToolUtils.compileGlob(filePattern);

            fileIndex.iterateContent(vf -> {
                if (isTestSourceFile(vf, filePattern, compiledGlob, fileIndex)) {
                    collectTestMethodsFromFile(vf, basePath, tests);
                }
                return tests.size() < 500;
            });

            if (tests.isEmpty()) return "No tests found";
            return tests.size() + " tests:\n" + String.join("\n", tests);
        });
    }

    private boolean isTestSourceFile(VirtualFile vf, String filePattern, java.util.regex.Pattern compiledGlob, ProjectFileIndex fileIndex) {
        if (vf.isDirectory()) return false;
        String name = vf.getName();
        if (!name.endsWith(ToolUtils.JAVA_EXTENSION) && !name.endsWith(".kt")) return false;
        if (!filePattern.isEmpty() && ToolUtils.doesNotMatchGlob(name, filePattern, compiledGlob)) return false;
        return fileIndex.isInTestSourceContent(vf);
    }

    private void collectTestMethodsFromFile(VirtualFile vf, String basePath, List<String> tests) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return;
        Document doc = FileDocumentManager.getInstance().getDocument(vf);

        psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (!(element instanceof PsiNamedElement named)) {
                    super.visitElement(element);
                    return;
                }
                String type = ToolUtils.classifyElement(element);
                if ((ToolUtils.ELEMENT_TYPE_METHOD.equals(type) || ToolUtils.ELEMENT_TYPE_FUNCTION.equals(type))
                    && hasTestAnnotation(element)) {
                    String methodName = named.getName();
                    String className = getContainingClassName(element);
                    String relPath = basePath != null ? relativize(basePath, vf.getPath()) : vf.getPath();
                    int line = doc != null ? doc.getLineNumber(element.getTextOffset()) + 1 : 0;
                    tests.add(String.format("%s.%s (%s:%d)", className, methodName, relPath, line));
                }
                super.visitElement(element);
            }
        });
    }

    private boolean hasTestAnnotation(PsiElement element) {
        return hasTestAnnotationViaReflection(element) || hasTestAnnotationViaText(element);
    }

    private boolean hasTestAnnotationViaReflection(PsiElement element) {
        try {
            var getModifierList = element.getClass().getMethod("getModifierList");
            Object modList = getModifierList.invoke(element);
            if (modList != null) {
                var getAnnotations = modList.getClass().getMethod("getAnnotations");
                Object[] annotations = (Object[]) getAnnotations.invoke(modList);
                for (Object anno : annotations) {
                    var getQualifiedName = anno.getClass().getMethod("getQualifiedName");
                    String qname = (String) getQualifiedName.invoke(anno);
                    if (qname != null && (qname.endsWith(".Test")
                        || qname.endsWith(".ParameterizedTest")
                        || qname.endsWith(".RepeatedTest"))) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            // Reflection may not work for all element types
        }
        return false;
    }

    private boolean hasTestAnnotationViaText(PsiElement element) {
        PsiElement prev = element.getPrevSibling();
        int depth = 0;
        while (prev != null && depth < 5) {
            if (prev instanceof PsiNamedElement && ToolUtils.classifyElement(prev) != null) break;
            String text = prev.getText().trim();
            if (text.startsWith("@Test") || text.startsWith("@ParameterizedTest")
                || text.startsWith("@RepeatedTest")
                || text.startsWith("@org.junit")) {
                return true;
            }
            prev = prev.getPrevSibling();
            depth++;
        }
        return false;
    }

    private String getContainingClassName(PsiElement element) {
        PsiElement parent = element.getParent();
        while (parent != null) {
            if (parent instanceof PsiNamedElement named) {
                String type = ToolUtils.classifyElement(parent);
                if (ToolUtils.ELEMENT_TYPE_CLASS.equals(type)) return named.getName();
            }
            parent = parent.getParent();
        }
        return "UnknownClass";
    }
}
