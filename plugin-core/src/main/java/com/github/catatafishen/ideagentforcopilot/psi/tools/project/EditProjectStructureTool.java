package com.github.catatafishen.ideagentforcopilot.psi.tools.project;

import com.github.catatafishen.ideagentforcopilot.psi.ProjectTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Views and modifies module dependencies, libraries, SDKs, and project structure.
 */
@SuppressWarnings("java:S112")
public final class EditProjectStructureTool extends ProjectTool {

    public EditProjectStructureTool(Project project, ProjectTools projectTools) {
        super(project, projectTools);
    }

    @Override
    public @NotNull String id() {
        return "edit_project_structure";
    }

    @Override
    public @NotNull String displayName() {
        return "Edit Project Structure";
    }

    @Override
    public @NotNull String description() {
        return "View and modify module dependencies, libraries, SDKs, and project structure";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"action", TYPE_STRING, "Action: 'list_modules', 'list_dependencies', 'add_dependency', 'remove_dependency', 'list_sdks', 'add_sdk', 'remove_sdk'"},
            {"module", TYPE_STRING, "Module name (required for list_dependencies, add_dependency, remove_dependency)"},
            {"dependency_name", TYPE_STRING, "Name of the dependency to add or remove"},
            {"dependency_type", TYPE_STRING, "Type of dependency to add: 'library' (default) or 'module'"},
            {"scope", TYPE_STRING, "Dependency scope: 'COMPILE' (default), 'TEST', 'RUNTIME', 'PROVIDED'"},
            {"jar_path", TYPE_STRING, "Path to JAR file (absolute or project-relative). Required when adding a library dependency"},
            {"sdk_type", TYPE_STRING, "SDK type name for add_sdk (e.g., 'Python SDK', 'JavaSDK'). Use list_sdks to see available types"},
            {"sdk_name", TYPE_STRING, "SDK name for remove_sdk. Use list_sdks to see configured SDK names"},
            {"home_path", TYPE_STRING, "Home path for add_sdk. Use list_sdks to see suggested paths for each SDK type"}
        }, "action");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return projectTools.editProjectStructure(args);
    }
}
