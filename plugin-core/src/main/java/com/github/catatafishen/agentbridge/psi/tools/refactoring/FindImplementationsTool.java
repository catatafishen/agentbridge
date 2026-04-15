package com.github.catatafishen.agentbridge.psi.tools.refactoring;

import com.github.catatafishen.agentbridge.psi.ToolUtils;
import com.github.catatafishen.agentbridge.ui.renderers.SearchResultRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import org.jetbrains.annotations.NotNull;

/**
 * Finds all implementations of a class/interface or overrides of a method.
 * <p>
 * When {@code file} and {@code line} are provided, uses platform-level
 * {@link com.github.catatafishen.agentbridge.psi.TypeHierarchySupport} via
 * {@code DefinitionsScopedSearch} — works in any JetBrains IDE.
 * When only {@code symbol} is given, falls back to Java-specific
 * {@code RefactoringJavaSupport} (requires IntelliJ IDEA).
 */
@SuppressWarnings("java:S112")
public final class FindImplementationsTool extends RefactoringTool {

    private static final String PARAM_SYMBOL = "symbol";

    private final boolean hasJava;

    public FindImplementationsTool(Project project, boolean hasJava) {
        super(project);
        this.hasJava = hasJava;
    }

    @Override
    public @NotNull String id() {
        return "find_implementations";
    }

    @Override
    public @NotNull String displayName() {
        return "Find Implementations";
    }

    @Override
    public @NotNull String description() {
        return """
            Find all implementations of a class/interface or overrides of a method. \
            Semantic — finds through interface boundaries and class hierarchies. \
            When 'file' and 'line' are provided, uses platform-level search that works \
            for Java, Kotlin, TypeScript, Python, and any language with PSI support. \
            When only 'symbol' is given (name-based lookup), requires a Java project. \
            Returns file paths and line numbers.""";
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
            Param.required(PARAM_SYMBOL, TYPE_STRING, "Class, interface, or method name to find implementations for"),
            Param.optional("file", TYPE_STRING, "File path for method context. Required for non-Java languages; also required when searching for method overrides"),
            Param.optional("line", TYPE_INTEGER, "Line number to disambiguate the method. Required for non-Java languages; also required when searching for method overrides")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SearchResultRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        if (!args.has(PARAM_SYMBOL)) return "Error: 'symbol' parameter is required";
        String symbolName = args.get(PARAM_SYMBOL).getAsString();
        String filePath = args.has("file") ? args.get("file").getAsString() : null;
        int line = args.has("line") ? args.get("line").getAsInt() : 0;

        // Platform path: file+line provided — works for all languages
        if (filePath != null && line > 0) {
            final String fp = filePath;
            final int ln = line;
            String result = ApplicationManager.getApplication().runReadAction(
                (Computable<String>) () ->
                    com.github.catatafishen.agentbridge.psi.TypeHierarchySupport
                        .findSubtypes(project, fp, ln, symbolName)
            );
            return ToolUtils.truncateOutput(result);
        }

        // Java path: symbol-only lookup via JavaPsiFacade
        if (hasJava) {
            String result = ApplicationManager.getApplication().runReadAction(
                (Computable<String>) () ->
                    com.github.catatafishen.agentbridge.psi.java.RefactoringJavaSupport
                        .findImplementations(project, symbolName, null, 0)
            );
            return ToolUtils.truncateOutput(result);
        }

        return "Error: Provide 'file' and 'line' parameters to locate the symbol. " +
            "Name-based lookup requires a Java project.";
    }
}
