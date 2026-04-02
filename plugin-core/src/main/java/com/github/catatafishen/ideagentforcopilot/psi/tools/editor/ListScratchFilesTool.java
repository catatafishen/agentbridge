package com.github.catatafishen.ideagentforcopilot.psi.tools.editor;

import com.github.catatafishen.ideagentforcopilot.psi.EdtUtil;
import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.ide.scratch.ScratchFileService;
import com.intellij.ide.scratch.ScratchRootType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists existing scratch files in the IDE scratch directory.
 */
public final class ListScratchFilesTool extends EditorTool {

    private static final Logger LOG = Logger.getInstance(ListScratchFilesTool.class);

    public ListScratchFilesTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "list_scratch_files";
    }

    @Override
    public @NotNull String displayName() {
        return "List Scratch Files";
    }

    @Override
    public @NotNull String description() {
        return "List existing scratch files in the IDE scratch directory";
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
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        try {
            final List<String> lines = new ArrayList<>();
            final String[] errorMsg = new String[1];

            EdtUtil.invokeAndWait(() -> {
                try {
                    ScratchFileService scratchService = ScratchFileService.getInstance();
                    ScratchRootType scratchRoot = ScratchRootType.getInstance();

                    VirtualFile scratchesDir = scratchService.findFile(
                        scratchRoot, "",
                        ScratchFileService.Option.existing_only
                    );

                    if (scratchesDir == null) return;

                    VfsUtil.markDirtyAndRefresh(false, true, true, scratchesDir);
                    collectScratchEntries(scratchesDir, "", lines);
                } catch (Exception e) {
                    LOG.warn("Failed to list scratch files", e);
                    errorMsg[0] = e.getMessage();
                }
            });

            if (errorMsg[0] != null) return "Error listing scratch files: " + errorMsg[0];

            if (lines.isEmpty()) {
                return "0 scratch files\nUse create_scratch_file to create one.";
            }

            lines.sort(String::compareTo);
            return lines.size() + " scratch files:\n" + String.join("\n", lines);
        } catch (Exception e) {
            LOG.warn("Failed to list scratch files", e);
            return "Error listing scratch files: " + e.getMessage();
        }
    }

    private static void collectScratchEntries(VirtualFile dir, String prefix, List<String> lines) {
        if (prefix.chars().filter(c -> c == '/').count() > 3) return;

        for (VirtualFile child : dir.getChildren()) {
            String relPath = prefix.isEmpty() ? child.getName() : prefix + File.separator + child.getName();
            if (child.isDirectory()) {
                collectScratchEntries(child, relPath, lines);
            } else {
                lines.add(String.format("%s [%s, %s, %s]",
                    relPath,
                    ToolUtils.fileType(child.getName()),
                    ToolUtils.formatFileSize(child.getLength()),
                    ToolUtils.formatFileTimestamp(child.getTimeStamp())));
            }
        }
    }
}
