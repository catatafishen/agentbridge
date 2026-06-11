package com.github.catatafishen.agentbridge.compat;

import com.github.catatafishen.agentbridge.psi.tools.navigation.SearchSymbolsTool;
import com.google.gson.JsonObject;

/**
 * IDE compatibility test for search_symbols (wildcard path).
 *
 * <p>Expected outcomes:</p>
 * <ul>
 *   <li>IntelliJ (IU): PASS — Java PSI produces {@code PsiNamedElement} for classes,
 *       so wildcard search finds the test class.</li>
 *   <li>CLion (CL): FAIL — CLion Nova C++ parser does not produce {@code PsiNamedElement}
 *       for C++ declarations, so wildcard search returns 0 results.
 *       This test documents bug #1b: search_symbols wildcard broken for CLion Nova C++.</li>
 * </ul>
 */
public class SearchSymbolsCompatTest extends IdeCompatBaseTest {

    public void testSearchSymbolsWildcardFindsSymbols() {
        // Create a platform-appropriate fixture file with a named declaration
        if ("CL".equals(PLATFORM_TYPE)) {
            // CLion Nova C++ — bug #1b: PsiNamedElement walk returns nothing
            myFixture.configureByText("widget.cpp",
                "class Widget { int width; int height; };\n" +
                    "void render(Widget* w) {}\n");
        } else {
            // IntelliJ IU — Java PSI, wildcard works correctly
            myFixture.configureByText("Widget.java",
                "public class Widget { private int width; private int height; }\n");
        }

        SearchSymbolsTool tool = new SearchSymbolsTool(getProject());
        JsonObject args = new JsonObject();
        args.addProperty("query", "*");
        args.addProperty("type", "class");

        String result = tool.execute(args);

        assertNotNull("Tool must return a non-null result", result);
        assertFalse("Tool result must not be an error", result.startsWith("Error:"));

        // On IU the wildcard walk finds Widget via PsiNamedElement → passes.
        // On CL this assertion fails because CLion Nova C++ PSI does not implement
        // PsiNamedElement, confirming bug #1b.
        assertTrue(
            "Expected at least one 'class' symbol from wildcard search, got: " + result,
            result.contains("Widget")
        );
    }
}
