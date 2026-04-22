package com.github.catatafishen.agentbridge.psi.tools.editor;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class OpenInEditorTool extends EditorTool {

    private static final String PARAM_FOCUS = "focus";

    public OpenInEditorTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "open_in_editor";
    }

    @Override
    public boolean requiresInteractiveEdt() {
        return true;
    }

    @Override
    public @NotNull String displayName() {
        return "Open in Editor";
    }

    @Override
    public @NotNull String description() {
        return "Open a file in the editor, optionally navigating to a specific line";
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
            Param.required("file", TYPE_STRING, "Path to the file to open"),
            Param.optional("line", TYPE_INTEGER, "Optional: line number to navigate to after opening"),
            Param.optional(PARAM_FOCUS, TYPE_BOOLEAN, "Optional: if true (default), the editor gets focus. Set to false to open without stealing focus")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("file")) {
            return "Error: 'file' parameter is required";
        }
        String pathStr = args.get("file").getAsString();
        int line = args.has("line") ? args.get("line").getAsInt() : -1;
        boolean requestedFocus = !args.has(PARAM_FOCUS) || args.get(PARAM_FOCUS).getAsBoolean();
        boolean focus = requestedFocus && ToolLayerSettings.getInstance(project).getFollowAgentFiles();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                    return;
                }

                // Don't steal focus when the user is actively typing in the chat prompt.
                boolean effectiveFocus = focus && !PsiBridgeService.isUserTypingInChat(project);

                if (line > 0) {
                    new OpenFileDescriptor(project, vf, line - 1, 0).navigate(effectiveFocus);
                } else {
                    FileEditorManager.getInstance(project).openFile(vf, effectiveFocus);
                }

                PsiFile psiFile = ApplicationManager.getApplication().runReadAction(
                    (Computable<PsiFile>) () -> PsiManager.getInstance(project).findFile(vf));
                if (psiFile != null) {
                    DaemonCodeAnalyzer.getInstance(project).restart(psiFile, "File opened in editor");
                }

                resultFuture.complete("Opened " + pathStr + (line > 0 ? " at line " + line : "") +
                    " (daemon analysis triggered - use get_highlights after a moment)");
            } catch (Exception e) {
                resultFuture.complete("Error opening file: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }
}
