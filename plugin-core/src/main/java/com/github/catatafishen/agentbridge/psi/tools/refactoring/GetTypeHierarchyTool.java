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
            When 'file' and 'line' are provided with direction='subtypes', uses platform-level \
            DefinitionsScopedSearch — works for Java, Kotlin, TypeScript, Python, C/C++ in \
            CLion, and any language with PSI support. \
            For direction='supertypes' or direction='both' in a non-Java IDE, falls back to the \
            platform's FindSuperElementsHelper via reflection when the Java module is loaded; \
            returns programmatic supertypes results for any PSI it recognises. \
            Returns a clear 'not available' error when the helper class is not loadable (pure \
            non-Java IDE).""";
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
                "Direction: 'supertypes' (ancestors) or 'subtypes' (descendants). Default: both. "
                    + "Non-Java IDEs require 'file' and 'line' for all directions."),
            Param.optional("path", TYPE_STRING,
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
        String filePath = readPathParam(args);
        int line = args.has("line") ? args.get("line").getAsInt() : 0;

        if (!isValidDirection(direction)) {
            return "Error: Direction '" + direction + "' is not supported. "
                + "Use 'supertypes', 'subtypes', or 'both'.";
        }

        // Platform path: pure subtypes with file+line — language-agnostic.
        if ("subtypes".equals(direction) && filePath != null && line > 0) {
            return runSubtypesSearch(filePath, line, symbolName);
        }

        // Java path: supertypes, 'both' directions, or symbol-only lookup all use Java PSI when
        // the Java module is the most authoritative source (JavaPsiFacade-based lookup, full
        // method signatures, etc.).
        if (hasJava) {
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () ->
                com.github.catatafishen.agentbridge.psi.java.RefactoringJavaSupport
                    .getTypeHierarchy(project, symbolName, direction)
            );
        }

        // Non-Java IDE: use programmatic supertypes via the shared reflective platform helper
        // (FindSuperElementsHelper). No manual-action suggestions — autonomous agents cannot
        // press hotkeys, so emit either real results or a clear "not available" error.
        if (filePath == null || line <= 0) {
            return "Error: Provide 'file' and 'line' parameters to locate the symbol in non-Java projects.";
        }

        return switch (direction) {
            case "supertypes" -> runSupertypesSearch(filePath, line, symbolName);
            case "both" -> runBothDirections(filePath, line, symbolName);
            default -> "Error: Provide 'file' and 'line' parameters to locate the symbol in non-Java projects.";
        };
    }

    private static boolean isValidDirection(@NotNull String direction) {
        return "supertypes".equals(direction) || "subtypes".equals(direction) || "both".equals(direction);
    }

    private @NotNull String runSubtypesSearch(@NotNull String filePath, int line, @NotNull String symbolName) {
        // Use shortNameOf so FQNs like "ns1::ns2::MyClass" resolve to "MyClass" at the declaration line.
        String shortName = com.github.catatafishen.agentbridge.psi.tools.FqnResolver.shortNameOf(symbolName);
        return ApplicationManager.getApplication().runReadAction(
            (Computable<String>) () ->
                com.github.catatafishen.agentbridge.psi.TypeHierarchySupport
                    .findSubtypes(project, filePath, line, shortName)
        );
    }

    private @NotNull String runSupertypesSearch(@NotNull String filePath, int line, @NotNull String symbolName) {
        // Use shortNameOf so FQNs like "ns1::ns2::MyClass" resolve to "MyClass" at the declaration line.
        String shortName = com.github.catatafishen.agentbridge.psi.tools.FqnResolver.shortNameOf(symbolName);
        return ApplicationManager.getApplication().runReadAction(
            (Computable<String>) () ->
                com.github.catatafishen.agentbridge.psi.TypeHierarchySupport
                    .findSupertypes(project, filePath, line, shortName)
        );
    }

    /**
     * Combined non-Java {@code direction=both} result. Runs subtypes and supertypes
     * independently and concatenates the outputs. If the supertypes helper is unavailable
     * (pure non-Java IDE), the supertypes section is replaced with a brief, agent-friendly
     * note explaining the limitation — no manual-action suggestions.
     */
    private @NotNull String runBothDirections(@NotNull String filePath, int line, @NotNull String symbolName) {
        String subtypes = runSubtypesSearch(filePath, line, symbolName);
        if (subtypes.startsWith("Error:")) return subtypes;
        String supertypes = runSupertypesSearch(filePath, line, symbolName);
        if (supertypes.startsWith("Error:")) {
            // Resolution succeeded for subtypes but the platform helper for supertypes is
            // unavailable in this IDE. Return subtypes plus a concise programmatic note —
            // NOT a manual-action hint.
            return subtypes + "\nSupertypes: not available (FindSuperElementsHelper not loadable in this IDE).";
        }
        return subtypes + "\n" + supertypes;
    }
}
