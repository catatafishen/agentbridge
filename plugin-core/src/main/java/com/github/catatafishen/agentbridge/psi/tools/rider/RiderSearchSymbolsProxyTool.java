package com.github.catatafishen.agentbridge.psi.tools.rider;

import com.github.catatafishen.agentbridge.psi.tools.navigation.NavigationTool;
import com.github.catatafishen.agentbridge.ui.renderers.SearchResultRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Rider-only implementation of {@code search_symbols} that proxies to resharper-mcp's
 * {@code search_symbol} tool.
 *
 * <p>The standard {@link com.github.catatafishen.agentbridge.psi.tools.navigation.SearchSymbolsTool}
 * uses {@code classifyElement()} which fails on Rider's coarse PSI stubs. resharper-mcp
 * delegates to the ReSharper backend where full C#/F# symbol resolution is available.
 *
 * <p>Registered only when running in Rider AND resharper-mcp is reachable at
 * {@value ReSharperMcpClient#BASE_URL}.
 */
public final class RiderSearchSymbolsProxyTool extends NavigationTool {

    public RiderSearchSymbolsProxyTool(Project project) {
        super(project);
    }

    @Override
    public @NotNull String id() {
        return "search_symbols";
    }

    @Override
    public @NotNull String displayName() {
        return "Search Symbols";
    }

    @Override
    public @NotNull String description() {
        return "Search for classes, methods, or fields by name using ReSharper's symbol index. " +
            "Semantic search — works for C#, F#, VB, and any language with ReSharper PSI support. " +
            "For textual/regex search across file contents, use search_text. " +
            "For all usages of a specific symbol, use find_references. " +
            "(Rider: delegated to resharper-mcp)";
    }

    @Override
    public @NotNull Kind kind() {
        return Kind.SEARCH;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        return schema(
            Param.required("query", TYPE_STRING, "Symbol name to search for"),
            Param.optional("type", TYPE_STRING,
                "Optional: filter by type (class, method, field, property, event). Default: all types", "")
        );
    }

    @Override
    public @NotNull Object resultRenderer() {
        return SearchResultRenderer.INSTANCE;
    }

    @Override
    public @NotNull String execute(@NotNull JsonObject args) {
        String query = args.has(PARAM_QUERY) ? args.get(PARAM_QUERY).getAsString() : "";
        String typeFilter = args.has("type") ? args.get("type").getAsString() : "";

        showSearchFeedback("🔍 Searching symbols: " + query);

        JsonObject rsmArgs = new JsonObject();
        rsmArgs.addProperty("symbolName", query);
        if (!typeFilter.isEmpty()) {
            rsmArgs.addProperty("kind", typeFilter);
        }

        String result = ReSharperMcpClient.callTool("search_symbol", rsmArgs);
        showSearchFeedback("✓ Symbol search complete: " + query);
        return result;
    }
}
