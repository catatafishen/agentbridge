package com.github.catatafishen.agentbridge.psi.tools.editor;

import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Factory that creates all editor tool instances.
 */
public final class EditorToolFactory {

    private EditorToolFactory() {
    }

    public static @NotNull List<Tool> create(@NotNull Project project) {
        return List.of(
            new OpenInEditorTool(project),
            new ShowDiffTool(project),
            new CreateScratchFileTool(project),
            new ListScratchFilesTool(project),
            new RunScratchFileTool(project),
            new GetChatHtmlTool(project),
            new GetActiveFileTool(project),
            new GetOpenEditorsTool(project),
            new ListThemesTool(project),
            new SetThemeTool(project),
            new SearchConversationHistoryTool(project)
        );
    }
}
