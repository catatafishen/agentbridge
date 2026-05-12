package com.github.catatafishen.agentbridge.psi.tools.project;

import com.github.catatafishen.agentbridge.ui.renderers.SimpleStatusRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Removes an external directory module that was previously attached via {@code attach_external_dir}.
 * The module is disposed and its .iml file is deleted. The source directory itself is not affected.
 */
public final class DetachExternalDirTool extends ProjectTool {

    private static final String PARAM_ALIAS = "alias";

    public DetachExternalDirTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "detach_external_dir";
    }

    @Override
    public @NotNull String displayName() {
        return "Detach External Dir";
    }

    @Override
    public @NotNull String description() {
        return "Removes an external directory that was attached with attach_external_dir. "
            + "The IntelliJ module is disposed and files are removed from the project index. "
            + "The source directory itself is not deleted. Use list_external_dirs to see attached aliases.";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.EDIT;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Detach external dir: {alias}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required(PARAM_ALIAS, TYPE_STRING, "Alias of the external directory to remove (from attach_external_dir or list_external_dirs)")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SimpleStatusRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        if (!args.has(PARAM_ALIAS) || args.get(PARAM_ALIAS).getAsString().isBlank()) {
            return "Error: 'alias' parameter is required";
        }
        String alias = args.get(PARAM_ALIAS).getAsString().trim();
        ExternalDirRegistry registry = ExternalDirRegistry.getInstance(project);
        String error = registry.detach(alias);
        if (error != null) return error;
        return "Detached external dir with alias '" + alias + "'. Module removed from project index.";
    }
}
