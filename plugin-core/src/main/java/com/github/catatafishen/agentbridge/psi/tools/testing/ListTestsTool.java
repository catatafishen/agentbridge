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
import com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lists test classes and methods in the project.
 *
 * <p>Uses IntelliJ's {@link TestFramework} extension point for framework-agnostic
 * test detection when available (JUnit, TestNG, pytest, etc.). Falls back to
 * naming-convention and macro-expansion heuristics for IDEs that do not register
 * the {@code com.intellij.testFramework} extension point (e.g., CLion, which uses
 * the Java plugin only in IntelliJ IDEA).
 *
 * <h3>CLion / C++ fallback detection</h3>
 * Google Test's {@code TEST(Suite, Name)} macro expands to a class
 * {@code Suite_Name_Test} with a {@code TestBody()} method. The fallback catches:
 * <ul>
 *   <li>{@code TestBody()} method inside a class whose name contains {@code _Test_}
 *       → Google Test macro expansion</li>
 *   <li>Functions whose name starts with {@code test} (case-insensitive)
 *       → Catch2 sections and conventional C test function naming</li>
 *   <li>Functions whose name ends with {@code Test} (e.g., {@code myFeatureTest})
 *       → common C++ convention</li>
 * </ul>
 * The fallback scans all source content (not restricted to test source roots) and
 * additionally filters by file name heuristics (file name contains "test" or "spec")
 * unless the caller supplied an explicit {@code file_pattern}.
 */
public final class ListTestsTool extends TestingTool {

    private static final String PARAM_FILE_PATTERN = "file_pattern";

    /**
     * Google Test method name produced by macro expansion:
     * {@code TEST(Suite, Name)} → class {@code Suite_Name_Test}, method {@code TestBody}.
     */
    private static final String GTEST_BODY_METHOD = "TestBody";

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
            JUnit, TestNG, pytest, and any other framework the IDE recognizes. In CLion, falls \
            back to naming-convention and Google Test macro-expansion detection. \
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
            String basePath = project.getBasePath();
            ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
            var compiledGlob = filePattern.isEmpty() ? null : ToolUtils.compileGlob(filePattern);

            // getExtensionList() throws IllegalArgumentException in IDEs that don't load the
            // Java plugin (e.g. CLion), because the com.intellij.testFramework extension point
            // is declared in java-impl.jar and is never registered there.
            List<TestFramework> frameworks = safeGetTestFrameworks();
            boolean hasFrameworkSupport = !frameworks.isEmpty();

            fileIndex.iterateContent(vf -> {
                if (isTestSourceFile(vf, filePattern, compiledGlob, fileIndex, hasFrameworkSupport)) {
                    collectTestMethodsFromFile(vf, basePath, tests, frameworks);
                }
                return tests.size() < 500;
            });

            if (tests.isEmpty()) return "No tests found";
            return tests.size() + " tests:\n" + String.join("\n", tests);
        });
    }

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

    /**
     * Returns true if {@code vf} should be scanned for tests.
     *
     * <p>When {@link TestFramework} extensions are available, restricts to files in
     * test source roots (the standard behaviour for Java/Python/etc.). When no
     * extensions are registered (CLion), accepts any source-content file whose name
     * suggests it contains tests — or all source files if an explicit
     * {@code file_pattern} was supplied.
     */
    private boolean isTestSourceFile(VirtualFile vf, String filePattern,
                                     java.util.regex.Pattern compiledGlob,
                                     ProjectFileIndex fileIndex,
                                     boolean hasFrameworkSupport) {
        if (vf.isDirectory()) return false;
        if (!filePattern.isEmpty() && ToolUtils.doesNotMatchGlob(vf.getName(), filePattern, compiledGlob)) {
            return false;
        }

        if (hasFrameworkSupport) {
            // Standard: use the IDE's test-source-root marking
            return fileIndex.isInTestSourceContent(vf);
        }

        // Fallback (CLion / non-Java IDEs): accept any source file
        if (!fileIndex.isInSourceContent(vf)) return false;

        // Without an explicit pattern, apply a file-name heuristic to avoid scanning
        // every source file in the project — only include files whose name suggests
        // they contain tests (e.g., "my_feature_test.cpp", "TestSomething.cpp").
        if (filePattern.isEmpty()) {
            String lower = vf.getNameWithoutExtension().toLowerCase(Locale.ROOT);
            return lower.contains("test") || lower.contains("spec");
        }
        return true;
    }

    private void collectTestMethodsFromFile(VirtualFile vf, String basePath,
                                            List<String> tests,
                                            List<TestFramework> frameworks) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return;
        Document doc = FileDocumentManager.getInstance().getDocument(vf);

        psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element instanceof PsiNamedElement named) {
                    String type = ToolUtils.classifyElement(element);
                    if (ToolUtils.ELEMENT_TYPE_METHOD.equals(type)
                        || ToolUtils.ELEMENT_TYPE_FUNCTION.equals(type)) {
                        if (isTestElement(element, named.getName(), frameworks)) {
                            String methodName = named.getName();
                            String className = getContainingClassName(element);
                            String relPath = basePath != null
                                ? relativize(basePath, vf.getPath()) : vf.getPath();
                            int line = doc != null
                                ? doc.getLineNumber(element.getTextOffset()) + 1 : 0;
                            tests.add(String.format("%s.%s (%s:%d)",
                                className, methodName, relPath, line));
                        }
                    }
                }
                super.visitElement(element);
            }
        });
    }

    /**
     * Returns true if {@code element} is a test method, using registered
     * {@link TestFramework} extensions when available, falling back to naming
     * and Google Test macro-expansion heuristics for IDEs without framework support.
     */
    private static boolean isTestElement(PsiElement element, @Nullable String name,
                                         List<TestFramework> frameworks) {
        // Primary: ask each registered framework
        for (TestFramework framework : frameworks) {
            try {
                if (framework.isTestMethod(element)) return true;
            } catch (ProcessCanceledException e) {
                throw e; // must not swallow cancellation
            } catch (Exception ignored) {
                // Framework may not support this element type
            }
        }

        if (!frameworks.isEmpty()) return false; // frameworks available but none matched

        // Fallback for IDEs without TestFramework (e.g. CLion)
        return isFallbackTestElement(element, name);
    }

    /**
     * Heuristic test detection for IDEs without registered {@link TestFramework} extensions.
     * Package-private for testing.
     *
     * <ul>
     *   <li>{@code TestBody()} inside a class whose name contains {@code _Test_} or
     *       ends with {@code _Test} → Google Test macro expansion
     *       ({@code TEST(Suite, Name)} generates {@code Suite_Name_Test::TestBody()})</li>
     *   <li>Function/method name starts with {@code test} (case-insensitive) with at least
     *       one more character — covers Catch2 {@code TEST_CASE} implementations and
     *       conventional C/C++ test naming</li>
     *   <li>Function/method name ends with {@code Test} — common C++ convention</li>
     * </ul>
     */
    static boolean isFallbackTestElement(PsiElement element, @Nullable String name) {
        if (name == null) return false;

        // Google Test: TestBody() inside a class whose name follows the _Test_ pattern.
        // TEST(Suite, Name) generates Suite_Name_Test::TestBody().
        // Check this BEFORE the general naming heuristics so TestBody in an ordinary class
        // doesn't become a false positive via the "starts with test" rule.
        if (GTEST_BODY_METHOD.equals(name)) {
            String containingClass = getContainingClassNameStatic(element);
            return containingClass != null
                && (containingClass.contains("_Test_") || containingClass.endsWith("_Test"));
        }

        // Naming conventions — covers Catch2 and conventional C/C++ test functions
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("test") && lower.length() > 4) return true;
        if (lower.endsWith("test") && lower.length() > 4) return true;

        return false;
    }

    private String getContainingClassName(PsiElement element) {
        String name = getContainingClassNameStatic(element);
        return name != null ? name : vf(element);
    }

    private static @Nullable String getContainingClassNameStatic(PsiElement element) {
        PsiElement parent = element.getParent();
        while (parent != null) {
            if (parent instanceof PsiNamedElement named) {
                String type = ToolUtils.classifyElement(parent);
                if (ToolUtils.ELEMENT_TYPE_CLASS.equals(type)) return named.getName();
            }
            parent = parent.getParent();
        }
        return null;
    }

    /**
     * Fallback class name: the containing file name without extension.
     * Used for top-level test functions (e.g. Python, Go, C++ without a test class).
     */
    private static String vf(PsiElement element) {
        PsiFile file = element.getContainingFile();
        if (file == null) return "UnknownFile";
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
