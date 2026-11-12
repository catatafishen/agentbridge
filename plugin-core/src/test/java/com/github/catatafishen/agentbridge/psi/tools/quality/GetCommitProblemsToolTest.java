package com.github.catatafishen.agentbridge.psi.tools.quality;

import com.google.gson.JsonObject;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Tests for {@link GetCommitProblemsTool}. The light fixture has no VCS changes, so these tests
 * cover the schema, argument validation and the "nothing to analyze" path. The response formatting is
 * covered by {@link CommitProblemsFormatterTest}.
 */
public class GetCommitProblemsToolTest extends BasePlatformTestCase {

    private GetCommitProblemsTool tool;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        tool = new GetCommitProblemsTool(getProject());
    }

    public void testIdAndReadOnlyContract() {
        assertEquals("get_commit_problems", tool.id());
        assertTrue(tool.isReadOnly());
        assertTrue(tool.requiresIndex());
    }

    public void testSchemaDeclaresPaginationFilterAndFixParameters() {
        JsonObject properties = tool.inputSchema().getAsJsonObject("properties");
        for (String name : new String[]{"max_results", "offset", "errors_only", "summary_only", "include_fixes"}) {
            assertTrue("Missing parameter: " + name, properties.has(name));
        }
        assertEquals("integer", properties.getAsJsonObject("max_results").get("type").getAsString());
        assertEquals("boolean", properties.getAsJsonObject("include_fixes").get("type").getAsString());
    }

    public void testSchemaHasNoRequiredParameters() {
        JsonObject schema = tool.inputSchema();
        assertEquals("object", schema.get("type").getAsString());
        assertFalse(schema.has("required") && !schema.getAsJsonArray("required").isEmpty());
    }

    public void testDescriptionMentionsHowToGetFixesAndPagination() {
        String description = tool.description();
        assertTrue(description, description.contains("include_fixes"));
        assertTrue(description, description.contains("offset"));
        assertTrue(description, description.contains("summary_only"));
    }

    public void testNoChangesReportsNothingToAnalyze() throws Exception {
        String result = tool.execute(new JsonObject());
        assertTrue(result, result.startsWith("No changed files to analyze"));
    }

    public void testNothingToAnalyzeIsReportedEvenWithAllOptionsSet() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("errors_only", true);
        args.addProperty("summary_only", true);
        args.addProperty("include_fixes", true);
        args.addProperty("max_results", 5);
        args.addProperty("offset", 2);
        String result = tool.execute(args);
        assertTrue(result, result.startsWith("No changed files to analyze"));
    }

    public void testRejectsNonPositiveMaxResults() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("max_results", 0);
        String result = tool.execute(args);
        assertTrue(result, result.startsWith("Error: "));
    }

    public void testRejectsNegativeOffset() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("offset", -1);
        String result = tool.execute(args);
        assertTrue(result, result.startsWith("Error: "));
    }
}
