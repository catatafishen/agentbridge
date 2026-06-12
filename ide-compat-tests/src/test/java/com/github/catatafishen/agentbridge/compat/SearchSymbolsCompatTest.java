package com.github.catatafishen.agentbridge.compat;

import com.github.catatafishen.agentbridge.psi.tools.navigation.SearchSymbolsTool;
import com.google.gson.JsonObject;
import com.intellij.psi.PsiFile;

import java.util.List;

/**
 * IDE compatibility matrix row: {@code search_symbols} tool.
 *
 * <table>
 *   <caption>Expected outcomes per IDE</caption>
 *   <tr><th>Test</th><th>IU</th><th>CL</th></tr>
 *   <tr>
 *     <td>{@link #testSearchSymbolsWithJava}</td>
 *     <td>PASS — end-to-end via addFileToProject</td>
 *     <td>SKIP — Java plugin not present in CLion</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #testSearchSymbolsCppClass}</td>
 *     <td>SKIP — C++ language not registered in IU</td>
 *     <td>PASS (classic engine) or SKIP (if com.intellij.cidr.lang not loaded)</td>
 *   </tr>
 * </table>
 */
public class SearchSymbolsCompatTest extends IdeCompatBaseTest {

    /**
     * End-to-end test for the wildcard {@code search_symbols} query.
     * Creates a Java source file in the project's source root (so
     * {@code isInSourceContent()} returns {@code true}), then invokes the full
     * MCP tool and asserts the class symbol is returned.
     *
     * <p>Uses Java because it is available in all IntelliJ-based headless test
     * environments. This test exercises the complete tool path: file-system scan,
     * PSI read, {@code classifyElement}, and result formatting.</p>
     */
    public void testSearchSymbolsWithJava() {
        if (createInMemoryPsiFile("Probe.java", LANGUAGE_JAVA, "class Probe {}") == null) return;

        // addFileToProject() is required — configureByText creates temp:/// files that
        // ProjectFileIndex.isInSourceContent() returns false for, so searchWildcard skips them.
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
            "Expected Widget class in results, got: " + result,
            result.contains("Widget")
        );
    }

    /**
     * Direct per-file C++ symbol extraction.
     * Creates an in-memory C++ {@link PsiFile} via {@link com.intellij.psi.PsiFileFactory}
     * (bypassing the {@code FileTypeManager} extension that maps {@code .cpp} to a language),
     * then calls {@link SearchSymbolsTool#analyzeFileSymbols} to exercise the per-file PSI
     * analysis without requiring the file to be in a source root.
     *
     * <p>Skipped automatically when the C++ language is not registered in the current IDE build.
     * In CLion CI the test runs with the classic CIDR engine ({@code com.intellij.cidr.lang})
     * and exercises the standard {@code PsiNamedElement} walk. The CLion Nova node-type fallback
     * ({@code walkCppSymbolsByNodeType}) is exercised separately in real CLion with the Radler
     * backend.</p>
     */
    public void testSearchSymbolsCppClass() {
        PsiFile cppFile = createInMemoryPsiFile("widget.cpp", LANGUAGE_CPP, """
            class Widget { int width; int height; };
            void render(Widget* w) {}
            """);
        if (cppFile == null) return;

        SearchSymbolsTool tool = new SearchSymbolsTool(getProject());
        List<String> results = tool.analyzeFileSymbols(cppFile, "class");

        assertFalse(
            "C++ PSI analysis should find at least one class symbol in widget.cpp",
            results.isEmpty()
        );
        assertTrue(
            "Expected Widget class symbol in results, got: " + results,
            results.stream().anyMatch(r -> r.contains("Widget"))
        );
    }
}
