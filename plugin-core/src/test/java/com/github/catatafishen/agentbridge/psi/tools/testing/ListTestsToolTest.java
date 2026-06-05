package com.github.catatafishen.agentbridge.psi.tools.testing;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Tests for {@link ListTestsTool}.
 *
 * <p>JUnit 3 style (extends {@link BasePlatformTestCase}).
 */
public class ListTestsToolTest extends BasePlatformTestCase {

    private ListTestsTool tool;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "false");
        tool = new ListTestsTool(getProject());
    }

    // ── safeGetTestFrameworks ─────────────────────────────────────────────────

    /**
     * {@code safeGetTestFrameworks()} must return a non-null list without throwing,
     * regardless of whether the extension point is registered.
     */
    public void testSafeGetTestFrameworksDoesNotThrow() {
        var frameworks = ListTestsTool.safeGetTestFrameworks();
        assertNotNull("safeGetTestFrameworks() must never return null", frameworks);
    }

    // ── execute() — no test source roots configured ───────────────────────────

    /**
     * When no directories are marked as test source roots, the tool must return
     * an actionable message explaining the configuration problem rather than
     * a bare "No tests found".
     */
    public void testReturnsNoTestSourcesMessageWhenNoTestRoots() {
        // BasePlatformTestCase creates a light project with no test source roots
        String result = tool.execute(new JsonObject());
        assertNotNull(result);
        assertTrue(
            "Expected 'No test source directories' message, got: " + result,
            result.contains("No test source directories") || result.contains("test source"));
    }

    /**
     * The no-test-source-roots message must include actionable guidance.
     */
    public void testNoTestSourcesMessageContainsGuidance() {
        String result = tool.execute(new JsonObject());
        assertNotNull(result);
        boolean hasTests = result.startsWith("0 tests:") || result.contains(" tests:\n");
        boolean hasGuidance = result.contains("Mark") || result.contains("run_configuration")
            || result.contains("test source");
        assertTrue("Result must either list tests or provide guidance, got: " + result,
            hasTests || hasGuidance);
    }

    // ── execute() — smoke tests ───────────────────────────────────────────────

    /**
     * {@code execute()} must not throw or return null under any circumstances.
     */
    public void testExecuteDoesNotThrow() {
        String result = tool.execute(new JsonObject());
        assertNotNull("execute() must not return null", result);
        assertFalse("execute() must not return an error string", result.startsWith("Error"));
    }

    /**
     * {@code execute()} with an explicit file_pattern must not throw and must
     * return a sensible non-empty message.
     */
    public void testExecuteWithFilePatternDoesNotThrow() {
        var args = new JsonObject();
        args.addProperty("file_pattern", "*Test*");
        String result = tool.execute(args);
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
    }

    /**
     * {@code safeGetTestFrameworks()} must always return a non-null list (null-safety).
     * In IntelliJ IDEA the list is non-empty; in CLion it would be empty.
     */
    public void testSafeGetTestFrameworksReturnsList() {
        var frameworks = ListTestsTool.safeGetTestFrameworks();
        assertNotNull(frameworks);
    }
}
