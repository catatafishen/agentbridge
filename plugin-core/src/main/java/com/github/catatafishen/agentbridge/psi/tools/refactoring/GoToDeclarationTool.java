package com.github.catatafishen.agentbridge.psi.tools.refactoring;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.FqnResolver;
import com.github.catatafishen.agentbridge.psi.tools.file.FileTool;
import com.github.catatafishen.agentbridge.ui.renderers.GoToDeclarationRenderer;
import com.google.gson.JsonObject;
import com.intellij.codeInsight.TargetElementUtil;
import com.intellij.codeInsight.navigation.action.GotoDeclarationUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Navigates to the declaration of a symbol at a given file and line.
 */
@SuppressWarnings("java:S112")
public final class GoToDeclarationTool extends RefactoringTool {

    private static final String PARAM_SYMBOL = "symbol";
    private static final String FORMAT_LINES_SUFFIX = " lines)";

    private final DeclarationNavigator declarationNavigator;

    public GoToDeclarationTool(Project project) {
        this(project, (sourceFile, sourceOffset) ->
            new IdeDeclarationNavigator(project).navigate(sourceFile, sourceOffset));
    }

    GoToDeclarationTool(Project project, DeclarationNavigator declarationNavigator) {
        super(project);
        this.declarationNavigator = declarationNavigator;
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
            Param.optional("path", TYPE_STRING, "Path to the file containing the symbol usage. "
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
        String pathStr = readPathParam(args);
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
        String result = findAndFormatDeclaration(pathStr, targetLine, symbolName, declInfo);

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

        Document document = ApplicationManager.getApplication().runReadAction(
            (Computable<Document>) () -> FileDocumentManager.getInstance().getDocument(vf));
        if (document == null) return "Error: Cannot get document for: " + pathStr;

        if (targetLine < 1 || targetLine > document.getLineCount()) {
            return "Error: Line " + targetLine + " is out of bounds (file has " +
                document.getLineCount() + FORMAT_LINES_SUFFIX;
        }

        IdeDeclarationNavigator.EditorState previousEditor =
            IdeDeclarationNavigator.captureEditorState(project);
        try {
            int[] nativeOffset = {-1};
            Editor editor = openNavigationEditor(document, vf);
            if (editor == null) return "Error: Cannot open editor for: " + pathStr;
            String psiResult = ApplicationManager.getApplication().runReadAction(
                (Computable<String>) () -> {
                    PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
                    if (psiFile == null) {
                        return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_CANNOT_PARSE + pathStr;
                    }
                    return resolveAndFormatDeclaration(
                        psiFile, document, editor, targetLine, symbolName, declInfo, nativeOffset);
                });

            if (psiResult != null) return psiResult;
            if (nativeOffset[0] >= 0) {
                IdeDeclarationNavigator.Location location =
                    declarationNavigator.navigate(vf, nativeOffset[0]);
                if (location != null) {
                    String nativeResult =
                        formatNavigatedDeclaration(location, symbolName, declInfo);
                    if (nativeResult != null) return nativeResult;
                }
            }
            return err("Could not resolve declaration for '" + symbolName
                + "' at line " + targetLine + " in " + pathStr
                + ". The symbol may be unresolved or from an unindexed library.");
        } finally {
            IdeDeclarationNavigator.restoreEditorState(project, previousEditor);
        }
    }

    private @Nullable String resolveAndFormatDeclaration(
        PsiFile psiFile, Document document, Editor editor, int targetLine, String symbolName,
        String[] declInfo, int[] nativeOffset) {
        int lineStartOffset = document.getLineStartOffset(targetLine - 1);
        int lineEndOffset = document.getLineEndOffset(targetLine - 1);

        List<PsiElement> declarations = resolveDeclarationsOnLine(
            psiFile, document, editor, lineStartOffset, lineEndOffset, symbolName, nativeOffset);
        if (declarations.isEmpty()) return null;

        captureDeclInfo(declarations.getFirst(), declInfo);
        return formatDeclarationResults(declarations, symbolName);
    }

    private @Nullable String formatNavigatedDeclaration(
        IdeDeclarationNavigator.Location location, String symbolName, String[] declInfo) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            VirtualFile declarationFile = location.file();
            Document declarationDocument =
                FileDocumentManager.getInstance().getDocument(declarationFile);
            if (declarationDocument == null) return null;

            int offset = Math.max(0,
                Math.min(location.offset(), declarationDocument.getTextLength()));
            int declarationLine = declarationDocument.getLineNumber(offset) + 1;
            String basePath = project.getBasePath();
            String declarationPath = resolveDeclPath(declarationFile, basePath);

            declInfo[0] = basePath == null
                ? declarationFile.getPath()
                : relativize(basePath, declarationFile.getPath());
            declInfo[1] = String.valueOf(declarationLine);

            StringBuilder result = new StringBuilder();
            result.append("Declaration of '").append(symbolName).append("':\n\n");
            result.append("  File: ").append(declarationPath).append("\n");
            result.append("  Line: ").append(declarationLine).append("\n");
            appendDeclarationContext(result, declarationDocument, declarationLine);
            result.append("\n");
            return result.toString();
        });
    }

    private @Nullable Editor openNavigationEditor(Document document, VirtualFile vf) {
        Editor[] editor = new Editor[1];
        EdtUtil.invokeAndWait(() -> {
            PsiDocumentManager.getInstance(project).commitDocument(document);
            editor[0] = FileEditorManager.getInstance(project).openTextEditor(
                new OpenFileDescriptor(project, vf, 0), false);
        });
        return editor[0];
    }

    /**
     * Finds declarations for {@code symbolName} occurring anywhere on the target line.
     * <p>
     * Tries the platform's non-UI declaration APIs before the caller invokes the live
     * {@code GotoDeclaration} action:
     *
     * <ol>
     *   <li>Ask registered frontend declaration providers through {@link GotoDeclarationUtil}.</li>
     *   <li>Use {@link TargetElementUtil#findTargetElement(Editor, int, int)} with the platform's
     *       accepted-target flags.</li>
     *   <li>Ask {@link PsiFile#findReferenceAt(int)} and handle polyvariant references via
     *       {@link PsiPolyVariantReference#multiResolve}.</li>
     *   <li>If no reference resolves, walk up the PSI tree because some language plugins attach
     *       the reference to a parent expression rather than the leaf identifier.</li>
     *   <li>Finally, accept a named ancestor only when its name equals the requested symbol. This
     *       covers a caret on the declaration itself without returning an enclosing method or
     *       class for an unresolved usage.</li>
     * </ol>
     *
     * <p>If all of these return nothing, {@link #findAndFormatDeclaration} invokes the IDE action
     * in a real project editor. That path covers backend-delegated products such as CLion Nova.
     */
    private List<PsiElement> resolveDeclarationsOnLine(
        PsiFile psiFile, Document document, Editor editor, int lineStartOffset,
        int lineEndOffset, String symbolName, int[] nativeOffset) {
        List<PsiElement> declarations = new ArrayList<>();
        if (symbolName.isEmpty()) return declarations;
        String lineText = document.getText(new TextRange(lineStartOffset, lineEndOffset));

        int searchFrom = 0;
        while (searchFrom <= lineText.length() - symbolName.length()) {
            int symIdx = lineText.indexOf(symbolName, searchFrom);
            if (symIdx < 0) break;
            if (isWholeIdentifierMatch(lineText, symIdx, symbolName.length())) {
                int rawOffset = lineStartOffset + symIdx;
                if (nativeOffset[0] < 0) nativeOffset[0] = rawOffset;
                PsiElement[] nativeTargets = findNativeDeclarationTargets(
                    psiFile, editor, rawOffset);
                if (nativeTargets.length > 0) {
                    declarations.addAll(List.of(nativeTargets));
                    return declarations;
                }

                int offset = TargetElementUtil.adjustOffset(psiFile, document, rawOffset);
                resolveAtOffset(psiFile, offset, symbolName, declarations);
                if (!declarations.isEmpty()) return declarations;
            }
            searchFrom = symIdx + symbolName.length();
        }
        return declarations;
    }

    private static PsiElement[] findNativeDeclarationTargets(
        PsiFile psiFile, Editor editor, int offset) {
        PsiElement[] providerTargets =
            GotoDeclarationUtil.findTargetElementsFromProviders(editor, offset, psiFile);
        if (providerTargets != null && providerTargets.length > 0) {
            return providerTargets;
        }

        int platformFlags = TargetElementUtil.getInstance().getAllAccepted()
            & ~TargetElementUtil.ELEMENT_NAME_ACCEPTED;
        PsiElement platformTarget = TargetElementUtil.getInstance()
            .findTargetElement(editor, platformFlags, offset);
        return platformTarget == null
            ? PsiElement.EMPTY_ARRAY
            : new PsiElement[]{platformTarget};
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
     * language plugins attach the reference to a parent node rather than the leaf identifier).
     * For the "go to declaration on a declaration" case, the nearest named ancestor is accepted
     * only when its name equals {@code symbolName}.
     */
    private static void resolveAtOffset(
        PsiFile psiFile, int offset, String symbolName, List<PsiElement> declarations) {
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
            if (ancestor != null && symbolName.equals(ancestor.getName())) {
                declarations.add(ancestor);
            }
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

    @FunctionalInterface
    interface DeclarationNavigator {
        @Nullable IdeDeclarationNavigator.Location navigate(
            @NotNull VirtualFile sourceFile, int sourceOffset);
    }

    private static final int MAX_PARENT_WALK = 5;
}
