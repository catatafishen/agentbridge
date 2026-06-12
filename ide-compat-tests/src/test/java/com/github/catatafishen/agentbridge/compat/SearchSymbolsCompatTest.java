package com.github.catatafishen.agentbridge.compat;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.navigation.SearchSymbolsTool;
import com.google.gson.JsonObject;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * IDE compatibility test for search_symbols (wildcard query).
 *
 * <p>Expected outcomes:</p>
 * <ul>
 *   <li>IntelliJ (IU): PASS — Java PSI produces {@code PsiNamedElement} for classes.</li>
 *   <li>CLion (CL): PASS — C/C++ Language Support plugin (classic engine, no Radler required)
 *       provides C++ PSI in headless tests. Additionally, the CLion Nova node-type fallback in
 *       {@code NavigationTool.collectSymbolsFromFile} handles the case where the Radler backend
 *       produces lazy {@code ASTWrapperPsiElement} nodes instead of {@code PsiNamedElement}.
 *       Fixes bug #1b from issue #794.</li>
 * </ul>
 */
public class SearchSymbolsCompatTest extends IdeCompatBaseTest {

    public void testSearchSymbolsWildcardFindsSymbols() {
        // addFileToProject() is required — configureByText creates in-memory temp:/// files
        // for which isInSourceContent() returns false, so searchWildcard skips them.
        PsiFile addedFile;
        if ("CL".equals(PLATFORM_TYPE)) {
            addedFile = myFixture.addFileToProject("widget.cpp", """
                class Widget { int width; int height; };
                void render(Widget* w) {}
                """);
        } else {
            addedFile = myFixture.addFileToProject("Widget.java", """
                public class Widget { private int width; private int height; }
                """);
        }

        SearchSymbolsTool tool = new SearchSymbolsTool(getProject());
        JsonObject args = new JsonObject();
        args.addProperty("query", "*");
        args.addProperty("type", "class");
        String result = tool.execute(args);

        assertNotNull("Tool must return a non-null result", result);
        assertFalse("Tool result must not be an error", result.startsWith("Error:"));
        assertTrue(
            "Expected at least one 'class' symbol from wildcard search, got: " + result
                + buildPsiDiagnostic(addedFile),
            result.contains("Widget")
        );
    }

    private String buildPsiDiagnostic(PsiFile psiFile) {
        StringBuilder sb = new StringBuilder("\n--- PSI diagnostic ---\n");
        sb.append("fileType=").append(psiFile.getFileType().getName()).append('\n');
        sb.append("psiClass=").append(psiFile.getClass().getSimpleName()).append('\n');
        sb.append("directChildren:\n");
        for (PsiElement child : psiFile.getChildren()) {
            sb.append("  ").append(child.getClass().getSimpleName())
                .append(" nodeType=").append(child.getNode().getElementType())
                .append(" text=").append(child.getText().length() > 30
                    ? child.getText().substring(0, 30) + "..." : child.getText())
                .append('\n');
        }
        List<String> named = new ArrayList<>();
        psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element instanceof PsiNamedElement ne) {
                    named.add(element.getClass().getSimpleName()
                        + " name=" + ne.getName()
                        + " classify=" + ToolUtils.classifyElement(element));
                }
                super.visitElement(element);
            }
        });
        sb.append("namedElements (").append(named.size()).append("):\n");
        named.forEach(n -> sb.append("  ").append(n).append('\n'));
        return sb.toString();
    }
}
