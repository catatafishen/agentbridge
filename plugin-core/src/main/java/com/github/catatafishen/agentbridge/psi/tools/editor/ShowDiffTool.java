package com.github.catatafishen.agentbridge.psi.tools.editor;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class ShowDiffTool extends EditorTool {

    private static final String PARAM_CONTENT = "content";
    private static final String PARAM_TITLE = "title";
    private static final String PARAM_FILE2 = "file2";
    private static final String DIFF_LABEL_CURRENT = "Current";

    public ShowDiffTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "show_diff";
    }

    @Override
    public @NotNull String displayName() {
        return "Show Diff";
    }

    @Override
    public @NotNull String description() {
        return "Open a visual diff viewer in the IDE comparing a file to proposed content or another file. " +
            "This is a UI action — it opens a panel for the user, not a text response. " +
            "Use git_diff for programmatic diff output.";
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
            Param.required("file", TYPE_STRING, "Path to the first file"),
            Param.optional(PARAM_FILE2, TYPE_STRING, "Optional: path to second file for two-file comparison"),
            Param.optional(PARAM_CONTENT, TYPE_STRING, "Optional: proposed new content to diff against the current file"),
            Param.optional(PARAM_TITLE, TYPE_STRING, "Optional: title for the diff viewer tab")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has("file")) {
            return "Error: 'file' parameter is required";
        }
        String pathStr = args.get("file").getAsString();

        CompletableFuture<String> resultFuture = new CompletableFuture<>();

        EdtUtil.invokeLater(() -> {
            try {
                VirtualFile vf = resolveVirtualFile(pathStr);
                if (vf == null) {
                    resultFuture.complete(ToolUtils.ERROR_PREFIX + ToolUtils.ERROR_FILE_NOT_FOUND + pathStr);
                    return;
                }
                resultFuture.complete(showDiffForFile(args, vf, pathStr));
            } catch (Exception e) {
                resultFuture.complete("Error showing diff: " + e.getMessage());
            }
        });

        return resultFuture.get(10, TimeUnit.SECONDS);
    }

    private String showDiffForFile(JsonObject args, VirtualFile vf, String pathStr) {
        if (args.has(PARAM_FILE2)) {
            return showTwoFileDiff(args, vf, pathStr);
        } else if (args.has(PARAM_CONTENT)) {
            return showContentDiff(args, vf, pathStr);
        } else {
            return showVcsDiff(vf, pathStr);
        }
    }

    private String showTwoFileDiff(JsonObject args, VirtualFile vf, String pathStr) {
        String pathStr2 = args.get(PARAM_FILE2).getAsString();
        VirtualFile vf2 = resolveVirtualFile(pathStr2);
        if (vf2 == null) {
            return "Error: Second file not found: " + pathStr2;
        }
        var content1 = DiffContentFactory.getInstance().create(project, vf);
        var content2 = DiffContentFactory.getInstance().create(project, vf2);
        var request = new SimpleDiffRequest(
            "Diff: " + vf.getName() + " vs " + vf2.getName(),
            content1, content2, vf.getName(), vf2.getName());
        DiffManager.getInstance().showDiff(project, request);
        return "Showing diff: " + pathStr + " vs " + pathStr2;
    }

    private String showContentDiff(JsonObject args, VirtualFile vf, String pathStr) {
        String newContent = args.get(PARAM_CONTENT).getAsString();
        String title = args.has(PARAM_TITLE) ? args.get(PARAM_TITLE).getAsString() : "Proposed Changes";
        var content1 = DiffContentFactory.getInstance().create(project, vf);
        var content2 = DiffContentFactory.getInstance()
            .create(project, newContent, vf.getFileType());
        var request = new SimpleDiffRequest(
            title, content1, content2, DIFF_LABEL_CURRENT, "Proposed");
        DiffManager.getInstance().showDiff(project, request);
        return "Showing diff for " + pathStr + ": current vs proposed changes";
    }

    private String showVcsDiff(VirtualFile vf, String pathStr) {
        var content1 = DiffContentFactory.getInstance().create(project, vf);
        DiffManager.getInstance().showDiff(project,
            new SimpleDiffRequest(
                "File: " + vf.getName(), content1, content1, DIFF_LABEL_CURRENT, DIFF_LABEL_CURRENT));
        return "Opened " + pathStr + " in diff viewer. " +
            "Tip: pass 'file2' for two-file diff, or 'content' to diff against proposed changes.";
    }
}
