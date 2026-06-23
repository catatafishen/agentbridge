package com.github.catatafishen.agentbridge.custommcp;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Configuration for a single external MCP server.
 * Supports two transport types:
 * <ul>
 *   <li>{@code http} — connects to an HTTP/SSE MCP endpoint via URL (default)</li>
 *   <li>{@code stdio} — spawns a local process and communicates via stdin/stdout</li>
 * </ul>
 * Stored as part of {@link CustomMcpSettings.State}.
 */
public final class CustomMcpServerConfig {

    public static final String TYPE_HTTP = "http";
    public static final String TYPE_SSE = "sse";
    public static final String TYPE_STDIO = "stdio";

    private String id = UUID.randomUUID().toString();
    private String name = "";
    private String type = TYPE_HTTP;
    private String url = "";
    private String command = "";
    private List<String> args = Collections.emptyList();
    private List<McpEnvVar> environment = Collections.emptyList();
    private List<McpHeader> headers = Collections.emptyList();
    private String instructions = "";
    private boolean enabled = true;
    private boolean defaultEnabled = true;

    /** Required for IntelliJ XML serialization. */
    public CustomMcpServerConfig() {
    }

    /** Backward-compatible constructor for HTTP-type servers. */
    public CustomMcpServerConfig(String id, String name, String url, String instructions, boolean enabled) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.instructions = instructions;
        this.enabled = enabled;
    }

    /** Full constructor. */
    public CustomMcpServerConfig(
        String id, String name, String type, String url, String command,
        List<String> args, List<McpEnvVar> environment, List<McpHeader> headers,
        String instructions, boolean enabled, boolean defaultEnabled
    ) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.url = url;
        this.command = command;
        this.args = args != null ? new ArrayList<>(args) : Collections.emptyList();
        this.environment = environment != null ? new ArrayList<>(environment) : Collections.emptyList();
        this.headers = headers != null ? new ArrayList<>(headers) : Collections.emptyList();
        this.instructions = instructions;
        this.enabled = enabled;
        this.defaultEnabled = defaultEnabled;
    }

    @NotNull
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @NotNull
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @NotNull
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = switch (type) {
            case TYPE_STDIO -> TYPE_STDIO;
            case TYPE_SSE -> TYPE_SSE;
            default -> TYPE_HTTP;
        };
    }

    @NotNull
    public String getUrl() {
        return url;
    }

    @NotNull
    public String getEffectiveUrl() {
        if (url.isBlank()) return url;
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if ((path == null || path.isEmpty() || path.equals("/")) && !url.endsWith("/mcp")) {
                String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
                return base + "/mcp";
            }
            return url;
        } catch (Exception e) {
            return url;
        }
    }

    public void setUrl(String url) {
        this.url = url != null ? url : "";
    }

    @NotNull
    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command != null ? command : "";
    }

    @NotNull
    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args != null ? new ArrayList<>(args) : Collections.emptyList();
    }

    @NotNull
    public List<String> getCommandParts() {
        if (command.isBlank()) return Collections.emptyList();
        if (args.isEmpty() && command.indexOf(' ') >= 0) {
            return splitLegacyCommand(command);
        }
        List<String> parts = new ArrayList<>(1 + args.size());
        parts.add(command);
        parts.addAll(args);
        return parts;
    }

    @NotNull
    public String getEffectiveCommand() {
        List<String> parts = getCommandParts();
        return parts.isEmpty() ? "" : parts.get(0);
    }

    @NotNull
    public List<String> getEffectiveArgs() {
        List<String> parts = getCommandParts();
        return parts.size() <= 1 ? Collections.emptyList() : new ArrayList<>(parts.subList(1, parts.size()));
    }

    public void setCommandParts(@NotNull List<String> parts) {
        if (parts.isEmpty()) {
            command = "";
            args = Collections.emptyList();
            return;
        }
        command = parts.getFirst() != null ? parts.getFirst() : "";
        args = parts.size() > 1 ? new ArrayList<>(parts.subList(1, parts.size())) : Collections.emptyList();
    }

    @NotNull
    public List<McpEnvVar> getEnvironment() {
        return environment;
    }

    public void setEnvironment(List<McpEnvVar> environment) {
        this.environment = environment != null ? new ArrayList<>(environment) : Collections.emptyList();
    }

    @NotNull
    public List<McpHeader> getHeaders() {
        return headers;
    }

    public void setHeaders(List<McpHeader> headers) {
        this.headers = headers != null ? new ArrayList<>(headers) : Collections.emptyList();
    }

    @NotNull
    public Map<String, String> getHeadersMap() {
        if (headers.isEmpty()) return Collections.emptyMap();
        Map<String, String> map = new LinkedHashMap<>();
        for (McpHeader header : headers) {
            storeHeader(map, header);
        }
        return map;
    }

    /** Returns environment as a mutable map for process building. */
    @NotNull
    public Map<String, String> getEnvironmentMap() {
        if (environment.isEmpty()) return Collections.emptyMap();
        Map<String, String> map = new LinkedHashMap<>();
        for (McpEnvVar e : environment) {
            storeEnvVar(map, e);
        }
        return map;
    }

    private static void storeEnvVar(Map<String, String> map, McpEnvVar e) {
        if (e != null && e.getName() != null && !e.getName().isBlank()) {
            map.put(e.getName(), e.getValue() != null ? e.getValue() : "");
        }
    }

    private static void storeHeader(Map<String, String> map, McpHeader header) {
        if (header != null && header.getName() != null && !header.getName().isBlank()) {
            map.put(header.getName(), header.getValue() != null ? header.getValue() : "");
        }
    }

    @NotNull
    private static List<String> splitLegacyCommand(@NotNull String raw) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inQuotes) {
                if (c == quoteChar) {
                    inQuotes = false;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                inQuotes = true;
                quoteChar = c;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    @NotNull
    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions != null ? instructions : "";
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDefaultEnabled() {
        return defaultEnabled;
    }

    public void setDefaultEnabled(boolean defaultEnabled) {
        this.defaultEnabled = defaultEnabled;
    }

    /** Returns true if this is a stdio-type server. */
    public boolean isStdio() {
        return TYPE_STDIO.equals(type);
    }

    /** Returns true if this is an HTTP-type server. */
    public boolean isHttp() {
        return !isStdio();
    }

    /**
     * Returns true if the server has enough config to be activated:
     * HTTP servers need a non-blank URL; stdio servers need a non-blank command.
     */
    public boolean isConfigured() {
        if (isStdio()) {
            return !command.isBlank();
        }
        return !url.isBlank();
    }

    /**
     * Returns a stable prefix for tool IDs derived from this server.
     * Converts the server name to lowercase alphanumeric with underscores,
     * prefixed with {@code cmcp_} to namespace all custom MCP proxy tools.
     */
    @NotNull
    public String toolPrefix() {
        String sanitized = name.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("(^_)|(_$)", "");
        if (sanitized.isEmpty()) {
            sanitized = id.substring(0, Math.min(8, id.length())).replace("-", "");
        }
        return "cmcp_" + sanitized;
    }

    @NotNull
    public CustomMcpServerConfig copy() {
        return new CustomMcpServerConfig(
            id, name, type, url, command,
            new ArrayList<>(args),
            new ArrayList<>(environment),
            new ArrayList<>(headers),
            instructions, enabled, defaultEnabled
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CustomMcpServerConfig that)) return false;
        return enabled == that.enabled
            && defaultEnabled == that.defaultEnabled
            && Objects.equals(id, that.id)
            && Objects.equals(name, that.name)
            && Objects.equals(type, that.type)
            && Objects.equals(url, that.url)
            && Objects.equals(command, that.command)
            && Objects.equals(args, that.args)
            && Objects.equals(environment, that.environment)
            && Objects.equals(headers, that.headers)
            && Objects.equals(instructions, that.instructions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, type, url, command, args, environment, headers, instructions, enabled, defaultEnabled);
    }

    /**
     * A single environment variable entry: name + value.
     */
    public static final class McpEnvVar {
        private String name = "";
        private String value = "";

        public McpEnvVar() {
        }

        public McpEnvVar(String name, String value) {
            this.name = name != null ? name : "";
            this.value = value != null ? value : "";
        }

        @NotNull
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name != null ? name : "";
        }

        @NotNull
        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value != null ? value : "";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof McpEnvVar that)) return false;
            return Objects.equals(name, that.name) && Objects.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, value);
        }
    }

    /**
     * A single HTTP header entry: name + value.
     */
    public static final class McpHeader {
        private String name = "";
        private String value = "";

        public McpHeader() {
        }

        public McpHeader(String name, String value) {
            this.name = name != null ? name : "";
            this.value = value != null ? value : "";
        }

        @NotNull
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name != null ? name : "";
        }

        @NotNull
        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value != null ? value : "";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof McpHeader that)) return false;
            return Objects.equals(name, that.name) && Objects.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, value);
        }
    }
}
