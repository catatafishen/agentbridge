package com.github.catatafishen.ideagentforcopilot.psi.tools.editing;

import com.github.catatafishen.ideagentforcopilot.psi.SymbolEditingTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Inserts content after a symbol definition.
 * Auto-formats and optimizes imports immediately on every call.
 */
@SuppressWarnings("java:S112")
public final class InsertAfterSymbolTool extends EditingTool {

    public InsertAfterSymbolTool(Project project, SymbolEditingTools editingTools) {
        super(project, editingTools);
    }

    @Override
    public @NotNull String id() {
        return "insert_after_symbol";
    }

    @Override
    public @NotNull String displayName() {
        return "Insert After Symbol";
    }

    @Override
    public @NotNull String description() {
        return "Insert content after a symbol definition. Auto-formats and optimizes imports immediately on every call";
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "Insert after {symbol} in {file}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        return schema(new Object[][]{
            {"file", TYPE_STRING, "Absolute or project-relative path to the file containing the symbol"},
            {"symbol", TYPE_STRING, "Name of the symbol to insert after"},
            {"content", TYPE_STRING, "The content to insert after the symbol"},
            {"line", TYPE_INTEGER, "Optional: line number hint to disambiguate if multiple symbols share the same name"}
        }, "file", "symbol", "content");
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return editingTools.insertAfterSymbol(args);
    }
}
