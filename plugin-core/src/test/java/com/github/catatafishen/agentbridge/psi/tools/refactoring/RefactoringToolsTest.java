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
        getTypeHierarchyTool = new GetTypeHierarchyTool(getProject());
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

    /** Builds a {@link JsonObject} from alternating key/value String pairs. */
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
}
