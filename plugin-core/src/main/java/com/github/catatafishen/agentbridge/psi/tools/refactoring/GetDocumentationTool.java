package com.github.catatafishen.agentbridge.psi.tools.refactoring;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.cpp.CppNovaPsiSupport;
import com.github.catatafishen.agentbridge.psi.tools.FqnResolver;
import com.github.catatafishen.agentbridge.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

public final class GetDocumentationTool extends RefactoringTool {

    private static final Logger LOG = Logger.getInstance(GetDocumentationTool.class);
    private static final String PARAM_SYMBOL = "symbol";
    private static final String PARAM_PATH = "path";
    private static final String PARAM_LINE = "line";

    public GetDocumentationTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_documentation";
    }

    @Override
    public boolean requiresIndex() {
        return true;
    }

    @Override
    public @NotNull String displayName() {
        return "Get Documentation";
    }

    @Override
    public @NotNull String description() {
        return "Get Javadoc or KDoc for a symbol by fully-qualified name (e.g. java.util.List). " +
            "Use get_symbol_info instead when you have a file+line position but not the FQN.";
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
            Param.required(PARAM_SYMBOL, TYPE_STRING,
                "Fully qualified symbol name (e.g. java.util.List, com.google.gson.Gson.fromJson). " +
                    "For non-Java symbols, provide 'file' and 'line' for language-agnostic resolution."),
            Param.optional(PARAM_PATH, TYPE_STRING,
                "File path (absolute or project-relative). When provided together with 'line', " +
                    "resolves the symbol by position instead of FQN — works for any language (C/C++, Python, Go, etc.)."),
            Param.optional(PARAM_LINE, TYPE_INTEGER,
                "1-based line number. Required when 'file' is provided.")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String symbol = args.has(PARAM_SYMBOL) ? args.get(PARAM_SYMBOL).getAsString() : "";
        if (symbol.isEmpty())
            return "Error: 'symbol' parameter required (e.g. java.util.List, com.google.gson.Gson.fromJson)";

        // Validate: file and line must be provided together.
        if (args.has(PARAM_PATH) != args.has(PARAM_LINE)) {
            return ToolUtils.ERROR_PREFIX + "'file' and 'line' must be provided together";
        }

        // Position-based path: language-agnostic, same approach as go_to_declaration / find_implementations.
        // The IDE's reference resolution handles C/C++, Python, Go, and every other language
        // without any knowledge of language-specific FQN separators (:: vs . vs /).
        if (args.has(PARAM_PATH) && args.has(PARAM_LINE)) {
            String filePath = args.get(PARAM_PATH).getAsString();
            int line = args.get(PARAM_LINE).getAsInt();
            // Resolve the VirtualFile before entering the read action — consistent with
            // all other tools (FindSuperMethodsTool, GoToDeclarationTool, etc.) that call
            // resolveVirtualFile outside the read-action lambda.
            com.intellij.openapi.vfs.VirtualFile resolved = resolveVirtualFile(filePath);
            if (resolved == null) resolved = ToolUtils.findFileInProjectContent(project, filePath);
            if (resolved == null) return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + filePath;
            final com.intellij.openapi.vfs.VirtualFile vf = resolved;
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                try {
                    com.intellij.psi.PsiFile psiFile =
                        com.intellij.psi.PsiManager.getInstance(project).findFile(vf);
                    if (psiFile == null)
                        return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_CANNOT_PARSE + filePath;
                    com.intellij.openapi.editor.Document doc =
                        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
                    if (doc == null || line < 1 || line > doc.getLineCount())
                        return ToolUtils.ERROR_PREFIX + "Line out of range: " + line;
                    int lineStart = doc.getLineStartOffset(line - 1);
                    int lineEnd = doc.getLineEndOffset(line - 1);
                    ToolUtils.LineContext ctx = new ToolUtils.LineContext(psiFile, lineStart, lineEnd);
                    String shortName = FqnResolver.shortNameOf(symbol);
                    com.intellij.psi.PsiNameIdentifierOwner element =
                        ToolUtils.resolveNamedElement(ctx, shortName);
                    if (element != null) return generateDocumentation(element, symbol);

                    // CLion Nova's lazy C++ parser produces no PsiNameIdentifierOwner for
                    // declarations, so resolveNamedElement finds nothing for C/C++. Fall back to the
                    // node-type-based lookup shared with get_symbol_info / get_file_outline /
                    // search_symbols (issue #794).
                    int offset = firstNonWhitespaceOffset(doc, lineStart, lineEnd);
                    CppNovaPsiSupport.CppDeclaration cppDecl =
                        CppNovaPsiSupport.findEnclosingDeclaration(psiFile.findElementAt(offset));
                    if (cppDecl != null) return generateDocumentation(cppDecl.node(), symbol);

                    return ToolUtils.ERROR_PREFIX + "No symbol '" + shortName
                        + "' found at " + filePath + ":" + line;
                } catch (Exception e) {
                    LOG.warn("get_documentation (position) error", e);
                    return ToolUtils.ERROR_PREFIX + e.getMessage();
                }
            });
        }

        // FQN-based path: Java/Kotlin only (JavaPsiFacade).
        // For non-Java languages without a file+line, direct FQN resolution is not possible
        // because FQN formats are language-specific and there is no platform-level FQN index.
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            try {
                PsiElement element = FqnResolver.resolve(symbol, project);
                if (element == null) {
                    return "Symbol not found: " + symbol + ". "
                        + "For Java/Kotlin use a fully qualified name (e.g. java.util.List). "
                        + "For other languages (C/C++, Python, Go, ...) also provide 'file' and 'line' "
                        + "for language-agnostic resolution.";
                }
                return generateDocumentation(element, symbol);
            } catch (Exception e) {
                LOG.warn("get_documentation error", e);
                return ToolUtils.ERROR_PREFIX + e.getMessage();
            }
        });
    }

    private static int firstNonWhitespaceOffset(com.intellij.openapi.editor.Document doc, int lineStart, int lineEnd) {
        String lineText = doc.getText(new com.intellij.openapi.util.TextRange(lineStart, lineEnd));
        int col = 0;
        while (col < lineText.length() && Character.isWhitespace(lineText.charAt(col))) col++;
        return Math.min(lineStart + col, doc.getTextLength() - 1);
    }

    private String generateDocumentation(PsiElement element, String symbol) {
        try {
            Class<?> langDocClass = Class.forName("com.intellij.lang.LanguageDocumentation");
            Object langDocInstance = langDocClass.getField("INSTANCE").get(null);
            Object provider = langDocClass.getMethod("forLanguage", com.intellij.lang.Language.class)
                .invoke(langDocInstance, element.getLanguage());

            if (provider == null) {
                return extractDocComment(element, symbol);
            }

            String doc = (String) provider.getClass().getMethod("generateDoc", PsiElement.class, PsiElement.class)
                .invoke(provider, element, null);

            if (doc == null || doc.isEmpty()) {
                return extractDocComment(element, symbol);
            }

            String text = stripHtmlForDocumentation(doc);
            return ToolUtils.truncateOutput("Documentation for " + symbol + ":\n\n" + text);
        } catch (Exception e) {
            LOG.warn("generateDocumentation error", e);
            return extractDocComment(element, symbol);
        }
    }

    private static String stripHtmlForDocumentation(String doc) {
        return doc.replaceAll("<[^>]+>", "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replaceAll("&#\\d+;", "")
            .replaceAll("\n{3,}", "\n\n")
            .trim();
    }

    private String extractDocComment(PsiElement element, String symbol) {
        try {
            Class<?> docOwnerClass = Class.forName("com.intellij.psi.PsiDocCommentOwner");
            if (docOwnerClass.isInstance(element)) {
                Object docComment = docOwnerClass.getMethod("getDocComment").invoke(element);
                if (docComment != null) {
                    String text = ((PsiElement) docComment).getText();
                    text = text.replace("/**", "")
                        .replace("*/", "")
                        .replaceAll("(?m)^\\s*\\*\\s?", "")
                        .trim();
                    return "Documentation for " + symbol + ":\n\n" + text;
                }
            }
        } catch (ClassNotFoundException ignored) {
            // Not a Java environment
        } catch (Exception e) {
            LOG.warn("extractDocComment error", e);
        }

        int textLen = element.getTextLength();
        String elementText = textLen > 500 ? element.getText().substring(0, 500) + "..." : element.getText();
        return "No documentation available for " + symbol + ". Element found:\n" + elementText;
    }
}
