package com.github.catatafishen.ideagentforcopilot.psi.tools.refactoring;

import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;

/**
 * Gets Javadoc or KDoc for a symbol by fully-qualified name.
 */
@SuppressWarnings("java:S112")
public final class GetDocumentationTool extends RefactoringTool {

    private static final Logger LOG = Logger.getInstance(GetDocumentationTool.class);
    private static final String PARAM_SYMBOL = "symbol";
    private static final String GET_INSTANCE_METHOD = "getInstance";

    public GetDocumentationTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_documentation";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Documentation";
    }

    @Override
    public @NotNull String description() {
        return "Get Javadoc or KDoc for a symbol by fully-qualified name";
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
            {PARAM_SYMBOL, TYPE_STRING, "Fully qualified symbol name (e.g. java.util.List)"}
        }, PARAM_SYMBOL);
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
                GlobalSearchScope scope = GlobalSearchScope.allScope(project);
                String[] parts = splitSymbolParts(symbol);
                String className = parts[0];
                String memberName = parts[1];

                PsiElement resolvedClass = resolveJavaClass(className, scope);
                if (resolvedClass == null && memberName == null) {
                    return "Symbol not found: " + symbol + ". Use a fully qualified name (e.g. java.util.List).";
                }
                if (resolvedClass == null) {
                    return "Symbol not found: " + symbol + ". Use a fully qualified name (e.g. java.util.List).";
                }

                PsiElement element = resolvedClass;
                if (memberName != null) {
                    PsiElement member = findMemberInClass(resolvedClass, memberName);
                    if (member != null) element = member;
                }

                return generateDocumentation(element, symbol);
            } catch (Exception e) {
                LOG.warn("get_documentation error", e);
                return "Error retrieving documentation: " + e.getMessage();
            }
        });
    }

    private PsiElement resolveJavaClass(String className, GlobalSearchScope scope) {
        try {
            Class<?> javaPsiFacadeClass = Class.forName("com.intellij.psi.JavaPsiFacade");
            Object facade = javaPsiFacadeClass.getMethod(GET_INSTANCE_METHOD, Project.class).invoke(null, project);
            return (PsiElement) javaPsiFacadeClass
                .getMethod("findClass", String.class, GlobalSearchScope.class)
                .invoke(facade, className, scope);
        } catch (Exception e) {
            LOG.warn("resolveJavaClass error", e);
            return null;
        }
    }

    private PsiElement findMemberInClass(PsiElement resolvedClass, String memberName) {
        for (PsiElement child : resolvedClass.getChildren()) {
            if (child instanceof PsiNamedElement named && memberName.equals(named.getName())) {
                return child;
            }
        }
        for (PsiElement child : resolvedClass.getChildren()) {
            if (child instanceof PsiNamedElement) {
                for (PsiElement grandchild : child.getChildren()) {
                    if (grandchild instanceof PsiNamedElement named && memberName.equals(named.getName())) {
                        return grandchild;
                    }
                }
            }
        }
        return null;
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

        String elementText = element.getText();
        if (elementText.length() > 500) elementText = elementText.substring(0, 500) + "...";
        return "No documentation available for " + symbol + ". Element found:\n" + elementText;
    }
}
