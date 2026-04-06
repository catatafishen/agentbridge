package com.github.catatafishen.agentbridge.psi.tools.refactoring;

import com.github.catatafishen.agentbridge.psi.tools.Tool;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory producing all individual refactoring tool instances.
 * Called from {@link com.github.catatafishen.agentbridge.psi.PsiBridgeService}
 * during initialization.
 */
public final class RefactoringToolFactory {

    private RefactoringToolFactory() {
    }

    public static @NotNull List<Tool> create(@NotNull Project project, boolean hasJava) {
        var tools = new ArrayList<Tool>();
        tools.add(new RefactorTool(project));
        tools.add(new GoToDeclarationTool(project));
        if (hasJava) {
            tools.add(new GetTypeHierarchyTool(project));
            tools.add(new FindImplementationsTool(project));
            tools.add(new GetCallHierarchyTool(project));
        }
        tools.add(new GetDocumentationTool(project));
        tools.add(new GetSymbolInfoTool(project));
        return List.copyOf(tools);
    }
}
