package com.github.catatafishen.agentbridge.bridge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the pure command builder in {@link McpStdioLaunch}. The environment-dependent
 * resolution methods ({@code resolveJavaBinaryPath}, {@code buildCommand}) are not unit-tested
 * here because they probe the host filesystem; {@code commandFor} holds the assembly logic.
 */
class McpStdioLaunchTest {

    @Test
    void commandForAssemblesProxyInvocation() {
        List<String> cmd = McpStdioLaunch.commandFor("/jdk/bin/java", "/lib/mcp-server.jar", 8642);
        assertEquals(
            List.of("/jdk/bin/java", "-jar", "/lib/mcp-server.jar", "--port", "8642"),
            cmd);
    }

    @Test
    void commandForRendersPortAsString() {
        List<String> cmd = McpStdioLaunch.commandFor("java", "mcp-server.jar", 12345);
        assertEquals("--port", cmd.get(3));
        assertEquals("12345", cmd.get(4));
    }

    @Test
    void commandForFirstElementIsTheExecutable() {
        List<String> cmd = McpStdioLaunch.commandFor("/usr/bin/java", "/x/mcp-server.jar", 1);
        assertEquals("/usr/bin/java", cmd.getFirst());
    }
}
