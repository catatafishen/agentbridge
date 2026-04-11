package com.github.catatafishen.agentbridge.psi.tools.debug;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.tools.debug.breakpoints.BreakpointAddExceptionTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.breakpoints.BreakpointAddTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.breakpoints.BreakpointListTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.breakpoints.BreakpointRemoveTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.breakpoints.BreakpointUpdateTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.inspection.DebugEvaluateTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.inspection.DebugInspectFrameTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.inspection.DebugReadConsoleTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.inspection.DebugSnapshotTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.inspection.DebugVariableDetailTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.navigation.DebugRunToLineTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.navigation.DebugStepTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.session.DebugSessionListTool;
import com.github.catatafishen.agentbridge.psi.tools.debug.session.DebugSessionStopTool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.breakpoints.XBreakpoint;

import java.util.List;

/**
 * Platform tests for all 14 debug tools created by {@link DebugToolFactory}.
 *
 * <p>JUnit 3 style (extends {@link BasePlatformTestCase}): test methods must be
 * {@code public void testXxx()}. Run via Gradle only:
 * {@code ./gradlew :plugin-core:test}.
 *
 * <p>Debug tools generally require an active (possibly paused) debug session.
 * Since a real debug session cannot be started in a light test environment, all
 * tests focus on two complementary strategies:
 * <ol>
 *   <li><b>Read-only state queries</b> — tools that can answer without a session
 *       ({@link BreakpointListTool}, {@link DebugSessionListTool}) are exercised
 *       against the empty state of a fresh test project.</li>
 *   <li><b>Graceful error paths</b> — tools that call
 *       {@code requireSession()} / {@code requirePausedSession()} must throw
 *       {@link IllegalStateException} with a clear user-facing message; tools with
 *       required parameters must return a well-formed error string when the
 *       parameters are invalid.</li>
 * </ol>
 *
 * <p>{@link #tearDown()} removes all breakpoints added during a test to prevent
 * state from leaking into subsequent test methods that share the light project.
 */
public class DebugToolsTest extends BasePlatformTestCase {

    // ── Tool instances ────────────────────────────────────────────────────────────

    // Breakpoint tools
    private BreakpointListTool breakpointListTool;
    private BreakpointAddTool breakpointAddTool;
    private BreakpointAddExceptionTool breakpointAddExceptionTool;
    private BreakpointRemoveTool breakpointRemoveTool;
    private BreakpointUpdateTool breakpointUpdateTool;

    // Session tools
    private DebugSessionListTool debugSessionListTool;
    private DebugSessionStopTool debugSessionStopTool;

    // Inspection tools
    private DebugSnapshotTool debugSnapshotTool;
    private DebugVariableDetailTool debugVariableDetailTool;
    private DebugInspectFrameTool debugInspectFrameTool;
    private DebugEvaluateTool debugEvaluateTool;
    private DebugReadConsoleTool debugReadConsoleTool;

    // Navigation tools
    private DebugRunToLineTool debugRunToLineTool;
    private DebugStepTool debugStepTool;

    // ── Lifecycle ─────────────────────────────────────────────────────────────────

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        // Clear any pre-existing breakpoints registered during project initialization
        // (e.g. the default disabled "Java Exception Breakpoints" added by the Java plugin).
        // This ensures every test method starts from a clean, zero-breakpoint state.
        clearAllBreakpoints();

        // Prevent followFileIfEnabled from opening editors during tests.
        // Use the String overload — the boolean overload removes the key when
        // value==defaultValue, which would leave the setting at its default (true).
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "false");

        breakpointListTool = new BreakpointListTool(getProject());
        breakpointAddTool = new BreakpointAddTool(getProject());
        breakpointAddExceptionTool = new BreakpointAddExceptionTool(getProject());
        breakpointRemoveTool = new BreakpointRemoveTool(getProject());
        breakpointUpdateTool = new BreakpointUpdateTool(getProject());

        debugSessionListTool = new DebugSessionListTool(getProject());
        debugSessionStopTool = new DebugSessionStopTool(getProject());

        debugSnapshotTool = new DebugSnapshotTool(getProject());
        debugVariableDetailTool = new DebugVariableDetailTool(getProject());
        debugInspectFrameTool = new DebugInspectFrameTool(getProject());
        debugEvaluateTool = new DebugEvaluateTool(getProject());
        debugReadConsoleTool = new DebugReadConsoleTool(getProject());

        debugRunToLineTool = new DebugRunToLineTool(getProject());
        debugStepTool = new DebugStepTool(getProject());
    }

    /**
     * Removes all breakpoints set during the test to prevent state leaking into
     * subsequent test methods that reuse the same light project instance.
     */
    @Override
    protected void tearDown() throws Exception {
        clearAllBreakpoints();
        super.tearDown();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * Builds a {@link JsonObject} from alternating key/value string pairs.
     * Example: {@code args("file", "/some/path.java", "line", "42")}
     */
    private JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // ── BreakpointListTool ────────────────────────────────────────────────────────
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that {@code execute()} returns the canonical "No breakpoints set."
     * message when no user breakpoints exist. Uses {@link BreakpointRemoveTool}
     * with {@code remove_all: true} to clear any default breakpoints (e.g. the
     * Java plugin's "Java Exception Breakpoints") that may be registered
     * asynchronously after {@link #setUp()}.
     *
     * <p>Because the Java plugin may re-register its default breakpoint between
     * the remove and the list calls, this test also accepts a response that only
     * contains the disabled default "Java Exception Breakpoints" entry.
     */
    public void testListBreakpointsEmptyReturnsNoBreakpointsMessage() throws Exception {
        JsonObject removeAll = new JsonObject();
        removeAll.addProperty("remove_all", true);
        breakpointRemoveTool.execute(removeAll);

        String result = breakpointListTool.execute(new JsonObject());
        assertTrue("Expected empty or default-only breakpoint list, got: " + result,
            result.equals("No breakpoints set.")
                || (result.contains("Java Exception Breakpoints") && result.contains("DISABLED")));
    }

    /**
     * Verifies that {@code execute()} never returns {@code null} and always
     * produces a non-blank string, regardless of breakpoint state.
     */
    public void testListBreakpointsResultIsNonNull() {
        String result = breakpointListTool.execute(new JsonObject());
        assertNotNull("execute() must not return null", result);
        assertFalse("Result must not be blank", result.isBlank());
    }

    /**
     * Verifies that {@link BreakpointListTool} is flagged as a read-only tool
     * (it only reads the breakpoint manager and never modifies project state).
     */
    public void testBreakpointListToolIsReadOnly() {
        assertTrue("BreakpointListTool.isReadOnly() must return true",
            breakpointListTool.isReadOnly());
    }

    /**
     * Verifies that {@link BreakpointListTool} reports the DEBUG category.
     */
    public void testBreakpointListToolCategory() {
        assertEquals("BreakpointListTool must be in the DEBUG category",
            ToolRegistry.Category.DEBUG, breakpointListTool.category());
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // ── BreakpointAddTool ─────────────────────────────────────────────────────────
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that attempting to add a breakpoint to an absolute path that does
     * not exist in the VFS returns a "File not found:" error instead of throwing.
     */
    public void testAddBreakpointAbsoluteFileNotFoundReturnsError() throws Exception {
        String result = breakpointAddTool.execute(args(
            "file", "/nonexistent/path/to/NonexistentSource.java",
            "line", "10"
        ));
        assertNotNull("execute() must not return null", result);
        assertTrue("Expected 'File not found:' message, got: " + result,
            result.startsWith("File not found:"));
        assertTrue("Error message must include the requested path, got: " + result,
            result.contains("NonexistentSource.java"));
    }

    /**
     * Verifies that a project-relative path that resolves to no real file
     * also returns a "File not found:" error gracefully.
     */
    public void testAddBreakpointProjectRelativeFileNotFoundReturnsError() throws Exception {
        String result = breakpointAddTool.execute(args(
            "file", "src/main/java/com/example/NoSuchClass.java",
            "line", "1"
        ));
        assertNotNull("execute() must not return null", result);
        // "File not found:" is the canonical prefix from BreakpointAddTool
        assertTrue("Expected file-not-found message, got: " + result,
            result.contains("File not found:") || result.contains("not found"));
    }

    /**
     * Verifies that {@link BreakpointAddTool} does not modify the breakpoint
     * manager when the target file cannot be resolved — i.e. no phantom breakpoint
     * is created. Compares the count before and after to avoid assuming a specific
     * absolute count (default breakpoints such as "Java Exception Breakpoints" may
     * be present in the test environment).
     */
    public void testAddBreakpointNonexistentFileDoesNotAddBreakpoint() throws Exception {
        var bpManager = XDebuggerManager.getInstance(getProject()).getBreakpointManager();
        int countBefore = bpManager.getAllBreakpoints().length;

        breakpointAddTool.execute(args(
            "file", "/does/not/exist/Phantom.java",
            "line", "5"
        ));

        int countAfter = bpManager.getAllBreakpoints().length;
        assertEquals("No breakpoint should have been added for a nonexistent file",
            countBefore, countAfter);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // ── BreakpointRemoveTool ──────────────────────────────────────────────────────
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that calling {@code execute()} with no selector arguments returns
     * a descriptive error explaining which selectors are accepted, rather than
     * throwing or returning a null/blank response.
     */
    public void testRemoveBreakpointNoSelectorReturnsError() throws Exception {
        String result = breakpointRemoveTool.execute(new JsonObject());
        assertNotNull("execute() must not return null", result);
        assertTrue("Expected 'Error:' prefix for missing selector, got: " + result,
            result.startsWith("Error:"));
        assertTrue("Error must mention valid selectors (index / file / remove_all), got: " + result,
            result.contains("index") || result.contains("file") || result.contains("remove_all"));
    }

    /**
     * Verifies that supplying an out-of-range 1-based index returns an error message
     * that mentions the index and the valid range, as well as a hint to run
     * {@code breakpoint_list}. Uses {@code total + 1} as the index so the test is
     * robust to any number of default breakpoints present in the test environment.
     */
    public void testRemoveBreakpointIndexOutOfRangeReturnsError() throws Exception {
        int total = XDebuggerManager.getInstance(getProject()).getBreakpointManager().getAllBreakpoints().length;
        JsonObject a = new JsonObject();
        a.addProperty("index", total + 1);
        String result = breakpointRemoveTool.execute(a);
        assertNotNull("execute() must not return null", result);
        assertTrue("Expected 'Error:' prefix for out-of-range index, got: " + result,
            result.startsWith("Error:"));
        assertTrue("Error must mention 'out of range', got: " + result,
            result.contains("out of range"));
        assertTrue("Error must suggest breakpoint_list, got: " + result,
            result.contains("breakpoint_list"));
    }

    /**
     * Verifies that {@code remove_all: true} succeeds and returns a confirmation
     * message (not an error). The Java plugin may asynchronously re-register its
     * default "Java Exception Breakpoints" between calls, so the count is not
     * asserted — only the message format.
     */
    public void testRemoveAllBreakpointsWhenNoneExistReturnsConfirmation() throws Exception {
        JsonObject a = new JsonObject();
        a.addProperty("remove_all", true);
        breakpointRemoveTool.execute(a); // clear any defaults (e.g. Java Exception Breakpoints)
        String result = breakpointRemoveTool.execute(a); // second call — count may be 0 or 1
        assertNotNull("execute() must not return null", result);
        assertTrue("Expected 'Removed all' confirmation, got: " + result,
            result.contains("Removed all") && result.contains("breakpoint(s)"));
    }

    /**
     * Verifies that supplying a {@code file} + {@code line} selector pointing to a
     * path that has no breakpoint returns an error message that includes the
     * requested file path.
     */
    public void testRemoveBreakpointByFileLineNoneExistReturnsError() throws Exception {
        String result = breakpointRemoveTool.execute(args(
            "file", "/nonexistent/src/Missing.java",
            "line", "5"
        ));
        assertNotNull("execute() must not return null", result);
        assertTrue("Expected 'Error:' prefix for missing breakpoint, got: " + result,
            result.startsWith("Error:"));
        assertTrue("Error must reference the file path, got: " + result,
            result.contains("Missing.java") || result.contains("no breakpoint found"));
    }

    /**
     * Verifies that supplying only a {@code file} argument (no {@code line}) still
     * produces a meaningful error rather than throwing a NullPointerException.
     */
    public void testRemoveBreakpointFileOnlyNoLineReturnsError() throws Exception {
        String result = breakpointRemoveTool.execute(args("file", "/some/path/File.java"));
        assertNotNull("execute() must not return null", result);
        assertFalse("Result must not be blank", result.isBlank());
        assertTrue("Expected error or 'no breakpoint found' message, got: " + result,
            result.startsWith("Error:") || result.contains("index") || result.contains("no breakpoint found"));
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // ── BreakpointUpdateTool ──────────────────────────────────────────────────────
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that calling {@code execute()} with an empty argument object returns
     * a guidance message asking the caller to supply an {@code index} or
     * {@code file}+{@code line} selector.
     */
    public void testUpdateBreakpointNoSelectorReturnsGuidance() throws Exception {
        String result = breakpointUpdateTool.execute(new JsonObject());
        assertNotNull("execute() must not return null", result);
        // "Provide 'index' or 'file'+'line' to identify the breakpoint."
        assertTrue("Expected guidance about providing a selector, got: " + result,
            result.contains("index") || result.contains("file") || result.contains("Provide"));
    }

    /**
     * Verifies that an out-of-range index returns an error with the index number
     * and a suggestion to run {@code breakpoint_list}.
     */
    public void testUpdateBreakpointIndexOutOfRangeReturnsError() throws Exception {
        JsonObject a = new JsonObject();
        a.addProperty("index", 99);
        a.addProperty("enabled", true);
        String result = breakpointUpdateTool.execute(a);
        assertNotNull("execute() must not return null", result);
        assertTrue("Expected 'out of range' in error, got: " + result,
            result.contains("out of range"));
        assertTrue("Error must suggest breakpoint_list, got: " + result,
            result.contains("breakpoint_list"));
    }

    /**
     * Verifies that supplying a {@code file}+{@code line} selector that matches no
     * breakpoint returns a human-readable "not found" error rather than throwing.
     */
    public void testUpdateBreakpointByFileLineNoneExistReturnsError() throws Exception {
        String result = breakpointUpdateTool.execute(args(
            "file", "/nonexistent/path/Absent.java",
            "line", "20"
        ));
        assertNotNull("execute() must not return null", result);
        // "No breakpoint found at /nonexistent/path/Absent.java:20."
        assertTrue("Expected 'No breakpoint found' or similar error, got: " + result,
            result.contains("No breakpoint found") || result.contains("not found") || result.contains("Provide"));
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // ── BreakpointAddExceptionTool ────────────────────────────────────────────────
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that adding an exception breakpoint for a concrete Java exception
     * class either succeeds (Java plugin present) or returns a human-readable
     * "no exception breakpoint type found" message (lightweight test environment
     * without the full Java plugin loaded).
     *
     * <p>In both cases the result must be non-null, non-blank, and must not be a
     * raw stack trace.
     */
    public void testAddExceptionBreakpointRuntimeExceptionReturnsValidResponse() throws Exception {
        String result = breakpointAddExceptionTool.execute(args(
            "exception_class", "java.lang.RuntimeException"
        ));
        assertNotNull("execute() must not return null", result);
        assertFalse("Result must not be blank", result.isBlank());
        // Either the breakpoint was added, or no exception BP type is available in the
        // lightweight test environment — both are valid outcomes.
        boolean isValidResponse =
            result.contains("exception breakpoint")
                || result.contains("No exception breakpoint type found");
        assertTrue("Expected a valid exception-breakpoint response, got: " + result, isValidResponse);
    }

    /**
     * Verifies that the wildcard {@code "*"} exception class is handled gracefully —
     * either a "catch-any" exception breakpoint is created, or the tool reports that
     * no exception breakpoint type is available.
     */
    public void testAddExceptionBreakpointWildcardReturnsValidResponse() throws Exception {
        String result = breakpointAddExceptionTool.execute(args("exception_class", "*"));
        assertNotNull("execute() must not return null", result);
        assertFalse("Result must not be blank", result.isBlank());
        boolean isValidResponse =
            result.contains("exception breakpoint")
                || result.contains("No exception breakpoint type found");
        assertTrue("Expected a valid wildcard exception-breakpoint response, got: " + result,
            isValidResponse);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // ── DebugSessionListTool ──────────────────────────────────────────────────────
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that {@code execute()} returns the exact string {@code "No active debug sessions."}
     * when no debug sessions are running, as guaranteed for a fresh test project.
     */
    public void testDebugSessionListEmptyReturnsNoSessionsMessage() {
        String result = debugSessionListTool.execute(new JsonObject());
        assertNotNull("execute() must not return null", result);
        assertEquals("Expected 'No active debug sessions.' for an empty session list",
            "No active debug sessions.", result);
    }

    /**
     * Verifies that {@link DebugSessionListTool} is a read-only tool.
     */
    public void testDebugSessionListIsReadOnly() {
        assertTrue("DebugSessionListTool.isReadOnly() must return true",
            debugSessionListTool.isReadOnly());
    }

    /**
     * Verifies that {@code execute()} never returns {@code null}.
     */
    public void testDebugSessionListResultIsNonNull() {
        assertNotNull("execute() must not return null",
            debugSessionListTool.execute(new JsonObject()));
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // ── DebugSessionStopTool ──────────────────────────────────────────────────────
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that attempting to stop the active session when none is running
     * throws {@link IllegalStateException} with the canonical "No active debug session"
     * message from {@code DebugTool.requireSession()}.
     */
    public void testDebugSessionStopNoSessionThrowsIllegalStateException() throws Exception {
        try {
            debugSessionStopTool.execute(new JsonObject());
            fail("Expected IllegalStateException when no active debug session is present");
        } catch (IllegalStateException e) {
            assertNotNull("Exception message must not be null", e.getMessage());
            assertTrue(
                "Expected 'No active debug session' in the exception message, got: " + e.getMessage(),
                e.getMessage().contains("No active debug session"));
        }
    }

    /**
     * Verifies that {@code stop_all: true} returns the "No active debug sessions to stop."
     * message gracefully (no exception) when there are no running sessions.
     */
    public void testDebugSessionStopAllNoSessionsReturnsMessage() throws Exception {
        JsonObject a = new JsonObject();
        a.addProperty("stop_all", true);
        String result = debugSessionStopTool.execute(a);
        assertNotNull("execute() must not return null", result);
        assertEquals("Expected 'No active debug sessions to stop.' when no sessions running",
            "No active debug sessions to stop.", result);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // ── DebugSnapshotTool (requires paused session) ───────────────────────────────
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that {@link DebugSnapshotTool#execute} throws {@link IllegalStateException}
     * with the "No active debug session" message when there is no running session.
     */
    public void testDebugSnapshotNoSessionThrowsIllegalStateException() throws Exception {
        try {
            debugSnapshotTool.execute(new JsonObject());
            fail("Expected IllegalStateException when no active debug session is present");
        } catch (IllegalStateException e) {
            assertTrue(
                "Expected 'No active debug session' in exception message, got: " + e.getMessage(),
                e.getMessage().contains("No active debug session"));
        }
    }

    /**
     * Verifies that explicitly providing all {@code include_*} flags still propagates
     * the no-session error — confirming that the session guard is checked first,
     * before any flag processing occurs.
     */
    public void testDebugSnapshotWithAllFlagsNoSessionThrowsIllegalStateException() throws Exception {
        JsonObject a = new JsonObject();
        a.addProperty("include_source", true);
        a.addProperty("include_variables", true);
        a.addProperty("include_stack", true);
        try {
            debugSnapshotTool.execute(a);
            fail("Expected IllegalStateException when no active debug session is present");
        } catch (IllegalStateException e) {
            assertTrue(
                "Expected 'No active debug session' in exception message, got: " + e.getMessage(),
                e.getMessage().contains("No active debug session"));
        }
    }

    /**
     * Verifies that passing {@code include_source: false} etc. also throws before
     * attempting any file access, confirming the guard is not bypassed by disabling flags.
     */
    public void testDebugSnapshotAllFlagsFalseNoSessionThrowsIllegalStateException() throws Exception {
        JsonObject a = new JsonObject();
        a.addProperty("include_source", false);
        a.addProperty("include_variables", false);
        a.addProperty("include_stack", false);
        try {
            debugSnapshotTool.execute(a);
            fail("Expected IllegalStateException when no active debug session is present");
        } catch (IllegalStateException e) {
            assertTrue(
                "Expected 'No active debug session' in exception message, got: " + e.getMessage(),
                e.getMessage().contains("No active debug session"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // ── DebugVariableDetailTool (requires paused session) ────────────────────────
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that {@link DebugVariableDetailTool#execute} throws
     * {@link IllegalStateException} with the "No active debug session" message.
     */
    public void testDebugVariableDetailNoSessionThrowsIllegalStateException() throws Exception {
        try {
            debugVariableDetailTool.execute(args("path", "someVariable"));
            fail("Expected IllegalStateException when no active debug session is present");
        } catch (IllegalStateException e) {
            assertTrue(
                "Expected 'No active debug session' in exception message, got: " + e.getMessage(),
                e.getMessage().contains("No active debug session"));
        }
    }

    /**
     * Verifies that a nested dot-path also hits the session guard before any
     * variable resolution is attempted.
     */
    public void testDebugVariableDetailDotPathNoSessionThrowsIllegalStateException() throws Exception {
        try {
            debugVariableDetailTool.execute(args("path", "obj.field.nested"));
            fail("Expected IllegalStateException when no active debug session is present");
        } catch (IllegalStateException e) {
            assertTrue(
                "Expected 'No active debug session' in exception message, got: " + e.getMessage(),
                e.getMessage().contains("No active debug session"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // ── DebugInspectFrameTool (requires paused session) ───────────────────────────
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that {@link DebugInspectFrameTool#execute} throws
     * {@link IllegalStateException} with the "No active debug session" message
     * for frame index 0 (the top/current frame).
     */
    public void testDebugInspectFrameZeroNoSessionThrowsIllegalStateException() throws Exception {
        JsonObject a = new JsonObject();
        a.addProperty("frame_index", 0);
        try {
            debugInspectFrameTool.execute(a);
            fail("Expected IllegalStateException when no active debug session is present");
        } catch (IllegalStateException e) {
            assertTrue(
                "Expected 'No active debug session' in exception message, got: " + e.getMessage(),
                e.getMessage().contains("No active debug session"));
        }
    }

    /**
     * Verifies that inspecting a deeper frame index also hits the session guard
     * first — no NullPointerException or ArrayIndexOutOfBoundsException should be thrown.
     */
    public void testDebugInspectFrameNonZeroNoSessionThrowsIllegalStateException() throws Exception {
        JsonObject a = new JsonObject();
        a.addProperty("frame_index", 3);
        try {
            debugInspectFrameTool.execute(a);
            fail("Expected IllegalStateException when no active debug session is present");
        } catch (IllegalStateException e) {
            assertTrue(
                "Expected 'No active debug session' in exception message, got: " + e.getMessage(),
                e.getMessage().contains("No active debug session"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // ── DebugEvaluateTool (requires paused session) ───────────────────────────────
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that evaluating a simple expression throws {@link IllegalStateException}
     * when there is no active debug session.
     */
    public void testDebugEvaluateSimpleExpressionNoSessionThrowsIllegalStateException() throws Exception {
        try {
            debugEvaluateTool.execute(args("expression", "1 + 1"));
            fail("Expected IllegalStateException when no active debug session is present");
        } catch (IllegalStateException e) {
            assertTrue(
                "Expected 'No active debug session' in exception message, got: " + e.getMessage(),
                e.getMessage().contains("No active debug session"));
        }
    }

    /**
     * Verifies that providing an explicit {@code frame_index} does not bypass
     * the session guard.
     */
    public void testDebugEvaluateWithFrameIndexNoSessionThrowsIllegalStateException() throws Exception {
        JsonObject a = new JsonObject();
        a.addProperty("expression", "myObj.toString()");
        a.addProperty("frame_index", 1);
        try {
            debugEvaluateTool.execute(a);
            fail("Expected IllegalStateException when no active debug session is present");
        } catch (IllegalStateException e) {
            assertTrue(
                "Expected 'No active debug session' in exception message, got: " + e.getMessage(),
                e.getMessage().contains("No active debug session"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // ── DebugReadConsoleTool (requires active — not necessarily paused — session) ─
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that reading the debug console throws {@link IllegalStateException}
     * with the "No active debug session" message when no session is running.
     */
    public void testDebugReadConsoleNoSessionThrowsIllegalStateException() throws Exception {
        try {
            debugReadConsoleTool.execute(new JsonObject());
            fail("Expected IllegalStateException when no active debug session is present");
        } catch (IllegalStateException e) {
            assertTrue(
                "Expected 'No active debug session' in exception message, got: " + e.getMessage(),
                e.getMessage().contains("No active debug session"));
        }
    }

    /**
     * Verifies that providing a custom {@code max_chars} limit does not prevent
     * the session guard from triggering.
     */
    public void testDebugReadConsoleWithMaxCharsNoSessionThrowsIllegalStateException() throws Exception {
        JsonObject a = new JsonObject();
        a.addProperty("max_chars", 1000);
        try {
            debugReadConsoleTool.execute(a);
            fail("Expected IllegalStateException when no active debug session is present");
        } catch (IllegalStateException e) {
            assertTrue(
                "Expected 'No active debug session' in exception message, got: " + e.getMessage(),
                e.getMessage().contains("No active debug session"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // ── DebugStepTool (requires paused session) ───────────────────────────────────
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that {@code action: "over"} throws {@link IllegalStateException}
     * (no session) rather than hanging on the completion latch.
     */
    public void testDebugStepOverNoSessionThrowsIllegalStateException() throws Exception {
        try {
            debugStepTool.execute(args("action", "over"));
            fail("Expected IllegalStateException when no active debug session is present");
        } catch (IllegalStateException e) {
            assertTrue(
                "Expected 'No active debug session' in exception message, got: " + e.getMessage(),
                e.getMessage().contains("No active debug session"));
        }
    }

    /**
     * Verifies that {@code action: "into"} also triggers the session guard.
     */
    public void testDebugStepIntoNoSessionThrowsIllegalStateException() throws Exception {
        try {
            debugStepTool.execute(args("action", "into"));
            fail("Expected IllegalStateException when no active debug session is present");
        } catch (IllegalStateException e) {
            assertTrue(
                "Expected 'No active debug session' in exception message, got: " + e.getMessage(),
                e.getMessage().contains("No active debug session"));
        }
    }

    /**
     * Verifies that {@code action: "out"} also triggers the session guard.
     */
    public void testDebugStepOutNoSessionThrowsIllegalStateException() throws Exception {
        try {
            debugStepTool.execute(args("action", "out"));
            fail("Expected IllegalStateException when no active debug session is present");
        } catch (IllegalStateException e) {
            assertTrue(
                "Expected 'No active debug session' in exception message, got: " + e.getMessage(),
                e.getMessage().contains("No active debug session"));
        }
    }

    /**
     * Verifies that {@code action: "continue"} also triggers the session guard.
     */
    public void testDebugStepContinueNoSessionThrowsIllegalStateException() throws Exception {
        try {
            debugStepTool.execute(args("action", "continue"));
            fail("Expected IllegalStateException when no active debug session is present");
        } catch (IllegalStateException e) {
            assertTrue(
                "Expected 'No active debug session' in exception message, got: " + e.getMessage(),
                e.getMessage().contains("No active debug session"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // ── DebugRunToLineTool (requires paused session) ──────────────────────────────
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that {@link DebugRunToLineTool#execute} throws
     * {@link IllegalStateException} with the "No active debug session" message
     * even before the file-path argument is resolved.
     */
    public void testDebugRunToLineNoSessionThrowsIllegalStateException() throws Exception {
        try {
            debugRunToLineTool.execute(args(
                "file", "/nonexistent/File.java",
                "line", "10"
            ));
            fail("Expected IllegalStateException when no active debug session is present");
        } catch (IllegalStateException e) {
            assertTrue(
                "Expected 'No active debug session' in exception message, got: " + e.getMessage(),
                e.getMessage().contains("No active debug session"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // ── DebugToolFactory ──────────────────────────────────────────────────────────
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that {@link DebugToolFactory#create(com.intellij.openapi.project.Project)}
     * produces exactly 14 tool instances, matching the count documented in the factory.
     */
    public void testDebugToolFactoryCreates14Tools() {
        List<?> tools = DebugToolFactory.create(getProject());
        assertNotNull("Tool list must not be null", tools);
        assertEquals("DebugToolFactory must create exactly 14 tools", 14, tools.size());
    }

    /**
     * Verifies that none of the 14 tools produced by the factory are {@code null}.
     */
    public void testDebugToolFactoryToolsAreAllNonNull() {
        List<?> tools = DebugToolFactory.create(getProject());
        for (int i = 0; i < tools.size(); i++) {
            assertNotNull("Tool at index " + i + " must not be null", tools.get(i));
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // ── Tool metadata (id, displayName, category) ────────────────────────────────
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that every debug tool exposes a non-null, non-blank {@code id()}.
     */
    public void testAllDebugToolsHaveNonBlankId() {
        assertNonBlankId(breakpointListTool.id(), "BreakpointListTool");
        assertNonBlankId(breakpointAddTool.id(), "BreakpointAddTool");
        assertNonBlankId(breakpointAddExceptionTool.id(), "BreakpointAddExceptionTool");
        assertNonBlankId(breakpointRemoveTool.id(), "BreakpointRemoveTool");
        assertNonBlankId(breakpointUpdateTool.id(), "BreakpointUpdateTool");
        assertNonBlankId(debugSessionListTool.id(), "DebugSessionListTool");
        assertNonBlankId(debugSessionStopTool.id(), "DebugSessionStopTool");
        assertNonBlankId(debugSnapshotTool.id(), "DebugSnapshotTool");
        assertNonBlankId(debugVariableDetailTool.id(), "DebugVariableDetailTool");
        assertNonBlankId(debugInspectFrameTool.id(), "DebugInspectFrameTool");
        assertNonBlankId(debugEvaluateTool.id(), "DebugEvaluateTool");
        assertNonBlankId(debugReadConsoleTool.id(), "DebugReadConsoleTool");
        assertNonBlankId(debugRunToLineTool.id(), "DebugRunToLineTool");
        assertNonBlankId(debugStepTool.id(), "DebugStepTool");
    }

    /**
     * Verifies that every debug tool's {@code id()} is unique within the set of
     * all 14 debug tools (no duplicate IDs that would cause MCP routing collisions).
     */
    public void testAllDebugToolIdsAreUnique() {
        List<?> tools = DebugToolFactory.create(getProject());
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Object t : tools) {
            if (t instanceof com.github.catatafishen.agentbridge.psi.tools.Tool tool) {
                String id = tool.id();
                assertTrue("Duplicate tool id '" + id + "' detected", ids.add(id));
            }
        }
    }

    /**
     * Verifies that every debug tool reports {@link ToolRegistry.Category#DEBUG}
     * as its category, so the tool registry routes them correctly.
     */
    public void testAllDebugToolsReportDebugCategory() {
        List<?> tools = DebugToolFactory.create(getProject());
        for (Object t : tools) {
            if (t instanceof com.github.catatafishen.agentbridge.psi.tools.Tool tool) {
                assertEquals(
                    "Tool '" + tool.id() + "' must report DEBUG category",
                    ToolRegistry.Category.DEBUG,
                    tool.category());
            }
        }
    }

    /**
     * Verifies the exact tool IDs that the MCP client depends on at runtime.
     */
    public void testCanonicalToolIds() {
        assertEquals("breakpoint_list", breakpointListTool.id());
        assertEquals("breakpoint_add", breakpointAddTool.id());
        assertEquals("breakpoint_add_exception", breakpointAddExceptionTool.id());
        assertEquals("breakpoint_remove", breakpointRemoveTool.id());
        assertEquals("breakpoint_update", breakpointUpdateTool.id());
        assertEquals("debug_session_list", debugSessionListTool.id());
        assertEquals("debug_session_stop", debugSessionStopTool.id());
        assertEquals("debug_snapshot", debugSnapshotTool.id());
        assertEquals("debug_variable_detail", debugVariableDetailTool.id());
        assertEquals("debug_inspect_frame", debugInspectFrameTool.id());
        assertEquals("debug_evaluate", debugEvaluateTool.id());
        assertEquals("debug_read_console", debugReadConsoleTool.id());
        assertEquals("debug_run_to_line", debugRunToLineTool.id());
        assertEquals("debug_step", debugStepTool.id());
    }

    /**
     * Verifies that the read-only / write classification of every tool matches the
     * documented contract from the source code:
     * <ul>
     *   <li>Read-only: {@code breakpoint_list}, {@code debug_session_list},
     *       {@code debug_snapshot}, {@code debug_variable_detail},
     *       {@code debug_inspect_frame}, {@code debug_read_console}.</li>
     *   <li>Write (or side-effecting): all other tools.</li>
     * </ul>
     */
    public void testReadOnlyFlagsMatchDocumentedContract() {
        assertTrue("breakpoint_list must be read-only", breakpointListTool.isReadOnly());
        assertFalse("breakpoint_add must NOT be read-only", breakpointAddTool.isReadOnly());
        assertFalse("breakpoint_add_exception must NOT be read-only", breakpointAddExceptionTool.isReadOnly());
        assertFalse("breakpoint_remove must NOT be read-only", breakpointRemoveTool.isReadOnly());
        assertFalse("breakpoint_update must NOT be read-only", breakpointUpdateTool.isReadOnly());
        assertTrue("debug_session_list must be read-only", debugSessionListTool.isReadOnly());
        assertFalse("debug_session_stop must NOT be read-only", debugSessionStopTool.isReadOnly());
        assertTrue("debug_snapshot must be read-only", debugSnapshotTool.isReadOnly());
        assertTrue("debug_variable_detail must be read-only", debugVariableDetailTool.isReadOnly());
        assertTrue("debug_inspect_frame must be read-only", debugInspectFrameTool.isReadOnly());
        assertFalse("debug_evaluate must NOT be read-only", debugEvaluateTool.isReadOnly());
        assertTrue("debug_read_console must be read-only", debugReadConsoleTool.isReadOnly());
        assertFalse("debug_run_to_line must NOT be read-only", debugRunToLineTool.isReadOnly());
        assertFalse("debug_step must NOT be read-only", debugStepTool.isReadOnly());
    }

    // ── Private helpers ──────────────────────────────────────────────────────────────

    /**
     * Removes breakpoints added during a test to prevent state leaking into subsequent
     * test methods. Note: the Java plugin's default "Java Exception Breakpoints" entry
     * is registered asynchronously and may reappear between calls; tests that need a
     * truly zero-breakpoint state must call {@link BreakpointRemoveTool} with
     * {@code remove_all: true} directly (as the tool's write action runs synchronously).
     */
    private void clearAllBreakpoints() {
        ApplicationManager.getApplication().runWriteAction(() -> {
            var mgr = XDebuggerManager.getInstance(getProject()).getBreakpointManager();
            for (XBreakpoint<?> bp : mgr.getAllBreakpoints()) {
                mgr.removeBreakpoint(bp);
            }
        });
    }

    private static void assertNonBlankId(String id, String toolName) {
        assertNotNull(toolName + ".id() must not be null", id);
        assertFalse(toolName + ".id() must not be blank, got: '" + id + "'", id.isBlank());
    }
}
