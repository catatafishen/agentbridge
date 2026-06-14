package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.FileOutlineRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Gets the structure of a file — classes, methods, and fields with line numbers.
 */
public final class GetFileOutlineTool extends NavigationTool {

    private static final String PARAM_TIMEOUT = "timeout";

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
        return schema(
            Param.required("path", TYPE_STRING, "Absolute or project-relative path to the file to outline"),
            Param.optional("wait", TYPE_BOOLEAN,
                "If true, retry until the language backend populates PSI (useful for CLion Nova/Radler " +
                    "which loads asynchronously after IDE startup). Pairs with 'timeout'."),
            Param.optional(PARAM_TIMEOUT, TYPE_INTEGER,
                "Max seconds to wait when wait=true (default: 60)")
        );
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

        boolean wait = args.has("wait") && args.get("wait").getAsBoolean();
        int timeoutSec = args.has(PARAM_TIMEOUT) ? args.get(PARAM_TIMEOUT).getAsInt() : 60;

        String result = computeOutline(pathStr);
        if (!wait || !result.startsWith("No structural elements")) return result;

        // CLion Nova's StructureViewModel only populates when a real editor is open for the file
        // (see NavigationTool.collectOutlineEntries Javadoc). Open it now on the EDT so that
        // subsequent computeOutline() calls can pick up the editor and get non-empty results.
        VirtualFile vf = ApplicationManager.getApplication().runReadAction(
            (Computable<VirtualFile>) () -> resolveVirtualFile(pathStr));
        if (vf != null) {
            ApplicationManager.getApplication().invokeAndWait(
                () -> FileEditorManager.getInstance(project).openFile(vf, false));
        }

        // Language backends (e.g., CLion Nova/Radler) load asynchronously AFTER IntelliJ's own
        // indexing completes, so PSI may be empty immediately after get_indexing_status reports
        // ready. Retry until elements appear or we exhaust the timeout.
        // The sleep-in-loop is intentional polling: there is no platform-agnostic subscription
        // API for language-backend readiness (CLion Nova/Radler exposes this only via CLion-specific
        // APIs not available on our compile classpath).
        long deadline = System.currentTimeMillis() + (long) timeoutSec * 1_000;
        do {
            try {
                //noinspection BusyWait
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            result = computeOutline(pathStr);
        } while (result.startsWith("No structural elements") && System.currentTimeMillis() < deadline);
        return result;
    }

    // The Computable<String> cast is required for javac to disambiguate between the
    // Computable and ThrowableComputable overloads of runReadAction (see execute()).
    // IntelliJ incorrectly reports the cast as redundant — removing it causes a javac error.
    @SuppressWarnings("RedundantCast")
    private @NotNull String computeOutline(@NotNull String pathStr) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            VirtualFile vf = resolveVirtualFile(pathStr);
            if (vf == null) return ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;

            PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
            if (psiFile == null) return ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;

            Document document = FileDocumentManager.getInstance().getDocument(vf);
            if (document == null) return ToolUtils.ERROR_FILE_NOT_FOUND + pathStr;

            // Pass the open editor if the file is already open — required for CLion Nova's
            // lazy StructureViewModel, which only populates when a real editor is present.
            Editor editor = findOpenEditor(vf);
            List<String> outline = collectOutlineEntries(psiFile, document, editor);
            if (outline.isEmpty()) return "No structural elements found in " + pathStr;

            String basePath = project.getBasePath();
            String display = basePath != null ? relativize(basePath, vf.getPath()) : pathStr;
            return "Outline of " + (display != null ? display : pathStr) + ":\n"
                + String.join("\n", outline);
        });
    }

    /**
     * Returns the first open text editor for {@code vf}, or {@code null} if the file is not open.
     */
    private @Nullable Editor findOpenEditor(@NotNull VirtualFile vf) {
        for (FileEditor fe : FileEditorManager.getInstance(project).getEditors(vf)) {
            if (fe instanceof TextEditor textEditor) {
                return textEditor.getEditor();
            }
        }
        return null;
    }
}
