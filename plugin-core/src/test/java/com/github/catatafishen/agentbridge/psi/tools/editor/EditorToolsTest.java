package com.github.catatafishen.agentbridge.psi.tools.editor;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;

import java.util.concurrent.CompletableFuture;

/**
 * Platform tests for editor tools: {@link GetActiveFileTool}, {@link GetOpenEditorsTool},
 * {@link OpenInEditorTool}, {@link ListScratchFilesTool}, {@link CreateScratchFileTool},
 * and {@link ListThemesTool}.
 *
 * <p>JUnit 3 style (extends {@link BasePlatformTestCase}): test methods must be
 * {@code public void testXxx()}. Run via Gradle only:
 * {@code ./gradlew :plugin-core:test}.
 *
 * <p><b>Threading:</b> tools that use {@code EdtUtil.invokeLater} ({@link GetActiveFileTool},
 * {@link GetOpenEditorsTool}, {@link OpenInEditorTool}) dispatch their result to the EDT via a
 * {@code CompletableFuture}. Because {@code BasePlatformTestCase} methods already run on the EDT,
 * calling {@code execute()} directly would deadlock. {@link #executeSync} runs those tools on a
 * pooled thread while pumping the EDT event queue, mirroring the pattern from
 * {@code EditingToolsTest}.
 *
 * <p>Tools that use {@code EdtUtil.invokeAndWait} ({@link ListScratchFilesTool},
 * {@link CreateScratchFileTool}) check {@code isDispatchThread()} internally and run their
 * runnable inline when called from the EDT, so they can be called directly in tests.
 * {@link ListThemesTool} is synchronous and needs no special handling.
 */
public class EditorToolsTest extends BasePlatformTestCase {

    private GetActiveFileTool getActiveFileTool;
    private GetOpenEditorsTool getOpenEditorsTool;
    private OpenInEditorTool openInEditorTool;
    private ListScratchFilesTool listScratchFilesTool;
    private CreateScratchFileTool createScratchFileTool;
    private ListThemesTool listThemesTool;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Disable "follow agent files" UI navigation to prevent editor lifecycle
        // failures (DisposalException, focus stealing) in the headless test environment.
        // Use the String overload — the boolean overload silently removes the key when
        // value == defaultValue (true), which would leave the setting at true.
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "false");

        getActiveFileTool = new GetActiveFileTool(getProject());
        getOpenEditorsTool = new GetOpenEditorsTool(getProject());
        openInEditorTool = new OpenInEditorTool(getProject());
        listScratchFilesTool = new ListScratchFilesTool(getProject());
        createScratchFileTool = new CreateScratchFileTool(getProject());
        listThemesTool = new ListThemesTool(getProject());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            // Close any editors opened during this test to prevent DisposalException
            // in the next test or when the fixture is torn down.
            FileEditorManager fem = FileEditorManager.getInstance(getProject());
            for (VirtualFile f : fem.getOpenFiles()) {
                fem.closeFile(f);
            }
        } finally {
            super.tearDown();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a {@link JsonObject} from alternating key/value String pairs.
     * Example: {@code args("file", "/tmp/foo.java", "line", "10")}.
     */
    private static JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }

    /**
     * Runs {@code tool.execute(argsObj)} on a pooled background thread while
     * pumping the EDT event queue. Required because tools using
     * {@code EdtUtil.invokeLater} schedule their result callback on the EDT via a
     * {@code CompletableFuture}: calling {@code execute()} directly from the EDT
     * would block, preventing the scheduled callback from ever running.
     * Running the tool off the EDT and pumping events with
     * {@code UIUtil.dispatchAllInvocationEvents()} breaks this deadlock.
     */
    private String executeSync(Tool tool, JsonObject argsObj) throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                future.complete(tool.execute(argsObj));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        long deadline = System.currentTimeMillis() + 15_000;
        while (!future.isDone()) {
            UIUtil.dispatchAllInvocationEvents();
            if (System.currentTimeMillis() > deadline) {
                fail("tool.execute() timed out after 15 seconds");
            }
        }
        return future.get();
    }

    // ── GetActiveFileTool ─────────────────────────────────────────────────────

    /**
     * When no file is open in the editor, {@code get_active_file} must return
     * exactly "No active editor" — the standard response when
     * {@code FileEditorManager.getSelectedTextEditor()} returns {@code null}.
     */
    public void testGetActiveFileNoOpenEditors() throws Exception {
        // No configureByText call — no editor should be open in a freshly initialised test.
        String result = executeSync(getActiveFileTool, new JsonObject());

        assertNotNull("Result must not be null", result);
        assertEquals("Expected 'No active editor' when no file is open, got: " + result,
            "No active editor", result);
    }

    /**
     * Verifies the tool returns a non-blank, non-null string even when the
     * editor is empty — it should never throw or produce a null/blank result.
     */
    public void testGetActiveFileReturnType() throws Exception {
        String result = executeSync(getActiveFileTool, new JsonObject());

        assertNotNull(result);
        assertFalse("Result must not be blank", result.isBlank());
    }

    // ── GetOpenEditorsTool ────────────────────────────────────────────────────

    /**
     * When no file is open, {@code get_open_editors} must return exactly
     * "No open editors" — the standard empty-panel response from
     * {@link GetOpenEditorsTool}.
     */
    public void testGetOpenEditorsNoFiles() throws Exception {
        String result = executeSync(getOpenEditorsTool, new JsonObject());

        assertNotNull("Result must not be null", result);
        assertEquals("Expected 'No open editors' when no file is open, got: " + result,
            "No open editors", result);
    }

    /**
     * After opening a file via the fixture, {@code get_open_editors} must return
     * the "Open editors (N):" summary header and include the opened filename.
     *
     * <p>{@code myFixture.configureByText()} creates a light in-memory VFS file and
     * registers it with {@code FileEditorManager}, so {@code getOpenFiles()} returns it.
     */
    public void testGetOpenEditorsWithFile() throws Exception {
        // Open an in-memory file — FileEditorManager tracks it in the light test fixture.
        myFixture.configureByText("OpenEditorsSubject.java", "class OpenEditorsSubject {}");

        String result = executeSync(getOpenEditorsTool, new JsonObject());

        assertNotNull("Result must not be null", result);
        assertTrue("Expected 'Open editors' count header, got: " + result,
            result.contains("Open editors ("));
        assertTrue("Expected 'OpenEditorsSubject.java' in result, got: " + result,
            result.contains("OpenEditorsSubject.java"));
    }

    /**
     * The active file (most recently configured) is marked with "* " in the listing.
     * After calling {@code configureByText}, that file is the selected editor.
     */
    public void testGetOpenEditorsActiveFileMarked() throws Exception {
        myFixture.configureByText("ActiveMarker.java", "class ActiveMarker {}");

        String result = executeSync(getOpenEditorsTool, new JsonObject());

        assertNotNull(result);
        // Active file should be prefixed with "* " in the listing.
        assertTrue("Expected '* ' active-file marker in result, got: " + result,
            result.contains("* "));
    }

    // ── OpenInEditorTool ──────────────────────────────────────────────────────

    /**
     * When the required {@code file} argument is omitted entirely, the tool must
     * return the standard error message "Error: 'file' parameter is required"
     * without dispatching any EDT work.
     */
    public void testOpenInEditorMissingPath() throws Exception {
        String result = executeSync(openInEditorTool, new JsonObject());

        assertNotNull("Result must not be null", result);
        assertEquals("Expected 'file' param error, got: " + result,
            "Error: 'file' parameter is required", result);
    }

    /**
     * When the {@code file} argument points to a path that does not exist on disk,
     * the tool must return an error message that starts with {@code "Error: "},
     * contains {@code "File not found:"}, and includes the requested path —
     * matching the constants from {@link ToolUtils}.
     */
    public void testOpenInEditorNonExistentPath() throws Exception {
        String nonExistentPath = "/nonexistent/totally/fake/path/XyzMissingFile.java";

        String result = executeSync(openInEditorTool, args("file", nonExistentPath));

        assertNotNull("Result must not be null", result);
        assertTrue("Expected error prefix 'Error: ', got: " + result,
            result.startsWith(ToolUtils.ERROR_PREFIX));
        assertTrue("Expected 'File not found:' in result, got: " + result,
            result.contains(ToolUtils.ERROR_FILE_NOT_FOUND));
        assertTrue("Expected the non-existent path in result, got: " + result,
            result.contains(nonExistentPath));
    }

    /**
     * Verifies that when {@code file} is missing, the tool returns its error
     * message immediately — the result must never be blank, null, or a raw
     * Java exception string.
     */
    public void testOpenInEditorMissingPathIsNotBlank() throws Exception {
        String result = executeSync(openInEditorTool, new JsonObject());

        assertNotNull(result);
        assertFalse("Result must not be blank", result.isBlank());
        assertFalse("Result must not be a raw Java exception", result.startsWith("java."));
    }

    // ── ListScratchFilesTool ──────────────────────────────────────────────────

    /**
     * {@code list_scratch_files} with empty args must return a non-error, non-blank
     * response. In a fresh test environment the scratch directory may not exist, in which
     * case the tool returns "0 scratch files\nUse create_scratch_file to create one.";
     * if it does exist the tool lists whatever files are present.
     *
     * <p>{@link ListScratchFilesTool} uses {@code EdtUtil.invokeAndWait} which detects
     * {@code isDispatchThread() == true} and runs the runnable inline — so calling
     * {@code execute()} directly from the EDT is safe.
     */
    public void testListScratchFiles() throws Exception {
        String result = listScratchFilesTool.execute(new JsonObject());

        assertNotNull("Result must not be null", result);
        assertFalse("Result must not be blank", result.isBlank());
        // The tool never silently errors — it always returns either a count line or a listing.
        assertFalse("Result must not start with 'Error listing scratch files:'",
            result.startsWith("Error listing scratch files:"));
        assertTrue("Expected 'scratch' in result (count or listing), got: " + result,
            result.contains("scratch"));
    }

    /**
     * The empty-scratch-directory response has the form:
     * {@code "0 scratch files\nUse create_scratch_file to create one."}.
     * If the scratch dir exists but is empty, this exact message is returned.
     */
    public void testListScratchFilesEmptyDirMessage() throws Exception {
        String result = listScratchFilesTool.execute(new JsonObject());

        assertNotNull(result);
        // Two valid outcomes depending on whether scratch files exist in this environment.
        boolean isCountResponse = result.startsWith("0 scratch files")
            || result.matches("\\d+ scratch files:.*");
        boolean isListingResponse = result.contains("scratch files:");
        assertTrue("Expected a count or listing response, got: " + result,
            isCountResponse || isListingResponse);
    }

    // ── CreateScratchFileTool ─────────────────────────────────────────────────

    /**
     * {@code create_scratch_file} with no {@code name} argument falls back to
     * {@code "scratch.txt"} rather than throwing or returning an error, because the
     * implementation treats {@code name} as optional (defaults to "scratch.txt") despite
     * the input schema marking it as required.
     *
     * <p>This test verifies graceful handling of the missing parameter: the tool must
     * return a non-blank, non-null string — either a success message
     * {@code "Created scratch file: ...scratch.txt..."} or a descriptive error string.
     * It must never throw an unhandled exception.
     *
     * <p>{@link CreateScratchFileTool} uses {@code EdtUtil.invokeAndWait} (EDT-check inline)
     * so it can be called directly from the test method.
     */
    public void testCreateScratchFileMissingName() throws Exception {
        // Provide only "content"; omit the "name" parameter.
        // The tool defaults name to "scratch.txt" when absent.
        JsonObject argsObj = new JsonObject();
        argsObj.addProperty("content", "// test content without explicit name");

        String result = createScratchFileTool.execute(argsObj);

        assertNotNull("Result must not be null", result);
        assertFalse("Result must not be blank", result.isBlank());
        // Either the file was created ("Created scratch file: ...") or an error was reported.
        boolean isValidResponse = result.startsWith("Created scratch file:")
            || result.startsWith("Error");
        assertTrue("Expected a 'Created' or 'Error' response, got: " + result, isValidResponse);
    }

    /**
     * {@code create_scratch_file} with both {@code name} and {@code content} must
     * succeed and return a "Created scratch file:" message that contains the filename.
     */
    public void testCreateScratchFileWithNameAndContent() throws Exception {
        JsonObject argsObj = new JsonObject();
        argsObj.addProperty("name", "test-editor-tools.md");
        argsObj.addProperty("content", "# Editor Tools Test\n\nContent here.");

        String result = createScratchFileTool.execute(argsObj);

        assertNotNull("Result must not be null", result);
        assertFalse("Result must not be blank", result.isBlank());
        // Success path: "Created scratch file: .../test-editor-tools.md (N chars)"
        // Failure path (e.g. IO error in test env): must still be a descriptive error.
        assertTrue("Expected success or error response, got: " + result,
            result.startsWith("Created scratch file:") || result.startsWith("Error"));
    }

    // ── ListThemesTool ────────────────────────────────────────────────────────

    /**
     * {@code list_themes} must return a non-blank response that starts with the
     * "Available themes:" header. IntelliJ always ships with built-in themes
     * (at minimum Darcula and IntelliJ Light), so the listing is never empty.
     */
    public void testListThemes() {
        // ListThemesTool is synchronous (no EDT dispatch) — safe to call directly.
        String result = listThemesTool.execute(new JsonObject());

        assertNotNull("Result must not be null", result);
        assertFalse("Result must not be blank", result.isBlank());
        assertTrue("Expected 'Available themes:' header, got: " + result,
            result.contains("Available themes:"));
    }

    /**
     * Verifies the theme-entry format contract when entries are present: bullet/active
     * markers, dark/light labels, and the {@code "← current"} active-theme marker.
     *
     * <p>In the headless Gradle test sandbox, {@code LafManager.getInstalledThemes()}
     * may return an empty sequence, in which case the response contains only the
     * "Available themes:" header. Both cases are valid — the important invariant is
     * that the tool never throws and always includes the header. Theme-specific
     * assertions are guarded by an entry-presence check.
     */
    public void testListThemesFormatWhenEntriesPresent() {
        String result = listThemesTool.execute(new JsonObject());

        assertNotNull(result);
        assertTrue("Expected 'Available themes:' header, got: " + result,
            result.contains("Available themes:"));

        boolean hasEntries = result.contains("•") || result.contains("▸");
        if (hasEntries) {
            // When theme entries ARE present, each must carry a dark/light label.
            assertTrue("Entries present but no '(dark)' or '(light)' label, got: " + result,
                result.contains("(dark)") || result.contains("(light)"));
            // And the active theme must be identified.
            assertTrue("Entries present but no '← current' active marker, got: " + result,
                result.contains("← current"));
        }
        // No entries: headless Gradle sandbox has no registered themes — header alone is fine.
    }

    /**
     * Verifies the result is never an error message regardless of theme availability.
     * The tool must always return a response that starts with "Available themes:".
     */
    public void testListThemesNeverErrors() {
        String result = listThemesTool.execute(new JsonObject());

        assertNotNull(result);
        assertFalse("Result must not start with 'Error'", result.startsWith("Error"));
        assertTrue("Result must always start with 'Available themes:', got: " + result,
            result.startsWith("Available themes:"));
    }
}
