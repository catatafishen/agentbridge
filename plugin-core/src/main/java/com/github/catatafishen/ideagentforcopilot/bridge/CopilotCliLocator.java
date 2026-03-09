package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.CopilotSettings;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Locates the Copilot CLI binary, MCP server JAR, and builds the ACP command.
 * Extracted from AcpClient for file-size reduction.
 */
final class CopilotCliLocator {
    private static final Logger LOG = Logger.getInstance(CopilotCliLocator.class);
    private static final String USER_HOME = "user.home";
    private static final String COMMAND = "command";
    private static final String MCP_SERVER_ERROR = "MCP Server Error";
    private static final Gson gson = new Gson();

    private CopilotCliLocator() {
    }

    static String findCopilotCli() throws AcpException {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        // Check PATH first
        String pathResult = checkCopilotInPath(isWindows);
        if (pathResult != null) return pathResult;

        // Check known Windows winget install location
        if (isWindows) {
            String wingetPath = checkWindowsWingetPath();
            if (wingetPath != null) return wingetPath;
        }

        // Check common Linux/macOS locations
        if (!isWindows) {
            String unixPath = checkUnixLocations();
            if (unixPath != null) return unixPath;
        }

        String installInstructions = isWindows
            ? "Install with: winget install GitHub.Copilot"
            : "Install with: npm install -g @anthropic-ai/copilot-cli";
        throw new AcpException("Copilot CLI not found. " + installInstructions, null, false);
    }

    @Nullable
    private static String checkCopilotInPath(boolean isWindows) {
        try {
            String command = isWindows ? "where" : "which";
            Process check = new ProcessBuilder(command, "copilot").start();
            if (check.waitFor() == 0) {
                String path = new String(check.getInputStream().readAllBytes()).trim().split("\\r?\\n")[0];
                if (new File(path).exists()) return path;
            }
        } catch (IOException e) {
            LOG.debug("Failed to check for copilot in PATH", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.debug("Interrupted while checking for copilot in PATH", e);
        }
        return null;
    }

    @Nullable
    private static String checkWindowsWingetPath() {
        String wingetPath = System.getenv("LOCALAPPDATA") +
            "\\Microsoft\\WinGet\\Packages\\GitHub.Copilot_Microsoft.Winget.Source_8wekyb3d8bbwe\\copilot.exe";
        if (new File(wingetPath).exists()) return wingetPath;
        return null;
    }

    @Nullable
    private static String checkUnixLocations() {
        String home = System.getProperty(USER_HOME);
        List<String> candidates = new ArrayList<>();

        // NVM-managed node installations
        addNvmCandidates(home, candidates);

        // Common global npm/yarn locations
        candidates.add(home + "/.local/bin/copilot");
        candidates.add("/usr/local/bin/copilot");
        candidates.add(home + "/.npm-global/bin/copilot");
        candidates.add(home + "/.yarn/bin/copilot");
        // Homebrew
        candidates.add("/opt/homebrew/bin/copilot");

        for (String path : candidates) {
            if (new File(path).exists()) {
                LOG.info("Found Copilot CLI at: " + path);
                return path;
            }
        }
        return null;
    }

    private static void addNvmCandidates(String home, List<String> candidates) {
        File nvmDir = new File(home, ".nvm/versions/node");
        if (nvmDir.isDirectory()) {
            File[] nodeDirs = nvmDir.listFiles(File::isDirectory);
            if (nodeDirs != null) {
                // Sort descending to prefer the latest version
                java.util.Arrays.sort(nodeDirs, (a, b) -> b.getName().compareTo(a.getName()));
                for (File nodeDir : nodeDirs) {
                    candidates.add(new File(nodeDir, "bin/copilot").getAbsolutePath());
                }
            }
        }
    }

    /**
     * Find the bundled MCP server JAR in the plugin's lib directory.
     * Returns null if not found (MCP tools will be unavailable).
     */
    @Nullable
    static String findMcpServerJar() {
        // Strategy 1: Use PlatformApiCompat to find plugin directory (avoids daemon false positives)
        try {
            java.nio.file.Path pluginPath = com.github.catatafishen.ideagentforcopilot.psi.PlatformApiCompat
                .getPluginPath("com.github.catatafishen.ideagentforcopilot");
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
            java.net.URL url = CopilotCliLocator.class.getProtectionDomain().getCodeSource().getLocation();
            if (url != null) {
                LOG.info("CodeSource URL: " + url);
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

        LOG.warn("MCP server JAR not found - MCP tools will be unavailable");
        return null;
    }

    /**
     * Resolve the node binary for NVM-managed copilot installations.
     */
    static void addNodeAndCopilotCommand(List<String> cmd, String copilotPath) {
        if (copilotPath.contains("/.nvm/versions/node/")) {
            String nodeDir = copilotPath.substring(0, copilotPath.indexOf("/bin/copilot"));
            String nodePath = nodeDir + "/bin/node";
            if (new File(nodePath).exists()) {
                cmd.add(nodePath);
            }
        }
        cmd.add(copilotPath);
    }

    /**
     * Build MCP config JSON, write to temp file, and add --additional-mcp-config flag.
     * The proxy connects to the MCP HTTP server at the specified port.
     */
    static void addMcpConfigFlags(List<String> cmd, int mcpPort) {
        if (mcpPort <= 0) {
            LOG.info("MCP port is " + mcpPort + " — skipping MCP config (no server available)");
            return;
        }
        String mcpJarPath = findMcpServerJar();
        if (mcpJarPath == null) {
            LOG.warn(MCP_SERVER_ERROR + ": MCP server JAR not found. IntelliJ code tools will be unavailable.");
            showNotification(MCP_SERVER_ERROR,
                "MCP server JAR not found. IntelliJ code tools will be unavailable.\nCheck IDE log for details.",
                com.intellij.notification.NotificationType.ERROR);
            return;
        }
        String javaExe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        String javaPath = System.getProperty("java.home") + File.separator + "bin" + File.separator + javaExe;
        if (!new File(javaPath).exists()) {
            LOG.warn("Java not found at: " + javaPath + ", MCP tools unavailable");
            showNotification(MCP_SERVER_ERROR,
                "Java not found at: " + javaPath + "\nIntelliJ tools will be unavailable.",
                com.intellij.notification.NotificationType.ERROR);
            return;
        }
        try {
            JsonObject mcpConfig = new JsonObject();
            JsonObject servers = new JsonObject();
            JsonObject codeTools = new JsonObject();
            codeTools.addProperty(COMMAND, javaPath);
            JsonArray args = new JsonArray();
            args.add("-jar");
            args.add(mcpJarPath);
            args.add("--port");
            args.add(String.valueOf(mcpPort));
            codeTools.add("args", args);
            servers.add("intellij-code-tools", codeTools);
            mcpConfig.add("mcpServers", servers);

            File mcpConfigFile = File.createTempFile("copilot-mcp-", ".json");
            mcpConfigFile.deleteOnExit();
            try (java.io.FileWriter fw = new java.io.FileWriter(mcpConfigFile)) {
                fw.write(gson.toJson(mcpConfig));
            }
            cmd.add("--additional-mcp-config");
            cmd.add("@" + mcpConfigFile.getAbsolutePath());
            LOG.info("MCP code-tools configured globally via " + mcpConfigFile.getAbsolutePath());
        } catch (IOException e) {
            LOG.warn("Failed to write MCP config file", e);
            showNotification(MCP_SERVER_ERROR,
                "Failed to write MCP config file: " + e.getMessage() + "\nIntelliJ tools will be unavailable.",
                com.intellij.notification.NotificationType.ERROR);
        }
    }

    static ProcessBuilder buildAcpCommand(String copilotPath, @Nullable String projectBasePath,
                                          int mcpPort) {
        List<String> cmd = new ArrayList<>();

        addNodeAndCopilotCommand(cmd, copilotPath);

        cmd.add("--acp");
        cmd.add("--stdio");

        String savedModel = CopilotSettings.getSelectedModel();
        if (savedModel != null && !savedModel.isEmpty()) {
            cmd.add("--model");
            cmd.add(savedModel);
            LOG.info("Copilot CLI model set to: " + savedModel);
        }

        // NOTE: --deny-tool, --available-tools, --excluded-tools all DON'T work in --acp mode.
        // All three are broken per CLI bug #556. See CLI-BUG-556-WORKAROUND.md

        if (projectBasePath != null) {
            Path agentWorkPath = Path.of(projectBasePath, ".agent-work");
            cmd.add("--config-dir");
            cmd.add(agentWorkPath.toString());
            LOG.info("Copilot CLI config-dir set to: " + agentWorkPath);
        }

        addMcpConfigFlags(cmd, mcpPort);

        return new ProcessBuilder(cmd);
    }

    private static void showNotification(String title, String content, com.intellij.notification.NotificationType type) {
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("Copilot Notifications")
            .createNotification(title, content, type)
            .notify(null);
    }
}
