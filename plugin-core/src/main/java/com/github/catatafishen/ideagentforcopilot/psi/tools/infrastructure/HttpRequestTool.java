package com.github.catatafishen.ideagentforcopilot.psi.tools.infrastructure;

import com.github.catatafishen.ideagentforcopilot.psi.InfrastructureTools;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Makes an HTTP request (GET/POST/PUT/PATCH/DELETE) to a URL.
 */
@SuppressWarnings("java:S112")
public final class HttpRequestTool extends InfrastructureTool {

    public HttpRequestTool(Project project, InfrastructureTools infraTools) {
        super(project, infraTools);
    }

    @Override
    public @NotNull String id() {
        return "http_request";
    }

    @Override
    public @NotNull String displayName() {
        return "HTTP Request";
    }

    @Override
    public @NotNull String description() {
        return "Make an HTTP request (GET/POST/PUT/PATCH/DELETE) to a URL";
    }

    @Override
    public boolean isOpenWorld() {
        return true;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "{method} {url}";
    }

    @Override
    public @Nullable JsonObject inputSchema() {
        JsonObject s = schema(new Object[][]{
            {"url", TYPE_STRING, "Full URL to request (e.g., http://localhost:8080/api)"},
            {"method", TYPE_STRING, "HTTP method: GET (default), POST, PUT, PATCH, DELETE"},
            {"body", TYPE_STRING, "Request body (for POST/PUT/PATCH)"}
        }, "url");
        addDictProperty(s, "headers", "Request headers as key-value pairs");
        return s;
    }

    @Override
    public @Nullable String execute(@NotNull JsonObject args) throws Exception {
        return infraTools.httpRequest(args);
    }
}
