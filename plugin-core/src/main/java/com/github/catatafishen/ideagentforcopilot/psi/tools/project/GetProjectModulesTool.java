package com.github.catatafishen.ideagentforcopilot.psi.tools.project;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import org.jetbrains.annotations.NotNull;

/**
 * Lists all modules in the project with their types and dependency counts.
 */
public final class GetProjectModulesTool extends ProjectTool {

    public GetProjectModulesTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_project_modules";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Project Modules";
    }

    @Override
    public @NotNull String description() {
        return "Returns all modules in the project with their types and dependency counts. "
            + "Use edit_project_structure with action=list_dependencies to see full dependency details for a module.";
    }

    @Override
    public @NotNull String kind() {
        return "read";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(new Object[][]{});
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            Module[] modules = ModuleManager.getInstance(project).getModules();
            if (modules.length == 0) return "No modules found in the project.";

            StringBuilder sb = new StringBuilder("Modules (").append(modules.length).append("):\n");
            for (Module module : modules) {
                sb.append('\n').append(module.getName()).append('\n');
                var rootManager = com.intellij.openapi.roots.ModuleRootManager.getInstance(module);
                int libCount = 0;
                int modDepCount = 0;
                for (var entry : rootManager.getOrderEntries()) {
                    if (entry instanceof com.intellij.openapi.roots.LibraryOrderEntry) libCount++;
                    else if (entry instanceof com.intellij.openapi.roots.ModuleOrderEntry me
                             && !me.getModuleName().isEmpty()) modDepCount++;
                }
                sb.append("  libraries: ").append(libCount).append('\n');
                sb.append("  module dependencies: ").append(modDepCount).append('\n');
            }
            return sb.toString().trim();
        });
    }
}
