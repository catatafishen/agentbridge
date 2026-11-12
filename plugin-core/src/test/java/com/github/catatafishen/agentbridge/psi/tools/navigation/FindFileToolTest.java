package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.google.gson.JsonObject;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class FindFileToolTest extends BasePlatformTestCase {

    private static final String QUERY = "query";
    private static final String LIMIT = "max_results";
    private static final String SCOPE = "scope";
    private FindFileTool tool;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        tool = new FindFileTool(getProject());
    }

    public void testFindBySubstring() {
        myFixture.addFileToProject("src/find/UserServiceUnique.java", "class UserServiceUnique {}");
        myFixture.addFileToProject("src/find/OtherUnique.txt", "other");

        String result = tool.execute(args(QUERY, "ServiceUnique"));

        assertTrue("Expected matching Java file, got: " + result,
            result.contains("UserServiceUnique.java"));
        assertFalse("Unexpected unrelated file, got: " + result,
            result.contains("OtherUnique.txt"));
    }

    public void testFindByCamelCase() {
        myFixture.addFileToProject("src/find/CamelCaseTargetUnique.java", "class CamelCaseTargetUnique {}");

        String result = tool.execute(args(QUERY, "CCTU"));

        assertTrue("Expected camel-case match, got: " + result,
            result.contains("CamelCaseTargetUnique.java"));
    }

    public void testFindByWildcardName() {
        myFixture.addFileToProject("src/find-wildcard/WildNameUnique.java", "class WildNameUnique {}");
        myFixture.addFileToProject("test/find-wildcard/WildNameUniqueTest.kt", "class WildNameUniqueTest");

        String result = tool.execute(args(QUERY, "WildName*.java"));

        assertTrue("Expected wildcard match, got: " + result,
            result.contains("WildNameUnique.java"));
        assertFalse("Wildcard should exclude Kotlin test, got: " + result,
            result.contains("WildNameUniqueTest.kt"));
    }

    public void testLimitClampsResultCount() {
        myFixture.addFileToProject("src/find-limit/LimitAlphaUnique.java", "class LimitAlphaUnique {}");
        myFixture.addFileToProject("src/find-limit/LimitBetaUnique.java", "class LimitBetaUnique {}");

        JsonObject params = args(QUERY, "Limit");
        params.addProperty(LIMIT, 1);
        String result = tool.execute(params);

        assertTrue("Expected single-result header, got: " + result,
            result.startsWith("1 files:"));
    }

    public void testMissingQueryReturnsError() {
        String result = tool.execute(new JsonObject());

        assertTrue("Expected error prefix, got: " + result,
            result.startsWith(ToolUtils.ERROR_PREFIX));
        assertTrue("Expected missing query message, got: " + result,
            result.contains("'query' parameter is required"));
    }

    public void testNullQueryReturnsError() {
        JsonObject params = new JsonObject();
        params.add(QUERY, com.google.gson.JsonNull.INSTANCE);
        String result = tool.execute(params);

        assertTrue("Expected error prefix, got: " + result,
            result.startsWith(ToolUtils.ERROR_PREFIX));
    }

    public void testBlankQueryReturnsError() {
        String result = tool.execute(args(QUERY, "   "));

        assertTrue("Expected error prefix, got: " + result,
            result.startsWith(ToolUtils.ERROR_PREFIX));
        assertTrue("Expected blank query message, got: " + result,
            result.contains("'query' parameter is required"));
    }

    public void testNoMatchesReturnsMessage() {
        String result = tool.execute(args(QUERY, "ZzzNonexistentFileXyz123"));

        assertTrue(result, result.startsWith("No files found"));
    }

    public void testResultTagsCategory() {
        myFixture.addFileToProject("CategoryTagUnique.txt", "x");
        String result = tool.execute(args(QUERY, "CategoryTagUnique.txt"));
        assertTrue("Expected a category tag, got: " + result,
            result.contains("CategoryTagUnique.txt [CategoryTagUnique.txt, "));
    }

    public void testExactNameRanksFirst() {
        myFixture.addFileToProject("src/rank/ExactNameUnique.java", "class ExactNameUnique {}");
        myFixture.addFileToProject("src/rank/ExactNameUniqueExtended.java", "class ExactNameUniqueExtended {}");

        String result = tool.execute(args(QUERY, "ExactNameUnique.java"));

        int firstIdx = result.indexOf("ExactNameUnique.java");
        int extendedIdx = result.indexOf("ExactNameUniqueExtended.java");
        assertTrue("Should find exact match: " + result, firstIdx >= 0);
        if (extendedIdx >= 0) {
            assertTrue("Exact match should rank first, got: " + result, firstIdx < extendedIdx);
        }
    }

    public void testCaseInsensitiveExactMatch() {
        myFixture.addFileToProject("src/case/MixedCaseUnique.java", "class MixedCaseUnique {}");

        String result = tool.execute(args(QUERY, "mixedcaseunique.java"));

        assertTrue("Should case-insensitively match: " + result,
            result.contains("MixedCaseUnique.java"));
    }

    public void testPathPatternBranchHandled() {
        // Query containing '/' triggers the pathPattern code path; we just
        // verify it does not throw and returns a stable string.
        String result = tool.execute(args(QUERY, "no-such/path/file.java"));
        assertNotNull(result);
    }

    public void testQuestionMarkWildcard() {
        myFixture.addFileToProject("src/find-q/QmarkAUnique.java", "class QmarkAUnique {}");

        String result = tool.execute(args(QUERY, "QmarkAUniqu?.java"));

        assertTrue("Should match single-char wildcard: " + result,
            result.contains("QmarkAUnique.java"));
    }

    public void testProjectScopeExplicit() {
        myFixture.addFileToProject("src/scope/ScopeProjUnique.java", "class ScopeProjUnique {}");

        JsonObject params = args(QUERY, "ScopeProjUnique");
        params.addProperty(SCOPE, "sources,other");
        String result = tool.execute(params);

        assertTrue("Should find file in project scope: " + result,
            result.contains("ScopeProjUnique.java"));
    }

    public void testAllScopeFindsProjectFiles() {
        myFixture.addFileToProject("src/scope/ScopeAllUnique.java", "class ScopeAllUnique {}");

        JsonObject params = args(QUERY, "ScopeAllUnique");
        params.addProperty(SCOPE, "all");
        String result = tool.execute(params);

        assertTrue("Should find file in all scope: " + result,
            result.contains("ScopeAllUnique.java"));
    }

    public void testLibrariesScopeReturnsNoneForProjectFile() {
        myFixture.addFileToProject("src/scope/ScopeLibUnique.java", "class ScopeLibUnique {}");

        JsonObject params = args(QUERY, "ScopeLibUnique");
        params.addProperty(SCOPE, "libraries");
        String result = tool.execute(params);

        assertTrue(result, result.startsWith("No files found"));
    }

    public void testUnknownScopeIsAnError() {
        JsonObject params = args(QUERY, "Anything");
        params.addProperty(SCOPE, "totally-bogus-scope");
        String result = tool.execute(params);

        assertTrue("Unknown scope should be rejected: " + result,
            result.startsWith("Error: unknown scope category 'totally-bogus-scope'"));
    }

    public void testLimitClampedToMaximum() {
        myFixture.addFileToProject("src/cap/LimitMaxUnique.java", "class LimitMaxUnique {}");

        JsonObject params = args(QUERY, "LimitMaxUnique");
        params.addProperty(LIMIT, 10_000);
        String result = tool.execute(params);

        assertTrue("Should still match when limit is over max: " + result,
            result.contains("LimitMaxUnique.java"));
    }

    public void testLimitClampedToMinimum() {
        myFixture.addFileToProject("src/cap/LimitMinUnique.java", "class LimitMinUnique {}");

        JsonObject params = args(QUERY, "LimitMinUnique");
        params.addProperty(LIMIT, 0);
        String result = tool.execute(params);

        assertTrue("Should produce at least one result with floored limit: " + result,
            result.contains("LimitMinUnique.java"));
    }

    public void testNullLimitUsesDefault() {
        myFixture.addFileToProject("src/cap/LimitNullUnique.java", "class LimitNullUnique {}");

        JsonObject params = args(QUERY, "LimitNullUnique");
        params.add(LIMIT, com.google.gson.JsonNull.INSTANCE);
        String result = tool.execute(params);

        assertTrue("Null limit should default and still match: " + result,
            result.contains("LimitNullUnique.java"));
    }

    public void testFormatIncludesDirectoryWhenNested() {
        myFixture.addFileToProject("src/nested/dir/NestedDirUnique.java", "class NestedDirUnique {}");

        String result = tool.execute(args(QUERY, "NestedDirUnique"));

        assertTrue("Result should reference file: " + result,
            result.contains("NestedDirUnique.java"));
        assertTrue("Result should show directory: " + result,
            result.contains("dir="));
    }

    public void testToolMetadata() {
        assertEquals("find_file", tool.id());
        assertEquals("Find File", tool.displayName());
        assertNotNull(tool.description());
        assertTrue(tool.isReadOnly());
        assertTrue(tool.requiresIndex());
        assertNotNull(tool.inputSchema());
        assertNotNull(tool.category());
    }

    public void testExactNameSurvivesManyWeakerMatches() {
        for (int i = 0; i < 1_200; i++) {
            myFixture.addFileToProject("src/bulk/BulkRankUnique" + i + ".txt", "x");
        }
        myFixture.addFileToProject("src/bulk/BulkRankUnique", "x");

        JsonObject params = args(QUERY, "BulkRankUnique");
        params.addProperty(LIMIT, 5);
        String result = tool.execute(params);

        String firstEntry = result.lines().skip(1).findFirst().orElse("");
        assertTrue("Exact name must rank first even among 1200 weaker matches, got: " + result,
            firstEntry.contains("[BulkRankUnique,"));
    }

    public void testRepeatedCallsReturnIdenticalResults() {
        for (int i = 0; i < 300; i++) {
            myFixture.addFileToProject("src/repeat/RepeatUnique" + i + ".txt", "x");
        }

        JsonObject params = args(QUERY, "RepeatUnique");
        params.addProperty(LIMIT, 20);
        String first = tool.execute(params);
        for (int i = 0; i < 20; i++) {
            assertEquals("Call " + i + " differs", first, tool.execute(params));
        }
    }

    public void testPathQueryWithoutWildcardMatchesOnlyThatPath() {
        myFixture.addFileToProject("src/pathq/a/SamePathName.java", "class SamePathName {}");
        myFixture.addFileToProject("src/pathq/b/SamePathName.java", "class SamePathName {}");
        myFixture.addFileToProject("src/pathq/b/Unrelated.java", "class Unrelated {}");

        String result = tool.execute(args(QUERY, "pathq/b/SamePathName.java"));

        assertTrue("Expected the requested file, got: " + result, result.contains("dir=b") || result.contains("pathq/b"));
        assertEquals("Only the file in directory b may match, got: " + result, "1 files:", result.lines().findFirst().orElse(""));
        assertFalse("Unrelated file must not match, got: " + result, result.contains("Unrelated.java"));
    }

    private static JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }
}
