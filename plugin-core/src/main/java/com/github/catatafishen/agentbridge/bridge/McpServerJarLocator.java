package com.github.catatafishen.agentbridge.bridge;

import com.github.catatafishen.agentbridge.psi.PlatformApiCompat;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * Locates the bundled MCP server JAR in the plugin's lib directory.
 * Extracted from CopilotCliLocator so all agent configs can use it.
 */
public final class McpServerJarLocator {

    private static final Logger LOG = Logger.getInstance(McpServerJarLocator.class);

    private McpServerJarLocator() {
    }

    /**
     * Find the bundled MCP server JAR in the plugin's lib directory.
     *
     * @return absolute path to the JAR, or null if not found
     */
    @Nullable
    public static String findMcpServerJar() {
        // Strategy 1: Use PlatformApiCompat to find plugin directory
        try {
            java.nio.file.Path pluginPath = PlatformApiCompat
                .getPluginPath("com.github.catatafishen.agentbridge");
            if (pluginPath != null) {
                File libDir = pluginPath.resolve("lib").toFile();
                File mcpJar = new File(libDir, "mcp-server.jar");
                if (mcpJar.exists()) {
                    LOG.info("Found MCP server JAR via plugin API: " + mcpJar.getAbsolutePath());
                    return mcpJar.getAbsolutePath();
                }
                LOG.warn("MCP JAR not in plugin lib dir: " + libDir);
            }
        } catch (Exception e) {
            LOG.warn("Plugin API lookup failed: " + e.getMessage());
        }

        // Strategy 2: Fall back to classloader-based discovery
        try {
            java.net.URL url = McpServerJarLocator.class.getProtectionDomain().getCodeSource().getLocation();
            if (url != null) {
                File jarDir = new File(url.toURI()).getParentFile();
                File mcpJar = new File(jarDir, "mcp-server.jar");
                if (mcpJar.exists()) {
                    LOG.info("Found MCP server JAR via classloader: " + mcpJar.getAbsolutePath());
                    return mcpJar.getAbsolutePath();
                }
            }
        } catch (java.net.URISyntaxException | SecurityException e) {
            LOG.warn("Classloader JAR lookup failed: " + e.getMessage());
        }

        LOG.warn("MCP server JAR not found — MCP tools will be unavailable");
        return null;
    }
}
