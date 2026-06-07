package com.github.catatafishen.agentbridge.psi.tools.refactoring;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Platform tests for {@link GetDocumentationTool} and
 * {@link GetTypeHierarchyTool}.
 *
 * <p>JUnit 3 style (extends BasePlatformTestCase): test methods must be
 * {@code public void testXxx()}.  Run via Gradle only:
 * {@code ./gradlew :plugin-core:test}.
 *
 * <p>Both tools execute inside
 * {@code ApplicationManager.getApplication().runReadAction()} — they are
 * synchronous when called from the EDT test thread so no extra threading
 * machinery is required. Both declare {@code throws Exception} in their
 * {@code execute()} signatures, so test methods follow suit.
 *
 * <p>{@link GetDocumentationTool} resolves symbols via
 * {@code JavaPsiFacade.findClass} against {@code allScope}, which includes the
 * JDK. Tests that look up built-in classes (e.g. {@code java.util.ArrayList})
 * therefore work without adding any fixture files.
 *
 * <p>{@link GetTypeHierarchyTool} delegates to
 * {@code RefactoringJavaSupport.getTypeHierarchy}, which also uses
 * {@code JavaPsiFacade} and {@code allScope}. A project-local class added via
 * {@code myFixture.addFileToProject()} is included in the scope and can be
 * found by its fully-qualified name.
 */
public class RefactoringToolsTest extends BasePlatformTestCase {

    private GetDocumentationTool getDocumentationTool;
    private GetTypeHierarchyTool getTypeHierarchyTool;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Disable follow-agent UI (editor navigation, status-bar feedback)
        // to avoid spurious editor-lifecycle failures in headless tests.
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "false");
        getDocumentationTool = new GetDocumentationTool(getProject());
        getTypeHierarchyTool = new GetTypeHierarchyTool(getProject(), true);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            // Close any editors that may have been opened during the test.
            FileEditorManager fem = FileEditorManager.getInstance(getProject());
            for (VirtualFile openFile : fem.getOpenFiles()) {
                fem.closeFile(openFile);
            }
        } finally {
            super.tearDown();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a {@link JsonObject} from alternating key/value String pairs.
     */
    private static JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }

    // ── GetDocumentationTool ──────────────────────────────────────────────────

    /**
     * Looking up the documentation for the built-in {@code java.util.ArrayList}
     * class must return a non-error response. The result should reference the
     * class either as documentation text or as the "element found" fallback.
     * It must not return the "symbol parameter required" validation error.
     */
    public void testGetDocumentationForBuiltinClass() throws Exception {
        String result = getDocumentationTool.execute(args("symbol", "java.util.ArrayList"));

        assertNotNull("Result should not be null", result);
        // Must not be the validation error for missing 'symbol' argument.
        assertFalse("Expected non-validation-error result, got: " + result,
            result.startsWith("Error: 'symbol' parameter required"));
        // Either doc content or the "element found" fallback must mention ArrayList.
        assertTrue("Expected 'ArrayList' to appear in result, got: " + result,
            result.contains("ArrayList"));
    }

    /**
     * When the {@code symbol} parameter is absent (empty string), the tool must
     * return the standard validation-error message beginning with
     * {@code "Error: 'symbol' parameter required"}.
     */
    public void testGetDocumentationMissingSymbol() throws Exception {
        String result = getDocumentationTool.execute(new JsonObject());

        assertTrue("Expected validation-error prefix, got: " + result,
            result.startsWith("Error: 'symbol' parameter required"));
        assertTrue("Expected 'symbol' mentioned in error, got: " + result,
            result.contains("symbol"));
    }

    // ── GetTypeHierarchyTool ──────────────────────────────────────────────────

    /**
     * After adding a Java class that implements {@code Runnable}, calling
     * {@code get_type_hierarchy} for that class with {@code direction=supertypes}
     * must return the standard {@code "Type hierarchy for:"} header and mention
     * {@code Runnable} in the supertypes section.
     */
    public void testGetTypeHierarchyForClass() throws Exception {
        myFixture.addFileToProject(
            "com/example/MyRunnableClass_8831.java",
            """
                package com.example;
                public class MyRunnableClass_8831 implements Runnable {
                    @Override
                    public void run() {}
                }
                """);

        String result = getTypeHierarchyTool.execute(args(
            "symbol", "com.example.MyRunnableClass_8831",
            "direction", "supertypes"
        ));

        assertNotNull("Result should not be null", result);
        // Must not be the "class not found" error path.
        assertFalse("Expected class to be found in hierarchy, got: " + result,
            result.startsWith("Error: Class/interface"));
        // The tool always emits this header when a class is resolved.
        assertTrue("Expected 'Type hierarchy for:' header, got: " + result,
            result.contains("Type hierarchy for:"));
        // MyRunnableClass_8831 implements Runnable, so Runnable must appear.
        assertTrue("Expected 'Runnable' in supertypes section, got: " + result,
            result.contains("Runnable"));
    }

    /**
     * When the required {@code symbol} parameter is absent, the tool must
     * return exactly {@code "Error: 'symbol' parameter is required"}.
     */
    public void testGetTypeHierarchyMissingSymbol() throws Exception {
        String result = getTypeHierarchyTool.execute(new JsonObject());

        assertEquals("Error: 'symbol' parameter is required", result);
    }

    /**
     * Invalid {@code direction} values must be rejected up-front with a clear error
     * listing the supported values, regardless of whether Java PSI is available.
     */
    public void testGetTypeHierarchyRejectsInvalidDirection() throws Exception {
        String result = getTypeHierarchyTool.execute(args(
            "symbol", "java.lang.Object",
            "direction", "sideways"
        ));

        assertNotNull("Result must not be null", result);
        assertTrue("Expected validation error for bad direction, got: " + result,
            result.startsWith("Error:") && result.contains("sideways"));
        assertTrue("Error must list supported directions, got: " + result,
            result.contains("supertypes") && result.contains("subtypes") && result.contains("both"));
    }

    /**
     * Regression for bug #6 in issue-794: requesting {@code direction=both} in a non-Java
     * IDE (e.g. CLion) used to hard-fail with "Direction 'both' requires a Java project".
     * The tool now returns programmatic subtypes AND supertypes (via the shared reflective
     * {@code ToolUtils.findSuperElementsViaPlatform} helper) when the Java module is loaded —
     * no manual-action hints anywhere in the output, because an autonomous agent cannot press
     * hotkeys.
     */
    public void testGetTypeHierarchyBothInNonJavaReturnsProgrammaticResults() throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("type-hierarchy-test");
        try {
            // Self-contained hierarchy in a single file so the parser can resolve references
            // without needing the JDK on the classpath (the file lives outside the project's
            // source roots).
            java.nio.file.Path file = tempDir.resolve("Hierarchy_7421.java");
            java.nio.file.Files.writeString(file, """
                class Base_7421 {}
                class Mid_7421 extends Base_7421 {}
                class Leaf_7421 extends Mid_7421 {}
                """);
            VirtualFile vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(file);
            assertNotNull("Failed to register temp file in VFS", vf);

            GetTypeHierarchyTool nonJavaTool = new GetTypeHierarchyTool(getProject(), false);
            String result = nonJavaTool.execute(args(
                "symbol", "Mid_7421",
                "direction", "both",
                "file", vf.getPath(),
                "line", "2"
            ));

            assertNotNull("Result must not be null", result);
            // Pre-fix: "Error: Direction 'both' requires a Java project". Must NOT happen.
            assertFalse("Expected no 'requires a Java project' error, got: " + result,
                result.contains("requires a Java project"));
            // Old approach added a manual-action hint ("use the IDE's built-in Type Hierarchy
            // view (Ctrl+H)") — useless to an autonomous agent. Must NOT appear anywhere.
            assertFalse("Result must not suggest Ctrl+H manual action, got: " + result,
                result.contains("Ctrl+H") || result.contains("Cmd+H") || result.contains("Type Hierarchy view"));
            // Subtypes path (any of these is acceptable — the project scope may not include
            // /tmp files so 'no subtypes' is fine; the point is the path was taken).
            assertTrue("Expected subtypes section or 'no subtypes' message, got: " + result,
                result.contains("Subtypes") || result.contains("No subtypes"));
            // Supertypes section must be present with Base_7421 (parent of Mid_7421).
            assertTrue("Expected supertypes section listing Base_7421, got: " + result,
                result.contains("Supertypes of Mid_7421") && result.contains("Base_7421"));
        } finally {
            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(tempDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        java.nio.file.Files.deleteIfExists(p);
                    } catch (java.io.IOException ignored) { /* best-effort cleanup */ }
                });
            }
        }
    }

    public void testGetTypeHierarchySupertypesInNonJavaReturnsProgrammaticResults() throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("type-hierarchy-test");
        try {
            java.nio.file.Path file = tempDir.resolve("SuperHierarchy.java");
            java.nio.file.Files.writeString(file, """
                class GrandParent {}
                class Parent extends GrandParent {}
                class SuperProbe extends Parent {}
                """);
            VirtualFile vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(file);
            assertNotNull("Failed to register temp file in VFS", vf);

            GetTypeHierarchyTool nonJavaTool = new GetTypeHierarchyTool(getProject(), false);
            String result = nonJavaTool.execute(args(
                "symbol", "SuperProbe",
                "direction", "supertypes",
                "file", vf.getPath(),
                "line", "3"
            ));

            assertNotNull("Result must not be null", result);
            assertFalse("Should not error when FindSuperElementsHelper is loadable, got: " + result,
                result.startsWith("Error:"));
            assertFalse("Result must not suggest Ctrl+H manual action, got: " + result,
                result.contains("Ctrl+H") || result.contains("Cmd+H"));
            assertTrue("Expected Supertypes header, got: " + result,
                result.contains("Supertypes of SuperProbe"));
            // Must walk the chain recursively: SuperProbe → Parent → GrandParent.
            assertTrue("Expected Parent in supertypes, got: " + result,
                result.contains("Parent"));
            assertTrue("Expected GrandParent in supertypes (recursive walk), got: " + result,
                result.contains("GrandParent"));
        } finally {
            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(tempDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        java.nio.file.Files.deleteIfExists(p);
                    } catch (java.io.IOException ignored) { /* best-effort cleanup */ }
                });
            }
        }
    }

    /**
     * In a non-Java IDE without file+line, a {@code subtypes} request must error out with a
     * clear message asking for the location parameters (the language-agnostic
     * DefinitionsScopedSearch path needs them to anchor the search).
     */
    public void testGetTypeHierarchySubtypesInNonJavaWithoutFileErrors() throws Exception {
        GetTypeHierarchyTool nonJavaTool = new GetTypeHierarchyTool(getProject(), false);
        String result = nonJavaTool.execute(args(
            "symbol", "SomeClass",
            "direction", "subtypes"
        ));

        assertNotNull("Result must not be null", result);
        assertTrue("Expected error, got: " + result, result.startsWith("Error:"));
        assertTrue("Error must ask for 'file' and 'line', got: " + result,
            result.contains("file") && result.contains("line"));
    }
}
