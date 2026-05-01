package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import static org.junit.Assert.assertNotEquals;

/**
 * Platform tests for infrastructure tools: {@link ListRunTabsTool},
 * {@link GetNotificationsTool}, {@link ReadBuildOutputTool}, and {@link ReadRunOutputTool}.
 *
 * <p>JUnit 3 style (extends {@link BasePlatformTestCase}): test methods must be
 * {@code public void testXxx()}. Run via Gradle only:
 * {@code ./gradlew :plugin-core:test}.
 *
 * <p>All tools in this group use {@code EdtUtil.invokeAndWait} or synchronous execution
 * only. {@code EdtUtil.invokeAndWait} contains an {@code isDispatchThread()} check that
 * runs the runnable inline when called from the EDT, so all tools can be called directly
 * from the test method without a {@code executeSync} wrapper.
 *
 * <p>A fresh {@link BasePlatformTestCase} project has no run/build activity and no
 * notifications, so all "empty state" assertions are deterministic.
 */
public class InfrastructureToolsExtendedTest extends BasePlatformTestCase {

    private ListRunTabsTool listRunTabsTool;
    private GetNotificationsTool getNotificationsTool;
    private ReadBuildOutputTool readBuildOutputTool;
    private ReadRunOutputTool readRunOutputTool;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Disable "follow agent files" UI navigation so tools do not open extra editors
        // or steal focus in the headless test environment.
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "false");

        listRunTabsTool = new ListRunTabsTool(getProject());
        getNotificationsTool = new GetNotificationsTool(getProject());
        readBuildOutputTool = new ReadBuildOutputTool(getProject());
        readRunOutputTool = new ReadRunOutputTool(getProject());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
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
     */
    private static JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }

    // ── ListRunTabsTool ───────────────────────────────────────────────────────

    /**
     * In a fresh test project with no run/build activity, {@code list_run_tabs}
     * must return a non-blank response that includes the "Run panel tabs" section
     * header and "(none)" for the empty run panel.
     *
     * <p>{@link ListRunTabsTool} uses {@code EdtUtil.invokeAndWait} which runs
     * inline on the EDT — safe to call directly from the test.
     */
    public void testListRunTabsEmpty() {
        String result = listRunTabsTool.execute(new JsonObject());

        assertNotNull("Result must not be null", result);
        assertFalse("Result must not be blank", result.isBlank());
        assertTrue("Expected 'Run panel tabs' section header, got: " + result,
            result.contains("Run panel tabs"));
        // No run/debug configurations have been launched — run panel must report empty.
        assertTrue("Expected '(none)' for empty run panel, got: " + result,
            result.contains("(none)"));
    }

    /**
     * Verifies that the result also includes the "Build panel tabs" section header.
     * Even when no build has run (Build tool window may be unavailable in a light
     * test project), the section header must appear in the output.
     */
    public void testListRunTabsIncludesBuildSection() {
        String result = listRunTabsTool.execute(new JsonObject());

        assertNotNull(result);
        assertTrue("Expected 'Build panel tabs' section header, got: " + result,
            result.contains("Build panel tabs"));
    }

    /**
     * The result must be a non-null, non-blank, non-exception string regardless
     * of IDE state — the tool must never throw or return null.
     */
    public void testListRunTabsReturnsNonBlank() {
        String result = listRunTabsTool.execute(new JsonObject());

        assertNotNull(result);
        assertFalse("Result must not be blank", result.isBlank());
        assertFalse("Result must not be a raw Java exception", result.startsWith("java."));
    }

    // ── GetNotificationsTool ──────────────────────────────────────────────────

    /**
     * In a fresh test project with no notifications, {@code get_notifications} must
     * return exactly "No recent notifications." — the standard empty-list response
     * from {@link GetNotificationsTool#execute(JsonObject)}.
     *
     * <p>{@link GetNotificationsTool} is synchronous (no EDT dispatch) and can be
     * called directly from the test.
     */
    public void testGetNotificationsEmpty() {
        String result = getNotificationsTool.execute(new JsonObject());

        assertNotNull("Result must not be null", result);
        assertEquals("Expected 'No recent notifications.' in fresh test project, got: " + result,
            "No recent notifications.", result);
    }

    /**
     * Verifies the tool never throws even when no notifications have been issued.
     * The response must be a non-blank string.
     */
    public void testGetNotificationsReturnsNonBlank() {
        String result = getNotificationsTool.execute(new JsonObject());

        assertNotNull(result);
        assertFalse("Result must not be blank", result.isBlank());
    }

    /**
     * Verifies the tool returns a human-readable response rather than a raw
     * Java exception string or null literal.
     */
    public void testGetNotificationsNoRawException() {
        String result = getNotificationsTool.execute(new JsonObject());

        assertNotNull(result);
        assertFalse("Result must not be a raw Java exception", result.startsWith("java."));
        assertNotEquals("Result must not be the 'null' string", "null", result);
    }

    // ── ReadBuildOutputTool ───────────────────────────────────────────────────

    /**
     * When no build has been run, {@code read_build_output} must return a
     * human-readable message describing why no output is available.
     *
     * <p>Two valid outcomes exist depending on the test IDE environment:
     * <ul>
     *   <li>"Build tool window is not available" — Build tool window absent
     *       (light test project without Java plugin active)</li>
     *   <li>"Build tool window is empty" — tool window present but no content yet</li>
     * </ul>
     * The tool must never throw an unhandled exception.
     *
     * <p>{@link ReadBuildOutputTool} uses {@code EdtUtil.invokeAndWait} which
     * runs inline on the EDT — safe to call directly.
     */
    public void testReadBuildOutputNoTabs() {
        String result = readBuildOutputTool.execute(new JsonObject());

        assertNotNull("Result must not be null", result);
        assertFalse("Result must not be blank", result.isBlank());
        // Either the Build tool window is absent or present-but-empty.
        boolean isExpectedResponse =
            result.contains("Build tool window is not available")
                || result.contains("Build tool window is empty")
                || result.contains("not available");
        assertTrue("Expected no-build description, got: " + result, isExpectedResponse);
    }

    /**
     * Specifying a {@code tab_name} when no build tabs exist must still return
     * a graceful message — never an unhandled exception or blank string.
     *
     * <p>The Build tool window is absent in a light test project, so the tool
     * returns its "not available" message before it can attempt tab matching.
     */
    public void testReadBuildOutputSpecificTabNoBuilds() {
        String result = readBuildOutputTool.execute(args("tab_name", "nonexistent_build_tab_xyz"));

        assertNotNull("Result must not be null", result);
        assertFalse("Result must not be blank", result.isBlank());
        assertFalse("Result must not start with 'Error reading Build output:'",
            result.startsWith("Error reading Build output:"));
    }

    /**
     * Verifies the result is never a raw Java exception stack trace or null literal,
     * regardless of the build state.
     */
    public void testReadBuildOutputNoRawException() {
        String result = readBuildOutputTool.execute(new JsonObject());

        assertNotNull(result);
        assertFalse("Result must not be a raw Java exception", result.startsWith("java."));
        assertNotEquals("Result must not be the 'null' string", "null", result);
    }

    // ── ReadRunOutputTool ─────────────────────────────────────────────────────

    /**
     * When no run configurations have been launched, {@code read_run_output} must
     * return exactly "No Run or Debug panel tabs available." — the standard
     * empty-panel response from {@link ReadRunOutputTool#execute(JsonObject)}.
     *
     * <p>{@link ReadRunOutputTool} uses {@code runReadAction} + {@code invokeAndWait},
     * both of which handle being called from the EDT correctly.
     */
    public void testReadRunOutputNoTabs() {
        String result = readRunOutputTool.execute(new JsonObject());

        assertNotNull("Result must not be null", result);
        assertEquals(
            "Expected 'No Run or Debug panel tabs available.' in fresh project, got: " + result,
            "No Run or Debug panel tabs available.", result);
    }

    /**
     * When a specific tab name is requested but no run tabs exist at all, the tool must
     * return the same "No Run or Debug panel tabs available." message, because the
     * empty-descriptors check occurs before any name-matching logic.
     */
    public void testReadRunOutputMissingTab() {
        String result = readRunOutputTool.execute(args("tab_name", "nonexistent_tab_xyz_abc"));

        assertNotNull("Result must not be null", result);
        assertFalse("Result must not be blank", result.isBlank());
        // With no run tabs at all, the empty-panel guard fires before name matching.
        assertEquals(
            "Expected 'No Run or Debug panel tabs available.' when no tabs exist, got: " + result,
            "No Run or Debug panel tabs available.", result);
    }

    /**
     * Verifies that when no run panel is available, the result is always a user-readable
     * message — never a Java exception stack trace, null string, or blank response.
     */
    public void testReadRunOutputNoTabsReturnsReadableMessage() {
        String result = readRunOutputTool.execute(new JsonObject());

        assertNotNull(result);
        assertFalse("Result must not be blank", result.isBlank());
        assertNotEquals("Result must not be the 'null' string", "null", result);
        assertFalse("Result must not be a raw Java exception", result.startsWith("java."));
    }

    /**
     * Verifies that omitting the optional {@code tab_name} and {@code max_chars}
     * parameters is handled gracefully — the tool must use its defaults and return
     * the same result as calling it with explicit defaults.
     */
    public void testReadRunOutputDefaultParameters() {
        String withoutParams = readRunOutputTool.execute(new JsonObject());

        JsonObject explicitDefaults = new JsonObject();
        explicitDefaults.addProperty("max_chars", 8000);
        String withExplicitDefaults = readRunOutputTool.execute(explicitDefaults);

        // Both calls with no tabs return the same empty-panel message.
        assertEquals("Default and explicit-default calls must return the same result",
            withoutParams, withExplicitDefaults);
    }
}
