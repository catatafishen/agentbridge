package com.github.catatafishen.agentbridge.psi.tools.refactoring;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.google.gson.JsonObject;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class FindSuperMethodsToolTest extends BasePlatformTestCase {

    private FindSuperMethodsTool tool;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        tool = new FindSuperMethodsTool(getProject(), true);
    }

    public void testFindsImplementedInterfaceMethod() {
        myFixture.addFileToProject("demo/SuperContract.java", """
                package demo;
                public interface SuperContract {
                    String load(String id);
                }
                """);
        PsiFile implementation = myFixture.addFileToProject("demo/SuperImpl.java", """
                package demo;
                public class SuperImpl implements SuperContract {
                    @Override
                    public String load(String id) {
                        return id;
                    }
                }
                """);

        String result = tool.execute(args(
                "file", implementation.getVirtualFile().getPath(),
                "line", "4",
                "column", "25"));

        assertTrue("Expected super method header, got: " + result,
                result.contains("Super methods for load(String):"));
        assertTrue("Expected interface method location, got: " + result,
                result.contains("SuperContract.java:3"));
        assertTrue("Expected interface label, got: " + result,
                result.contains("interface demo.SuperContract"));
    }

    public void testNoSuperMethodsForStandaloneMethod() {
        PsiFile standalone = myFixture.addFileToProject("demo/StandaloneSuperMethod.java", """
                package demo;
                public class StandaloneSuperMethod {
                    public void localOnly() {}
                }
                """);

        String result = tool.execute(args(
                "file", standalone.getVirtualFile().getPath(),
                "line", "3",
                "column", "25"));

        assertEquals("No super methods found for localOnly", result);
    }

    public void testMissingPositionReturnsError() {
        String result = tool.execute(new JsonObject());

        assertTrue("Expected error prefix, got: " + result,
                result.startsWith(ToolUtils.ERROR_PREFIX));
        assertTrue("Expected required params message, got: " + result,
                result.contains("'file' and 'line' parameters are required"));
    }

    private static JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }
}
