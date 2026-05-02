package com.github.catatafishen.agentbridge.services;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolOutputHookRunnerTest {

    @Test
    void interpretHookOutput_keepsOriginalWhenHookWritesNothing() {
        assertEquals("original", ToolOutputHookRunner.interpretHookOutput("\n", "original"));
    }

    @Test
    void interpretHookOutput_replacesOutputFromJsonObject() {
        assertEquals("rewritten", ToolOutputHookRunner.interpretHookOutput("{\"output\":\"rewritten\"}", "original"));
    }

    @Test
    void interpretHookOutput_allowsEmptyReplacementFromJsonObject() {
        assertEquals("", ToolOutputHookRunner.interpretHookOutput("{\"output\":null}", "original"));
    }

    @Test
    void interpretHookOutput_appendsOutputFromJsonObject() {
        assertEquals("original\nextra", ToolOutputHookRunner.interpretHookOutput("{\"append\":\"\\nextra\"}", "original"));
    }

    @Test
    void interpretHookOutput_usesPlainTextAsReplacement() {
        assertEquals("plain replacement", ToolOutputHookRunner.interpretHookOutput("plain replacement\n", "original"));
    }

    @Test
    void interpretHookOutput_treatsJsonPrimitiveAsPlainTextReplacement() {
        assertEquals("123", ToolOutputHookRunner.interpretHookOutput("123\n", "original"));
    }

    @Test
    void interpretHookOutput_rejectsJsonWithoutOutputOrAppend() {
        assertThrows(IllegalArgumentException.class,
            () -> ToolOutputHookRunner.interpretHookOutput("{\"message\":\"ignored\"}", "original"));
    }

    @Test
    void hookPayload_containsRawArgumentsAndOutput() {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", "README.md");

        ToolOutputHookRunner.HookPayload payload = new ToolOutputHookRunner.HookPayload(
            "read_file",
            arguments,
            arguments.toString(),
            "raw output",
            false,
            "project",
            "2026-05-02T00:00:00Z"
        );

        assertEquals("read_file", payload.toolName());
        assertEquals(arguments, payload.arguments());
        assertEquals("{\"path\":\"README.md\"}", payload.argumentsJson());
        assertEquals("raw output", payload.output());
    }
}
