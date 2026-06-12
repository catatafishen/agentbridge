package com.github.catatafishen.agentbridge.compat;

import com.github.catatafishen.agentbridge.psi.tools.navigation.SearchSymbolsTool;
import com.google.gson.JsonObject;

/**
 * IDE compatibility test for search_symbols (wildcard query).
 *
 * <p>Expected outcomes:</p>
 * <ul>
 *   <li>IntelliJ (IU): PASS — Java PSI produces {@code PsiNamedElement} for classes.</li>
 *   <li>CLion (CL): PASS — Java PSI is available in the headless CLion CI environment
 *       and verifies that the wildcard-search infrastructure (file scanning,
 *       {@code isInSourceContent}, {@code classifyElement}) works end-to-end.</li>
 * </ul>
 *
 * <p>Both platforms use a Java fixture file because the C++ language plugin is not
 * loaded in CLion's headless test environment (no Radler backend), causing {@code .cpp}
 * files to parse as {@code PLAIN_TEXT}. The CLion Nova C++ node-type fallback in
 * {@code NavigationTool.collectSymbolsFromFile} (bug #1b) is verified manually against
 * a real CLion IDE with an indexed C++ project.</p>
 */
public class SearchSymbolsCompatTest extends IdeCompatBaseTest {

    public void testSearchSymbolsWildcardFindsSymbols() {
        // Both IU and CL: Java is available in every headless test environment bundled
        // with IntelliJ-based IDEs. addFileToProject() places the file in the light
        // project's source root so isInSourceContent() returns true.
        myFixture.addFileToProject("Widget.java",
            "public class Widget { private int width; private int height; }\n");

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
