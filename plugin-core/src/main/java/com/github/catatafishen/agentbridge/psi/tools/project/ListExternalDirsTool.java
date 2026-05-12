package com.github.catatafishen.agentbridge.psi.tools.project;

import com.github.catatafishen.agentbridge.ui.renderers.IdeInfoRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Lists all external directories currently attached via {@code attach_external_dir}.
 */
public final class ListExternalDirsTool extends ProjectTool {

    public ListExternalDirsTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "list_external_dirs";
    }

    @Override
    public @NotNull String displayName() {
        return "List External Dirs";
    }

    @Override
    public @NotNull String description() {
        return "Lists all external directories currently attached as temporary read-only modules "
            + "via attach_external_dir. Returns alias, module name, and absolute path for each. "
            + "Use detach_external_dir with the alias to remove one.";
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
        return schema();
    }

    @Override
    public @NotNull Object resultRenderer() {
        return IdeInfoRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        List<ExternalDirRegistry.AttachedDir> dirs = ExternalDirRegistry.getInstance(project).listAttached();
        if (dirs.isEmpty()) {
            return "No external directories attached. Use attach_external_dir to attach one.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Attached external directories (").append(dirs.size()).append("):\n");
        for (ExternalDirRegistry.AttachedDir dir : dirs) {
            sb.append("\n• Alias: ").append(dir.alias()).append("\n");
            sb.append("  Path:   ").append(dir.absolutePath()).append("\n");
            sb.append("  Module: ").append(dir.moduleName()).append("\n");
        }
        sb.append("\nUse detach_external_dir with the alias to remove a directory.");
        return sb.toString().trim();
    }
}
