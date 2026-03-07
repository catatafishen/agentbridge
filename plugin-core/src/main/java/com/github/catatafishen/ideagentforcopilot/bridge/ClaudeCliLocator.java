package com.github.catatafishen.ideagentforcopilot.bridge;

import com.github.catatafishen.ideagentforcopilot.services.ClaudeSettings;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Locates the Claude Code CLI binary and builds the ACP command.
 */
final class ClaudeCliLocator {
    private static final Logger LOG = Logger.getInstance(ClaudeCliLocator.class);

    private ClaudeCliLocator() {
    }

    static String findClaudeCli() throws AcpException {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        String pathResult = checkClaudeInPath(isWindows);
        if (pathResult != null) return pathResult;

        if (!isWindows) {
            String unixPath = checkUnixLocations();
            if (unixPath != null) return unixPath;
        }

        String installInstructions = isWindows
            ? "Install with: npm install -g @anthropic-ai/claude-code"
            : "Install with: npm install -g @anthropic-ai/claude-code";
        throw new AcpException("Claude Code CLI not found. " + installInstructions, null, false);
    }

    @Nullable
    private static String checkClaudeInPath(boolean isWindows) {
        try {
            String command = isWindows ? "where" : "which";
            Process check = new ProcessBuilder(command, "claude").start();
            if (check.waitFor() == 0) {
                String path = new String(check.getInputStream().readAllBytes()).trim().split("\\r?\\n")[0];
                if (new File(path).exists()) return path;
            }
        } catch (IOException e) {
            LOG.debug("Failed to check for claude in PATH", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.debug("Interrupted while checking for claude in PATH", e);
        }
        return null;
    }

    @Nullable
    private static String checkUnixLocations() {
        String home = System.getProperty("user.home");
        List<String> candidates = new ArrayList<>();

        addNvmCandidates(home, candidates);

        candidates.add(home + "/.local/bin/claude");
        candidates.add("/usr/local/bin/claude");
        candidates.add(home + "/.npm-global/bin/claude");
        candidates.add(home + "/.yarn/bin/claude");
        candidates.add("/opt/homebrew/bin/claude");

        for (String path : candidates) {
            if (new File(path).exists()) {
                LOG.info("Found Claude CLI at: " + path);
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
                java.util.Arrays.sort(nodeDirs, (a, b) -> b.getName().compareTo(a.getName()));
                for (File nodeDir : nodeDirs) {
                    candidates.add(new File(nodeDir, "bin/claude").getAbsolutePath());
                }
            }
        }
    }

    static void addNodeAndClaudeCommand(List<String> cmd, String claudePath) {
        if (claudePath.contains("/.nvm/versions/node/")) {
            String nodeDir = claudePath.substring(0, claudePath.indexOf("/bin/claude"));
            String nodePath = nodeDir + "/bin/node";
            if (new File(nodePath).exists()) {
                cmd.add(nodePath);
            }
        }
        cmd.add(claudePath);
    }

    static ProcessBuilder buildAcpCommand(String claudePath, @Nullable String projectBasePath) {
        List<String> cmd = new ArrayList<>();

        addNodeAndClaudeCommand(cmd, claudePath);

        cmd.add("--acp");
        cmd.add("--stdio");

        String savedModel = ClaudeSettings.getSelectedModel();
        if (savedModel != null && !savedModel.isEmpty()) {
            cmd.add("--model");
            cmd.add(savedModel);
            LOG.info("Claude CLI model set to: " + savedModel);
        }

        if (projectBasePath != null) {
            Path agentWorkPath = Path.of(projectBasePath, ".agent-work");
            cmd.add("--config-dir");
            cmd.add(agentWorkPath.toString());
            LOG.info("Claude CLI config-dir set to: " + agentWorkPath);
        }

        CopilotCliLocator.addMcpConfigFlags(cmd, projectBasePath, ClaudeSettings.getDisabledMcpToolIds());

        return new ProcessBuilder(cmd);
    }
}
