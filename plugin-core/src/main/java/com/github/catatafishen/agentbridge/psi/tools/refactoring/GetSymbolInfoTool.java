package com.github.catatafishen.agentbridge.psi.tools.refactoring;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNamedElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Returns symbol information (documentation / declaration) at a file+line+column position.
 */
@SuppressWarnings("java:S112")
public final class GetSymbolInfoTool extends RefactoringTool {

    private static final Logger LOG = Logger.getInstance(GetSymbolInfoTool.class);
    private static final String PARAM_FILE = "file";
    private static final String PARAM_LINE = "line";
    private static final String PARAM_COLUMN = "column";

    public GetSymbolInfoTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_symbol_info";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Symbol Info";
    }

    @Override
    public @NotNull String description() {
        return "Get documentation and declaration for the symbol at a given file position. "
            + "Provides the same info as IntelliJ Quick Documentation. "
            + "Use this when you have a file+line location but not the fully-qualified name.";
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
            {PARAM_FILE, TYPE_STRING, "File path (absolute or project-relative)"},
            {PARAM_LINE, TYPE_INTEGER, "1-based line number"},
            {PARAM_COLUMN, TYPE_INTEGER, "1-based column number (optional, defaults to first non-whitespace on the line)"},
        }, PARAM_FILE, PARAM_LINE);
    }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String filePath = args.get(PARAM_FILE).getAsString();
        int line = args.get(PARAM_LINE).getAsInt();

        VirtualFile vf = resolveVirtualFile(filePath);
        if (vf == null) return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + filePath;

        // Cast required: disambiguates Computable<T> vs ThrowableComputable<T,E> overloads.
        // The IDE falsely reports this as redundant; Gradle fails without it.
        Computable<String> action = () -> {
            try {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
                if (psiFile == null) return "Error: cannot parse " + filePath;

                Document doc = FileDocumentManager.getInstance().getDocument(vf);
                if (doc == null) return "Error: cannot open document " + filePath;

                int lineCount = doc.getLineCount();
                if (line < 1 || line > lineCount)
                    return "Error: line " + line + " out of range (file has " + lineCount + " lines)";

                int lineStart = doc.getLineStartOffset(line - 1);
                int lineEnd = doc.getLineEndOffset(line - 1);
                int col = resolveColumn(doc, lineStart, lineEnd, args);
                int offset = Math.min(lineStart + col, doc.getTextLength() - 1);

                PsiElement element = psiFile.findElementAt(offset);
                PsiNamedElement named = findNamedAncestor(element);
                if (named == null) {
                    int snippetEnd = Math.min(lineEnd, lineStart + 120);
                    String snippet = doc.getText(new com.intellij.openapi.util.TextRange(lineStart, snippetEnd)).trim();
                    return "No named symbol found at " + filePath + ":" + line + ". Line content: " + snippet;
                }

                return describeElement(named);
            } catch (Exception e) {
                LOG.warn("get_symbol_info error", e);
                return "Error: " + e.getMessage();
            }
        };
        return ApplicationManager.getApplication().runReadAction(action);
    }

    private static int resolveColumn(Document doc, int lineStart, int lineEnd, JsonObject args) {
        if (args.has(PARAM_COLUMN)) {
            return Math.max(0, args.get(PARAM_COLUMN).getAsInt() - 1);
        }
        String lineText = doc.getText(new com.intellij.openapi.util.TextRange(lineStart, lineEnd));
        int col = 0;
        while (col < lineText.length() && Character.isWhitespace(lineText.charAt(col))) col++;
        return col;
    }

    @Nullable
    private static PsiNamedElement findNamedAncestor(@Nullable PsiElement element) {
        PsiElement current = element;
        for (int depth = 0; depth < 10 && current != null; depth++) {
            if (current instanceof PsiNamedElement named && named.getName() != null) return named;
            current = current.getParent();
        }
        return null;
    }

    private String describeElement(@NotNull PsiNamedElement element) {
        StringBuilder sb = new StringBuilder();
        sb.append("Symbol: ").append(element.getName()).append('\n');
        sb.append("Type: ").append(element.getClass().getSimpleName()).append('\n');

        // Location (file + line)
        var containingFile = element.getContainingFile();
        if (containingFile != null && containingFile.getVirtualFile() != null) {
            var vf = containingFile.getVirtualFile();
            String projectBase = project.getBasePath();
            String location = projectBase != null ? relativize(projectBase, vf.getPath()) : vf.getPath();
            Document locationDoc = FileDocumentManager.getInstance().getDocument(vf);
            if (locationDoc != null) {
                int line = locationDoc.getLineNumber(element.getTextOffset()) + 1;
                sb.append("Location: ").append(location).append(':').append(line).append('\n');
            }
        }

        // Try to get documentation
        try {
            Class<?> langDocClass = Class.forName("com.intellij.lang.LanguageDocumentation");
            Object langDocInstance = langDocClass.getField("INSTANCE").get(null);
            Object provider = langDocClass.getMethod("forLanguage", com.intellij.lang.Language.class)
                .invoke(langDocInstance, element.getLanguage());
            if (provider != null) {
                String doc = (String) provider.getClass()
                    .getMethod("generateDoc", PsiElement.class, PsiElement.class)
                    .invoke(provider, element, null);
                if (doc != null && !doc.isEmpty()) {
                    String text = doc.replaceAll("<[^>]+>", "")
                        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                        .replace("&amp;", "&").replaceAll("&#\\d+;", "")
                        .replaceAll("\n{3,}", "\n\n").trim();
                    sb.append("\nDocumentation:\n").append(text);
                    return sb.toString();
                }
            }
        } catch (Exception ignored) {
            // fall through to text excerpt
        }

        // Fallback: show element text
        int textLen = element.getTextLength();
        String text = textLen > 400 ? element.getText().substring(0, 400) + "..." : element.getText();
        sb.append("\nDeclaration:\n").append(text);
        return sb.toString();
    }
}
