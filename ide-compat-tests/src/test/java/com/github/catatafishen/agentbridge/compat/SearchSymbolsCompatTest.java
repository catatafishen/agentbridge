package com.github.catatafishen.agentbridge.compat;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.navigation.SearchSymbolsTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.testFramework.PsiTestUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * IDE compatibility test for search_symbols (wildcard query).
 *
 * <p>Expected outcomes:</p>
 * <ul>
 *   <li>IntelliJ (IU): PASS — Java PSI produces {@code PsiNamedElement} for classes.</li>
 *   <li>CLion (CL): PASS — CLion Nova C++ PSI node-type fallback in
 *       {@code NavigationTool.collectSymbolsFromFile} detects declarations via
 *       {@code walkCppSymbolsByNodeType} when the {@code PsiNamedElement} walk yields nothing.
 *       Fixes bug #1b.</li>
 * </ul>
 *
 * <p>CLion note: {@code addFileToProject()} creates TempFS files that are treated as
 * {@code PLAIN_TEXT} in headless CLion (no Radler backend). Instead, a real disk directory
 * is created and registered as a source root via {@link PsiTestUtil#addSourceRoot} so that
 * both the file type and {@code isInSourceContent()} work correctly.</p>
 */
public class SearchSymbolsCompatTest extends IdeCompatBaseTest {

    private Path tempSourceDir;
    private VirtualFile cppSourceRootVf;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        if ("CL".equals(PLATFORM_TYPE)) {
            tempSourceDir = Files.createTempDirectory("search-symbols-compat-cpp");
            Files.writeString(tempSourceDir.resolve("widget.cpp"), """
                    class Widget { int width; int height; };
                    void render(Widget* w) {}
                    """);
            cppSourceRootVf = LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(tempSourceDir.toString());
            assertNotNull("VFS cannot find tempSourceDir: " + tempSourceDir, cppSourceRootVf);
            cppSourceRootVf.refresh(false, true);
            PsiTestUtil.addSourceRoot(getModule(), cppSourceRootVf);
        }
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (cppSourceRootVf != null) {
                PsiTestUtil.removeSourceRoot(getModule(), cppSourceRootVf);
                cppSourceRootVf = null;
            }
            if (tempSourceDir != null) {
                try (var paths = Files.walk(tempSourceDir)) {
                    paths.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                                // best-effort cleanup
                            }
                        });
                }
                tempSourceDir = null;
            }
        } finally {
            super.tearDown();
        }
    }

    public void testSearchSymbolsWildcardFindsSymbols() {
        if (!"CL".equals(PLATFORM_TYPE)) {
            // IU: addFileToProject() works fine — Java files are correctly typed in the fixture.
            myFixture.addFileToProject("Widget.java",
                "public class Widget { private int width; private int height; }\n");
        }
        // CL: widget.cpp was written to tempSourceDir (a real disk path) in setUp(),
        // so CLion's file-type registry recognises it as C++ (not PLAIN_TEXT).

        SearchSymbolsTool tool = new SearchSymbolsTool(getProject());
        JsonObject args = new JsonObject();
        args.addProperty("query", "*");
        args.addProperty("type", "class");
        String result = tool.execute(args);

        assertNotNull("Tool must return a non-null result", result);
        assertFalse("Tool result must not be an error", result.startsWith("Error:"));
        assertTrue(
            "Expected at least one 'class' symbol from wildcard search, got: " + result
                + psiDiagForCpp(),
            result.contains("Widget")
        );
    }

    /** Appends a PSI dump for the CLion C++ file — empty string on IU. */
    private String psiDiagForCpp() {
        if (cppSourceRootVf == null) return "";
        VirtualFile cppVf = cppSourceRootVf.findChild("widget.cpp");
        if (cppVf == null) return "\n[widget.cpp not found in VFS]";
        PsiFile psiFile = PsiManager.getInstance(getProject()).findFile(cppVf);
        if (psiFile == null) return "\n[no PsiFile for widget.cpp]";

        StringBuilder sb = new StringBuilder("\n--- PSI diagnostic (real disk) ---\n");
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
