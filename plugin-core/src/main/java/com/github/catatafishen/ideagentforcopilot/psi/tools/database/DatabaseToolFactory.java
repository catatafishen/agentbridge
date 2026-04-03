package com.github.catatafishen.ideagentforcopilot.psi.tools.database;

import com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat;
import com.github.catatafishen.ideagentforcopilot.psi.tools.Tool;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class DatabaseToolFactory {

    private static final String DATABASE_PLUGIN_ID = "com.intellij.database";

    private DatabaseToolFactory() {
    }

    public static @NotNull List<Tool> create(@NotNull Project project) {
        if (!PlatformApiCompat.isPluginInstalled(DATABASE_PLUGIN_ID)) {
            return List.of();
        }
        List<Tool> tools = new ArrayList<>();
        tools.add(new ListDataSourcesTool(project));
        tools.add(new ListTablesTool(project));
        tools.add(new GetSchemaTool(project));
        tools.add(new ExecuteQueryTool(project));
        return tools;
    }
}
