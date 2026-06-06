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
    public boolean requiresIndex() {
        return true;
    }

    @Override
    public @NotNull String displayName() {
        return "Get Type Hierarchy";
    }

    @Override
    public @NotNull String description() {
        return """
            Show supertypes and/or subtypes of a class or interface. \
            When 'file' and 'line' are provided with direction='subtypes' (or with direction='both' \
            in a non-Java IDE), uses platform-level DefinitionsScopedSearch — works for Java, \
            Kotlin, TypeScript, Python, C/C++ in CLion, and any language with PSI support. \
            Supertypes direction and symbol-only (name-based) lookup require a Java project; \
            in non-Java IDEs the tool falls back to subtypes-only with a note when 'both' is \
            requested.""";
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
                    "Non-Java IDEs only support 'subtypes' when 'file' and 'line' are provided. " +
                    "Requesting 'both' in a non-Java IDE returns subtypes only with a note."),
            Param.optional("file", TYPE_STRING,
                "File path where the symbol is defined. Required for non-Java languages"),
            Param.optional("line", TYPE_INTEGER,
                "Line number where the symbol is defined. Required for non-Java languages")
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

        if (!isValidDirection(direction)) {
            return "Error: Direction '" + direction + "' is not supported. "
                + "Use 'supertypes', 'subtypes', or 'both'.";
        }

        // Platform path: pure subtypes with file+line — language-agnostic.
        if ("subtypes".equals(direction) && filePath != null && line > 0) {
            return runSubtypesSearch(filePath, line, symbolName);
        }

        // Java path: supertypes, 'both' directions, or symbol-only lookup all need Java PSI.
        if (hasJava) {
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () ->
                com.github.catatafishen.agentbridge.psi.java.RefactoringJavaSupport
                    .getTypeHierarchy(project, symbolName, direction)
            );
        }

        // Non-Java IDE fallbacks: prefer partial results over a hard "Java required" failure.
        // For 'both' with file+line we can still return language-agnostic subtypes plus a note
        // explaining the supertypes limitation; this matches the most common user intent of
        // "show me what I can about this type".
        if ("both".equals(direction) && filePath != null && line > 0) {
            String subtypes = runSubtypesSearch(filePath, line, symbolName);
            if (subtypes.startsWith("Error:")) return subtypes;
            return subtypes + "\n\n"
                + "(Note: supertypes lookup is not supported in this IDE without the Java module. "
                + "Use the IDE's built-in Type Hierarchy view (Ctrl+H / Cmd+H) for supertypes, "
                + "or run this tool inside an IDE that bundles the Java plugin.)";
        }

        if ("supertypes".equals(direction)) {
            return "Error: 'supertypes' direction requires a Java project. "
                + "Use direction='subtypes' with 'file' and 'line' for non-Java languages, "
                + "or use the IDE's built-in Type Hierarchy view (Ctrl+H / Cmd+H).";
        }

        // direction='subtypes' or 'both' but missing file/line.
        return "Error: Provide 'file' and 'line' parameters to locate the symbol in non-Java projects.";
    }

    private static boolean isValidDirection(@NotNull String direction) {
        return "supertypes".equals(direction) || "subtypes".equals(direction) || "both".equals(direction);
    }

    private @NotNull String runSubtypesSearch(@NotNull String filePath, int line, @NotNull String symbolName) {
        final String fp = filePath;
        final int ln = line;
        return ApplicationManager.getApplication().runReadAction(
            (Computable<String>) () ->
                com.github.catatafishen.agentbridge.psi.TypeHierarchySupport
                    .findSubtypes(project, fp, ln, symbolName)
        );
    }
}
