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
import com.intellij.psi.PsiParameter;
import com.intellij.psi.search.searches.SuperMethodsSearch;
import com.intellij.psi.util.MethodSignatureBackedByPsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

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
    public @NotNull String id() {
        return "find_super_methods";
    }

    @Override
    public boolean requiresIndex() {
        return true;
    }

    @Override
    public @NotNull String displayName() {
        return "Find Super Methods";
    }

    @Override
    public @NotNull String description() {
        return "Find parent methods that the method at a file position overrides or implements. " +
            "Java-aware lookup returns the inheritance chain with file locations and containing class details.";
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
            Param.required(PARAM_FILE, TYPE_STRING, "File path containing the overriding method"),
            Param.required(PARAM_LINE, TYPE_INTEGER, "1-based line number inside the method declaration or body"),
            Param.optional(PARAM_COLUMN, TYPE_INTEGER, "1-based column number. Defaults to the first non-whitespace character on the line", 1)
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SearchResultRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        if (!hasJava) return ToolUtils.ERROR_PREFIX + "find_super_methods requires Java PSI support";
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
            return findSuperMethods(targetFile, line, column);
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

    private String findSuperMethods(VirtualFile vf, int line, int column) {
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

    private PsiMethod findMethodAt(PsiFile psiFile, Document document, int line, int column) {
        int lineIndex = line - 1;
        int lineStart = document.getLineStartOffset(lineIndex);
        int lineEnd = document.getLineEndOffset(lineIndex);
        int requestedOffset = lineStart + Math.max(0, column - 1);
        int offset = Math.clamp(requestedOffset, lineStart, Math.max(lineStart, lineEnd - 1));
        PsiElement element = psiFile.findElementAt(offset);
        return element == null ? null : PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
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
