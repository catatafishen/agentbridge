package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.google.gson.JsonObject;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class FindFileToolTest extends BasePlatformTestCase {

    private static final String QUERY = "query";
    private static final String LIMIT = "limit";
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

    public void testBlankQueryReturnsError() {
        String result = tool.execute(args(QUERY, "   "));

        assertTrue("Expected error prefix, got: " + result,
            result.startsWith(ToolUtils.ERROR_PREFIX));
        assertTrue("Expected blank query message, got: " + result,
            result.contains("Query cannot be empty"));
    }

    private static JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }
}
