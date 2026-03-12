package com.github.catatafishen.ideagentforcopilot.psi;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.UsageSearchContext;
import com.intellij.psi.search.searches.ReferencesSearch;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Handles refactoring tool calls: refactor, go_to_declaration,
 * get_type_hierarchy, and get_documentation.
 */
@SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
public final class RefactoringTools extends AbstractToolHandler {

    private static final Logger LOG = Logger.getInstance(RefactoringTools.class);

    private static final String PARAM_SYMBOL = "symbol";
    private static final String FORMAT_LINES_SUFFIX = " lines)";
    private static final String GET_INSTANCE_METHOD = "getInstance";
    private static final String TEST_TYPE_CLASS = "class";

    /**
     * Functional interface for class resolution, used by RunConfigurationService.
     */
    public interface ClassResolver {
        @SuppressWarnings("unused")
            // parameter used by implementations
        ClassInfo resolveClass(String className);
    }

    public record ClassInfo(String fqn, Module module) {
    }

    RefactoringTools(Project project) {
        super(project);
    }

    // ---- Class Resolution ----

    ClassInfo resolveClass(String className) {
        return ApplicationManager.getApplication().runReadAction((Computable<ClassInfo>) () -> {
            String searchName = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;
            List<ClassInfo> matches = new ArrayList<>();
            PsiSearchHelper.getInstance(project).processElementsWithWord(
                (element, offset) -> processClassCandidate(element, searchName, className, matches),
                GlobalSearchScope.projectScope(project),
                searchName,
                UsageSearchContext.IN_CODE,
                true
            );
            return matches.isEmpty() ? new ClassInfo(className, null) : matches.getFirst();
        });
    }

    @SuppressWarnings({"java:S3516", "SameReturnValue"}) // always returns true to continue PSI search
    private boolean processClassCandidate(PsiElement element, String searchName,
                                          String className, List<ClassInfo> matches) {
        String type = ToolUtils.classifyElement(element);
        if (!TEST_TYPE_CLASS.equals(type) || !(element instanceof PsiNamedElement named)
            || !searchName.equals(named.getName())) {
            return true;
        }
        try {
            var getQualifiedName = element.getClass().getMethod("getQualifiedName");
            String fqn = (String) getQualifiedName.invoke(element);
            if (fqn != null && (!className.contains(".") || fqn.equals(className))) {
                VirtualFile vf = element.getContainingFile().getVirtualFile();
                Module mod = vf != null
                    ? ProjectFileIndex.getInstance(project).getModuleForFile(vf) : null;
                matches.add(new ClassInfo(fqn, mod));
            }
        } catch (NoSuchMethodException | java.lang.reflect.InvocationTargetException
                 | IllegalAccessException ignored) {
            // Reflection method not available or failed
        }
        return true;
    }

    // ---- get_documentation ----

    public String getDocumentation(JsonObject args) {
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

    private String[] splitSymbolParts(String symbol) {
        try {
            Class<?> javaPsiFacadeClass = Class.forName("com.intellij.psi.JavaPsiFacade");
            Object facade = javaPsiFacadeClass.getMethod(GET_INSTANCE_METHOD, Project.class).invoke(null, project);
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);

            PsiElement resolvedClass = (PsiElement) javaPsiFacadeClass
                .getMethod("findClass", String.class, GlobalSearchScope.class)
                .invoke(facade, symbol, scope);

            if (resolvedClass != null) {
                return new String[]{symbol, null};
            }
        } catch (Exception ignored) {
            // Reflection errors handled by caller
        }

        int lastDot = symbol.lastIndexOf('.');
        if (lastDot > 0) {
            return new String[]{symbol.substring(0, lastDot), symbol.substring(lastDot + 1)};
        }
        return new String[]{symbol, null};
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
        // Direct children first
        for (PsiElement child : resolvedClass.getChildren()) {
            if (child instanceof PsiNamedElement named && memberName.equals(named.getName())) {
                return child;
            }
        }
        // Try inner classes
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
            return truncateOutput("Documentation for " + symbol + ":\n\n" + text);
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
        // Fallback: try to get raw PsiDocComment for Java elements
        try {
            Class<?> docOwnerClass = Class.forName("com.intellij.psi.PsiDocCommentOwner");
            if (docOwnerClass.isInstance(element)) {
                Object docComment = docOwnerClass.getMethod("getDocComment").invoke(element);
                if (docComment != null) {
                    String text = ((PsiElement) docComment).getText();
                    // Clean up the comment markers
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

        // Last resort: show element text signature
        String elementText = element.getText();
        if (elementText.length() > 500) elementText = elementText.substring(0, 500) + "...";
        return "No documentation available for " + symbol + ". Element found:\n" + elementText;
    }

    // ---- refactor ----

    public String refactor(JsonObject args) throws Exception {
        if (!args.has("operation") || !args.has("file") || !args.has(PARAM_SYMBOL)) {
            return "Error: 'operation', 'file', and 'symbol' parameters are required";
        }
        String operation = args.get("operation").getAsString();
        String pathStr = args.get("file").getAsString();
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        int targetLine = args.has("line") ? args.get("line").getAsInt() : -1;
        String newName = args.has("new_name") ? args.get("new_name").getAsString() : null;

        if ("rename".equals(operation) && (newName == null || newName.isEmpty())) {
            return "Error: 'new_name' is required for rename operation";
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                String result = resolveAndRefactor(operation, pathStr, symbolName, targetLine, newName);
                resultFuture.complete(result);
            } catch (Exception e) {
                resultFuture.complete(ToolUtils.ERROR_PREFIX + e.getMessage());
            }
        });

        String result = resultFuture.get(30, TimeUnit.SECONDS);
        if (!result.startsWith("Error")) {
            FileTools.followFileIfEnabled(project, pathStr, Math.max(targetLine, 1), Math.max(targetLine, 1),
                FileTools.HIGHLIGHT_EDIT, FileTools.agentLabel(project) + " refactored");
        }
        return result;
    }

    private String resolveAndRefactor(String operation, String pathStr, String symbolName,
                                      int targetLine, String newName) {
        VirtualFile vf = resolveVirtualFile(pathStr);
        if (vf == null) return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;

        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_CANNOT_PARSE + pathStr;

        Document document = FileDocumentManager.getInstance().getDocument(vf);
        PsiNamedElement targetElement = findNamedElement(psiFile, document, symbolName, targetLine);
        if (targetElement == null) {
            return "Error: Symbol '" + symbolName + "' not found in " + pathStr +
                (targetLine > 0 ? " at line " + targetLine : "") +
                ". Use search_symbols to find the correct name and location.";
        }

        String[] result = new String[1];
        ApplicationManager.getApplication().runWriteAction(() -> {
            try {
                result[0] = executeRefactoring(operation, targetElement, symbolName, newName, pathStr);
            } catch (Exception e) {
                LOG.warn("Refactoring error", e);
                result[0] = "Error during refactoring: " + e.getMessage();
            }
        });
        return result[0];
    }

    private String executeRefactoring(String operation, PsiNamedElement targetElement,
                                      String symbolName, String newName, String pathStr) {
        return switch (operation) {
            case "rename" -> performRename(targetElement, symbolName, newName, pathStr);
            case "safe_delete" -> performSafeDelete(targetElement, symbolName, pathStr);
            case "inline" -> "Error: 'inline' refactoring is not yet supported via this tool. " +
                "Use edit_text to manually inline the code.";
            case "extract_method" -> "Error: 'extract_method' requires a code selection range " +
                "which is not well-suited for tool-based invocation. " +
                "Use edit_text to manually extract the method.";
            default -> "Error: Unknown operation '" + operation + "'. Supported: rename, safe_delete";
        };
    }

    private String performRename(PsiNamedElement targetElement, String symbolName,
                                 String newName, String pathStr) {
        var refs = ReferencesSearch.search(targetElement, GlobalSearchScope.projectScope(project)).findAll();
        int refCount = refs.size();

        var factory = com.intellij.refactoring.RefactoringFactory.getInstance(project);
        var rename = factory.createRename(targetElement, newName);
        rename.setSearchInComments(true);
        rename.setSearchInNonJavaFiles(true);
        com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
            project,
            () -> {
                var usages = rename.findUsages();
                rename.doRefactoring(usages);
            },
            "Rename " + symbolName + " to " + newName,
            null
        );

        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
        FileDocumentManager.getInstance().saveAllDocuments();

        return "Renamed '" + symbolName + "' to '" + newName + "'\n" +
            "  Updated " + refCount + " references across the project.\n" +
            "  File: " + pathStr;
    }

    private String performSafeDelete(PsiNamedElement targetElement, String symbolName, String pathStr) {
        var refs = ReferencesSearch.search(targetElement, GlobalSearchScope.projectScope(project)).findAll();

        if (!refs.isEmpty()) {
            return formatUsageReport(symbolName, refs);
        }

        com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
            project,
            targetElement::delete,
            "Safe Delete " + symbolName,
            null
        );
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments();
        FileDocumentManager.getInstance().saveAllDocuments();

        return "Safely deleted '" + symbolName + "' (no usages found).\n  File: " + pathStr;
    }

    private String formatUsageReport(String symbolName, Collection<PsiReference> refs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Cannot safely delete '").append(symbolName)
            .append("' — it has ").append(refs.size()).append(" usages:\n");
        int shown = 0;
        for (var ref : refs) {
            if (shown++ >= 10) {
                sb.append("  ... and ").append(refs.size() - 10).append(" more\n");
                break;
            }
            PsiFile refFile = ref.getElement().getContainingFile();
            int line = -1;
            if (refFile != null) {
                Document refDoc = FileDocumentManager.getInstance()
                    .getDocument(refFile.getVirtualFile());
                if (refDoc != null) {
                    line = refDoc.getLineNumber(ref.getElement().getTextOffset()) + 1;
                }
            }
            sb.append("  ").append(refFile != null ? refFile.getName() : "?")
                .append(":").append(line).append("\n");
        }
        return sb.toString();
    }

    /**
     * Find a PsiNamedElement by name, optionally constrained to a specific line.
     */
    private PsiNamedElement findNamedElement(PsiFile psiFile, Document document, String name, int targetLine) {
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

    // ---- go_to_declaration ----

    public String goToDeclaration(JsonObject args) {
        if (!args.has("file") || !args.has(PARAM_SYMBOL) || !args.has("line")) {
            return "Error: 'file', 'symbol', and 'line' parameters are required";
        }
        String pathStr = args.get("file").getAsString();
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        int targetLine = args.get("line").getAsInt();

        // [0] = declPath, [1] = declLine (as string)
        String[] declInfo = new String[2];

        String result = ApplicationManager.getApplication().runReadAction(
            (Computable<String>) () -> findAndFormatDeclaration(pathStr, targetLine, symbolName, declInfo));

        if (declInfo[0] != null && declInfo[1] != null) {
            int declLine = Integer.parseInt(declInfo[1]);
            FileTools.followFileIfEnabled(project, declInfo[0], declLine, declLine,
                FileTools.HIGHLIGHT_READ, FileTools.agentLabel(project) + " found declaration");
        }
        return result;
    }

    /**
     * Must be called inside a read action.
     */
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

    private void captureDeclInfo(PsiElement firstDecl, String[] declInfo) {
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
        return element.getText().equals(symbolName)
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
        String lineText = document.getText(new com.intellij.openapi.util.TextRange(lineStartOffset, lineEndOffset));
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

    private String formatDeclarationResults(List<PsiElement> declarations, String symbolName) {
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

    private String resolveDeclPath(VirtualFile declVf, String basePath) {
        if (declVf != null && basePath != null) return relativize(basePath, declVf.getPath());
        if (declVf != null) return declVf.getName();
        return "?";
    }

    private void appendDeclarationContext(StringBuilder sb, Document declDoc, int declLine) {
        if (declDoc == null || declLine <= 0) return;
        int startLine = Math.max(0, declLine - 3);
        int endLine = Math.min(declDoc.getLineCount() - 1, declLine + 2);
        sb.append("  Context:\n");
        for (int l = startLine; l <= endLine; l++) {
            int ls = declDoc.getLineStartOffset(l);
            int le = declDoc.getLineEndOffset(l);
            String lineContent = declDoc.getText(new com.intellij.openapi.util.TextRange(ls, le));
            sb.append(l == declLine - 1 ? "  → " : "    ")
                .append(l + 1).append(": ").append(lineContent).append("\n");
        }
    }

    // ---- get_type_hierarchy (delegates to RefactoringJavaSupport) ----

    public String getTypeHierarchyWrapper(JsonObject args) {
        if (!args.has(PARAM_SYMBOL)) return "Error: 'symbol' parameter is required";
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        String direction = args.has("direction") ? args.get("direction").getAsString() : "both";

        return ApplicationManager.getApplication().runReadAction((Computable<String>) () ->
            com.github.catatafishen.ideagentforcopilot.psi.java.RefactoringJavaSupport.getTypeHierarchy(project, symbolName, direction)
        );
    }

    // ---- Utilities ----

    public String findImplementationsWrapper(JsonObject args) {
        if (!args.has(PARAM_SYMBOL)) return "Error: 'symbol' parameter is required";
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        String filePath = args.has("file") ? args.get("file").getAsString() : null;
        int line = args.has("line") ? args.get("line").getAsInt() : 0;

        String result = ApplicationManager.getApplication().runReadAction((Computable<String>) () ->
            com.github.catatafishen.ideagentforcopilot.psi.java.RefactoringJavaSupport
                .findImplementations(project, symbolName, filePath, line)
        );
        return truncateOutput(result);
    }

    public String getCallHierarchyWrapper(JsonObject args) {
        if (!args.has(PARAM_SYMBOL) || !args.has("file") || !args.has("line")) {
            return "Error: 'symbol', 'file', and 'line' parameters are required";
        }
        String methodName = args.get(PARAM_SYMBOL).getAsString();
        String filePath = args.get("file").getAsString();
        int line = args.get("line").getAsInt();

        String result = ApplicationManager.getApplication().runReadAction((Computable<String>) () ->
            com.github.catatafishen.ideagentforcopilot.psi.java.RefactoringJavaSupport
                .getCallHierarchy(project, methodName, filePath, line)
        );
        return truncateOutput(result);
    }

    private static String truncateOutput(String output) {
        if (output.length() <= 8000) return output;
        return "...(truncated)\n" + output.substring(output.length() - 8000);
    }

}
