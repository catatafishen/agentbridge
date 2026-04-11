package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Platform tests for quality tools: {@link GetCompilationErrorsTool},
 * {@link GetProblemsTool}, {@link FormatCodeTool}, and {@link OptimizeImportsTool}.
 *
 * <p>JUnit 3 style (extends {@link BasePlatformTestCase}): test methods must be
 * {@code public void testXxx()}. Run via Gradle only:
 * {@code ./gradlew :plugin-core:test}.
 *
 * <h3>Threading model</h3>
 * <ul>
 *   <li>{@link FormatCodeTool} and {@link OptimizeImportsTool} schedule work via
 *       {@code EdtUtil.invokeLater} and then block on a {@code CompletableFuture}. Calling
 *       {@code execute()} directly from the EDT test thread would deadlock (EDT blocked by
 *       {@code future.get()}, so the {@code invokeLater} callback can never run). These tools
 *       are driven via {@link #executeSync}, which runs {@code execute()} on a pooled thread
 *       while pumping the EDT queue on the test thread.
 *   <li>{@link GetCompilationErrorsTool} and {@link GetProblemsTool} submit work to a pooled
 *       thread via {@code executeOnPooledThread}, then block on a future. Their pooled-thread
 *       callbacks use only {@code runReadAction} (no EDT requirement), so it is safe to call
 *       {@code execute()} directly from the EDT test thread.
 * </ul>
 *
 * <h3>File creation</h3>
 * {@code myFixture.addFileToProject()} creates files in an in-memory VFS namespace that
 * {@code LocalFileSystem#findFileByPath} cannot see. Tests that pass file paths to tools
 * therefore create real on-disk files under a temp directory and register them via
 * {@code LocalFileSystem#refreshAndFindFileByPath}.
 */
public class QualityToolsTest extends BasePlatformTestCase {

    private GetCompilationErrorsTool compilationErrorsTool;
    private GetProblemsTool problemsTool;
    private FormatCodeTool formatTool;
    private OptimizeImportsTool optimizeImportsTool;

    /**
     * Temporary directory for on-disk test files; deleted in {@link #tearDown()}.
     */
    private Path tempDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Prevent followFileIfEnabled from opening editors during tests.
        // Use the String overload — the boolean overload removes the key when value==defaultValue,
        // which would leave the setting at its default (true) instead of setting it to false.
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "false");

        compilationErrorsTool = new GetCompilationErrorsTool(getProject());
        problemsTool = new GetProblemsTool(getProject());
        formatTool = new FormatCodeTool(getProject());
        optimizeImportsTool = new OptimizeImportsTool(getProject());

        tempDir = Files.createTempDirectory("quality-tools-test");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            // Close any editors the tools may have opened to prevent DisposalException.
            FileEditorManager fem = FileEditorManager.getInstance(getProject());
            for (VirtualFile openFile : fem.getOpenFiles()) {
                fem.closeFile(openFile);
            }
            deleteDir(tempDir);
        } finally {
            super.tearDown();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * Creates a real file on disk under {@link #tempDir} and registers it in the
     * VFS via {@link LocalFileSystem#refreshAndFindFileByPath} so that
     * {@code LocalFileSystem#findFileByPath} (used internally by the tools) can
     * locate it.
     */
    private VirtualFile createTestFile(String name, String content) {
        try {
            Path file = tempDir.resolve(name);
            Files.writeString(file, content);
            VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(file.toString());
            assertNotNull("Failed to register test file in VFS: " + file, vf);
            return vf;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test file: " + name, e);
        }
    }

    /**
     * Recursively deletes a directory tree; best-effort (ignores errors).
     */
    private static void deleteDir(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    /**
     * Builds a {@link JsonObject} from alternating key/value string pairs.
     * Example: {@code args("path", "/tmp/Foo.java")}
     */
    private JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }

    /**
     * Runs the given {@code action} on a pooled background thread while pumping the EDT
     * event queue on the calling thread.
     *
     * <p>Required for tools that use {@code EdtUtil.invokeLater} to schedule write-actions
     * back onto the EDT: calling {@code execute()} directly from the EDT would block the
     * queue and prevent the invokeLater callback from ever running. Running {@code execute()}
     * off-EDT while pumping the queue breaks that cycle.
     *
     * @param action callable that invokes the tool and returns its result string
     * @return the string returned by {@code action}
     */
    private String executeSync(ThrowingSupplier<String> action) throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                future.complete(action.get());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        long deadline = System.currentTimeMillis() + 15_000;
        while (!future.isDone()) {
            UIUtil.dispatchAllInvocationEvents();
            if (System.currentTimeMillis() > deadline) {
                fail("executeSync timed out after 15 seconds");
            }
        }
        return future.get();
    }

    /**
     * Minimal checked-exception-throwing {@link java.util.function.Supplier} variant.
     */
    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    // ── GetCompilationErrorsTool ──────────────────────────────────────────────────

    /**
     * Calls the tool with no arguments (checks all source files). A fresh test project
     * with no Java sources should report zero errors.
     *
     * <p>Safe to call directly from the EDT: the tool delegates to a pooled thread via
     * {@code executeOnPooledThread} and only uses {@code runReadAction} (no EDT callback
     * requirement), so blocking the EDT on {@code future.get()} will not deadlock.
     */
    public void testGetCompilationErrorsNoErrors() throws Exception {
        String result = compilationErrorsTool.execute(new JsonObject());
        assertNotNull("execute() must not return null", result);
        assertFalse("Result should not be empty", result.isBlank());
        // A fresh test project with no source files has zero errors
        assertTrue("Expected 'No compilation errors' response, got: " + result,
            result.contains("No compilation errors"));
    }

    /**
     * Calls the tool with an explicit path to a real Java file on disk. The file is
     * outside the project's source roots so the tool scans 0 indexed files and reports
     * "No compilation errors in 0 files checked." — a valid non-error response.
     */
    public void testGetCompilationErrorsWithPath() throws Exception {
        VirtualFile vf = createTestFile("CheckMe.java",
            "package com.example;\npublic class CheckMe {\n}\n");

        String result = compilationErrorsTool.execute(args("path", vf.getPath()));
        assertNotNull("execute() must not return null", result);
        assertFalse("Result should not be empty", result.isBlank());
        // Must not be a hard error — either zero-errors report or a file listing
        assertFalse("Expected non-error response for valid path, got: " + result,
            result.startsWith("{\"error\""));
        assertTrue("Expected compilation-errors response, got: " + result,
            result.contains("compilation error") || result.contains("No compilation"));
    }

    // ── GetProblemsTool ───────────────────────────────────────────────────────────

    /**
     * Calls the tool with no arguments when no files are open in the editor.
     * Expects a "No problems found in open files" response (no daemon highlights
     * are available for un-opened files).
     *
     * <p>Safe to call directly from the EDT for the same reason as
     * {@link #testGetCompilationErrorsNoErrors}.
     */
    public void testGetProblemsNoOpenFiles() throws Exception {
        String result = problemsTool.execute(new JsonObject());
        assertNotNull("execute() must not return null", result);
        assertFalse("Result should not be empty", result.isBlank());
        assertTrue("Expected 'No problems found in open files', got: " + result,
            result.contains("No problems found"));
    }

    /**
     * Calls the tool with a path to a real Java file on disk. Since the daemon has not
     * yet run on this file there are no cached highlights, so the tool should report
     * "No problems found" (or a problem count — never a crash).
     */
    public void testGetProblemsWithPath() throws Exception {
        VirtualFile vf = createTestFile("SomeClass.java",
            "package com.example;\npublic class SomeClass {\n}\n");

        String result = problemsTool.execute(args("path", vf.getPath()));
        assertNotNull("execute() must not return null", result);
        assertFalse("Result should not be empty", result.isBlank());
        assertFalse("Expected non-error response for valid path, got: " + result,
            result.startsWith("{\"error\""));
        assertTrue("Expected 'No problems found' or problem listing, got: " + result,
            result.contains("No problems found") || result.contains("problems"));
    }

    // ── FormatCodeTool ────────────────────────────────────────────────────────────

    /**
     * Passes a real Java file to {@link FormatCodeTool} and verifies the success
     * message "Code formatted: &lt;path&gt;" is returned.
     *
     * <p>Uses {@link #executeSync} because {@code FormatCodeTool} schedules the actual
     * write-action via {@code EdtUtil.invokeLater}; calling {@code execute()} directly
     * from the EDT would deadlock.
     */
    public void testFormatCodeWithValidFile() throws Exception {
        VirtualFile vf = createTestFile("FormatMe.java",
            "package com.example;\n\npublic class FormatMe {\npublic void foo(){\n}\n}\n");

        String result = executeSync(() -> formatTool.execute(args("path", vf.getPath())));
        assertNotNull("execute() must not return null", result);
        assertTrue("Expected 'Code formatted' success message, got: " + result,
            result.startsWith("Code formatted"));
    }

    /**
     * Passes an empty path string to {@link FormatCodeTool}. The tool should return a
     * file-not-found or cannot-parse error rather than "Code formatted".
     *
     * <p>An empty path may resolve to the project root directory (a directory has no
     * PsiFile), so both {@link ToolUtils#ERROR_FILE_NOT_FOUND} and
     * {@link ToolUtils#ERROR_CANNOT_PARSE} are valid error prefixes.
     */
    public void testFormatCodeMissingPath() throws Exception {
        String result = executeSync(() -> formatTool.execute(args("path", "")));
        assertNotNull("execute() must not return null", result);
        assertFalse("Expected error for empty path — must not be 'Code formatted', got: " + result,
            result.startsWith("Code formatted"));
        assertTrue("Expected file-resolution error, got: " + result,
            result.startsWith(ToolUtils.ERROR_FILE_NOT_FOUND)
                || result.startsWith(ToolUtils.ERROR_CANNOT_PARSE)
                || result.contains("not found")
                || result.contains("Cannot parse"));
    }

    /**
     * Passes a non-existent absolute path to {@link FormatCodeTool}. The tool should
     * return a {@link ToolUtils#ERROR_FILE_NOT_FOUND} error message.
     */
    public void testFormatCodeNonExistentPath() throws Exception {
        String nonExistentPath = "/nonexistent/quality/path/does/not/exist_xyz.java";
        String result = executeSync(() -> formatTool.execute(args("path", nonExistentPath)));
        assertNotNull("execute() must not return null", result);
        assertFalse("Expected error — must not be 'Code formatted', got: " + result,
            result.startsWith("Code formatted"));
        assertTrue("Expected file-not-found error, got: " + result,
            result.startsWith(ToolUtils.ERROR_FILE_NOT_FOUND)
                || result.contains("not found"));
    }

    // ── OptimizeImportsTool ───────────────────────────────────────────────────────

    /**
     * Passes a real Java file to {@link OptimizeImportsTool} and verifies the success
     * message "Imports optimized: &lt;path&gt;" is returned.
     *
     * <p>Uses {@link #executeSync} because {@code OptimizeImportsTool} schedules the
     * actual write-action via {@code EdtUtil.invokeLater}; calling {@code execute()}
     * directly from the EDT would deadlock.
     */
    public void testOptimizeImportsWithValidFile() throws Exception {
        VirtualFile vf = createTestFile("OptimizeMe.java",
            "package com.example;\n"
                + "import java.util.ArrayList;\n"
                + "import java.util.List;\n"
                + "public class OptimizeMe {\n"
                + "    private List<String> items = new ArrayList<>();\n"
                + "}\n");

        String result = executeSync(() -> optimizeImportsTool.execute(args("path", vf.getPath())));
        assertNotNull("execute() must not return null", result);
        assertTrue("Expected 'Imports optimized' success message, got: " + result,
            result.startsWith("Imports optimized"));
    }

    /**
     * Passes an empty path string to {@link OptimizeImportsTool}. The tool should
     * return a file-resolution error rather than "Imports optimized".
     */
    public void testOptimizeImportsMissingPath() throws Exception {
        String result = executeSync(() -> optimizeImportsTool.execute(args("path", "")));
        assertNotNull("execute() must not return null", result);
        assertFalse("Expected error for empty path — must not be 'Imports optimized', got: " + result,
            result.startsWith("Imports optimized"));
        assertTrue("Expected file-resolution error, got: " + result,
            result.startsWith(ToolUtils.ERROR_FILE_NOT_FOUND)
                || result.startsWith(ToolUtils.ERROR_CANNOT_PARSE)
                || result.contains("not found")
                || result.contains("Cannot parse"));
    }
}
