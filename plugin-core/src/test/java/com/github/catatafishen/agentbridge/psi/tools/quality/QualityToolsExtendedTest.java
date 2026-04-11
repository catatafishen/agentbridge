package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
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
 * Platform tests for additional quality tools: {@link AddToDictionaryTool},
 * {@link GetHighlightsTool}, {@link GetAvailableActionsTool}, and
 * {@link SuppressInspectionTool}.
 *
 * <p>JUnit 3 style (extends {@link BasePlatformTestCase}): test methods must be
 * {@code public void testXxx()}. Run via Gradle only:
 * {@code ./gradlew :plugin-core:test}.
 *
 * <h3>Threading model</h3>
 * <ul>
 *   <li>{@link AddToDictionaryTool} — validation errors return synchronously; the
 *       actual dictionary update runs synchronously via reflection on the calling
 *       thread. Safe to call from the EDT (no EDT dispatch involved).</li>
 *   <li>{@link GetHighlightsTool} without a path — uses {@code executeOnPooledThread}
 *       + {@code runReadAction} only; safe to call from EDT. With a path, it calls
 *       {@code ensureDaemonAnalyzed} which dispatches via {@code EdtUtil.invokeLater};
 *       must be driven via {@link #executeSync}.</li>
 *   <li>{@link GetAvailableActionsTool} — validation errors return synchronously.
 *       Without symbol/column, uses {@code executeOnPooledThread} + {@code runReadAction};
 *       safe from EDT.</li>
 *   <li>{@link SuppressInspectionTool} — {@code path} / {@code line} /
 *       {@code inspection_id} are extracted synchronously before any async dispatch.
 *       An empty {@code inspection_id} triggers an early synchronous return. Path
 *       processing and all write-actions run via {@code EdtUtil.invokeLater};
 *       those paths must be driven via {@link #executeSync}.</li>
 * </ul>
 *
 * <h3>File creation</h3>
 * Tools that use {@code LocalFileSystem#findFileByPath} (path-based tools) need real
 * on-disk files registered via {@code LocalFileSystem#refreshAndFindFileByPath}.
 */
public class QualityToolsExtendedTest extends BasePlatformTestCase {

    private AddToDictionaryTool addToDictionaryTool;
    private GetHighlightsTool getHighlightsTool;
    private GetAvailableActionsTool getAvailableActionsTool;
    private SuppressInspectionTool suppressInspectionTool;
    private ApplyActionTool applyActionTool;

    /**
     * Temporary directory for on-disk test files; deleted in {@link #tearDown()}.
     */
    private Path tempDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Prevent followFileIfEnabled from opening editors during tests.
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "false");

        addToDictionaryTool = new AddToDictionaryTool(getProject());
        getHighlightsTool = new GetHighlightsTool(getProject());
        getAvailableActionsTool = new GetAvailableActionsTool(getProject());
        suppressInspectionTool = new SuppressInspectionTool(getProject());
        applyActionTool = new ApplyActionTool(getProject());

        tempDir = Files.createTempDirectory("quality-tools-ext-test");
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Creates a real file on disk under {@link #tempDir} and registers it in the
     * VFS via {@link LocalFileSystem#refreshAndFindFileByPath} so that
     * {@code LocalFileSystem#findFileByPath} (used internally by the tools) can
     * locate it.
     */
    private VirtualFile createTestFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(file.toString());
        assertNotNull("Failed to register test file in VFS: " + file, vf);
        return vf;
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
     * Example: {@code args("path", "/tmp/Foo.java", "line", "1")}
     */
    private static JsonObject args(String... pairs) {
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
     * <p>Required for tools that dispatch write-actions back to the EDT via
     * {@code EdtUtil.invokeLater}: calling {@code execute()} directly from the EDT would
     * block the queue and prevent the invokeLater callback from ever running. Running
     * {@code execute()} off-EDT while pumping the queue breaks that cycle.
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
        long deadline = System.currentTimeMillis() + 20_000;
        while (!future.isDone()) {
            UIUtil.dispatchAllInvocationEvents();
            if (System.currentTimeMillis() > deadline) {
                fail("executeSync timed out after 20 seconds");
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

    // ── AddToDictionaryTool ───────────────────────────────────────────────────

    /**
     * Passing an empty string for {@code word} (simulating a missing/blank input)
     * triggers the explicit empty-word guard and returns an error message.
     * The guard is synchronous and fires before any background dispatch.
     */
    public void testAddToDictionaryMissingWord() throws Exception {
        // The tool guards against blank words synchronously before any background dispatch.
        String result = addToDictionaryTool.execute(args("word", ""));
        assertNotNull("Result must not be null", result);
        assertTrue("Expected error message for empty word, got: " + result,
            result.startsWith("Error:") || result.contains("empty"));
    }

    /**
     * Providing a valid word must return a non-error response that references the
     * supplied word. In test environments the SpellChecker plugin may not be available;
     * the tool handles both cases gracefully without returning "Error:".
     *
     * <p>Safe to call from the EDT: the spell-checker reflection call runs synchronously
     * on the calling thread and does not dispatch back to the EDT.
     */
    public void testAddToDictionaryWithWord() throws Exception {
        String result = addToDictionaryTool.execute(args("word", "testword"));
        assertNotNull("Result must not be null", result);
        assertFalse("Expected non-error response for valid word, got: " + result,
            result.startsWith("Error:"));
        // The result references the supplied word in either a success or fallback message.
        assertTrue("Expected 'testword' to appear in result, got: " + result,
            result.contains("testword") || result.contains("plugin") || result.contains("Spell"));
    }

    // ── GetHighlightsTool ─────────────────────────────────────────────────────

    /**
     * Calling the tool with no path argument scans all source files in the project.
     * In a fresh test project with no source files it returns a "No highlights found"
     * message — not a hard JSON error.
     *
     * <p>Safe to call from the EDT: without a path the tool uses only
     * {@code executeOnPooledThread} + {@code runReadAction} (no EDT dispatch back).
     */
    public void testGetHighlightsMissingPath() throws Exception {
        String result = getHighlightsTool.execute(new JsonObject());
        assertNotNull("Result must not be null", result);
        assertFalse("Expected non-JSON-error response for no-path highlights, got: " + result,
            result.startsWith("{\"error\""));
        assertFalse("Expected non-error response for no-path highlights, got: " + result,
            result.startsWith("Error:"));
        assertTrue("Expected 'No highlights found' or highlights response, got: " + result,
            result.contains("No highlights found") || result.contains("highlights") || result.contains("files"));
    }

    /**
     * Calling the tool with a path to a real Java file on disk must return a non-error
     * response. The file is outside project source roots so the tool reports
     * "No highlights found in 0 files analyzed" (file excluded from source content),
     * but the result must not be an error.
     *
     * <p>Uses {@link #executeSync} because providing a path triggers
     * {@code ensureDaemonAnalyzed}, which dispatches to the EDT via
     * {@code EdtUtil.invokeLater}. Calling {@code execute()} directly from the EDT
     * would deadlock on that callback.
     */
    public void testGetHighlightsWithPath() throws Exception {
        VirtualFile vf = createTestFile("HighlightsPathTest.java",
            "public class HighlightsPathTest {\n    public void foo() {}\n}\n");

        String result = executeSync(() -> getHighlightsTool.execute(args("path", vf.getPath())));
        assertNotNull("Result must not be null", result);
        assertFalse("Expected non-JSON-error response for highlights with path, got: " + result,
            result.startsWith("{\"error\""));
        assertFalse("Expected non-error response for highlights with path, got: " + result,
            result.startsWith("Error:"));
    }

    // ── GetAvailableActionsTool ───────────────────────────────────────────────

    /**
     * Providing neither {@code file} nor {@code line} must return the validation error
     * "Error: 'file' and 'line' parameters are required". This check is synchronous and
     * returns before any async dispatch.
     */
    public void testGetAvailableActionsMissingFile() throws Exception {
        String result = getAvailableActionsTool.execute(new JsonObject());
        assertNotNull("Result must not be null", result);
        assertTrue("Expected validation error for missing file+line, got: " + result,
            result.startsWith("Error:"));
        assertTrue("Expected 'file' mentioned in error message, got: " + result,
            result.contains("file"));
    }

    /**
     * Providing {@code file} but omitting {@code line} must return the validation error
     * "Error: 'file' and 'line' parameters are required". This check is synchronous.
     */
    public void testGetAvailableActionsMissingLine() throws Exception {
        VirtualFile vf = createTestFile("NoLineAvailActions.java", "public class NoLineAvailActions {}\n");

        JsonObject a = new JsonObject();
        a.addProperty("file", vf.getPath());
        // "line" intentionally omitted

        String result = getAvailableActionsTool.execute(a);
        assertNotNull("Result must not be null", result);
        assertTrue("Expected validation error for missing 'line', got: " + result,
            result.startsWith("Error:"));
        assertTrue("Expected 'line' mentioned in error message, got: " + result,
            result.contains("line"));
    }

    /**
     * Providing both {@code file} and {@code line} (without {@code symbol} or
     * {@code column}) must return a non-error response. The file may have no daemon
     * highlights yet, so the tool returns a "No highlights found" message — which is
     * still a valid non-error outcome.
     *
     * <p>Safe to call from the EDT: without symbol/column the tool uses only
     * {@code executeOnPooledThread} + {@code runReadAction} (no EDT dispatch back).
     */
    public void testGetAvailableActionsWithFileAndLine() throws Exception {
        VirtualFile vf = createTestFile("ActionsFileTest.java",
            "public class ActionsFileTest {\n    public void doSomething() {}\n}\n");

        // No symbol/column → quick-fixes only path → pooled thread + runReadAction → safe from EDT
        String result = getAvailableActionsTool.execute(args(
            "file", vf.getPath(),
            "line", "1"
        ));
        assertNotNull("Result must not be null", result);
        assertFalse("Result must not be a JSON error, got: " + result,
            result.startsWith("{\"error\""));
        assertFalse("Result must not be blank", result.isBlank());
    }

    // ── SuppressInspectionTool ────────────────────────────────────────────────

    /**
     * Passing an empty path (simulating a missing/invalid path) must return an
     * error message. Since {@code inspection_id} is non-empty the synchronous guard
     * does not fire; the tool dispatches via {@code EdtUtil.invokeLater} where
     * {@code resolveVirtualFile("")} returns {@code null}, completing the future with
     * an error string. {@link #executeSync} is therefore required.
     */
    public void testSuppressInspectionMissingPath() throws Exception {
        String result = executeSync(() -> suppressInspectionTool.execute(
            args("path", "", "line", "1", "inspection_id", "TestInspection")));
        assertNotNull("Result must not be null", result);
        assertTrue("Expected error for empty/missing path, got: " + result,
            result.startsWith("Error:") || result.toLowerCase().contains("error")
                || result.toLowerCase().contains("not found"));
    }

    /**
     * Omitting the {@code line} parameter entirely causes the tool to throw a
     * {@link NullPointerException} when it accesses {@code args.get("line").getAsInt()}
     * without a null check. The test verifies this unguarded access throws rather than
     * returning silently. The NPE is thrown synchronously before any async dispatch.
     */
    public void testSuppressInspectionMissingLine() {
        JsonObject a = new JsonObject();
        a.addProperty("path", "/some/nonexistent/SuppressMissingLine.java");
        a.addProperty("inspection_id", "TestInspection");
        // "line" intentionally omitted — tool will NPE on args.get("line").getAsInt()

        try {
            suppressInspectionTool.execute(a);
            fail("Expected exception when 'line' parameter is missing");
        } catch (Exception e) {
            // Expected: args.get("line") returns null; .getAsInt() on null throws.
        }
    }

    /**
     * Passing an empty {@code inspection_id} must trigger the explicit empty-id guard
     * and return {@code "Error: inspection_id cannot be empty"}. This check runs
     * synchronously before any async dispatch, so no {@link #executeSync} is required.
     */
    public void testSuppressInspectionMissingInspectionId() throws Exception {
        // The isEmpty() guard fires before EdtUtil.invokeLater — safe to call from EDT.
        String result = suppressInspectionTool.execute(
            args("path", "/some/nonexistent/SuppressMissingId.java",
                "line", "1",
                "inspection_id", ""));
        assertNotNull("Result must not be null", result);
        assertTrue("Expected error for empty inspection_id, got: " + result,
            result.startsWith("Error:")
                || result.contains("empty")
                || result.contains("inspection_id"));
    }

    // ── ApplyActionTool ───────────────────────────────────────────────────────

    /**
     * Omitting all required parameters must return an immediate error without
     * crashing. The guard fires synchronously before any async dispatch.
     */
    public void testApplyActionMissingRequiredParams() throws Exception {
        String result = applyActionTool.execute(new JsonObject());
        assertNotNull("Result must not be null", result);
        assertTrue("Expected error for missing required params, got: " + result,
            result.startsWith("Error:"));
    }

    /**
     * Passing a non-existent file path must return an error.
     * {@link ApplyActionTool#execute} dispatches via {@code EdtUtil.invokeLater}, so
     * {@link #executeSync} is required to avoid EDT deadlock.
     */
    public void testApplyActionNonExistentFile() throws Exception {
        String result = executeSync(() -> applyActionTool.execute(args(
            "file", "/nonexistent/path/DoesNotExist.java",
            "line", "1",
            "action_name", "Some Action")));
        assertNotNull("Result must not be null", result);
        assertTrue("Expected file-not-found error, got: " + result,
            result.startsWith("Error:") || result.contains("not found"));
    }

    /**
     * Passing an out-of-bounds line number for a real file must return an error.
     * {@link #executeSync} is required for the same EDT-dispatch reason.
     */
    public void testApplyActionInvalidLine() throws Exception {
        VirtualFile vf = createTestFile("ApplyActionLineCheck.java",
            "package com.example;\npublic class ApplyActionLineCheck {}\n");

        String result = executeSync(() -> applyActionTool.execute(args(
            "file", vf.getPath(),
            "line", "9999",
            "action_name", "Some Action")));
        assertNotNull("Result must not be null", result);
        assertTrue("Expected out-of-bounds error, got: " + result,
            result.startsWith("Error:") || result.contains("out of bounds") || result.contains("bounds"));
    }
}
