package com.github.catatafishen.agentbridge.compat;

import com.github.catatafishen.agentbridge.psi.tools.refactoring.GetSymbolInfoTool;
import com.google.gson.JsonObject;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * IDE compatibility test for get_symbol_info (position-based symbol lookup).
 *
 * <p>Expected outcomes:</p>
 * <ul>
 *   <li>IntelliJ (IU): PASS — Java PSI produces {@code PsiNamedElement} for class
 *       declarations, so {@code findNamedAncestor} finds the class and returns its info.</li>
 *   <li>CLion (CL): FAIL — CLion Nova C++ declarations are {@code ASTWrapperPsiElement},
 *       not {@code PsiNamedElement}. {@code findNamedAncestor} walks up the PSI tree and
 *       finds nothing, returning "No named symbol found". Documents bug #2 from issue #794.</li>
 * </ul>
 *
 * <p>Uses a real disk file (registered via {@code LocalFileSystem#refreshAndFindFileByPath})
 * because {@code GetSymbolInfoTool} resolves the path via {@code LocalFileSystem#findFileByPath},
 * which cannot locate in-memory fixture files.</p>
 */
public class GetSymbolInfoCompatTest extends IdeCompatBaseTest {

    private Path tempDir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        tempDir = Files.createTempDirectory("symbol-info-compat-test");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            try (var paths = Files.walk(tempDir)) {
                paths.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best-effort cleanup
                        }
                    });
            }
        } finally {
            super.tearDown();
        }
    }

    public void testGetSymbolInfoFindsSymbolAtPosition() throws Exception {
        Path filePath;
        int line;
        if ("CL".equals(PLATFORM_TYPE)) {
            // CLion Nova C++ — bug #2: ASTWrapperPsiElement is not PsiNamedElement,
            // so findNamedAncestor() returns null → "No named symbol found".
            filePath = tempDir.resolve("widget.cpp");
            Files.writeString(filePath, """
                class Widget {
                    int width;
                    int height;
                };
                """);
            line = 1; // "class Widget {"
        } else {
            // IntelliJ IU — Java PSI, PsiClass is PsiNamedElement → works correctly.
            filePath = tempDir.resolve("Widget.java");
            Files.writeString(filePath, """
                public class Widget {
                    private int width;
                    private int height;
                }
                """);
            line = 1; // "public class Widget {"
        }

        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath.toString());
        assertNotNull("Failed to register file in VFS: " + filePath, vf);

        GetSymbolInfoTool tool = new GetSymbolInfoTool(getProject());
        JsonObject args = new JsonObject();
        args.addProperty("file", filePath.toString());
        args.addProperty("line", line);

        String result = tool.execute(args);

        assertNotNull("Tool must return a non-null result", result);
        assertFalse("Tool result must not be an error", result.startsWith("Error:"));

        if ("CL".equals(PLATFORM_TYPE)) {
            // Bug #2 (issue #794): headless CLion CI has no C++ language support
            // (no Radler backend), so .cpp files parse as PLAIN_TEXT, and real CLion Nova
            // uses ASTWrapperPsiElement for C++ declarations (not PsiNamedElement).
            // Either way findNamedAncestor() finds nothing → "No named symbol found".
            // This assertion confirms the documented failure; passing "Widget" would be
            // a false positive because the error message includes the raw line content.
            assertTrue(
                "Expected 'No named symbol found' on CLion (bug #2), got: " + result,
                result.contains("No named symbol found")
            );
        } else {
            // On IU, findNamedAncestor() finds the Java PsiClass → "Symbol: Widget …"
            assertTrue(
                "Expected symbol info for 'Widget', got: " + result,
                result.contains("Widget")
            );
        }
    }
}
