package com.github.catatafishen.ideagentforcopilot.psi.tools.editing;

import com.github.catatafishen.ideagentforcopilot.psi.SymbolEditingTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Replaces the entire definition of a symbol (method, class, field) by name.
 * Auto-formats and optimizes imports immediately on every call.
 */
@SuppressWarnings("java:S112")
public final class ReplaceSymbolBodyTool extends EditingTool {

    public ReplaceSymbolBodyTool(Project project, SymbolEditingTools editingTools) {
        super(project, editingTools);
    }

    @Override
    public @NotNull String id() {
        return "replace_symbol_body";
    }

    @Override
    public @NotNull String displayName() {
        return "Replace Symbol Body";
    }

    @Override
    public @NotNull String description() {
        return "Replace the entire definition of a symbol (method, class, field) by name -- no line numbers needed. "
            + "Auto-formats and optimizes imports immediately on every call";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Replace {symbol} in {file}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"file", TYPE_STRING, "Absolute or project-relative path to the file containing the symbol"},
            {"symbol", TYPE_STRING, "Name of the symbol to replace (method, class, function, or field)"},
            {"new_body", TYPE_STRING, "The complete new definition to replace the symbol with"},
            {"line", TYPE_INTEGER, "Optional: line number hint to disambiguate if multiple symbols share the same name"}
        }, "file", "symbol", "new_body");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return editingTools.replaceSymbolBody(args);
    }
}
