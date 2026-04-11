package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Extended platform tests for {@link GetFileOutlineTool} and
 * {@link SearchSymbolsTool}.
 *
 * <p>JUnit 3 style (extends BasePlatformTestCase): test methods must be
 * {@code public void testXxx()}.  Run via Gradle only:
 * {@code ./gradlew :plugin-core:test}.
 *
 * <p>Both tools execute entirely inside
 * {@code ApplicationManager.getApplication().runReadAction()} so they are
 * synchronous when called from the EDT test thread — no extra threading
 * machinery is needed.
 *
 * <p><b>File creation strategy:</b>
 * <ul>
 *   <li>{@link GetFileOutlineTool} takes a file path and resolves it via
 *       {@code ToolUtils#resolveVirtualFile} ({@code LocalFileSystem#findFileByPath}).
 *       Tests for this tool create real disk files under a temp directory and
 *       register them in the VFS via {@code LocalFileSystem#refreshAndFindFileByPath}.
 *   <li>{@link SearchSymbolsTool} searches the PSI project index without a file
 *       path argument, so {@code myFixture.addFileToProject()} (TempFS) works fine.
 * </ul>
 *
 * <p>Each test method uses a unique file name to avoid "file already exists"
 * conflicts when multiple tests run in the same light project.
 */
public class NavigationToolsExtendedTest extends BasePlatformTestCase {

    private GetFileOutlineTool getFileOutlineTool;
    private SearchSymbolsTool searchSymbolsTool;
    /**
     * Real temp directory for tests that need {@code LocalFileSystem#findFileByPath}.
     */
    private Path tempDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Disable follow-agent UI (status-bar feedback, UsageView) that would
        // be no-ops or noisy in a headless test environment.
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "false");
        getFileOutlineTool = new GetFileOutlineTool(getProject());
        searchSymbolsTool = new SearchSymbolsTool(getProject());
        tempDir = Files.createTempDirectory("nav-tools-test");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            // Close any editors that may have been opened during the test.
            FileEditorManager fem = FileEditorManager.getInstance(getProject());
            for (VirtualFile openFile : fem.getOpenFiles()) {
                fem.closeFile(openFile);
            }
            // Remove temp files created during this test.
            try (var paths = Files.walk(tempDir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
            }
        } finally {
            super.tearDown();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Creates a real file on disk and registers it in the VFS so that
     * {@code LocalFileSystem#findFileByPath} can find it during {@code execute()}.
     */
    private String createTestFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(file.toString());
        assertNotNull("Failed to register test file in VFS: " + file, vf);
        return file.toString();
    }

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

    // ── GetFileOutlineTool ────────────────────────────────────────────────────

    /**
     * After creating a Java file with a class and a method, the outline must
     * start with {@code "Outline of"} and include the class or method name.
     *
     * <p>Uses a real disk file so that {@code resolveVirtualFile} can find it
     * via {@code LocalFileSystem#findFileByPath}.
     */
    public void testGetFileOutlineWithJavaFile() throws Exception {
        String path = createTestFile("OutlineClass.java", """
            public class OutlineClass {
                public String greet() {
                    return "hello";
                }
            }
            """);

        String result = getFileOutlineTool.execute(args("path", path));

        assertFalse("Expected non-error result, got: " + result,
            result.startsWith(ToolUtils.ERROR_PREFIX));
        assertTrue("Expected 'Outline of' header, got: " + result,
            result.startsWith("Outline of"));
        // The outline must mention either the class name or the method name.
        assertTrue("Expected class or method name in outline, got: " + result,
            result.contains("OutlineClass") || result.contains("greet"));
    }

    /**
     * When the {@code path} parameter is omitted entirely, the tool must return
     * the standard {@code "Error: 'path' parameter is required"} error.
     */
    public void testGetFileOutlineMissingPath() {
        String result = getFileOutlineTool.execute(new JsonObject());

        assertEquals(ToolUtils.ERROR_PATH_REQUIRED, result);
    }

    /**
     * When the supplied path points to a file that does not exist, the tool
     * must return a {@code "File not found:"} message.
     */
    public void testGetFileOutlineNonExistentPath() {
        String result = getFileOutlineTool.execute(
            args("path", "/no/such/path/DoesNotExist_99982.java"));

        assertTrue("Expected 'File not found:' error, got: " + result,
            result.startsWith(ToolUtils.ERROR_FILE_NOT_FOUND));
    }

    // ── SearchSymbolsTool ─────────────────────────────────────────────────────

    /**
     * After adding a Java file that defines {@code SearchTargetClass_7741},
     * searching for that exact name must return a result containing the
     * class name (verifying the PSI index found it).
     *
     * <p>Uses {@code myFixture.addFileToProject()} (TempFS) because
     * {@link SearchSymbolsTool} queries the PSI index rather than a file path.
     */
    public void testSearchSymbolsFindsClass() {
        myFixture.addFileToProject(
            "SearchTargetClass_7741.java",
            """
                public class SearchTargetClass_7741 {
                    public void doSomething() {}
                }
                """);

        String result = searchSymbolsTool.execute(args("query", "SearchTargetClass_7741"));

        assertFalse("Expected non-error result, got: " + result,
            result.startsWith(ToolUtils.ERROR_PREFIX));
        assertTrue("Expected class name in search result, got: " + result,
            result.contains("SearchTargetClass_7741"));
    }

    /**
     * Searching for a token that definitely does not exist in the project must
     * return the tool's {@code "No symbols found matching '...'"} message.
     */
    public void testSearchSymbolsNoResults() {
        String result = searchSymbolsTool.execute(
            args("query", "zzzNeverExists_9283_UniqueToken"));

        assertTrue("Expected no-match message, got: " + result,
            result.contains("No symbols found matching 'zzzNeverExists_9283_UniqueToken'"));
    }

    /**
     * When the {@code query} parameter is absent, the tool defaults to an empty
     * query which triggers the wildcard path. Without a {@code type} filter, the
     * tool returns guidance asking for a type filter rather than an empty result.
     * The response must be non-null and non-blank.
     */
    public void testSearchSymbolsMissingQuery() {
        // No "query" key in args — tool defaults to "" → searchWildcard("")
        String result = searchSymbolsTool.execute(new JsonObject());

        assertNotNull("Result should not be null", result);
        assertFalse("Result should not be blank", result.isBlank());
        // The wildcard path without a type filter returns a guidance message.
        assertTrue("Expected guidance about 'type' filter, got: " + result,
            result.contains("type"));
    }
}
