package com.github.catatafishen.agentbridge.compat;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.navigation.SearchSymbolsTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

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
        PsiFile addedFile;
        if ("CL".equals(PLATFORM_TYPE)) {
            addedFile = myFixture.addFileToProject("widget.cpp",
                "class Widget { int width; int height; };\n" +
                    "void render(Widget* w) {}\n");
        } else {
            addedFile = myFixture.addFileToProject("Widget.java",
                "public class Widget { private int width; private int height; }\n");
        }

        // Diagnostic: dump PSI structure for the added file to aid debugging if the
        // search returns no results. Output is included in the assertion failure message.
        String psiDiag = buildPsiDiagnostic(addedFile);

        SearchSymbolsTool tool = new SearchSymbolsTool(getProject());
        JsonObject args = new JsonObject();
        args.addProperty("query", "*");
        args.addProperty("type", "class");

        String result = tool.execute(args);

        assertNotNull("Tool must return a non-null result", result);
        assertFalse("Tool result must not be an error", result.startsWith("Error:"));

        assertTrue(
            "Expected at least one 'class' symbol from wildcard search, got: " + result
                + "\n--- PSI diagnostic ---\n" + psiDiag,
            result.contains("Widget")
        );
    }

    private String buildPsiDiagnostic(PsiFile psiFile) {
        StringBuilder sb = new StringBuilder();
        VirtualFile vf = psiFile.getVirtualFile();
        ProjectFileIndex idx = ProjectFileIndex.getInstance(getProject());

        sb.append("file=").append(vf != null ? vf.getPath() : "null").append('\n');
        sb.append("fileType=").append(psiFile.getFileType().getName()).append('\n');
        sb.append("psiClass=").append(psiFile.getClass().getSimpleName()).append('\n');
        sb.append("isInSourceContent=").append(vf != null && idx.isInSourceContent(vf)).append('\n');

        // Collect direct children of PsiFile (node types)
        sb.append("directChildren:\n");
        for (PsiElement child : psiFile.getChildren()) {
            sb.append("  ").append(child.getClass().getSimpleName())
                .append(" nodeType=").append(child.getNode().getElementType())
                .append(" text=").append(child.getText().length() > 30
                    ? child.getText().substring(0, 30) + "..." : child.getText())
                .append('\n');
        }

        // Collect all PsiNamedElement instances in file with their class and classifyElement result
        List<String> namedElements = new ArrayList<>();
        psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element instanceof PsiNamedElement named) {
                    String name = named.getName();
                    String type = ToolUtils.classifyElement(element);
                    namedElements.add(element.getClass().getSimpleName()
                        + " name=" + name + " classify=" + type);
                }
                super.visitElement(element);
            }
        });
        sb.append("namedElements (").append(namedElements.size()).append("):\n");
        for (String ne : namedElements) {
            sb.append("  ").append(ne).append('\n');
        }
        return sb.toString();
    }
}
