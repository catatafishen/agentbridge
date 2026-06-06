package com.github.catatafishen.agentbridge.psi.tools.testing;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.ListTestsRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
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
import java.util.regex.Pattern;

/**
 * Lists test classes and methods in the project.
 *
 * <p>Uses IntelliJ's {@link TestFramework} extension point for framework-agnostic
 * test detection (JUnit, TestNG, pytest, and any other registered framework).
 * File filtering relies on {@code ProjectFileIndex.isInTestSourceContent()} —
 * the IDE's authoritative answer on what constitutes test source.
 *
 * <h3>CLion / C++ notes</h3>
 * The {@code com.intellij.testFramework} extension point is declared in the Java
 * plugin ({@code java-impl.jar}). CLion does not load the Java plugin, so the
 * extension point is not registered there. If no frameworks are detected:
 * <ul>
 *   <li>The tool still scans test source files (files in directories the IDE considers
 *       test source roots).</li>
 *   <li>It surfaces a clear message if no test source directories are configured,
 *       telling the agent this is a project-configuration issue rather than a bug.</li>
 *   <li>It surfaces a clear message if test source files exist but no test-framework
 *       plugin is registered.</li>
 * </ul>
 * No file-name heuristics or method-name heuristics are used — if the IDE cannot
 * identify a test, neither can this tool.
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
        return """
            List test classes and methods in the project. Returns fully-qualified test names with \
            file paths and line numbers. Uses IntelliJ's test framework detection — works with \
            JUnit, TestNG, pytest, and any other framework the IDE recognizes. \
            Use file_pattern to filter (e.g., '*IntegrationTest*'). Use run_tests to execute discovered tests.""";
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
            Param.optional(PARAM_FILE_PATTERN, TYPE_STRING,
                "Optional glob pattern to filter test files (e.g., '*IntegrationTest*')", "")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return ListTestsRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        String filePattern = args.has(PARAM_FILE_PATTERN)
            ? args.get(PARAM_FILE_PATTERN).getAsString() : "";

        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            List<String> tests = new ArrayList<>();
            ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
            var compiledGlob = filePattern.isEmpty() ? null : ToolUtils.compileGlob(filePattern);
            List<TestFramework> frameworks = safeGetTestFrameworks();
            int[] totalTestSourceFiles = {0};

            fileIndex.iterateContent(vf -> {
                scanTestSourceFile(vf, filePattern, compiledGlob,
                    tests, frameworks, totalTestSourceFiles);
                return tests.size() < 500;
            });

            return summarizeResults(tests, frameworks, totalTestSourceFiles[0], filePattern);
        });
    }

    private void scanTestSourceFile(VirtualFile vf, String filePattern, Pattern compiledGlob,
                                    List<String> tests, List<TestFramework> frameworks,
                                    int[] totalTestSourceFiles) {
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        if (vf.isDirectory() || !fileIndex.isInTestSourceContent(vf)) return;
        totalTestSourceFiles[0]++;
        if (filePattern.isEmpty() || !ToolUtils.doesNotMatchGlob(vf.getName(), filePattern, compiledGlob)) {
            collectTestMethodsFromFile(vf, project.getBasePath(), tests, frameworks);
        }
    }

    private static String summarizeResults(List<String> tests, List<TestFramework> frameworks,
                                           int totalTestSourceFiles, String filePattern) {
        if (totalTestSourceFiles == 0) return noTestSourcesMessage(filePattern);
        if (tests.isEmpty() && frameworks.isEmpty()) return noFrameworkMessage(totalTestSourceFiles, filePattern);
        if (tests.isEmpty()) {
            return filePattern.isEmpty() ? "No tests found" : "No tests found matching '" + filePattern + "'";
        }
        return tests.size() + " tests:\n" + String.join("\n", tests);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns registered {@link TestFramework} extensions, or an empty list if the
     * {@code com.intellij.testFramework} extension point is not registered in this IDE.
     * Package-private for testing.
     */
    static List<TestFramework> safeGetTestFrameworks() {
        try {
            return TestFramework.EXTENSION_NAME.getExtensionList();
        } catch (ProcessCanceledException e) {
            throw e; // must not swallow cancellation
        } catch (Exception ignored) {
            // Extension point not registered (e.g. CLion without Java plugin)
            return List.of();
        }
    }

    private void collectTestMethodsFromFile(VirtualFile vf, String basePath,
                                            List<String> tests,
                                            List<TestFramework> frameworks) {
        if (frameworks.isEmpty()) return;
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return;
        Document doc = FileDocumentManager.getInstance().getDocument(vf);

        psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element instanceof PsiNamedElement named) {
                    addTestEntry(element, named, doc, vf, basePath, tests, frameworks);
                }
                super.visitElement(element);
            }
        });
    }

    private void addTestEntry(PsiElement element, PsiNamedElement named, Document doc,
                              VirtualFile vf, String basePath, List<String> tests,
                              List<TestFramework> frameworks) {
        String type = ToolUtils.classifyElement(element);
        boolean isMethod = ToolUtils.ELEMENT_TYPE_METHOD.equals(type)
            || ToolUtils.ELEMENT_TYPE_FUNCTION.equals(type);
        if (!isMethod || !isTestElement(element, frameworks)) return;
        String relPath = basePath != null ? relativize(basePath, vf.getPath()) : vf.getPath();
        int line = doc != null ? doc.getLineNumber(element.getTextOffset()) + 1 : 0;
        tests.add(String.format("%s.%s (%s:%d)",
            getContainingClassName(element), named.getName(), relPath, line));
    }

    private static boolean isTestElement(PsiElement element, List<TestFramework> frameworks) {
        for (TestFramework framework : frameworks) {
            try {
                if (framework.isTestMethod(element)) return true;
            } catch (ProcessCanceledException e) {
                throw e; // must not swallow cancellation
            } catch (Exception ignored) {
                // Framework may not support this element type
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

    private static String vf(PsiElement element) {
        PsiFile file = element.getContainingFile();
        if (file == null) return "UnknownFile";
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ── Informative messages when IDE cannot list tests ────────────────────────

    private static String noTestSourcesMessage(String filePattern) {
        String intro = filePattern.isEmpty()
            ? "No test source directories are configured in this project."
            : "No test source files matching '" + filePattern + "' found in any test source directory.";
        return intro + """

            Test discovery requires the IDE to know which directories contain tests. \
            Possible fixes:
            - Mark a directory as 'Test Sources Root': right-click the directory in the Project view \
            → Mark Directory as → Test Sources Root.
            - In CLion/CMake projects: enable testing in CMakeLists.txt (\
            enable_testing() + add_test()) and reload the CMake project — CLion may \
            automatically recognise test source directories.
            - Use run_configuration or run_tests with an explicit test name to run tests \
            without source-root configuration.""";
    }

    private static String noFrameworkMessage(int fileCount, String filePattern) {
        String scope = filePattern.isEmpty()
            ? fileCount + " test source file(s) found"
            : fileCount + " test source file(s) found (some may not match '" + filePattern + "')";
        return scope + ", but no test-framework plugin is registered in this IDE "
            + "(the com.intellij.testFramework extension point is not available). "
            + "Test method detection requires a framework plugin such as JUnit, "
            + "TestNG, or the IDE's built-in language test support.\n\n"
            + "In CLion (C/C++ projects), test methods cannot be listed this way. "
            + "Use run_configuration to run tests by name, or run_tests if a run "
            + "configuration already exists for the test target.";
    }
}
