package com.github.catatafishen.agentbridge.psi.tools.refactoring;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.file.FileTool;
import com.github.catatafishen.agentbridge.ui.renderers.GoToDeclarationRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiReference;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Navigates to the declaration of a symbol at a given file and line.
 */
@SuppressWarnings("java:S112")
public final class GoToDeclarationTool extends RefactoringTool {

    private static final String PARAM_SYMBOL = "symbol";
    private static final String FORMAT_LINES_SUFFIX = " lines)";

    public GoToDeclarationTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "go_to_declaration";
    }

    @Override
    public @NotNull String displayName() {
        return "Go to Declaration";
    }

    @Override
    public @NotNull String description() {
        return "Navigate to the declaration of a symbol at a given file and line";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"file", TYPE_STRING, "Path to the file containing the symbol usage"},
            {PARAM_SYMBOL, TYPE_STRING, "Name of the symbol to look up"},
            {"line", TYPE_INTEGER, "Line number where the symbol appears"}
        }, "file", PARAM_SYMBOL, "line");
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GoToDeclarationRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("file") || !args.has(PARAM_SYMBOL) || !args.has("line")) {
            return "Error: 'file', 'symbol', and 'line' parameters are required";
        }
        String pathStr = args.get("file").getAsString();
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        int targetLine = args.get("line").getAsInt();

        String[] declInfo = new String[2];

        String result = ApplicationManager.getApplication().runReadAction(
            (Computable<String>) () -> findAndFormatDeclaration(pathStr, targetLine, symbolName, declInfo));

        if (declInfo[0] != null && declInfo[1] != null) {
            int declLine = Integer.parseInt(declInfo[1]);
            FileTool.followFileIfEnabled(project, declInfo[0], declLine, declLine,
                FileTool.HIGHLIGHT_READ, FileTool.agentLabel(project) + " found declaration");
        }
        return result;
    }

    private String findAndFormatDeclaration(String pathStr, int targetLine,
                                            String symbolName, String[] declInfo) {
        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;

        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_CANNOT_PARSE + pathStr;

        Document document = FileDocumentManager.getInstance().getDocument(vf);
        if (document == null) return "Error: Cannot get document for: " + pathStr;

        if (targetLine < 1 || targetLine > document.getLineCount()) {
            return "Error: Line " + targetLine + " is out of bounds (file has " +
                document.getLineCount() + FORMAT_LINES_SUFFIX;
        }
        int lineStartOffset = document.getLineStartOffset(targetLine - 1);
        int lineEndOffset = document.getLineEndOffset(targetLine - 1);

        List<PsiElement> declarations = findDeclarationsOnLine(
            psiFile, lineStartOffset, lineEndOffset, symbolName);
        if (declarations.isEmpty()) {
            declarations = findDeclarationByOffset(
                psiFile, document, lineStartOffset, lineEndOffset, symbolName);
        }
        if (declarations.isEmpty()) {
            return "Could not resolve declaration for '" + symbolName + "' at line " + targetLine +
                " in " + pathStr + ". The symbol may be unresolved or from an unindexed library.";
        }

        captureDeclInfo(declarations.getFirst(), declInfo);
        return formatDeclarationResults(declarations, symbolName);
    }

    private List<PsiElement> findDeclarationsOnLine(
        PsiFile psiFile, int lineStartOffset, int lineEndOffset, String symbolName) {
        List<PsiElement> declarations = new ArrayList<>();
        psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                int offset = element.getTextOffset();
                if (offset >= lineStartOffset && offset <= lineEndOffset
                    && matchesSymbolName(element, symbolName)) {
                    resolveDeclarations(element, declarations);
                }
                super.visitElement(element);
            }
        });
        return declarations;
    }

    private boolean matchesSymbolName(PsiElement element, String symbolName) {
        return element.textMatches(symbolName)
            || (element instanceof PsiNamedElement named && symbolName.equals(named.getName()));
    }

    private void resolveDeclarations(PsiElement element, List<PsiElement> declarations) {
        PsiReference ref = element.getReference();
        if (ref != null) {
            PsiElement resolved = ref.resolve();
            if (resolved != null) declarations.add(resolved);
        }
        if (element instanceof PsiNamedElement) {
            for (PsiReference r : element.getReferences()) {
                PsiElement res = r.resolve();
                if (res != null && res != element) declarations.add(res);
            }
        }
    }

    private List<PsiElement> findDeclarationByOffset(
        PsiFile psiFile, Document document, int lineStartOffset, int lineEndOffset, String symbolName) {
        List<PsiElement> declarations = new ArrayList<>();
        String lineText = document.getText(new TextRange(lineStartOffset, lineEndOffset));
        int symIdx = lineText.indexOf(symbolName);
        if (symIdx < 0) return declarations;

        int offset = lineStartOffset + symIdx;
        PsiElement elemAtOffset = psiFile.findElementAt(offset);
        if (elemAtOffset == null) return declarations;

        PsiElement current = elemAtOffset;
        for (int i = 0; i < 5 && current != null; i++) {
            PsiReference ref = current.getReference();
            if (ref != null) {
                PsiElement resolved = ref.resolve();
                if (resolved != null) {
                    declarations.add(resolved);
                    break;
                }
            }
            current = current.getParent();
        }
        return declarations;
    }
}
