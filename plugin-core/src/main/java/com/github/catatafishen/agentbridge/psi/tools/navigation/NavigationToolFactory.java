package com.github.catatafishen.agentbridge.psi.tools.navigation;

import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory that creates all navigation tool instances.
 */
public final class NavigationToolFactory {

    private NavigationToolFactory() {
    }

    public static @NotNull List<Tool> create(@NotNull Project project, boolean hasJava) {
        List<Tool> tools = new ArrayList<>();
        tools.add(new ListProjectFilesTool(project));
        tools.add(new FindFileTool(project));
        tools.add(new GetFileOutlineTool(project));
        tools.add(new SearchSymbolsTool(project));
        tools.add(new FindReferencesTool(project));
        tools.add(new SearchTextTool(project));
        if (hasJava) {
            tools.add(new GetClassOutlineTool(project));
        }
        tools.add(new ListDirectoryTreeTool(project));
        return tools;
    }
}
