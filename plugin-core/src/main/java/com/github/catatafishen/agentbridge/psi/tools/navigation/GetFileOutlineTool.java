package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.FileOutlineRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Gets the structure of a file — classes, methods, and fields with line numbers.
 */
public final class GetFileOutlineTool extends NavigationTool {

    public GetFileOutlineTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_file_outline";
    }

    @Override
    public @NotNull String displayName() {
        return "Get File Outline";
    }

    @Override
    public @NotNull String description() {
        return "Get the structure of a file — classes, methods, and fields with line numbers. " +
                "Works on any project file. Use get_class_outline for library/JDK classes by fully-qualified name.";
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
            {"path", TYPE_STRING, "Absolute or project-relative path to the file to outline"}
        }, "path");
    }

    @Override
    public @NotNull Object resultRenderer() {
        return FileOutlineRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        if (!args.has("path") || args.get("path").isJsonNull())
            return ToolUtils.ERROR_PATH_REQUIRED;
        String pathStr = args.get("path").getAsString();

        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) return ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;

            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return "Cannot parse file: " + pathStr;

            Document document = FileDocumentManager.getInstance().getDocument(vf);
            if (document == null) return "Cannot read file: " + pathStr;

            List<String> outline = collectOutlineEntries(psiFile, document);

            if (outline.isEmpty()) return "No structural elements found in " + pathStr;
            String basePath = project.getBasePath();
            String display = basePath != null ? relativize(basePath, vf.getPath()) : pathStr;
            return "Outline of " + (display != null ? display : pathStr) + ":\n"
                + String.join("\n", outline);
        });
    }
}
