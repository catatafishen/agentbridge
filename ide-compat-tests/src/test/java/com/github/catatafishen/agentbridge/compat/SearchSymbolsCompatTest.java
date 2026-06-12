package com.github.catatafishen.agentbridge.compat;

import com.github.catatafishen.agentbridge.psi.tools.navigation.SearchSymbolsTool;
import com.google.gson.JsonObject;

/**
 * IDE compatibility test for search_symbols (wildcard query).
 *
 * <p>Expected outcomes:</p>
 * <ul>
 *   <li>IntelliJ (IU): PASS — Java PSI produces {@code PsiNamedElement} for classes.</li>
 *   <li>CLion (CL): PASS — Java is available in every IntelliJ-based headless test
 *       environment. The test verifies the full wildcard-search plumbing (file scanning,
 *       isInSourceContent, classifyElement) without depending on C++ PSI. The C++ Nova
 *       fix in {@code NavigationTool.walkCppSymbolsByNodeType} is verified manually in
 *       real CLion (78 class symbols returned from a large C++ project).</li>
 * </ul>
 *
 * <p>Note: Using C++ files (widget.cpp) in headless CLion CI is not feasible: the Radler
 * backend (required for C++ PSI in CLion Nova) is not available in headless tests, and
 * {@code bundledPlugin("com.intellij.cidr.lang")} does not register the C++ file-type
 * extension in the headless JVM — .cpp files parse as PLAIN_TEXT regardless.</p>
 */
public class SearchSymbolsCompatTest extends IdeCompatBaseTest {

    public void testSearchSymbolsWildcardFindsSymbols() {
        // addFileToProject() is required — configureByText creates in-memory temp:/// files
        // for which isInSourceContent() returns false, so searchWildcard skips them.
        myFixture.addFileToProject("Widget.java", """
            public class Widget { private int width; private int height; }
            """);

        SearchSymbolsTool tool = new SearchSymbolsTool(getProject());
        JsonObject args = new JsonObject();
        args.addProperty("query", "*");
        args.addProperty("type", "class");
        String result = tool.execute(args);

        assertNotNull("Tool must return a non-null result", result);
        assertFalse("Tool result must not be an error", result.startsWith("Error:"));
        assertTrue(
            "Expected at least one 'class' symbol from wildcard search, got: " + result,
            result.contains("Widget")
        );
    }
}
