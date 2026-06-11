package com.github.catatafishen.agentbridge.compat;

import com.github.catatafishen.agentbridge.psi.tools.navigation.SearchSymbolsTool;
import com.google.gson.JsonObject;

/**
 * IDE compatibility test for search_symbols (wildcard path).
 *
 * <p>Expected outcomes:</p>
 * <ul>
 *   <li>IntelliJ (IU): PASS — Java PSI produces {@code PsiNamedElement} for classes.</li>
 *   <li>CLion (CL): PASS — CLion Nova C++ PSI node-type fallback in
 *       {@code NavigationTool.collectSymbolsFromFile} detects declarations via
 *       {@code walkCppSymbolsByNodeType} when the {@code PsiNamedElement} walk yields nothing.
 *       Fixes bug #1b.</li>
 * </ul>
 */
public class SearchSymbolsCompatTest extends IdeCompatBaseTest {

    public void testSearchSymbolsWildcardFindsSymbols() {
        // addFileToProject (TempFS) is required — configureByText creates in-memory temp:///
        // files that isInSourceContent() returns false for, so searchWildcard skips them.
        if ("CL".equals(PLATFORM_TYPE)) {
            myFixture.addFileToProject("widget.cpp",
                "class Widget { int width; int height; };\n" +
                    "void render(Widget* w) {}\n");
        } else {
            myFixture.addFileToProject("Widget.java",
                "public class Widget { private int width; private int height; }\n");
        }

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
