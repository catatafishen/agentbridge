package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.github.catatafishen.agentbridge.services.ToolRegistry;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.UsageSearchContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Abstract base for code navigation tools. Provides shared constants
 * and helpers for symbol search and code exploration.
 */
public abstract class NavigationTool extends Tool {

    protected static final String ERROR_NO_PROJECT_PATH = "No project base path";
    protected static final String PARAM_SYMBOL = "symbol";
    protected static final String PARAM_FILE_PATTERN = "file_pattern";
    protected static final String FORMAT_LOCATION = "%s:%d [%s] %s";
    protected static final String FORMAT_LINE_REF = "%s:%d: %s";
    protected static final String PARAM_QUERY = "query";

    protected NavigationTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull ToolRegistry.Category category() {
        return ToolRegistry.Category.SEARCH;
    }

    protected void showSearchFeedback(String message) {
        if (!ToolLayerSettings.getInstance(project).getFollowAgentFiles()) return;
        EdtUtil.invokeLater(() -> {
            var statusBar = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project);
            if (statusBar != null) {
                statusBar.setInfo(message);
            }
        });
    }

    protected PsiElement findDefinition(String name, GlobalSearchScope scope) {
        PsiElement[] result = {null};
        PsiSearchHelper.getInstance(project).processElementsWithWord(
            (element, offsetInElement) -> {
                PsiElement parent = element.getParent();
                if (parent instanceof PsiNamedElement named && name.equals(named.getName())) {
                    String type = ToolUtils.classifyElement(parent);
                    if (type != null && !type.equals(ToolUtils.ELEMENT_TYPE_FIELD)) {
                        result[0] = parent;
                        return false;
                    }
                }
                return true;
            },
            scope, name, UsageSearchContext.IN_CODE, true
        );
        return result[0];
    }

    protected String buildReferenceEntry(com.intellij.psi.PsiReference ref, String filePattern, java.util.regex.Pattern compiledGlob, String basePath) {
        PsiElement refEl = ref.getElement();
        PsiFile file = refEl.getContainingFile();
        if (file == null || file.getVirtualFile() == null) return null;
        String relPath = basePath != null
            ? relativize(basePath, file.getVirtualFile().getPath())
            : file.getVirtualFile().getPath();
        if (!filePattern.isEmpty() && ToolUtils.doesNotMatchGlob(relPath, filePattern, compiledGlob)) return null;
        Document doc = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
        if (doc == null) return null;
        int line = doc.getLineNumber(refEl.getTextOffset()) + 1;
        String lineText = ToolUtils.getLineText(doc, line - 1);
        return String.format(FORMAT_LINE_REF, relPath, line, lineText);
    }

    protected void addSymbolResult(PsiElement element, String basePath,
                                   java.util.Set<String> seen, List<String> results) {
        PsiFile file = element.getContainingFile();
        if (file == null || file.getVirtualFile() == null) return;
        Document doc = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
        if (doc == null) return;
        int line = doc.getLineNumber(element.getTextOffset()) + 1;
        String relPath = basePath != null
            ? relativize(basePath, file.getVirtualFile().getPath())
            : file.getVirtualFile().getPath();
        if (seen.add(relPath + ":" + line)) {
            String lineText = ToolUtils.getLineText(doc, line - 1);
            String type = ToolUtils.classifyElement(element);
            results.add(String.format(FORMAT_LOCATION, relPath, line, type, lineText));
        }
    }

    protected List<String> collectOutlineEntries(PsiFile psiFile, Document document) {
        List<String> outline = new java.util.ArrayList<>();
        psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element instanceof PsiNamedElement named) {
                    String name = named.getName();
                    if (name != null && !name.isEmpty()) {
                        String type = ToolUtils.classifyElement(element);
                        if (type != null) {
                            int line = document.getLineNumber(element.getTextOffset()) + 1;
                            outline.add(String.format("  %d: %s %s", line, type, name));
                        }
                    }
                }
                super.visitElement(element);
            }
        });
        return outline;
    }

    protected void collectSymbolsFromFile(PsiFile psiFile, Document doc, VirtualFile vf,
                                          String typeFilter, String basePath,
                                          java.util.Set<String> seen, List<String> results) {
        String relPath = basePath != null ? relativize(basePath, vf.getPath()) : vf.getPath();
        psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (results.size() >= 200) return;
                if (!(element instanceof PsiNamedElement named)) {
                    super.visitElement(element);
                    return;
                }
                String name = named.getName();
                String type = ToolUtils.classifyElement(element);
                if (name != null && type != null && type.equals(typeFilter)) {
                    int line = doc.getLineNumber(element.getTextOffset()) + 1;
                    if (seen.add(relPath + ":" + line)) {
                        results.add(String.format(FORMAT_LOCATION, relPath, line, type, name));
                    }
                }
                super.visitElement(element);
            }
        });
    }
}
