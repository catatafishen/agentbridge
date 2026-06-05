package com.github.catatafishen.agentbridge.psi.tools.testing;

import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.Nullable;

/**
 * Tests for {@link ListTestsTool} — focusing on the crash fix and fallback
 * detection for IDEs without the {@code com.intellij.testFramework} extension point.
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
     * {@code safeGetTestFrameworks()} must return a non-null list without throwing
     * regardless of whether the extension point is registered.
     */
    public void testSafeGetTestFrameworksDoesNotThrow() {
        var frameworks = ListTestsTool.safeGetTestFrameworks();
        assertNotNull("safeGetTestFrameworks() must never return null", frameworks);
    }

    // ── isFallbackTestElement ─────────────────────────────────────────────────

    /**
     * {@code TestBody()} inside a class named {@code SomeSuite_MyTest_Test} matches
     * Google Test's macro-expansion naming: {@code TEST(SomeSuite, MyTest)} →
     * {@code SomeSuite_MyTest_Test::TestBody()}.
     */
    public void testFallbackDetectsGoogleTestBody() {
        myFixture.configureByText("GTestSample.java",
            "public class SomeSuite_MyTest_Test { public void TestBody<caret>() {} }");
        var method = findMethodAtCaret();
        assertNotNull("Should find a PsiMethod", method);
        assertTrue("isFallbackTestElement should recognize TestBody in *_Test_* class",
            ListTestsTool.isFallbackTestElement(method, "TestBody"));
    }

    /**
     * A function named {@code testSomething} (starts with "test", length > 4)
     * must be recognized — covers Catch2 sections and conventional C/C++ naming.
     */
    public void testFallbackDetectsTestPrefixMethod() {
        myFixture.configureByText("MyClass.java",
            "public class MyClass { public void testSomething<caret>() {} }");
        var method = findMethodAtCaret();
        assertNotNull(method);
        assertTrue("testSomething should be recognized as a fallback test",
            ListTestsTool.isFallbackTestElement(method, "testSomething"));
    }

    /**
     * A function ending in {@code Test} (e.g. {@code somethingTest}) must be recognized.
     */
    public void testFallbackDetectsTestSuffixMethod() {
        myFixture.configureByText("MyClass.java",
            "public class MyClass { public void somethingTest<caret>() {} }");
        var method = findMethodAtCaret();
        assertNotNull(method);
        assertTrue("somethingTest should be recognized as a fallback test",
            ListTestsTool.isFallbackTestElement(method, "somethingTest"));
    }

    /**
     * A function named {@code processData} must NOT be recognized as a test.
     */
    public void testFallbackDoesNotMatchNonTestMethod() {
        myFixture.configureByText("MyClass.java",
            "public class MyClass { public void processData<caret>() {} }");
        var method = findMethodAtCaret();
        assertNotNull(method);
        assertFalse("processData should not be recognized as a test",
            ListTestsTool.isFallbackTestElement(method, "processData"));
    }

    /**
     * {@code null} name must return false without throwing.
     */
    public void testFallbackNullNameReturnsFalse() {
        myFixture.configureByText("MyClass.java",
            "public class MyClass { public void something<caret>() {} }");
        var method = findMethodAtCaret();
        assertNotNull(method);
        assertFalse("null name must return false",
            ListTestsTool.isFallbackTestElement(method, null));
    }

    /**
     * {@code TestBody()} inside a class that does NOT follow the {@code _Test_}
     * Google Test naming must NOT be flagged — avoids false positives.
     */
    public void testFallbackIgnoresTestBodyInNonGoogleTestClass() {
        myFixture.configureByText("MyClass.java",
            "public class MyOrdinaryClass { public void TestBody<caret>() {} }");
        var method = findMethodAtCaret();
        assertNotNull(method);
        assertFalse("TestBody in an ordinary class must not be a false positive",
            ListTestsTool.isFallbackTestElement(method, "TestBody"));
    }

    /**
     * Short names like {@code test} (length == 4) must NOT match — guards against
     * trivially-named utility functions.
     */
    public void testFallbackDoesNotMatchBareTestName() {
        myFixture.configureByText("MyClass.java",
            "public class MyClass { public void test<caret>() {} }");
        var method = findMethodAtCaret();
        assertNotNull(method);
        assertFalse("bare 'test' name (length == 4) must not match",
            ListTestsTool.isFallbackTestElement(method, "test"));
    }

    // ── execute() smoke tests ─────────────────────────────────────────────────

    /**
     * In a project with no test files the tool must return "No tests found" without throwing.
     */
    public void testExecuteReturnsNoTestsFoundWhenEmpty() {
        String result = tool.execute(new JsonObject());
        assertNotNull(result);
        assertEquals("No tests found", result);
    }

    /**
     * {@code execute()} must not throw or crash — the result may be "No tests found"
     * if the lightweight test project has no test source roots, but it must never throw.
     */
    public void testExecuteDoesNotThrow() {
        String result = tool.execute(new JsonObject());
        assertNotNull("execute() must not return null", result);
        assertFalse("execute() must not return an error string", result.startsWith("Error"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Walks up from the caret position to the nearest containing {@link PsiMethod}.
     */
    private @Nullable PsiMethod findMethodAtCaret() {
        var element = myFixture.getFile().findElementAt(myFixture.getCaretOffset() - 1);
        if (element == null) return null;
        var current = element.getParent();
        while (current != null && !(current instanceof PsiMethod)) {
            current = current.getParent();
        }
        return (PsiMethod) current;
    }
}
