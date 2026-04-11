package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Platform tests for {@link SearchTextTool}.
 *
 * <p>JUnit 3 style (extends BasePlatformTestCase): test methods must be
 * {@code public void testXxx()}.  Run via Gradle only:
 * {@code ./gradlew :plugin-core:test}.
 *
 * <p>{@link SearchTextTool#execute} runs entirely inside
 * {@code ApplicationManager.getApplication().runReadAction()} so it is
 * synchronous when called from the EDT test thread — no extra threading is
 * needed.
 *
 * <p>Files added via {@code myFixture.addFileToProject()} land in the
 * project's in-memory VFS, are indexed by {@code ProjectFileIndex}, and are
 * therefore found by the tool's {@code iterateContent} pass.
 */
public class SearchTextToolTest extends BasePlatformTestCase {

    private SearchTextTool tool;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Disable follow-agent UI features (status-bar feedback, UsageView) that
        // would be no-ops or noisy in a headless test environment.
        PropertiesComponent.getInstance(getProject())
            .setValue(ToolLayerSettings.FOLLOW_AGENT_FILES_KEY, "false");
        tool = new SearchTextTool(getProject());
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds a {@link JsonObject} from alternating String key/value pairs.
     * For integer or boolean parameters use {@link JsonObject#addProperty}
     * directly on the returned object.
     */
    private static JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Search for a unique token that exists in one project file.
     * Expects the match count prefix and the file name to appear in the result.
     */
    public void testBasicTextSearch() {
        myFixture.addFileToProject("Alpha.java",
            """
                public class Alpha {
                    // UNIQUE_SEARCH_TOKEN_ALPHA_7261
                    private String value = "hello";
                }
                """);

        String result = tool.execute(args("query", "UNIQUE_SEARCH_TOKEN_ALPHA_7261"));

        assertTrue("Expected match-count prefix, got: " + result,
            result.contains("matches:"));
        assertTrue("Expected file name in result, got: " + result,
            result.contains("Alpha.java"));
        assertTrue("Expected matching token in result, got: " + result,
            result.contains("UNIQUE_SEARCH_TOKEN_ALPHA_7261"));
    }

    /**
     * Searching for a string that does not appear in any project file must
     * return the "No matches found" message.
     */
    public void testSearchWithNoResults() {
        myFixture.addFileToProject("Beta.java",
            "public class Beta { /* nothing interesting here */ }\n");

        String result = tool.execute(args("query", "ZZZNOMATCHSTRINGZZZ_BETA_9999"));

        assertTrue("Expected no-match message, got: " + result,
            result.contains("No matches found"));
    }

    /**
     * The {@code file_pattern} parameter restricts which files are searched.
     * Adding the target token to both a {@code .java} and a {@code .txt} file
     * and filtering to {@code *.txt} should yield a match only in the text file.
     */
    public void testSearchWithFilePattern() {
        myFixture.addFileToProject("SearchMe.java",
            "// FILEPATTERN_TOKEN_5512 java file\n");
        myFixture.addFileToProject("SearchMe.txt",
            "FILEPATTERN_TOKEN_5512 text file\n");

        JsonObject a = args("query", "FILEPATTERN_TOKEN_5512", "file_pattern", "*.txt");
        String result = tool.execute(a);

        assertTrue("Expected match in .txt file, got: " + result,
            result.contains("SearchMe.txt"));
        assertFalse("Should not match .java file when pattern is *.txt, got: " + result,
            result.contains("SearchMe.java"));
    }

    /**
     * When {@code context_lines} is set to 1, the line immediately before and
     * after each match must appear in the result alongside the matching line.
     */
    public void testSearchWithContextLines() {
        myFixture.addFileToProject("ContextTest.java",
            """
                // line one
                // line two
                // TARGET_CONTEXT_LINE_8843
                // line four
                // line five
                """);

        JsonObject a = args("query", "TARGET_CONTEXT_LINE_8843");
        a.addProperty("context_lines", 1);
        String result = tool.execute(a);

        assertTrue("Expected matching token in result, got: " + result,
            result.contains("TARGET_CONTEXT_LINE_8843"));
        // Context line immediately before the match
        assertTrue("Expected 'line two' as context before match, got: " + result,
            result.contains("line two"));
        // Context line immediately after the match
        assertTrue("Expected 'line four' as context after match, got: " + result,
            result.contains("line four"));
        // The line outside the context window must not appear
        assertFalse("'line one' is outside the 1-line context window, got: " + result,
            result.contains("line one"));
    }

    /**
     * With {@code case_sensitive} defaulting to {@code true}, an all-uppercase
     * query must not match a mixed-case string.  Setting {@code case_sensitive}
     * to {@code false} must then find it.
     */
    public void testCaseInsensitiveSearch() {
        myFixture.addFileToProject("CaseTest.java",
            """
                public class CaseTest {
                    String value = "MixedCaseToken3391";
                }
                """);

        // Case-sensitive (default) — should NOT match
        String sensitiveResult = tool.execute(args("query", "MIXEDCASETOKEN3391"));
        assertTrue("Case-sensitive search should not match, got: " + sensitiveResult,
            sensitiveResult.contains("No matches found"));

        // Case-insensitive — MUST match
        JsonObject a = args("query", "MIXEDCASETOKEN3391");
        a.addProperty("case_sensitive", false);
        String insensitiveResult = tool.execute(a);

        assertTrue("Expected match with case_sensitive=false, got: " + insensitiveResult,
            insensitiveResult.contains("matches:"));
        assertTrue("Expected CaseTest.java in case-insensitive result, got: " + insensitiveResult,
            insensitiveResult.contains("CaseTest.java"));
    }

    /**
     * With {@code regex=true} the query is compiled as a regular expression.
     * The pattern {@code foo\d+} should match "foo123" but not "foobar".
     */
    public void testSearchRegexMode() {
        myFixture.addFileToProject("RegexTest.java",
            """
                int foo123 = 42;
                int bar456 = 99;
                String foobar = "abc";
                """);

        JsonObject a = args("query", "foo\\d+");
        a.addProperty("regex", true);
        String result = tool.execute(a);

        assertTrue("Expected regex match for foo\\d+, got: " + result,
            result.contains("matches:"));
        assertTrue("Expected RegexTest.java in result, got: " + result,
            result.contains("RegexTest.java"));
        assertTrue("Expected line containing 'foo123' in result, got: " + result,
            result.contains("foo123"));
    }

    /**
     * An invalid regex pattern (with {@code regex=true}) must return an error
     * message that starts with "Error:".
     */
    public void testInvalidRegexReturnsError() {
        JsonObject a = args("query", "[invalid(regex");
        a.addProperty("regex", true);
        String result = tool.execute(a);

        assertTrue("Expected error for invalid regex, got: " + result,
            result.startsWith("Error:"));
    }

    /**
     * The {@code max_results} parameter must cap the number of returned matches.
     * With 20 identical token lines and {@code max_results=5}, the result prefix
     * must say "5 matches:".
     */
    public void testMaxResultsLimit() {
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            content.append("// REPEATED_TOKEN_MAX_4471 line ").append(i).append("\n");
        }
        myFixture.addFileToProject("MaxResults.java", content.toString());

        JsonObject a = args("query", "REPEATED_TOKEN_MAX_4471");
        a.addProperty("max_results", 5);
        String result = tool.execute(a);

        assertTrue("Expected match result, got: " + result,
            result.contains("matches:"));
        assertTrue("Expected exactly 5 matches reported, got: " + result,
            result.startsWith("5 matches:"));
    }

    /**
     * Results from multiple files must all be collected.  Two files each
     * containing the search token should yield exactly 2 matches, one per file.
     */
    public void testSearchMultipleMatchesAcrossFiles() {
        myFixture.addFileToProject("Multi1.java",
            "// CROSS_FILE_TOKEN_6637 first occurrence\n");
        myFixture.addFileToProject("Multi2.java",
            "// CROSS_FILE_TOKEN_6637 second occurrence\n");

        String result = tool.execute(args("query", "CROSS_FILE_TOKEN_6637"));

        assertTrue("Expected 2 matches, got: " + result,
            result.startsWith("2 matches:"));
        assertTrue("Expected Multi1.java in result, got: " + result,
            result.contains("Multi1.java"));
        assertTrue("Expected Multi2.java in result, got: " + result,
            result.contains("Multi2.java"));
    }

    /**
     * When the {@code query} parameter is omitted entirely, the tool must return
     * the standard error message referencing "query".
     */
    public void testMissingQueryReturnsError() {
        String result = tool.execute(new JsonObject());

        assertTrue("Expected error message for missing query, got: " + result,
            result.startsWith("Error:"));
        assertTrue("Expected 'query' mentioned in error, got: " + result,
            result.contains("query"));
    }

    /**
     * Each result entry follows the format {@code <relpath>:<line>: <linetext>}.
     * Verifies that the output contains a colon-separated file and line number.
     */
    public void testResultFormatContainsLineReference() {
        myFixture.addFileToProject("FormatCheck.java",
            "// LINE_FORMAT_TOKEN_2219 check\n");

        String result = tool.execute(args("query", "LINE_FORMAT_TOKEN_2219"));

        assertTrue("Expected file-name:line: format in result, got: " + result,
            result.matches("(?s).*FormatCheck\\.java:\\d+:.*"));
    }
}
