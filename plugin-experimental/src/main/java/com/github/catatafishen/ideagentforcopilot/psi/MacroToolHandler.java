package com.github.catatafishen.ideagentforcopilot.psi;

import com.google.gson.JsonObject;
import com.intellij.ide.actionMacro.ActionMacro;
import com.intellij.ide.actionMacro.ActionMacroManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes a user-recorded IntelliJ macro as an MCP tool.
 *
 * <p>Each instance is bound to a specific macro name. When invoked, it:
 * <ol>
 *   <li>Optionally opens a target file in the editor</li>
 *   <li>Snapshots the active document content</li>
 *   <li>Replays the macro via {@link ActionMacroManager#playMacro(ActionMacro)}</li>
 *   <li>Captures document changes and returns a diff summary</li>
 * </ol>
 */
public final class MacroToolHandler implements ToolHandler {

    private static final int MACRO_TIMEOUT_SECONDS = 30;

    private final Project project;
    private final String macroName;

    public MacroToolHandler(@NotNull Project project, @NotNull String macroName) {
        this.project = project;
        this.macroName = macroName;
    }

    @Override
    public String handle(JsonObject args) throws Exception {
        ActionMacro macro = findMacro();
        if (macro == null) {
            return "Error: Macro '" + macroName + "' not found. "
                + "It may have been deleted. Re-record it via Edit > Macros > Start Macro Recording.";
        }

        ActionMacroManager manager = ActionMacroManager.getInstance();
        if (manager.isRecording()) {
            return "Error: Cannot run macro while another macro is being recorded.";
        }
        if (manager.isPlaying()) {
            return "Error: Another macro is currently playing. Wait for it to finish.";
        }

        // Optionally open a target file first
        if (args.has("file")) {
            String fileResult = openFile(args.get("file").getAsString());
            if (fileResult != null) {
                return fileResult;
            }
        }

        // Snapshot before
        String[] beforeContent = new String[]{null};
        String[] beforeFilePath = new String[]{null};
        EdtUtil.invokeAndWait(() -> {
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor != null) {
                beforeContent[0] = editor.getDocument().getText();
                VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
                if (vf != null) {
                    beforeFilePath[0] = vf.getPath();
                }
            }
        });

        // Play the macro on EDT
        CompletableFuture<Void> playbackDone = new CompletableFuture<>();
        EdtUtil.invokeLater(() -> {
            try {
                manager.playMacro(macro);
                playbackDone.complete(null);
            } catch (Exception e) {
                playbackDone.completeExceptionally(e);
            }
        });

        try {
            playbackDone.get(MACRO_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return "Error: Macro '" + macroName + "' timed out after " + MACRO_TIMEOUT_SECONDS
                + " seconds. It may be waiting for user input (e.g., a dialog).";
        }

        // Small delay for EDT to settle after macro playback
        Thread.sleep(200);

        // Snapshot after and build result
        StringBuilder result = new StringBuilder();
        result.append("Macro '").append(macroName).append("' executed successfully.");

        String[] afterContent = new String[]{null};
        String[] afterFilePath = new String[]{null};
        EdtUtil.invokeAndWait(() -> {
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor != null) {
                afterContent[0] = editor.getDocument().getText();
                VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
                if (vf != null) {
                    afterFilePath[0] = vf.getPath();
                }
            }
        });

        // Report what changed
        appendChangeReport(result, beforeFilePath[0], beforeContent[0],
            afterFilePath[0], afterContent[0]);

        // Report macro action sequence for transparency
        appendActionSequence(result, macro);

        return result.toString();
    }

    private ActionMacro findMacro() {
        ActionMacroManager manager = ActionMacroManager.getInstance();
        for (ActionMacro macro : manager.getAllMacros()) {
            if (macroName.equals(macro.getName())) {
                return macro;
            }
        }
        return null;
    }

    /**
     * Opens a file in the editor and waits for it. Returns an error string on failure, null on success.
     */
    private String openFile(String pathStr) {
        CompletableFuture<String> result = new CompletableFuture<>();
        EdtUtil.invokeAndWait(() -> {
            VirtualFile vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath(pathStr);
            if (vf == null) {
                // Try project-relative
                String basePath = project.getBasePath();
                if (basePath != null) {
                    vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                        .findFileByPath(basePath + "/" + pathStr);
                }
            }
            if (vf == null) {
                result.complete("Error: File not found: " + pathStr);
                return;
            }
            new OpenFileDescriptor(project, vf).navigate(true);
            result.complete(null);
        });
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "Error opening file: " + e.getMessage();
        }
    }

    private static void appendChangeReport(StringBuilder result,
                                           String beforePath, String beforeContent,
                                           String afterPath, String afterContent) {
        if (beforeContent == null && afterContent == null) {
            result.append("\nNo editor was active during macro execution.");
            return;
        }

        boolean sameFile = beforePath != null && beforePath.equals(afterPath);
        if (sameFile && beforeContent != null && beforeContent.equals(afterContent)) {
            result.append("\nFile: ").append(afterPath);
            result.append("\nNo content changes detected in the active editor.");
            return;
        }

        if (!sameFile) {
            if (beforePath != null) {
                result.append("\nBefore: ").append(beforePath);
            }
            if (afterPath != null) {
                result.append("\nAfter: ").append(afterPath);
                result.append(" (editor switched to a different file)");
            }
        } else {
            result.append("\nFile: ").append(afterPath);
        }

        if (beforeContent != null && afterContent != null && sameFile) {
            int beforeLines = beforeContent.split("\n", -1).length;
            int afterLines = afterContent.split("\n", -1).length;
            int delta = afterLines - beforeLines;
            result.append("\nLines: ").append(beforeLines).append(" → ").append(afterLines);
            if (delta != 0) {
                result.append(" (").append(delta > 0 ? "+" : "").append(delta).append(")");
            }
            int beforeLen = beforeContent.length();
            int afterLen = afterContent.length();
            int charDelta = afterLen - beforeLen;
            result.append("\nChars: ").append(beforeLen).append(" → ").append(afterLen);
            if (charDelta != 0) {
                result.append(" (").append(charDelta > 0 ? "+" : "").append(charDelta).append(")");
            }
        }
    }

    private static void appendActionSequence(StringBuilder result, ActionMacro macro) {
        ActionMacro.ActionDescriptor[] actions = macro.getActions();
        if (actions.length == 0) return;

        result.append("\n\nAction sequence (").append(actions.length).append(" steps):");
        for (int i = 0; i < actions.length && i < 20; i++) {
            result.append("\n  ").append(i + 1).append(". ");
            StringBuffer desc = new StringBuffer();
            actions[i].generateTo(desc);
            result.append(desc);
        }
        if (actions.length > 20) {
            result.append("\n  ... and ").append(actions.length - 20).append(" more steps");
        }
    }
}
