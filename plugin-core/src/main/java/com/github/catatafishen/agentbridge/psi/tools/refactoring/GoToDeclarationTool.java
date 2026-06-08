package com.github.catatafishen.agentbridge.psi.tools.refactoring;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.FqnResolver;
import com.github.catatafishen.agentbridge.psi.tools.file.FileTool;
import com.github.catatafishen.agentbridge.ui.renderers.GoToDeclarationRenderer;
import com.google.gson.JsonObject;
import com.intellij.codeInsight.TargetElementUtil;
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
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
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
    public boolean requiresIndex() {
        return true;
    }

    @Override
    public @NotNull String displayName() {
        return "Go to Declaration";
    }

    @Override
    public @NotNull String description() {
        return "Navigate to the declaration of a symbol at a given file and line. Returns the source file path, line number, " +
            "and a code snippet of the declaration. " +
            "Accepts a fully-qualified name (e.g. 'com.example.MyClass.myMethod') as the 'symbol' parameter " +
            "— when an FQN is provided, 'file' and 'line' are optional. " +
            "Use get_symbol_info for documentation at a position, " +
            "or get_documentation when you have the fully-qualified name.";
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
        return schema(
            Param.optional("file", TYPE_STRING, "Path to the file containing the symbol usage. "
                + "Optional when 'symbol' is a fully-qualified name (e.g. 'com.example.MyClass')"),
            Param.required(PARAM_SYMBOL, TYPE_STRING, "Name of the symbol to look up. "
                + "Can be a simple name (requires file+line) or a fully-qualified name "
                + "(e.g. 'com.example.MyClass.myMethod') to resolve without file+line"),
            Param.optional("line", TYPE_INTEGER, "Line number where the symbol appears. "
                + "Optional when 'symbol' is a fully-qualified name")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return GoToDeclarationRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has(PARAM_SYMBOL)) {
            return "Error: 'symbol' parameter is required";
        }
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        String pathStr = args.has("file") ? args.get("file").getAsString() : null;
        int targetLine = args.has("line") ? args.get("line").getAsInt() : -1;

        // FQN mode: resolve directly by fully-qualified name
        if (FqnResolver.looksLikeFqn(symbolName) && pathStr == null) {
            return resolveFqnDeclaration(symbolName);
        }

        // Standard mode: resolve from file:line
        if (pathStr == null || targetLine < 1) {
            return "Error: 'file' and 'line' are required when 'symbol' is not a fully-qualified name. "
                + "Use a fully-qualified name (e.g. 'com.example.MyClass.myMethod') to resolve without file+line.";
        }

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

    private String resolveFqnDeclaration(String fqn) {
        String[] declInfo = new String[2];
        String result = ApplicationManager.getApplication().runReadAction(
            (Computable<String>) () -> {
                PsiElement resolved = FqnResolver.resolve(fqn, project);
                if (resolved == null) {
                    return "Error: Could not resolve FQN '" + fqn + "'. "
                        + "Ensure it is a valid fully-qualified Java/Kotlin class or member name. "
                        + "Use 'file' + 'line' parameters for non-Java symbols.";
                }
                captureDeclInfo(resolved, declInfo);
                return formatDeclarationResults(java.util.List.of(resolved), fqn);
            });

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

        List<PsiElement> declarations = resolveDeclarationsOnLine(
            psiFile, document, lineStartOffset, lineEndOffset, symbolName);
        if (declarations.isEmpty()) {
            return "Could not resolve declaration for '" + symbolName + "' at line " + targetLine +
                " in " + pathStr + ". The symbol may be unresolved or from an unindexed library.";
        }

        captureDeclInfo(declarations.getFirst(), declInfo);
        return formatDeclarationResults(declarations, symbolName);
    }

    /**
     * Finds declarations for {@code symbolName} occurring anywhere on the target line.
     * <p>
     * Uses three layered strategies, all of which delegate to the IDE's own
     * reference-resolution infrastructure and are therefore language-agnostic:
     *
     * <ol>
     *   <li>For each text occurrence of {@code symbolName} on the line, ask the IDE for the
     *       reference at that offset via {@link PsiFile#findReferenceAt(int)} (uses every language
     *       plugin's registered reference contributors — works for C/C++, Python, JS, Go, etc.).
     *       Handles polyvariant references via {@link PsiPolyVariantReference#multiResolve}.</li>
     *   <li>If no reference resolves, walk up the PSI tree from each occurrence — some language
     *       plugins put the reference on a parent expression rather than the leaf identifier.</li>
     *   <li>Finally, if the caret sits on a declaration itself, return the enclosing named element
     *       (matches the IDE's behaviour of "go to declaration on a declaration" showing the
     *       declaration itself).</li>
     * </ol>
     */
    private List<PsiElement> resolveDeclarationsOnLine(
        PsiFile psiFile, Document document, int lineStartOffset, int lineEndOffset, String symbolName) {
        List<PsiElement> declarations = new ArrayList<>();
        if (symbolName.isEmpty()) return declarations;
        String lineText = document.getText(new TextRange(lineStartOffset, lineEndOffset));

        int searchFrom = 0;
        while (searchFrom <= lineText.length() - symbolName.length()) {
            int symIdx = lineText.indexOf(symbolName, searchFrom);
            if (symIdx < 0) break;
            if (isWholeIdentifierMatch(lineText, symIdx, symbolName.length())) {
                int rawOffset = lineStartOffset + symIdx;
                int offset = TargetElementUtil.adjustOffset(psiFile, document, rawOffset);
                resolveAtOffset(psiFile, offset, declarations);
                if (!declarations.isEmpty()) return declarations;
            }
            searchFrom = symIdx + symbolName.length();
        }
        return declarations;
    }

    /**
     * Returns true if the substring at {@code [start, start+length)} in {@code text} is a whole
     * identifier — i.e. it is not preceded or followed by an identifier character. This prevents
     * spurious matches such as locating {@code bar} inside {@code foobar()}.
     */
    private static boolean isWholeIdentifierMatch(String text, int start, int length) {
        if (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) return false;
        int end = start + length;
        return end >= text.length() || !Character.isJavaIdentifierPart(text.charAt(end));
    }

    /**
     * Resolves the reference at {@code offset} via the platform-level
     * {@link PsiFile#findReferenceAt(int)}, then falls back to walking up the PSI tree (some
     * language plugins attach the reference to a parent node rather than the leaf identifier),
     * and finally to {@link TargetElementUtil#getNamedElement(PsiElement)} for the
     * "go to declaration on a declaration" case.
     */
    private static void resolveAtOffset(PsiFile psiFile, int offset, List<PsiElement> declarations) {
        PsiReference ref = psiFile.findReferenceAt(offset);
        if (ref != null) {
            addResolved(ref, declarations);
            if (!declarations.isEmpty()) return;
        }
        PsiElement elementAt = psiFile.findElementAt(offset);
        PsiElement current = elementAt;
        for (int i = 0; i < MAX_PARENT_WALK && current != null; i++) {
            PsiReference parentRef = current.getReference();
            if (parentRef != null) {
                addResolved(parentRef, declarations);
                if (!declarations.isEmpty()) return;
            }
            current = current.getParent();
        }
        if (elementAt != null) {
            com.intellij.psi.PsiNamedElement ancestor = ToolUtils.findNearestNamedAncestor(elementAt);
            if (ancestor != null) declarations.add(ancestor);
        }
    }

    private static void addResolved(PsiReference ref, List<PsiElement> declarations) {
        if (ref instanceof PsiPolyVariantReference poly) {
            for (ResolveResult rr : poly.multiResolve(false)) {
                PsiElement el = rr.getElement();
                if (el != null) declarations.add(el);
            }
        } else {
            PsiElement resolved = ref.resolve();
            if (resolved != null) declarations.add(resolved);
        }
    }

    private static final int MAX_PARENT_WALK = 5;
}
