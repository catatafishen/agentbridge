package com.github.catatafishen.agentbridge.psi.tools.refactoring;

import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Abstract base for refactoring tools. Provides shared helpers for
 * symbol resolution, declaration formatting, and output truncation.
 */
public abstract class RefactoringTool extends Tool {

    protected RefactoringTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.REFACTOR;
    }

    // ── Shared helpers ──────────────────────────────────────

    /**
     * Finds a {@link PsiNamedElement} by name in the given file, optionally constrained to a line.
     */
    protected PsiNamedElement findNamedElement(PsiFile psiFile, Document document, String name, int targetLine) {
        PsiNamedElement[] found = {null};
        psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element instanceof PsiNamedElement named && name.equals(named.getName())
                    && isMatchingLine(element, document, targetLine, found[0] == null)) {
                    found[0] = named;
                }
                if (found[0] == null) super.visitElement(element);
            }
        });
        return found[0];
    }

    private boolean isMatchingLine(PsiElement element, Document document, int targetLine, boolean noMatchYet) {
        if (targetLine <= 0) return noMatchYet;
        if (document == null) return false;
        int line = document.getLineNumber(element.getTextOffset()) + 1;
        return line == targetLine;
    }

    /**
     * Formats resolved declaration results into a human-readable string.
     */
    protected String formatDeclarationResults(List<PsiElement> declarations, String symbolName) {
        StringBuilder sb = new StringBuilder();
        sb.append("Declaration of '").append(symbolName).append("':\n\n");
        String basePath = project.getBasePath();

        for (PsiElement decl : declarations) {
            PsiFile declFile = decl.getContainingFile();
            if (declFile == null) continue;

            VirtualFile declVf = declFile.getVirtualFile();
            String declPath = resolveDeclPath(declVf, basePath);

            Document declDoc = declVf != null ? FileDocumentManager.getInstance().getDocument(declVf) : null;
            int declLine = declDoc != null ? declDoc.getLineNumber(decl.getTextOffset()) + 1 : -1;

            sb.append("  File: ").append(declPath).append("\n");
            sb.append("  Line: ").append(declLine).append("\n");
            appendDeclarationContext(sb, declDoc, declLine);
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Captures the file path and line of the first declaration into a two-element array.
     * {@code declInfo[0]} = relative path, {@code declInfo[1]} = line number as string.
     */
    protected void captureDeclInfo(PsiElement firstDecl, String[] declInfo) {
        PsiFile declFile = firstDecl.getContainingFile();
        if (declFile == null || declFile.getVirtualFile() == null) return;

        String basePath = project.getBasePath();
        VirtualFile declVf = declFile.getVirtualFile();
        declInfo[0] = basePath != null ? relativize(basePath, declVf.getPath()) : declVf.getPath();
        Document declDoc = FileDocumentManager.getInstance().getDocument(declVf);
        if (declDoc != null) {
            declInfo[1] = String.valueOf(declDoc.getLineNumber(firstDecl.getTextOffset()) + 1);
        }
    }

    protected String resolveDeclPath(VirtualFile declVf, String basePath) {
        if (declVf != null && basePath != null) return relativize(basePath, declVf.getPath());
        if (declVf != null) return declVf.getName();
        return "?";
    }

    protected void appendDeclarationContext(StringBuilder sb, Document declDoc, int declLine) {
        if (declDoc == null || declLine <= 0) return;
        int startLine = Math.max(0, declLine - 3);
        int endLine = Math.min(declDoc.getLineCount() - 1, declLine + 2);
        sb.append("  Context:\n");
        for (int l = startLine; l <= endLine; l++) {
            int ls = declDoc.getLineStartOffset(l);
            int le = declDoc.getLineEndOffset(l);
            String lineContent = declDoc.getText(new TextRange(ls, le));
            sb.append(l == declLine - 1 ? "  → " : "    ")
                .append(l + 1).append(": ").append(lineContent).append("\n");
        }
    }
}
