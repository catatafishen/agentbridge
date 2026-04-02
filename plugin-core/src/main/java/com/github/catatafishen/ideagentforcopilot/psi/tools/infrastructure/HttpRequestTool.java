package com.github.catatafishen.ideagentforcopilot.psi.tools.infrastructure;

import com.github.catatafishen.ideagentforcopilot.psi.ToolUtils;
import com.github.catatafishen.ideagentforcopilot.ui.renderers.HttpRequestRenderer;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Makes an HTTP request (GET/POST/PUT/PATCH/DELETE) to a URL.
 */
public final class HttpRequestTool extends InfrastructureTool {

    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";
    private static final String JSON_HEADERS = "headers";
    private static final String PARAM_METHOD = "method";

    public HttpRequestTool(Project project) {
        super(project);
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
    public @NotNull Kind kind() {
        return Kind.EDIT;
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
    public @NotNull JsonObject inputSchema() {
        JsonObject s = schema(new Object[][]{
            {"url", TYPE_STRING, "Full URL to request (e.g., http://localhost:8080/api)"},
            {PARAM_METHOD, TYPE_STRING, "HTTP method: GET (default), POST, PUT, PATCH, DELETE"},
            {"body", TYPE_STRING, "Request body (for POST/PUT/PATCH)"}
        }, "url");
        addDictProperty(s, JSON_HEADERS, "Request headers as key-value pairs");
        return s;
    }

    @Override
    @SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String urlStr = args.get("url").getAsString();
        String method = args.has(PARAM_METHOD) ? args.get(PARAM_METHOD).getAsString().toUpperCase() : "GET";
        String body = args.has("body") ? args.get("body").getAsString() : null;

        URL url = URI.create(urlStr).toURL();
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);

        if (args.has(JSON_HEADERS)) {
            JsonObject headers = args.getAsJsonObject(JSON_HEADERS);
            for (String key : headers.keySet()) {
                conn.setRequestProperty(key, headers.get(key).getAsString());
            }
        }

        if (body != null && ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
            if (!args.has(JSON_HEADERS) || !args.getAsJsonObject(JSON_HEADERS).has(CONTENT_TYPE_HEADER)) {
                conn.setRequestProperty(CONTENT_TYPE_HEADER, APPLICATION_JSON);
            }
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        int status = conn.getResponseCode();
        StringBuilder result = new StringBuilder();
        result.append("HTTP ").append(status).append(" ").append(conn.getResponseMessage()).append("\n");

        result.append("\n--- Headers ---\n");
        conn.getHeaderFields().forEach((k, v) -> {
            if (k != null) result.append(k).append(": ").append(String.join(", ", v)).append("\n");
        });

        result.append("\n--- Body ---\n");
        try (InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
            if (is != null) {
                String responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                result.append(ToolUtils.truncateOutput(responseBody));
            }
        }
        return result.toString();
    }

    @Override
    public @NotNull Object resultRenderer() {
        return HttpRequestRenderer.INSTANCE;
    }
}
