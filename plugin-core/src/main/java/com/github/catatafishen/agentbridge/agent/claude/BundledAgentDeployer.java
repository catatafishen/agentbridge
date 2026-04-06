package com.github.catatafishen.agentbridge.agent.claude;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class BundledAgentDeployer {
    private static final Logger LOG = Logger.getInstance(BundledAgentDeployer.class);

    private static final String SENTINEL =
        "<!-- Deployed by AgentBridge — edits are preserved, delete to stop auto-deploy -->";

    private static final Object LOCK = new Object();

    private BundledAgentDeployer() {
    }

    /**
     * Ensures the given agent definitions exist in the project's {@code .github/agents/} directory.
     * Each filename in {@code agentFiles} must correspond to a classpath resource under {@code /agents/}.
     * Safe to call multiple times — uses a sentinel to skip already-deployed files.
     *
     * @param projectBasePath the project root directory, or null to skip
     * @param agentFiles      list of agent filenames to deploy (e.g. {@code "ide-explore.md"})
     */
    public static void ensureAgents(@Nullable String projectBasePath, @NotNull List<String> agentFiles) {
        ensureAgents(projectBasePath, ".github/agents", agentFiles);
    }

    /**
     * Ensures the given agent definitions exist in a custom directory.
     * Each filename in {@code agentFiles} must correspond to a classpath resource under {@code /agents/}.
     * Safe to call multiple times — uses a sentinel to skip already-deployed files.
     *
     * @param projectBasePath the project root directory, or null to skip (ignored for absolute paths)
     * @param agentsDirectory directory — absolute or relative to project root
     * @param agentFiles      list of agent filenames to deploy (e.g. {@code "kiro-intellij-agent.json"})
     */
    public static void ensureAgents(@Nullable String projectBasePath,
                                    @NotNull String agentsDirectory,
                                    @NotNull List<String> agentFiles) {
        if (agentFiles.isEmpty()) return;

        Path agentsDir;
        if (Path.of(agentsDirectory).isAbsolute()) {
            agentsDir = Path.of(agentsDirectory);
        } else {
            if (projectBasePath == null) return;
            agentsDir = Path.of(projectBasePath, agentsDirectory);
        }

        synchronized (LOCK) {
            for (String agentFile : agentFiles) {
                deployAgent(agentsDir, agentFile);
            }
        }
    }

    private static void deployAgent(@NotNull Path agentsDir, @NotNull String filename) {
        Path targetFile = agentsDir.resolve(filename);

        if (Files.isRegularFile(targetFile)) {
            try {
                String existing = Files.readString(targetFile, StandardCharsets.UTF_8);
                if (existing.contains(SENTINEL)) {
                    return; // already deployed by us — don't overwrite user edits
                }
                // File exists but wasn't deployed by us — don't overwrite
                LOG.info("Skipping " + targetFile + " — exists and not managed by plugin");
                return;
            } catch (IOException e) {
                LOG.warn("Failed to read existing agent file: " + targetFile, e);
                return;
            }
        }

        String content = loadBundledAgent(filename);
        if (content == null) return;

        try {
            Files.createDirectories(agentsDir);
            String deployed = SENTINEL + "\n" + content;
            Files.writeString(targetFile, deployed, StandardCharsets.UTF_8);
            LOG.info("Deployed bundled agent: " + targetFile);
        } catch (IOException e) {
            LOG.warn("Failed to deploy agent: " + targetFile, e);
        }
    }

    @Nullable
    private static String loadBundledAgent(@NotNull String filename) {
        String resourcePath = "/agents/" + filename;
        try (InputStream is = BundledAgentDeployer.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOG.warn("Bundled agent resource not found: " + resourcePath);
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Failed to load bundled agent resource: " + resourcePath, e);
            return null;
        }
    }
}
