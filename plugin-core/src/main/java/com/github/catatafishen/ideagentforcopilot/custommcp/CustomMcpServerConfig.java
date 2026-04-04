package com.github.catatafishen.ideagentforcopilot.custommcp;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Configuration for a single external MCP server.
 * Stored as part of {@link CustomMcpSettings.State}.
 */
public final class CustomMcpServerConfig {

    private String id = UUID.randomUUID().toString();
    private String name = "";
    private String url = "";
    private String instructions = "";
    private boolean enabled = true;

    /** Required for IntelliJ XML serialization. */
    public CustomMcpServerConfig() {
    }

    public CustomMcpServerConfig(String id, String name, String url, String instructions, boolean enabled) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.instructions = instructions;
        this.enabled = enabled;
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
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    @NotNull
    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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
        return new CustomMcpServerConfig(id, name, url, instructions, enabled);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CustomMcpServerConfig that)) return false;
        return enabled == that.enabled
            && Objects.equals(id, that.id)
            && Objects.equals(name, that.name)
            && Objects.equals(url, that.url)
            && Objects.equals(instructions, that.instructions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, url, instructions, enabled);
    }
}
