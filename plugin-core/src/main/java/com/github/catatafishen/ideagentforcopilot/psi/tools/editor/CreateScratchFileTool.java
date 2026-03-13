package com.github.catatafishen.ideagentforcopilot.psi.tools.editor;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.github.catatafishen.ideagentforcopilot.psi.ToolLayerSettings;
import com.github.catatafishen.ideagentforcopilot.psi.tools.file.FileTool;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.ScratchFileRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.WriteAction;
import com.intellij.ide.scratch.ScratchFileService;
import com.intellij.ide.scratch.ScratchRootType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class CreateScratchFileTool extends EditorTool {

    private static final Logger LOG = Logger.getInstance(CreateScratchFileTool.class);
    private static final String PARAM_CONTENT = "content";

    public CreateScratchFileTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "create_scratch_file";
    }

    @Override
    public @NotNull String displayName() {
        return "Create Scratch File";
    }

    @Override
    public @NotNull String description() {
        return "Create a temporary scratch file with the given name and content";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{
            {"name", TYPE_STRING, "Scratch file name with extension (e.g., 'test.py', 'notes.md')"},
            {PARAM_CONTENT, TYPE_STRING, "The content to write to the scratch file"}
        }, "name", PARAM_CONTENT);
    }

    @Override
    public @NotNull Object resultRenderer() {
        return ScratchFileRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String name = args.has("name") ? args.get("name").getAsString() : "scratch.txt";
        String content = args.has(PARAM_CONTENT) ? args.get(PARAM_CONTENT).getAsString() : "";

        try {
            final VirtualFile[] resultFile = new VirtualFile[1];
            final String[] errorMsg = new String[1];

            EdtUtil.invokeAndWait(() -> createAndOpenScratchFile(name, content, resultFile, errorMsg));

            if (resultFile[0] == null) {
                return "Error: Failed to create scratch file" +
                    (errorMsg[0] != null ? ": " + errorMsg[0] : "");
            }

            String scratchPath = resultFile[0].getPath();
            int lineCount = content.isEmpty() ? 1 : (int) content.lines().count();
            FileTool.followFileIfEnabled(project, scratchPath, 1, lineCount,
                FileTool.HIGHLIGHT_EDIT, FileTool.agentLabel(project) + " created scratch");

            return "Created scratch file: " + scratchPath + " (" + content.length() + " chars)";
        } catch (Exception e) {
            LOG.warn("Failed to create scratch file", e);
            return "Error creating scratch file: " + e.getMessage();
        }
    }

    private void createAndOpenScratchFile(String name, String content,
                                          VirtualFile[] resultFile, String[] errorMsg) {
        try {
            ScratchFileService scratchService = ScratchFileService.getInstance();
            ScratchRootType scratchRoot = ScratchRootType.getInstance();

            resultFile[0] = WriteAction.compute(
                () -> {
                    try {
                        VirtualFile file = scratchService.findFile(
                            scratchRoot, name,
                            ScratchFileService.Option.create_if_missing
                        );
                        if (file != null) {
                            OutputStream out = file.getOutputStream(null);
                            out.write(content.getBytes(StandardCharsets.UTF_8));
                            out.close();
                        }
                        return file;
                    } catch (IOException e) {
                        LOG.warn("Failed to create/write scratch file", e);
                        errorMsg[0] = e.getMessage();
                        return null;
                    }
                }
            );

            if (resultFile[0] != null) {
                boolean focusScratch = ToolLayerSettings.getInstance(project).getFollowAgentFiles();
                FileEditorManager.getInstance(project).openFile(resultFile[0], focusScratch);
            }
        } catch (Exception e) {
            LOG.warn("Failed in EDT execution", e);
            errorMsg[0] = e.getMessage();
        }
    }
}
