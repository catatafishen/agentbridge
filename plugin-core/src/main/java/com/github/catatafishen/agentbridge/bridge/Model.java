package com.github.catatafishen.agentbridge.bridge;

/**
 * ACP model descriptor returned by the agent's model listing.
 */
public class Model {
    private String id;
    private String name;
    private String description;
    private String usage; // e.g., "1x", "3x", "0.33x"

    public String getId() {
        return id;
    }

    @SuppressWarnings("unused") // Public API for external use
    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    @SuppressWarnings("unused") // Public API for external use
    public void setName(String name) {
        this.name = name;
    }

    @SuppressWarnings("unused") // Public API for external use
    public String getDescription() {
        return description;
    }

    @SuppressWarnings("unused") // Public API for external use
    public void setDescription(String description) {
        this.description = description;
    }

    public String getUsage() {
        return usage;
    }

    @SuppressWarnings("unused") // Public API for external use
    public void setUsage(String usage) {
        this.usage = usage;
    }
}
