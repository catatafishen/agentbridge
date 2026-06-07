package com.github.catatafishen.agentbridge.psi.tools.refactoring;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.SearchResultRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.search.searches.SuperMethodsSearch;
import com.intellij.psi.util.MethodSignatureBackedByPsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class FindSuperMethodsTool extends RefactoringTool {

    private static final String PARAM_FILE = "file";
    private static final String PARAM_LINE = "line";
    private static final String PARAM_COLUMN = "column";

    private final boolean hasJava;

    public FindSuperMethodsTool(Project project, boolean hasJava) {
        super(project);
        this.hasJava = hasJava;
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.READ;
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SearchResultRenderer.INSTANCE;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_FILE, TYPE_STRING, "File path containing the overriding method"),
            Param.required(PARAM_LINE, TYPE_INTEGER, "1-based line number inside the method declaration or body"),
            Param.optional(PARAM_COLUMN, TYPE_INTEGER, "1-based column number. Defaults to the first non-whitespace character on the line", 1)
        );
    }

    @Override
    public @NotNull String displayName() {
        return "Find Super Methods";
    }

    @Override
    public boolean requiresIndex() {
        return true;
    }

    @Override
    public @NotNull String id() {
        return "find_super_methods";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull String description() {
        return "Find parent methods that the method at a file position overrides or implements. "
            + "Java/Kotlin lookup returns the inheritance chain with file locations and containing "
            + "class details via SuperMethodsSearch. In non-Java IDEs (CLion, PyCharm, GoLand, "
            + "WebStorm), falls back to the platform's FindSuperElementsHelper via reflection when "
            + "the Java module is available — returns programmatic results for any PSI it "
            + "recognises. Returns a clear 'not available' error when the helper class is not "
            + "loadable (genuinely-non-Java IDE).";
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        if (!args.has(PARAM_FILE) || !args.has(PARAM_LINE)) {
            return ToolUtils.ERROR_PREFIX + "'file' and 'line' parameters are required";
        }

        String filePath = args.get(PARAM_FILE).getAsString();
        int line = args.get(PARAM_LINE).getAsInt();
        int column = args.has(PARAM_COLUMN) ? args.get(PARAM_COLUMN).getAsInt() : 1;
        VirtualFile vf = resolveVirtualFile(filePath);
        if (vf == null) vf = refreshAndFindVirtualFile(filePath);
        VirtualFile resolvedFile = vf;

        Computable<String> computation = () -> {
            VirtualFile targetFile = resolvedFile != null ? resolvedFile : findProjectContentFile(filePath);
            if (targetFile == null) return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + filePath;
            if (hasJava) {
                return findSuperMethodsJava(targetFile, line, column);
            }
            return findSuperMethodsNonJava(targetFile, line, column);
        };
        return ApplicationManager.getApplication().runReadAction(computation);
    }

    private VirtualFile findProjectContentFile(String filePath) {
        String normalizedPath = filePath.replace('\\', '/');
        String basePath = project.getBasePath();
        VirtualFile[] match = {null};
        ProjectFileIndex.getInstance(project).iterateContent(vf -> {
            if (vf.isDirectory()) return true;
            if (!matchesPath(vf, normalizedPath, basePath)) return true;
            match[0] = vf;
            return false;
        });
        return match[0];
    }

    private boolean matchesPath(VirtualFile vf, String normalizedPath, String basePath) {
        String virtualPath = vf.getPath().replace('\\', '/');
        if (virtualPath.equals(normalizedPath)) return true;
        if (basePath == null) return false;
        return relativize(basePath, virtualPath).equals(stripLeadingSlash(normalizedPath));
    }

    private static String stripLeadingSlash(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private String findSuperMethodsJava(VirtualFile vf, int line, int column) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_CANNOT_PARSE + vf.getPath();
        Document document = FileDocumentManager.getInstance().getDocument(vf);
        if (document == null) return ToolUtils.ERROR_PREFIX + "Cannot read document: " + vf.getPath();
        if (line < 1 || line > document.getLineCount()) return ToolUtils.ERROR_PREFIX + "Line out of range: " + line;

        PsiMethod method = findMethodAt(psiFile, document, line, column);
        if (method == null) return ToolUtils.ERROR_PREFIX + "No method found at position";

        List<PsiMethod> superMethods = SuperMethodsSearch.search(method, null, true, false).findAll().stream()
            .map(MethodSignatureBackedByPsiMethod::getMethod)
            .sorted(Comparator.comparing(this::methodLocation))
            .toList();
        if (superMethods.isEmpty()) return "No super methods found for " + method.getName();

        StringBuilder sb = new StringBuilder();
        sb.append("Super methods for ").append(formatMethodLabel(method)).append(":\n");
        for (PsiMethod superMethod : superMethods) {
            sb.append(formatMethodEntry(superMethod)).append('\n');
        }
        return ToolUtils.truncateOutput(sb.toString().stripTrailing());
    }

    /**
     * Non-Java fallback path. Tries the platform's programmatic super-element APIs via
     * {@link ToolUtils#findSuperElementsViaPlatform}. The helper ships with the Java module —
     * when it is available it works for Java, Kotlin (via light classes), and any other PSI it
     * recognises. No editor, no navigation, no popup.
     * <p>
     * Since this tool is specifically <em>find_super_methods</em>, the resolved position needs
     * to be a method-like declaration, not a parameter or local variable. We walk every
     * enclosing {@link PsiNameIdentifierOwner} ancestor (innermost first) and try the platform
     * helper on each in turn; the first ancestor that yields non-empty supers wins. This
     * avoids the obvious failure mode of resolving the caret to a parameter's
     * {@code PsiParameter} (still a {@code PsiNameIdentifierOwner}) and reporting "no super
     * methods found for {@code <var>}" when the user pointed inside an overriding method body.
     * <p>
     * For genuinely-non-Java IDEs (no Java plugin at all) the helper returns {@code null} and
     * we report a clear "not available" error without any manual-action hint (an autonomous
     * agent can't press Ctrl+U).
     */
    private String findSuperMethodsNonJava(VirtualFile vf, int line, int column) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) return ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_CANNOT_PARSE + vf.getPath();
        Document document = FileDocumentManager.getInstance().getDocument(vf);
        if (document == null) return ToolUtils.ERROR_PREFIX + "Cannot read document: " + vf.getPath();
        if (line < 1 || line > document.getLineCount()) return ToolUtils.ERROR_PREFIX + "Line out of range: " + line;

        PsiNameIdentifierOwner innermost = findNamedAt(psiFile, document, line, column);
        if (innermost == null) {
            return ToolUtils.ERROR_PREFIX + "No named declaration found at " + vf.getPath() + ":" + line;
        }

        // Walk enclosing PsiNameIdentifierOwner ancestors (innermost first) and try the
        // platform helper on each. The first ancestor with non-empty supers is the one we
        // report. If none yield supers, fall back to the innermost element's name for the
        // "none found" / "not available" message.
        boolean anyHelperLoadable = false;
        PsiNameIdentifierOwner current = innermost;
        while (current != null) {
            // Stop at class-level: PsiClass supers are types, not methods.
            // Walking into the enclosing class would report interface/superclass parents
            // as "super methods", which is semantically wrong for find_super_methods.
            if (current instanceof PsiClass) break;
            PsiElement[] supers = ToolUtils.findSuperElementsViaPlatform(current);
            if (supers != null) {
                anyHelperLoadable = true;
                if (supers.length > 0) {
                    return formatNonJavaSuperMethods(current, supers);
                }
            }
            current = PsiTreeUtil.getParentOfType(current, PsiNameIdentifierOwner.class, true);
        }

        if (!anyHelperLoadable) {
            return ToolUtils.ERROR_PREFIX + "Programmatic super-methods lookup is not available "
                + "for " + psiFile.getLanguage().getDisplayName() + " in this IDE: neither the "
                + "platform helper (com.intellij.psi.impl.FindSuperElementsHelper) nor the "
                + "PsiClass.getSupers() API is reachable, and the IntelliJ platform has no other "
                + "language-agnostic equivalent of SuperMethodsSearch.";
        }
        String name = innermost.getName();
        return "No super methods found for " + (name != null ? name : "(unnamed)");
    }

    private String formatNonJavaSuperMethods(@NotNull PsiNameIdentifierOwner target,
                                             PsiElement @NotNull [] superElements) {
        StringBuilder sb = new StringBuilder();
        String label = target.getName() == null ? "(unnamed)" : target.getName();
        sb.append("Super methods for ").append(label).append(":\n");
        String basePath = project.getBasePath();
        for (PsiElement superElement : superElements) {
            sb.append(formatSuperElementEntry(superElement, basePath)).append('\n');
        }
        return ToolUtils.truncateOutput(sb.toString().stripTrailing());
    }

    private String formatSuperElementEntry(PsiElement element, String basePath) {
        PsiFile file = element.getContainingFile();
        VirtualFile vf = file == null ? null : file.getVirtualFile();
        String path = resolveDeclPath(vf, basePath);
        int line = elementLine(element, vf);
        String label = element instanceof com.intellij.psi.PsiNamedElement named && named.getName() != null
            ? named.getName()
            : element.getClass().getSimpleName();
        return path + ":" + line + " " + label;
    }

    private static int elementLine(PsiElement element, VirtualFile vf) {
        Document doc = vf == null ? null : FileDocumentManager.getInstance().getDocument(vf);
        return doc == null ? -1 : doc.getLineNumber(element.getTextOffset()) + 1;
    }

    private PsiMethod findMethodAt(PsiFile psiFile, Document document, int line, int column) {
        int offset = positionOffset(document, line, column);
        PsiElement element = psiFile.findElementAt(offset);
        return element == null ? null : PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
    }

    private @Nullable PsiNameIdentifierOwner findNamedAt(PsiFile psiFile, Document document, int line, int column) {
        int offset = positionOffset(document, line, column);
        PsiElement element = psiFile.findElementAt(offset);
        return element == null ? null : PsiTreeUtil.getParentOfType(element, PsiNameIdentifierOwner.class, false);
    }

    private static int positionOffset(Document document, int line, int column) {
        int lineIndex = line - 1;
        int lineStart = document.getLineStartOffset(lineIndex);
        int lineEnd = document.getLineEndOffset(lineIndex);
        int requestedOffset = lineStart + Math.max(0, column - 1);
        return Math.clamp(requestedOffset, lineStart, Math.max(lineStart, lineEnd - 1));
    }

    private String formatMethodEntry(PsiMethod method) {
        PsiFile file = method.getContainingFile();
        VirtualFile vf = file == null ? null : file.getVirtualFile();
        String basePath = project.getBasePath();
        String path = resolveDeclPath(vf, basePath);
        int line = methodLine(method, vf);
        return path + ":" + line + " [" + containingClassLabel(method) + "] " + formatMethodLabel(method);
    }

    private String formatMethodLabel(PsiMethod method) {
        List<String> params = new ArrayList<>();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            params.add(parameter.getType().getPresentableText());
        }
        return method.getName() + "(" + String.join(", ", params) + ")";
    }

    private String containingClassLabel(PsiMethod method) {
        PsiClass psiClass = method.getContainingClass();
        if (psiClass == null) return "method";
        String kind = psiClass.isInterface() ? "interface" : "class";
        return kind + " " + psiClass.getQualifiedName();
    }

    private int methodLine(PsiMethod method, VirtualFile vf) {
        Document doc = vf == null ? null : FileDocumentManager.getInstance().getDocument(vf);
        return doc == null ? -1 : doc.getLineNumber(method.getTextOffset()) + 1;
    }

    private String methodLocation(PsiMethod method) {
        PsiFile file = method.getContainingFile();
        VirtualFile vf = file == null ? null : file.getVirtualFile();
        return resolveDeclPath(vf, project.getBasePath()) + ":" + methodLine(method, vf);
    }
}
