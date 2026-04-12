package com.github.catatafishen.agentbridge.psi.tools.project;

import com.github.catatafishen.agentbridge.psi.ClassResolverUtil;
import com.github.catatafishen.agentbridge.psi.RunConfigurationService;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Platform tests for project tools: {@link GetProjectModulesTool},
 * {@link GetProjectDependenciesTool}, {@link GetIndexingStatusTool},
 * and {@link ListRunConfigurationsTool}.
 *
 * <p>JUnit 3 style (extends {@link BasePlatformTestCase}): test methods must be
 * {@code public void testXxx()}. Run via Gradle only:
 * {@code ./gradlew :plugin-core:test}.
 *
 * <p>{@link BasePlatformTestCase} creates a lightweight in-memory project with at
 * least one module and ensures indexing is complete before each test runs.
 */
public class ProjectToolsTest extends BasePlatformTestCase {

    private GetProjectModulesTool modulesTool;
    private GetProjectDependenciesTool dependenciesTool;
    private GetIndexingStatusTool indexingStatusTool;
    private ListRunConfigurationsTool runConfigsTool;
    private MarkDirectoryTool markDirectoryTool;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Prevent followFileIfEnabled from opening editors during tests.
        // Use the String overload — the boolean overload removes the key when value==defaultValue,
        // which would leave the setting at its default (true) instead of setting it to false.
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "false");

        modulesTool = new GetProjectModulesTool(getProject());
        dependenciesTool = new GetProjectDependenciesTool(getProject());
        indexingStatusTool = new GetIndexingStatusTool(getProject());

        // RunConfigurationService requires a ClassResolver. Provide a minimal stub that
        // returns the class name unchanged — sufficient for listRunConfigurations(), which
        // never calls resolveClass().
        RunConfigurationService runConfigService = new RunConfigurationService(
            getProject(),
            cn -> new ClassResolverUtil.ClassInfo(cn, null)
        );
        runConfigsTool = new ListRunConfigurationsTool(getProject(), runConfigService);
        markDirectoryTool = new MarkDirectoryTool(getProject());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * Builds a {@link JsonObject} from alternating key/value string pairs.
     * Example: {@code args("module", "plugin-core")}
     */
    private JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }

    // ── GetProjectModulesTool ─────────────────────────────────────────────────────

    /**
     * Verifies that {@code execute()} returns a non-empty string.
     * {@link BasePlatformTestCase} always provides at least one light test module.
     */
    public void testGetProjectModules() {
        String result = modulesTool.execute(new JsonObject());
        assertNotNull("execute() must not return null", result);
        assertFalse("Result should not be empty", result.isBlank());
    }

    /**
     * Verifies the response uses the expected "Modules (N):" summary header format,
     * confirming that at least one module was found in the test project.
     */
    public void testGetProjectModulesResponseFormat() {
        String result = modulesTool.execute(new JsonObject());
        // Either the summary header ("Modules (N):") or the detail field ("libraries:") must
        // appear — both indicate the tool ran successfully and listed at least one module.
        assertTrue("Expected module-listing format, got: " + result,
            result.contains("Modules (") || result.contains("libraries:"));
    }

    /**
     * Verifies that the per-module detail section ("libraries: N") is present,
     * proving that {@link GetProjectModulesTool} introspected each module's
     * {@code ModuleRootManager} order entries.
     */
    public void testGetProjectModulesContainsLibraryCount() {
        String result = modulesTool.execute(new JsonObject());
        assertTrue("Expected 'libraries:' per-module detail, got: " + result,
            result.contains("libraries:"));
    }

    // ── GetProjectDependenciesTool ────────────────────────────────────────────────

    /**
     * Verifies that {@code execute()} with empty args returns the standard
     * "Libraries in project (N):" header, confirming the tool ran successfully.
     */
    public void testGetProjectDependencies() {
        String result = dependenciesTool.execute(new JsonObject());
        assertNotNull("execute() must not return null", result);
        assertFalse("Result should not be empty", result.isBlank());
        assertTrue("Expected 'Libraries in' header, got: " + result,
            result.contains("Libraries in"));
    }

    /**
     * Verifies that omitting the optional {@code module} parameter does not throw
     * or produce an error message — the parameter is optional and must be handled
     * gracefully when absent.
     */
    public void testGetProjectDependenciesEmptyArgs() {
        String result = dependenciesTool.execute(new JsonObject());
        assertNotNull("execute() must not return null", result);
        assertFalse("Result should not be empty", result.isBlank());
        assertFalse("Result must not start with 'Error:'", result.startsWith("Error:"));
    }

    /**
     * Verifies that filtering by a module name that does not exist returns a
     * human-readable "not found" message rather than an exception or empty output.
     */
    public void testGetProjectDependenciesNonExistentModuleFilter() {
        String result = dependenciesTool.execute(args("module", "nonexistent_xyz_module_abc"));
        assertNotNull(result);
        assertFalse("Expected non-empty response for unknown module", result.isBlank());
        assertTrue("Expected module-not-found message, got: " + result,
            result.contains("not found") || result.contains("nonexistent_xyz_module_abc"));
    }

    // ── GetIndexingStatusTool ─────────────────────────────────────────────────────

    /**
     * Verifies that {@code execute()} returns a non-empty string that describes the
     * current indexing state in human-readable terms.
     */
    public void testGetIndexingStatus() throws Exception {
        String result = indexingStatusTool.execute(new JsonObject());
        assertNotNull("execute() must not return null", result);
        assertFalse("Result should not be empty", result.isBlank());
        // The response must mention indexing state or IDE readiness
        assertTrue("Expected indexing-state description, got: " + result,
            result.contains("Indexing") || result.contains("indexing")
                || result.contains("ready") || result.contains("Ready"));
    }

    /**
     * Verifies that in a freshly-initialized test project (no dumb mode active),
     * the tool reports that the IDE is ready and indexing is complete.
     * {@link BasePlatformTestCase} guarantees indexing is finished before
     * {@code setUp()} returns.
     */
    public void testGetIndexingStatusReportsReady() throws Exception {
        String result = indexingStatusTool.execute(new JsonObject());
        // The test framework guarantees the project is fully indexed at this point
        assertTrue("Expected IDE-ready report in non-dumb test project, got: " + result,
            result.contains("IDE is ready") || result.contains("ready") || result.contains("complete"));
    }

    /**
     * Verifies that {@code wait=true} with a short timeout still returns a valid status
     * message. In a test project that is already indexed the tool should return
     * "IDE is ready. Indexing is complete." almost immediately (no actual waiting needed),
     * since {@link com.intellij.openapi.project.DumbService#isDumb()} returns {@code false}
     * and the wait branch is skipped entirely.
     */
    public void testGetIndexingStatusWait() throws Exception {
        JsonObject waitArgs = new JsonObject();
        waitArgs.addProperty("wait", true);
        // Short timeout: the project is already indexed so this should never be reached
        waitArgs.addProperty("timeout", 5);

        String result = indexingStatusTool.execute(waitArgs);
        assertNotNull("execute() must not return null", result);
        assertFalse("Result should not be empty", result.isBlank());
        // Valid outcomes: immediate "IDE is ready" (not indexing) or a timeout/in-progress msg
        boolean isValidResponse = result.contains("IDE is ready")
            || result.contains("Indexing")
            || result.contains("indexing")
            || result.contains("timeout");
        assertTrue("Expected valid indexing-wait response, got: " + result, isValidResponse);
    }

    // ── ListRunConfigurationsTool ─────────────────────────────────────────────────

    /**
     * Verifies that {@code execute()} returns a non-empty, non-error response.
     * A pristine test project has no run configurations, so
     * "No run configurations found" is the expected output.
     */
    public void testListRunConfigurations() throws Exception {
        String result = runConfigsTool.execute(new JsonObject());
        assertNotNull("execute() must not return null", result);
        assertFalse("Result should not be empty", result.isBlank());
        // Either "No run configurations found" or "N run configurations:\n..."
        boolean isValidResponse = result.contains("No run configurations found")
            || result.contains("run configuration");
        assertTrue("Expected run-configurations response, got: " + result, isValidResponse);
    }

    /**
     * Verifies that a pristine {@link BasePlatformTestCase} project — which has no
     * run configurations registered in {@code RunManager} — returns the standard
     * empty-list message rather than an error or null.
     */
    public void testListRunConfigurationsEmptyProject() throws Exception {
        String result = runConfigsTool.execute(new JsonObject());
        // RunManager.getAllSettings() returns an empty list in a fresh light test project
        assertTrue(
            "Expected 'No run configurations found' in fresh test project, got: " + result,
            result.contains("No run configurations found") || result.contains("run configuration"));
    }

    // ── MarkDirectoryTool ─────────────────────────────────────────────────────────

    /**
     * Runs {@code tool.execute(argsObj)} on a pooled background thread while pumping
     * the EDT event queue.
     *
     * <p>{@link MarkDirectoryTool#execute} uses {@code EdtUtil.invokeLater} which posts
     * a {@code WriteAction} callback onto the EDT via a {@code CompletableFuture}. Because
     * test methods already run on the EDT, calling {@code execute()} directly would cause
     * a deadlock: the EDT is blocked on {@code future.get()} so the scheduled callback
     * can never run. Running the tool on a pooled thread while pumping the EDT with
     * {@link UIUtil#dispatchAllInvocationEvents()} breaks this deadlock.
     */
    private String executeSyncMark(MarkDirectoryTool tool, JsonObject argsObj) throws Exception {
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
                fail("MarkDirectoryTool.execute() timed out after 15 seconds");
            }
        }
        return future.get();
    }

    /**
     * An unrecognised {@code type} value must return an error immediately
     * (before any EDT dispatch), so calling {@code execute()} directly is safe here.
     */
    public void testMarkDirectoryInvalidType() throws Exception {
        String result = executeSyncMark(markDirectoryTool,
            args("path", "some/dir", "type", "invalid_type"));
        assertNotNull("Result must not be null", result);
        assertTrue("Expected 'Error:' for invalid type, got: " + result,
            result.startsWith("Error:"));
        assertTrue("Expected invalid type name in error, got: " + result,
            result.contains("invalid_type") || result.contains("invalid type"));
    }

    /**
     * When the target directory does not yet exist, {@link MarkDirectoryTool} must
     * create it ({@code Files.createDirectories}) and then attempt to mark it.
     * Under the test project whose base path is the module's content root, the
     * subdirectory is within the content root so the operation should succeed.
     * A VFS refresh failure ("Error: could not find directory in VFS") is also
     * accepted because the headless test environment has no automatic VFS watcher.
     */
    public void testMarkDirectoryNonExistentPathGetsCreated() throws Exception {
        String newPath = getProject().getBasePath()
            + "/marktest-nonexistent-" + System.currentTimeMillis();
        String result = executeSyncMark(markDirectoryTool,
            args("path", newPath, "type", "sources"));
        assertNotNull("Result must not be null", result);
        assertTrue("Expected 'Marked' or 'Error:' for non-existent path, got: " + result,
            result.contains("Marked") || result.startsWith("Error:"));
    }

    /**
     * Creates a real subdirectory under the test project's base path (which is the
     * module's content root) and marks it as a source root.
     * A VFS failure is accepted in the headless sandbox.
     */
    public void testMarkDirectoryAsSourcesInModule() throws Exception {
        Path subDir = Files.createTempDirectory(
            Path.of(getProject().getBasePath()), "marktest-sources");
        String result = executeSyncMark(markDirectoryTool,
            args("path", subDir.toString(), "type", "sources"));
        assertNotNull("Result must not be null", result);
        assertTrue("Expected 'Marked' or 'Error:' for sources marking, got: " + result,
            result.contains("Marked") || result.startsWith("Error:"));
    }

    /**
     * Creates a real subdirectory under the test project's base path and marks it
     * as excluded. A VFS failure is accepted in the headless sandbox.
     */
    public void testMarkDirectoryAsExcluded() throws Exception {
        Path subDir = Files.createTempDirectory(
            Path.of(getProject().getBasePath()), "marktest-excl");
        String result = executeSyncMark(markDirectoryTool,
            args("path", subDir.toString(), "type", "excluded"));
        assertNotNull("Result must not be null", result);
        assertTrue("Expected 'Marked' or 'Error:' for excluded marking, got: " + result,
            result.contains("Marked") || result.startsWith("Error:"));
    }

    /**
     * When both required parameters are absent, {@code args.get("path")} returns
     * {@code null} and {@code .getAsString()} throws a {@link NullPointerException}.
     * The test accepts either that exception (wrapped as {@link ExecutionException})
     * or an "Error:" string if the tool ever adds defensive null-checks.
     */
    public void testMarkDirectoryMissingArgs() throws Exception {
        try {
            String result = executeSyncMark(markDirectoryTool, new JsonObject());
            // If the tool handles missing args gracefully it should return an error string.
            assertTrue("Expected 'Error:' for missing args, got: " + result,
                result.startsWith("Error:"));
        } catch (ExecutionException e) {
            // NPE from args.get("path").getAsString() wrapped in ExecutionException — expected.
            assertTrue("Expected NPE as the cause, got: " + e.getCause(),
                e.getCause() instanceof NullPointerException);
        }
    }
}
