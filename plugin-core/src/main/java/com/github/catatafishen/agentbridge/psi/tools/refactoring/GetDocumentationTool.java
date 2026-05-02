package com.github.catatafishen.agentbridge.psi.tools.refactoring;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.FqnResolver;
import com.github.catatafishen.agentbridge.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

/**
 * Gets Javadoc or KDoc for a symbol by fully-qualified name.
 */
@SuppressWarnings("java:S112")
public final class GetDocumentationTool extends RefactoringTool {

    private static final Logger LOG = Logger.getInstance(GetDocumentationTool.class);
    private static final String PARAM_SYMBOL = "symbol";

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
            Param.required(PARAM_SYMBOL, TYPE_STRING, "Fully qualified symbol name (e.g. java.util.List)")
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

        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            try {
                PsiElement element = FqnResolver.resolve(symbol, project);
                if (element == null) {
                    return "Symbol not found: " + symbol + ". Use a fully qualified name (e.g. java.util.List).";
                }
                return generateDocumentation(element, symbol);
            } catch (Exception e) {
                LOG.warn("get_documentation error", e);
                return "Error retrieving documentation: " + e.getMessage();
            }
        });
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
