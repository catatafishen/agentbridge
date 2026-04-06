package com.github.catatafishen.agentbridge.bridge;

import java.util.List;

/**
 * Authentication method descriptor returned by the ACP agent during initialization.
 */
public class AuthMethod {
    private String id;
    private String name;
    private String description;
    private String command;
    private List<String> args;

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

    public String getCommand() {
        return command;
    }

    @SuppressWarnings("unused") // Public API for external use
    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    @SuppressWarnings("unused") // Public API for external use
    public void setArgs(List<String> args) {
        this.args = args;
    }
}
