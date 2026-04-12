package com.github.catatafishen.agentbridge.psi.tools.project;

import com.google.gson.JsonObject;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Platform tests for {@link EditProjectStructureTool}.
 *
 * <p>JUnit 3 style (extends {@link BasePlatformTestCase}): test methods must be
 * {@code public void testXxx()}. Run via Gradle only:
 * {@code ./gradlew :plugin-core:test}.
 *
 * <p>Only read-only actions ({@code list_modules}, {@code list_dependencies},
 * {@code list_sdks}) are exercised here. Those delegate to
 * {@code ApplicationManager.getApplication().runReadAction()} which is safe to
 * call directly from the EDT (the test thread). Mutation actions that use
 * {@code EdtUtil.invokeLater} + {@code CompletableFuture.get()} are covered only
 * through error-path cases that return before any EDT dispatch (missing required
 * parameters).
 */
public class EditProjectStructureToolTest extends BasePlatformTestCase {

    private EditProjectStructureTool tool;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        tool = new EditProjectStructureTool(getProject());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds a {@link JsonObject} from alternating key/value String pairs.
     * Example: {@code args("action", "list_modules")}.
     */
    private static JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }

    // ── action=list_modules ───────────────────────────────────────────────────

    /**
     * {@code list_modules} must return a non-null, non-blank response.
     * {@link BasePlatformTestCase} always provides at least one light test module.
     */
    public void testListModulesReturnsNonEmpty() throws Exception {
        String result = tool.execute(args("action", "list_modules"));
        assertNotNull("Result must not be null", result);
        assertFalse("Result must not be blank", result.isBlank());
        assertFalse("Result must not start with 'Error:'", result.startsWith("Error:"));
    }

    /**
     * The response header for {@code list_modules} must contain the "Modules (N):"
     * count prefix, confirming the tool found at least one module.
     */
    public void testListModulesContainsModuleName() throws Exception {
        String result = tool.execute(args("action", "list_modules"));
        assertNotNull(result);
        assertTrue(
            "Expected 'Modules (' count header in list_modules output, got: " + result,
            result.contains("Modules (") || result.contains("No modules found"));
    }

    /**
     * Each module section must include a "Libraries:" dependency summary line,
     * confirming that {@code appendModuleDependencySummary} ran for each module.
     */
    public void testListModulesContainsDependenciesSection() throws Exception {
        String result = tool.execute(args("action", "list_modules"));
        assertNotNull(result);
        // The per-module block always appends "Libraries: N" and "Module dependencies: N"
        assertTrue(
            "Expected 'Libraries:' per-module detail in list_modules output, got: " + result,
            result.contains("Libraries:") || result.contains("No modules found"));
    }

    // ── action=list_dependencies ──────────────────────────────────────────────

    /**
     * Calling {@code list_dependencies} without a {@code module} parameter must
     * return a descriptive error rather than throwing.
     */
    public void testListDependenciesNoModuleFilter() throws Exception {
        String result = tool.execute(args("action", "list_dependencies"));
        assertNotNull("Result must not be null", result);
        assertTrue(
            "Expected Error: when 'module' param is absent for list_dependencies, got: " + result,
            result.startsWith("Error:"));
        assertTrue(
            "Expected the error to mention 'module' param, got: " + result,
            result.contains("module"));
    }

    /**
     * Requesting dependencies for a module name that does not exist must return a
     * descriptive "not found" error rather than throwing or returning blank output.
     */
    public void testListDependenciesNonExistentModule() throws Exception {
        String result = tool.execute(args("action", "list_dependencies", "module", "nonexistent_xyz_module_abc"));
        assertNotNull("Result must not be null", result);
        assertTrue(
            "Expected Error: for non-existent module in list_dependencies, got: " + result,
            result.startsWith("Error:"));
        assertTrue(
            "Expected the module name in the error message, got: " + result,
            result.contains("nonexistent_xyz_module_abc") || result.contains("not found"));
    }

    // ── action=list_sdks ──────────────────────────────────────────────────────

    /**
     * {@code list_sdks} must return a non-blank response that starts with the
     * "Project SDK:" header line, confirming the SDK table was read successfully.
     */
    public void testListSdksReturnsNonEmpty() throws Exception {
        String result = tool.execute(args("action", "list_sdks"));
        assertNotNull("Result must not be null", result);
        assertFalse("Result must not be blank", result.isBlank());
        assertTrue(
            "Expected 'Project SDK:' header in list_sdks output, got: " + result,
            result.startsWith("Project SDK:"));
    }

    /**
     * The response must include the configured-SDKs count line, confirming
     * the inner loop over {@code ProjectJdkTable.getAllJdks()} ran to completion.
     */
    public void testListSdksContainsConfiguredSdksHeader() throws Exception {
        String result = tool.execute(args("action", "list_sdks"));
        assertNotNull(result);
        assertTrue(
            "Expected 'Configured SDKs (' in list_sdks output, got: " + result,
            result.contains("Configured SDKs ("));
    }

    // ── missing / invalid action ──────────────────────────────────────────────

    /**
     * Submitting an empty {@link JsonObject} (no {@code action} key) must return
     * the standard unknown-action error message rather than throwing.
     */
    public void testMissingActionReturnsError() throws Exception {
        String result = tool.execute(new JsonObject());
        assertNotNull("Result must not be null", result);
        assertTrue(
            "Expected Error: for missing action, got: " + result,
            result.startsWith("Error:"));
        assertTrue(
            "Expected 'Unknown action' in error for missing action, got: " + result,
            result.contains("Unknown action"));
    }

    /**
     * An unrecognised {@code action} value must return the standard
     * "Error: Unknown action '...' ..." message rather than throwing.
     */
    public void testInvalidActionReturnsError() throws Exception {
        String result = tool.execute(args("action", "totally_invalid_action_xyz"));
        assertNotNull("Result must not be null", result);
        assertTrue(
            "Expected Error: for invalid action, got: " + result,
            result.startsWith("Error:"));
        assertTrue(
            "Expected 'Unknown action' in error for invalid action, got: " + result,
            result.contains("Unknown action") || result.contains("totally_invalid_action_xyz"));
    }

    /**
     * Error-path of {@code add_dependency}: missing required {@code module} param.
     * Returns early before any EDT dispatch, so it is safe to call directly.
     */
    public void testAddDependencyMissingModuleReturnsError() throws Exception {
        String result = tool.execute(args("action", "add_dependency"));
        assertNotNull(result);
        assertTrue(
            "Expected Error: for add_dependency without module, got: " + result,
            result.startsWith("Error:"));
        assertTrue(
            "Expected 'module' mentioned in the error, got: " + result,
            result.contains("module"));
    }

    /**
     * Error-path of {@code remove_dependency}: missing required {@code module} param.
     * Returns early before any EDT dispatch, so it is safe to call directly.
     */
    public void testRemoveDependencyMissingModuleReturnsError() throws Exception {
        String result = tool.execute(args("action", "remove_dependency"));
        assertNotNull(result);
        assertTrue(
            "Expected Error: for remove_dependency without module, got: " + result,
            result.startsWith("Error:"));
    }

    /**
     * Error-path of {@code add_sdk}: missing required {@code sdk_type} param.
     * Returns early before any EDT dispatch, so it is safe to call directly.
     */
    public void testAddSdkMissingSdkTypeReturnsError() throws Exception {
        String result = tool.execute(args("action", "add_sdk"));
        assertNotNull(result);
        assertTrue(
            "Expected Error: for add_sdk without sdk_type, got: " + result,
            result.startsWith("Error:"));
        assertTrue(
            "Expected 'sdk_type' mentioned in the error, got: " + result,
            result.contains("sdk_type"));
    }
}
