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
            "path", implementation.getVirtualFile().getPath(),
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
            "path", standalone.getVirtualFile().getPath(),
            "line", "3",
            "column", "25"));

        assertEquals("No super methods found for localOnly", result);
    }

    public void testMissingPositionReturnsError() {
        String result = tool.execute(new JsonObject());

        assertTrue("Expected error prefix, got: " + result,
            result.startsWith(ToolUtils.ERROR_PREFIX));
        assertTrue("Expected required params message, got: " + result,
            result.contains("'path' and 'line' parameters are required"));
    }

    public void testNonJavaResolvesMethodFromParameterPosition() {
        // Regression for review feedback on PR #819: the non-Java path used to resolve the
        // innermost PsiNameIdentifierOwner at the caret — which for a parameter position
        // would be PsiParameter, leading to a misleading "No super methods found for <param>"
        // result. The fix walks every enclosing PsiNameIdentifierOwner and tries the platform
        // helper on each in turn, so a caret inside the parameter list still resolves to the
        // overriding method's supers.
        myFixture.addFileToProject("demo/SuperContractParam.java", """
            package demo;
            public interface SuperContractParam {
                String load(String id);
            }
            """);
        com.intellij.psi.PsiFile impl = myFixture.addFileToProject(
            "demo/SuperImplParam.java",
            """
                package demo;
                public class SuperImplParam implements SuperContractParam {
                    @Override
                    public String load(String id) { return id; }
                }
                """);

        FindSuperMethodsTool nonJavaTool = new FindSuperMethodsTool(getProject(), false);
        String result = nonJavaTool.execute(args(
            "path", impl.getVirtualFile().getPath(),
            "line", "4",
            // Column 32 lands on the parameter `id`, NOT the method name.
            "column", "32"));

        assertFalse("Resolution should walk up past the parameter to the method, got: " + result,
            result.startsWith(ToolUtils.ERROR_PREFIX));
        assertFalse("Must not report supers for the parameter name 'id', got: " + result,
            result.contains("Super methods for id") || result.contains("No super methods found for id"));
        assertTrue("Expected method name 'load' in result, got: " + result,
            result.contains("Super methods for load") || result.contains("No super methods found for load"));
    }

    public void testNonJavaReturnsProgrammaticResultsViaPlatformHelper() {
        // In the test environment (IntelliJ IDEA Ultimate) the Java module is loaded, so
        // `FindSuperElementsHelper` IS on the classpath. The non-Java path reflectively invokes
        // it and returns programmatic results — no editor, no navigation, no "press Ctrl+U"
        // hint that an autonomous agent can't act on.
        myFixture.addFileToProject("demo/NonJavaProbeIface.java", """
            package demo;
            public interface NonJavaProbeIface {
                String load(String id);
            }
            """);
        com.intellij.psi.PsiFile impl = myFixture.addFileToProject(
            "demo/NonJavaProbeImpl.java",
            """
                package demo;
                public class NonJavaProbeImpl implements NonJavaProbeIface {
                    @Override
                    public String load(String id) { return id; }
                }
                """);

        FindSuperMethodsTool nonJavaTool = new FindSuperMethodsTool(getProject(), false);
        String result = nonJavaTool.execute(args(
            "path", impl.getVirtualFile().getPath(),
            "line", "4",
            // Column 19 lands on the method name 'load' itself, not the parameter.
            "column", "19"));

        assertFalse("Result must not be an error in this IDE — FindSuperElementsHelper IS "
            + "loadable. Got: " + result, result.startsWith(ToolUtils.ERROR_PREFIX));
        // Must NOT suggest manual Ctrl+U — that's useless to an autonomous agent.
        assertFalse("Result must not suggest manual Ctrl+U action, got: " + result,
            result.contains("Ctrl+U") || result.contains("Cmd+U"));
        // Must include actual super-element location.
        assertTrue("Expected NonJavaProbeIface.java location, got: " + result,
            result.contains("NonJavaProbeIface.java"));
        assertTrue("Expected method name header, got: " + result,
            result.contains("Super methods for load:"));
    }

    public void testNonJavaWithNoSuperReturnsNoneFoundMessage() {
        com.intellij.psi.PsiFile file = myFixture.addFileToProject(
            "demo/StandaloneNonJava.java",
            """
                package demo;
                public class StandaloneNonJava {
                    public void localOnly() {}
                }
                """);

        FindSuperMethodsTool nonJavaTool = new FindSuperMethodsTool(getProject(), false);
        String result = nonJavaTool.execute(args(
            "path", file.getVirtualFile().getPath(),
            "line", "3",
            // Column 17 lands on the method name 'localOnly' itself.
            "column", "17"));

        // No super methods exist; helper returns empty — must report a clean "none found"
        // message with the resolved name, NOT an error and NOT a Ctrl+U suggestion.
        assertFalse("Should not error when helper returns empty, got: " + result,
            result.startsWith(ToolUtils.ERROR_PREFIX));
        assertFalse("Must not suggest manual Ctrl+U action, got: " + result,
            result.contains("Ctrl+U") || result.contains("Cmd+U"));
        assertEquals("No super methods found for localOnly", result);
    }

    public void testNonJavaWithoutNamedDeclarationReturnsClearError() {
        // The position lands on a blank line — no enclosing named declaration exists. The
        // non-Java path must return a clear, agent-friendly error WITHOUT any manual-action
        // suggestion.
        FindSuperMethodsTool nonJavaTool = new FindSuperMethodsTool(getProject(), false);
        com.intellij.psi.PsiFile file = myFixture.addFileToProject(
            "demo/EmptyLineProbe2.java",
            """
                package demo;

                public class EmptyLineProbe2 {}
                """);

        String result = nonJavaTool.execute(args(
            "path", file.getVirtualFile().getPath(),
            "line", "2"));

        assertTrue("Expected error prefix, got: " + result,
            result.startsWith(ToolUtils.ERROR_PREFIX));
        assertTrue("Error must mention no named declaration was found, got: " + result,
            result.contains("No named declaration found"));
        assertFalse("Must not suggest manual Ctrl+U action, got: " + result,
            result.contains("Ctrl+U") || result.contains("Cmd+U"));
    }

    public void testLineOutOfRangeReturnsError() {
        PsiFile file = myFixture.addFileToProject("demo/ShortSuperMethod.java", """
            package demo;
            public class ShortSuperMethod {
                public void localOnly() {}
            }
            """);

        String result = tool.execute(args(
            "path", file.getVirtualFile().getPath(),
            "line", "99"));

        assertTrue("Expected error prefix, got: " + result,
            result.startsWith(ToolUtils.ERROR_PREFIX));
        assertTrue("Expected line range error, got: " + result,
            result.contains("Line out of range"));
    }

    public void testNoMethodAtPositionReturnsError() {
        PsiFile file = myFixture.addFileToProject("demo/NoMethodAtPosition.java", """
            package demo;
            public class NoMethodAtPosition {
                private String value;
            }
            """);

        String result = tool.execute(args(
            "path", file.getVirtualFile().getPath(),
            "line", "1"));

        assertTrue("Expected error prefix, got: " + result,
            result.startsWith(ToolUtils.ERROR_PREFIX));
        assertTrue("Expected no method message, got: " + result,
            result.contains("No method found at position"));
    }

    private static JsonObject args(String... pairs) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.addProperty(pairs[i], pairs[i + 1]);
        }
        return obj;
    }
}
