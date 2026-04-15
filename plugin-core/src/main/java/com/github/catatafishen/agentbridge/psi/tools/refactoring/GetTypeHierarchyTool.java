package com.github.catatafishen.agentbridge.psi.tools.refactoring;

import com.github.catatafishen.agentbridge.ui.renderers.TypeHierarchyRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import org.jetbrains.annotations.NotNull;

/**
 * Shows supertypes and/or subtypes of a class or interface.
 * <p>
 * <b>Subtypes via file+line:</b> Uses platform-level {@code DefinitionsScopedSearch} —
 * works for Java, Kotlin, TypeScript, Python, and any language with PSI support.
 * <b>Supertypes, or symbol-only lookup:</b> Uses Java PSI — requires IntelliJ IDEA.
 */
@SuppressWarnings("java:S112")
public final class GetTypeHierarchyTool extends RefactoringTool {

    private static final String PARAM_SYMBOL = "symbol";
    private static final String PARAM_DIRECTION = "direction";

    private final boolean hasJava;

    public GetTypeHierarchyTool(Project project, boolean hasJava) {
        super(project);
        this.hasJava = hasJava;
    }

    @Override
    public @NotNull String id() {
        return "get_type_hierarchy";
    }

    @Override
    public @NotNull String displayName() {
        return "Get Type Hierarchy";
    }

    @Override
    public @NotNull String description() {
        return """
            Show supertypes and/or subtypes of a class or interface. \
            When 'file' and 'line' are provided with direction='subtypes', uses platform-level \
            DefinitionsScopedSearch — works for Java, Kotlin, TypeScript, Python, and any language \
            with PSI support. \
            Supertypes direction and symbol-only (name-based) lookup require a Java project.""";
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
        return schema(
            Param.required(PARAM_SYMBOL, TYPE_STRING, "Fully qualified or simple class/interface name"),
            Param.optional(PARAM_DIRECTION, TYPE_STRING,
                "Direction: 'supertypes' (ancestors) or 'subtypes' (descendants). Default: both. " +
                    "Non-Java IDEs only support 'subtypes' when 'file' and 'line' are provided."),
            Param.optional("file", TYPE_STRING,
                "File path where the symbol is defined. Required for non-Java languages when direction='subtypes'"),
            Param.optional("line", TYPE_INTEGER,
                "Line number where the symbol is defined. Required for non-Java languages when direction='subtypes'")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return TypeHierarchyRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has(PARAM_SYMBOL)) return "Error: 'symbol' parameter is required";
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        String direction = args.has(PARAM_DIRECTION) ? args.get(PARAM_DIRECTION).getAsString() : "both";
        String filePath = args.has("file") ? args.get("file").getAsString() : null;
        int line = args.has("line") ? args.get("line").getAsInt() : 0;

        // Platform path: subtypes only, file+line provided — works for all languages
        if ("subtypes".equals(direction) && filePath != null && line > 0) {
            final String fp = filePath;
            final int ln = line;
            return ApplicationManager.getApplication().runReadAction(
                (Computable<String>) () ->
                    com.github.catatafishen.agentbridge.psi.TypeHierarchySupport
                        .findSubtypes(project, fp, ln, symbolName)
            );
        }

        // Java path: supertypes, both directions, or symbol-only lookup
        if (hasJava) {
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () ->
                com.github.catatafishen.agentbridge.psi.java.RefactoringJavaSupport
                    .getTypeHierarchy(project, symbolName, direction)
            );
        }

        if ("subtypes".equals(direction)) {
            return "Error: Provide 'file' and 'line' parameters to locate the symbol in non-Java projects.";
        }
        return "Error: Direction '" + direction + "' requires a Java project. " +
            "Use direction='subtypes' with 'file' and 'line' for non-Java languages.";
    }
}
