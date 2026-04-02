package com.github.catatafishen.ideagentforcopilot.psi.tools.project;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.LibraryOrderEntry;
import com.intellij.openapi.roots.ModuleOrderEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Computable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * Lists all library dependencies across all modules in the project.
 */
public final class GetProjectDependenciesTool extends ProjectTool {

    private static final String PARAM_MODULE = "module";

    public GetProjectDependenciesTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "get_project_dependencies";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Project Dependencies";
    }

    @Override
    public @NotNull String description() {
        return "Returns all library dependencies defined in the project. "
            + "Optionally filter by module name. "
            + "Use edit_project_structure with action=list_dependencies for full details including scope and JARs.";
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
        return schema(new Object[][]{
            {PARAM_MODULE, TYPE_STRING, "Filter by module name (optional). If omitted, lists all unique libraries across all modules."}
        });
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            String filterModule = args.has(PARAM_MODULE) ? args.get(PARAM_MODULE).getAsString() : null;
            Module[] allModules = ModuleManager.getInstance(project).getModules();

            var libraryNames = new LinkedHashSet<String>();
            var moduleDepNames = new ArrayList<String>();
            int foundModules = 0;

            for (Module module : allModules) {
                if (filterModule != null && !filterModule.equals(module.getName())) continue;
                foundModules++;
                for (var entry : ModuleRootManager.getInstance(module).getOrderEntries()) {
                    if (entry instanceof LibraryOrderEntry lib && lib.getPresentableName() != null) {
                        libraryNames.add(lib.getPresentableName());
                    } else if (entry instanceof ModuleOrderEntry me && !me.getModuleName().isEmpty()) {
                        moduleDepNames.add(me.getModuleName());
                    }
                }
            }

            if (filterModule != null && foundModules == 0)
                return "Module '" + filterModule + "' not found. Use get_project_modules to list available modules.";

            StringBuilder sb = new StringBuilder();
            String scope = filterModule != null ? "module '" + filterModule + "'" : "project";
            sb.append("Libraries in ").append(scope).append(" (").append(libraryNames.size()).append("):\n");
            for (String name : libraryNames) sb.append("  ").append(name).append('\n');

            if (!moduleDepNames.isEmpty()) {
                var unique = new LinkedHashSet<>(moduleDepNames);
                sb.append("\nModule dependencies (").append(unique.size()).append("):\n");
                for (String name : unique) sb.append("  ").append(name).append('\n');
            }

            return sb.toString().trim();
        });
    }
}
