package com.github.catatafishen.agentbridge.settings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-tool configuration stored in {@link McpServerSettings}.
 *
 * <p>Currently holds an output template string that is appended to every
 * successful tool response. The {@code customOptions} map provides
 * forward-compatible storage for future per-tool settings (e.g. timeout
 * overrides, rate limits, custom parameter defaults).
 */
public class ToolOptions {

    private String outputTemplate = "";
    private Map<String, String> customOptions = new LinkedHashMap<>();

    public ToolOptions() {
    }

    public ToolOptions(String outputTemplate) {
        this.outputTemplate = outputTemplate != null ? outputTemplate : "";
    }

    public String getOutputTemplate() {
        return outputTemplate;
    }

    public void setOutputTemplate(String outputTemplate) {
        this.outputTemplate = outputTemplate != null ? outputTemplate : "";
    }

    public Map<String, String> getCustomOptions() {
        return customOptions;
    }

    public void setCustomOptions(Map<String, String> customOptions) {
        this.customOptions = customOptions != null ? customOptions : new LinkedHashMap<>();
    }

    /**
     * Returns true if this instance has no meaningful configuration
     * (empty template and no custom options). Used to avoid persisting
     * default entries.
     */
    public boolean isEmpty() {
        return (outputTemplate == null || outputTemplate.isEmpty())
                && (customOptions == null || customOptions.isEmpty());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ToolOptions that)) return false;
        return Objects.equals(outputTemplate, that.outputTemplate)
                && Objects.equals(customOptions, that.customOptions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(outputTemplate, customOptions);
    }
}
