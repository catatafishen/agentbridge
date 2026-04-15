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
import com.intellij.testIntegration.TestFramework;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists test classes and methods in the project.
 * <p>
 * Uses IntelliJ's {@link TestFramework} extension point for framework-agnostic
 * test detection — works with JUnit, TestNG, pytest, and any other framework
 * that registers a {@code TestFramework} implementation.
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
            "Uses IntelliJ's test framework detection — works with JUnit, TestNG, pytest, and any other framework the IDE recognizes. " +
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
            var frameworks = TestFramework.EXTENSION_NAME.getExtensionList();

            fileIndex.iterateContent(vf -> {
                if (isTestSourceFile(vf, filePattern, compiledGlob, fileIndex)) {
                    collectTestMethodsFromFile(vf, basePath, tests, frameworks);
                }
                return tests.size() < 500;
            });

            if (tests.isEmpty()) return "No tests found";
            return tests.size() + " tests:\n" + String.join("\n", tests);
        });
    }

    private boolean isTestSourceFile(VirtualFile vf, String filePattern, java.util.regex.Pattern compiledGlob, ProjectFileIndex fileIndex) {
        if (vf.isDirectory()) return false;
        if (!fileIndex.isInTestSourceContent(vf)) return false;
        return filePattern.isEmpty() || !ToolUtils.doesNotMatchGlob(vf.getName(), filePattern, compiledGlob);
    }

    private void collectTestMethodsFromFile(VirtualFile vf, String basePath, List<String> tests,
                                            List<TestFramework> frameworks) {
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
                    && isTestElement(element, frameworks)) {
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

    /**
     * Checks if a PSI element is a test method using IntelliJ's registered test frameworks.
     * Iterates all {@link TestFramework} extensions (JUnit, TestNG, pytest, etc.) and returns
     * true if any framework recognizes the element as a test method.
     */
    private static boolean isTestElement(PsiElement element, List<TestFramework> frameworks) {
        for (TestFramework framework : frameworks) {
            try {
                if (framework.isTestMethod(element)) return true;
            } catch (Exception ignored) {
                // Framework may not support this element type — skip silently
            }
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
        return vf(element);
    }

    /**
     * Fallback for test elements not inside a class (e.g., top-level Python test functions,
     * Go test functions). Returns the containing file name without extension.
     */
    private static String vf(PsiElement element) {
        PsiFile file = element.getContainingFile();
        if (file == null) return "UnknownFile";
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
