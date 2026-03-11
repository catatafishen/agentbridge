package com.github.catatafishen.ideagentforcopilot.psi;

import com.google.gson.JsonObject;

/**
 * Functional interface for PSI Bridge tool handlers.
 * Each tool implementation takes a JSON arguments object and returns a string result.
 */
@FunctionalInterface
@SuppressWarnings("java:S112") // handlers throw generic Exception caught at JSON-RPC dispatch level
public interface ToolHandler {
    @SuppressWarnings({"RedundantThrows", "unused"})
        // handlers may throw checked exceptions
    String handle(JsonObject args) throws Exception;
}
