package com.github.catatafishen.agentbridge.psi.tools.infrastructure;

import com.github.catatafishen.agentbridge.psi.EdtUtil;
import com.github.catatafishen.agentbridge.services.AgentTabTracker;
import com.github.catatafishen.agentbridge.ui.renderers.HttpRequestRenderer;
import com.google.gson.JsonObject;
import com.intellij.execution.RunContentExecutor;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

/**
 * Makes HTTP requests using {@link HttpClient} and displays the request/response
 * in IntelliJ's Run panel for user visibility.
 * <p>
 * This tool is for programmatic HTTP API calls (REST APIs, form submissions, webhooks).
 * For reading web pages and extracting content, agents should use web_fetch or similar
 * tools instead.
 */
public final class HttpRequestTool extends InfrastructureTool {

    private static final Logger LOG = Logger.getInstance(HttpRequestTool.class);

    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";
    private static final String FORM_URL_ENCODED = "application/x-www-form-urlencoded";
    private static final String PARAM_METHOD = "method";
    private static final String PARAM_URL = "url";
    private static final String PARAM_BODY = "body";
    private static final String PARAM_HEADERS = "headers";
    private static final String PARAM_FORM_DATA = "form_data";
    private static final String PARAM_COOKIES = "cookies";
    private static final String PARAM_AUTH = "auth";
    private static final String PARAM_FOLLOW_REDIRECTS = "follow_redirects";
    private static final String PARAM_TIMEOUT = "timeout";
    private static final String PARAM_MAX_CHARS = "max_chars";
    private static final String PARAM_SAVE_TO = "save_to";
    private static final String PARAM_SHOW_HEADERS = "show_headers";

    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_READ_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_MAX_CHARS = 8000;
    private static final int MAX_BODY_PREVIEW_CHARS = 2000;
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String HTTP_PREFIX = "HTTP ";

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
        return "Make an HTTP request (GET/POST/PUT/PATCH/DELETE) to a URL. Returns status code, headers, and response body. "
            + "Supports custom headers, JSON/form-encoded bodies, cookies, authentication, and redirect control. "
            + "Each request is shown in the IDE's Run panel for visibility. "
            + "This tool is for API calls and form submissions — for reading web pages, use web_fetch instead.";
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
    public boolean needsWriteLock() {
        return false;
    }

    @Override
    public @NotNull String permissionTemplate() {
        return "{method} {url}";
    }

    @Override
    public @NotNull JsonObject inputSchema() {
        JsonObject s = schema(
            Param.required(PARAM_URL, TYPE_STRING, "Full URL to request (e.g., https://api.example.com/data)"),
            Param.optional(PARAM_METHOD, TYPE_STRING, "HTTP method: GET (default), POST, PUT, PATCH, DELETE"),
            Param.optional(PARAM_BODY, TYPE_STRING, "Request body (for POST/PUT/PATCH). Mutually exclusive with form_data"),
            Param.optional(PARAM_AUTH, TYPE_STRING, "Auth shorthand: 'bearer TOKEN' or 'basic user:pass'"),
            Param.optional(PARAM_FOLLOW_REDIRECTS, TYPE_BOOLEAN, "Follow 3xx redirects (default: true)"),
            Param.optional(PARAM_TIMEOUT, TYPE_INTEGER, "Read timeout in seconds (default: 30)"),
            Param.optional(PARAM_MAX_CHARS, TYPE_INTEGER, "Maximum response body characters to return (default: 8000)"),
            Param.optional(PARAM_SAVE_TO, TYPE_STRING, "Save response body to this file path instead of returning it (for binary/large responses)"),
            Param.optional(PARAM_SHOW_HEADERS, TYPE_BOOLEAN, "Include response headers in output (default: false)")
        );
        addDictProperty(s, PARAM_HEADERS, "Request headers as key-value pairs");
        addDictProperty(s, PARAM_FORM_DATA,
            "Form-encoded body as key-value pairs (auto-sets Content-Type: application/x-www-form-urlencoded). Mutually exclusive with body");
        addDictProperty(s, PARAM_COOKIES, "Cookies as key-value pairs (sent as Cookie header)");
        return s;
    }

    @Override
    @SuppressWarnings("java:S112") // generic exceptions are caught at the JSON-RPC dispatch level
    public @NotNull String execute(@NotNull JsonObject args) throws Exception {
        String urlStr = args.get(PARAM_URL).getAsString();
        String method = args.has(PARAM_METHOD) ? args.get(PARAM_METHOD).getAsString().toUpperCase() : "GET";
        int timeoutSec = args.has(PARAM_TIMEOUT) ? args.get(PARAM_TIMEOUT).getAsInt() : DEFAULT_READ_TIMEOUT_SECONDS;
        int maxChars = args.has(PARAM_MAX_CHARS) ? args.get(PARAM_MAX_CHARS).getAsInt() : DEFAULT_MAX_CHARS;
        boolean followRedirects = !args.has(PARAM_FOLLOW_REDIRECTS) || args.get(PARAM_FOLLOW_REDIRECTS).getAsBoolean();
        boolean showHeaders = args.has(PARAM_SHOW_HEADERS) && args.get(PARAM_SHOW_HEADERS).getAsBoolean();
        String saveTo = args.has(PARAM_SAVE_TO) ? args.get(PARAM_SAVE_TO).getAsString() : null;

        String body = resolveBody(args);
        String bodyContentType = resolveBodyContentType(args);

        try (HttpClient client = buildClient(followRedirects)) {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(urlStr))
                .timeout(Duration.ofSeconds(timeoutSec));

            applyHeaders(reqBuilder, args, bodyContentType);
            applyCookies(reqBuilder, args);
            applyAuth(reqBuilder, args);

            HttpRequest.BodyPublisher bodyPublisher = body != null
                ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody();

            switch (method) {
                case "POST" -> reqBuilder.POST(bodyPublisher);
                case "PUT" -> reqBuilder.PUT(bodyPublisher);
                case "PATCH" -> reqBuilder.method("PATCH", bodyPublisher);
                case "DELETE" -> reqBuilder.DELETE();
                default -> reqBuilder.GET();
            }

            HttpRequest request = reqBuilder.build();

            showRequestInRunPanel(method, urlStr, request, body);

            long startMs = System.currentTimeMillis();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            long elapsedMs = System.currentTimeMillis() - startMs;

            String responseBodyStr = new String(response.body(), StandardCharsets.UTF_8);
            logResponse(response.statusCode(), elapsedMs, responseBodyStr.length());

            if (saveTo != null) {
                return handleSaveTo(saveTo, response.body(), response.statusCode(), elapsedMs);
            }

            return formatResponse(response, elapsedMs, responseBodyStr, showHeaders, maxChars);
        }
    }

    private static @NotNull HttpClient buildClient(boolean followRedirects) {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(DEFAULT_CONNECT_TIMEOUT_SECONDS))
            .followRedirects(followRedirects ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER)
            .build();
    }

    // ── Body resolution ──────────────────────────────────────

    private static @Nullable String resolveBody(@NotNull JsonObject args) {
        if (args.has(PARAM_BODY)) {
            return args.get(PARAM_BODY).getAsString();
        }
        if (args.has(PARAM_FORM_DATA) && args.get(PARAM_FORM_DATA).isJsonObject()) {
            return encodeFormData(args.getAsJsonObject(PARAM_FORM_DATA));
        }
        return null;
    }

    private static @Nullable String resolveBodyContentType(@NotNull JsonObject args) {
        if (args.has(PARAM_FORM_DATA) && args.get(PARAM_FORM_DATA).isJsonObject()) {
            return FORM_URL_ENCODED;
        }
        if (args.has(PARAM_BODY)) {
            return APPLICATION_JSON;
        }
        return null;
    }

    private static @NotNull String encodeFormData(@NotNull JsonObject formData) {
        StringBuilder sb = new StringBuilder();
        for (String key : formData.keySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(formData.get(key).getAsString(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    // ── Header/Cookie/Auth application ───────────────────────

    private static void applyHeaders(@NotNull HttpRequest.Builder builder,
                                     @NotNull JsonObject args,
                                     @Nullable String bodyContentType) {
        boolean hasExplicitContentType = false;

        if (args.has(PARAM_HEADERS) && args.get(PARAM_HEADERS).isJsonObject()) {
            JsonObject headers = args.getAsJsonObject(PARAM_HEADERS);
            for (String key : headers.keySet()) {
                builder.header(key, headers.get(key).getAsString());
                if (CONTENT_TYPE_HEADER.equalsIgnoreCase(key)) {
                    hasExplicitContentType = true;
                }
            }
        }

        if (!hasExplicitContentType && bodyContentType != null) {
            builder.header(CONTENT_TYPE_HEADER, bodyContentType);
        }
    }

    private static void applyCookies(@NotNull HttpRequest.Builder builder,
                                     @NotNull JsonObject args) {
        if (!args.has(PARAM_COOKIES) || !args.get(PARAM_COOKIES).isJsonObject()) return;
        JsonObject cookies = args.getAsJsonObject(PARAM_COOKIES);
        if (cookies.isEmpty()) return;

        StringBuilder cookieHeader = new StringBuilder();
        for (String key : cookies.keySet()) {
            if (!cookieHeader.isEmpty()) cookieHeader.append("; ");
            cookieHeader.append(key).append('=').append(cookies.get(key).getAsString());
        }
        builder.header("Cookie", cookieHeader.toString());
    }

    private static void applyAuth(@NotNull HttpRequest.Builder builder,
                                  @NotNull JsonObject args) {
        if (!args.has(PARAM_AUTH)) return;
        String auth = args.get(PARAM_AUTH).getAsString().trim();

        if (auth.toLowerCase().startsWith("bearer ")) {
            builder.header(AUTHORIZATION_HEADER, "Bearer " + auth.substring(7).trim());
        } else if (auth.toLowerCase().startsWith("basic ")) {
            String credentials = auth.substring(6).trim();
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            builder.header(AUTHORIZATION_HEADER, "Basic " + encoded);
        } else {
            builder.header(AUTHORIZATION_HEADER, auth);
        }
    }

    // ── Run panel visibility ─────────────────────────────────

    private void showRequestInRunPanel(@NotNull String method, @NotNull String url,
                                       @NotNull HttpRequest request, @Nullable String body) {
        String host = extractHost(url);
        String tabTitle = HTTP_PREFIX + method + " " + host;

        StringBuilder console = new StringBuilder();
        console.append("→ ").append(method).append(' ').append(url).append('\n');
        for (var entry : request.headers().map().entrySet()) {
            for (String val : entry.getValue()) {
                console.append("  ").append(entry.getKey()).append(": ").append(val).append('\n');
            }
        }
        if (body != null && !body.isEmpty()) {
            console.append('\n');
            String bodyPreview = body.length() > MAX_BODY_PREVIEW_CHARS
                ? body.substring(0, MAX_BODY_PREVIEW_CHARS) + "\n  ...(truncated)"
                : body;
            for (String line : bodyPreview.split("\n")) {
                console.append("  ").append(line).append('\n');
            }
        }
        console.append('\n');

        String text = console.toString();
        EdtUtil.invokeLater(() -> {
            try {
                var factory = com.intellij.execution.filters.TextConsoleBuilderFactory.getInstance();
                var view = factory.createBuilder(project).getConsole();

                // NopProcessHandler calls startNotify() in its constructor, but
                // RunContentExecutor.run() also calls startNotify(), causing
                // "startNotify called already". Use a bare ProcessHandler instead.
                ProcessHandler processHandler = new ProcessHandler() {
                    @Override
                    protected void destroyProcessImpl() {
                        notifyProcessTerminated(0);
                    }

                    @Override
                    protected void detachProcessImpl() {
                        notifyProcessDetached();
                    }

                    @Override
                    public boolean detachIsDefault() {
                        return false;
                    }

                    @Override
                    public @Nullable OutputStream getProcessInput() {
                        return null;
                    }
                };

                new RunContentExecutor(project, processHandler)
                    .withTitle(tabTitle)
                    .withConsole(view)
                    .withActivateToolWindow(false)
                    .run();

                view.print(text, com.intellij.execution.ui.ConsoleViewContentType.SYSTEM_OUTPUT);
                processHandler.destroyProcess();
            } catch (Exception e) {
                LOG.warn("Failed to show HTTP request in Run panel", e);
            }
        });

        AgentTabTracker.getInstance(project).trackTab("Run", tabTitle);
    }

    private static @NotNull String extractHost(@NotNull String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null ? host : truncateUrl(url);
        } catch (Exception e) {
            return truncateUrl(url);
        }
    }

    private static @NotNull String truncateUrl(@NotNull String url) {
        return url.length() > 30 ? url.substring(0, 27) + "..." : url;
    }

    private static void logResponse(int statusCode, long elapsedMs, int bodyLength) {
        LOG.debug(HTTP_PREFIX + statusCode + " (" + elapsedMs + "ms) - " + bodyLength + " chars");
    }

    // ── Response formatting ──────────────────────────────────

    private static @NotNull String formatResponse(@NotNull HttpResponse<byte[]> response,
                                                  long elapsedMs,
                                                  @NotNull String responseBody,
                                                  boolean showHeaders,
                                                  int maxChars) {
        StringBuilder result = new StringBuilder();
        result.append(HTTP_PREFIX).append(response.statusCode())
            .append(" (").append(elapsedMs).append("ms)\n");

        if (showHeaders) {
            result.append("\n--- Headers ---\n");
            for (var entry : response.headers().map().entrySet()) {
                for (String val : entry.getValue()) {
                    result.append(entry.getKey()).append(": ").append(val).append('\n');
                }
            }
        }

        result.append("\n--- Body ---\n");
        if (responseBody.isEmpty()) {
            result.append("(empty response body)");
        } else if (responseBody.length() <= maxChars) {
            result.append(responseBody);
        } else {
            result.append(responseBody, 0, maxChars);
            result.append("\n\n(").append(maxChars).append(" of ").append(responseBody.length())
                .append(" chars shown. Use max_chars to see more.)");
        }

        return result.toString();
    }

    private @NotNull String handleSaveTo(@NotNull String saveTo, byte[] responseBytes,
                                         int statusCode, long elapsedMs) throws Exception {
        String basePath = project.getBasePath();
        if (basePath == null) return "Error: No project base path";

        Path filePath = saveTo.startsWith("/")
            ? Path.of(saveTo)
            : Path.of(basePath, saveTo);
        Files.createDirectories(filePath.getParent());
        Files.write(filePath, responseBytes);

        return HTTP_PREFIX + statusCode + " (" + elapsedMs + "ms)\n\n"
            + "Response saved to: " + filePath + "\n"
            + "Size: " + responseBytes.length + " bytes";
    }

    @Override
    public @NotNull Object resultRenderer() {
        return HttpRequestRenderer.INSTANCE;
    }
}
