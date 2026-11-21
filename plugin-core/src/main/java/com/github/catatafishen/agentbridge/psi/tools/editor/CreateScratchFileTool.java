package com.github.catatafishen.agentbridge.psi.tools.editor;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.psi.PsiBridgeService;
import com.github.catatafishen.agentbridge.psi.ToolLayerSettings;
import com.github.catatafishen.agentbridge.psi.tools.file.FileTool;
import com.github.catatafishen.agentbridge.services.AgentScratchTracker;
import com.github.catatafishen.agentbridge.ui.renderers.ScratchFileRenderer;
import com.google.gson.JsonObject;
import com.intellij.ide.scratch.ScratchFileService;
import com.intellij.ide.scratch.ScratchRootType;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public final class CreateScratchFileTool extends EditorTool {

    private static final Logger LOG = Logger.getInstance(CreateScratchFileTool.class);
    public static final String TOOL_ID = "create_scratch_file";
    private static final String PARAM_NAME = "name";
    private static final String PARAM_CONTENT = "content";
    private static final String DEFAULT_NAME = "scratch.txt";

    public CreateScratchFileTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return TOOL_ID;
    }

    @Override
    public @NotNull String displayName() {
        return "Create Scratch File";
    }

    @Override
    public @NotNull String description() {
        return "Create a scratch file (IDE scratches, outside the project) with the given name and content, "
            + "and open it in the editor. A scratch file with the same name is overwritten. "
            + "Returns the absolute path of the scratch file.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_NAME, TYPE_STRING,
                "Scratch file name with extension (e.g., 'test.py', 'notes.md'); must stay inside the scratches directory"),
            Param.required(PARAM_CONTENT, TYPE_STRING, "The content to write to the scratch file")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return ScratchFileRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String name = scratchName(args);
        String content = scratchContent(args);
        String invalidName = invalidNameReason(scratchRootPath(), name);
        if (invalidName != null) return "Error: " + invalidName;

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

    /**
     * {@code write_file}-shaped arguments ({@code path}, {@code content}) for what this call would
     * write, so the approval prompt can show the same diff card as {@code write_file}, or
     * {@code null} when {@code execute} would reject the name.
     * {@code findFile(..., create_if_missing)} reuses an existing scratch at this path.
     */
    public static @Nullable JsonObject toWriteFileArgs(@NotNull JsonObject args) {
        String rootPath = scratchRootPath();
        String name = scratchName(args);
        if (invalidNameReason(rootPath, name) != null) return null;
        JsonObject writeArgs = new JsonObject();
        writeArgs.addProperty("path", rootPath + "/" + name);
        writeArgs.addProperty(PARAM_CONTENT, scratchContent(args));
        return writeArgs;
    }

    /** Why {@code name} cannot be used as a scratch file name under {@code rootPath}, or {@code null}. */
    static @Nullable String invalidNameReason(@NotNull String rootPath, @NotNull String name) {
        if (name.isBlank()) return "Scratch file name must not be blank.";
        try {
            Path root = Path.of(rootPath).normalize();
            Path target = root.resolve(name).normalize();
            if (!target.startsWith(root) || target.equals(root)) {
                return "Scratch file name '" + name + "' points outside the scratches directory. "
                    + "Use a plain file name such as 'notes.md'.";
            }
            return null;
        } catch (InvalidPathException e) {
            return "Scratch file name '" + name + "' is not a valid path: " + e.getMessage();
        }
    }

    private static @NotNull String scratchRootPath() {
        return ScratchFileService.getInstance().getRootPath(ScratchRootType.getInstance());
    }

    private static @NotNull String scratchName(@NotNull JsonObject args) {
        String name = stringArg(args, PARAM_NAME);
        return name != null ? name : DEFAULT_NAME;
    }

    private static @NotNull String scratchContent(@NotNull JsonObject args) {
        String content = stringArg(args, PARAM_CONTENT);
        return content != null ? content : "";
    }

    private static @Nullable String stringArg(@NotNull JsonObject args, @NotNull String key) {
        return args.has(key) && args.get(key).isJsonPrimitive() ? args.get(key).getAsString() : null;
    }

    /**
     * Writes through the Document when one is loaded, so unsaved edits in an open scratch are replaced
     * exactly as the approval diff showed (it is built from the live Document) instead of conflicting.
     */
    private static void writeContent(@NotNull VirtualFile file, @NotNull String content) throws IOException {
        FileDocumentManager documents = FileDocumentManager.getInstance();
        Document document = documents.getDocument(file);
        if (document != null) {
            document.setText(StringUtil.convertLineSeparators(content));
            documents.saveDocument(document);
            return;
        }
        try (OutputStream out = file.getOutputStream(null)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
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
                        if (file != null) writeContent(file, content);
                        return file;
                    } catch (IOException e) {
                        LOG.warn("Failed to create/write scratch file", e);
                        errorMsg[0] = e.getMessage();
                        return null;
                    }
                }
            );

            if (resultFile[0] != null) {
                boolean focusScratch = ToolLayerSettings.getInstance(project).getFollowAgentFiles()
                    && !PsiBridgeService.isUserTypingInChat(project);
                FileEditorManager.getInstance(project).openFile(resultFile[0], focusScratch);
                AgentScratchTracker.getInstance(project).trackScratchFile(resultFile[0].getPath());
            }
        } catch (Exception e) {
            LOG.warn("Failed in EDT execution", e);
            errorMsg[0] = e.getMessage();
        }
    }
}
