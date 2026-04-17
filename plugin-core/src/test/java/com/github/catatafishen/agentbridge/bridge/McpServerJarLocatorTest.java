package com.github.catatafishen.agentbridge.bridge;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression test for the Junie/Kiro "Cannot configure MCP server" bug.
 *
 * <p>The bug was caused by {@link McpServerJarLocator#PLUGIN_ID} drifting from the
 * {@code <id>} declared in plugin.xml. When the constant doesn't match, every call to
 * {@code PlatformApiCompat.getPluginPath(PLUGIN_ID)} returns null and stdio-based MCP
 * agents silently fail to inject mcp-server.jar into their session/new params.</p>
 */
class McpServerJarLocatorTest {

    @Test
    void pluginIdConstantMatchesManifest() throws Exception {
        String manifestId = readPluginIdFromManifest();
        assertNotNull(manifestId, "plugin.xml must contain an <id> element");
        assertEquals(McpServerJarLocator.PLUGIN_ID, manifestId,
            "McpServerJarLocator.PLUGIN_ID must match the <id> in plugin.xml — "
                + "otherwise PlatformApiCompat.getPluginPath() returns null and "
                + "stdio MCP agents (Junie, Kiro) cannot locate mcp-server.jar.");
    }

    @Test
    void pluginIdIsNotBlank() {
        assertNotNull(McpServerJarLocator.PLUGIN_ID);
        assertFalse(McpServerJarLocator.PLUGIN_ID.isEmpty());
    }

    private static String readPluginIdFromManifest() throws Exception {
        // plugin.xml is on the test classpath via plugin-core's main resources.
        try (InputStream in = McpServerJarLocatorTest.class
            .getResourceAsStream("/META-INF/plugin.xml")) {
            assertNotNull(in, "plugin.xml should be on the test classpath");
            String xml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("<id>([^<]+)</id>").matcher(xml);
            return m.find() ? m.group(1).trim() : null;
        }
    }
}
